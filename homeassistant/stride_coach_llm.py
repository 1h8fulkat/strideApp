#!/usr/bin/env python3
"""
Install the STRIDE reflective coach into Home Assistant.

Creates one script (`script.stride_coach`) that every trigger calls with a
different `kind`, plus the automations that fire it. Keeping the prompt in a
single script means the persona and the guard-rails are written down once.

Design notes that matter:

* **The coach starts from zero.** As of writing there are three identical
  weight readings and a day of treadmill history. The prompt therefore tells it
  what it does *not* know and forbids inventing a trend — a coach that
  manufactures narrative from one data point is worse than one that says
  "you showed up, that's the whole job today".
* **Readings are tracked trends, never alarms.** The coach may note that a
  number has moved. It may not express concern, nag, or drift anywhere near
  medical advice — and `coach_avoid` in stride.conf lets somebody take a
  subject off the table entirely without explaining themselves.
* The rules layer (`stride_reminders.py`, `stride_coach_postworkout.json`)
  handles anything that must be instant or offline. This layer is only for
  things where a few seconds of latency is irrelevant.

Run:  python3 ha/stride_coach_llm.py
"""
import json
import os
import re
import sys
import urllib.request

from stride_config import HA, ai_task, conf, mqtt, optional, token, treadmill

# Resolved when the script runs, not when it is imported. There is no default
# — see stride_config.ai_task — and a missing one should stop somebody running
# the installer, not stop another module importing this one to read its tables.
NOTIFY = conf("notify_service", "persistent_notification.create")

# Where tapping the notification lands.
#
# Without this the companion app opens whatever dashboard it opened last, which
# in practice means a notification read on the lock screen — where the preview
# may be hidden entirely — becomes a phone unlocking onto the default dashboard
# with no way to find out what it said. The coach message is retained on MQTT
# and rendered by COACH_CARD on this view, so landing here shows the message
# that prompted the tap.
#
# `url` is the iOS companion app's key. Android's equivalent is `clickAction`
# and is deliberately not set: the only Android in this project is the treadmill
# console, which runs the HUD rather than the companion app.
COACH_DASHBOARD = "/stride-health/today"

# Your scale and blood-pressure cuff, if you have them. Left blank they are
# simply absent — the coach already treats "not tracked yet" as a subject to be
# silent about rather than something to hedge around, so nothing needs a
# fallback. Set them in stride.conf.
W = conf("weight_entity", "")
SYS = conf("systolic_entity", "")
DIA = conf("diastolic_entity", "")
FAT = conf("body_fat_entity", "")

# The stable half of the prompt: who the coach is and what it must not do.
# The persona is assembled from configuration rather than written about one
# person. It used to name its author, state his weight and target, and record
# that he had a medical condition and was medicated for it — in a committed file.
# That was fine while this ran in one house and is not something to publish.
#
# `coach_avoid` is the important one and deliberately free text: it is where
# somebody says "do not comment on my blood pressure" or "never mention weight"
# without the code needing to know why. The default is empty, which means the
# coach simply has less to go on.
NAME = conf("walker_name", "")

# An unset name used to default to "there", which the persona then dropped into
# "You speak to there directly, by name" — and the model, told to use a name and
# given nonsense, invented one. It called its owner Peter. So the two cases are
# now genuinely different sentences: with a name, use it; without one, say so
# and forbid inventing one.
ADDRESS = (f"You speak to {NAME} directly, by name, in plain English."
           if NAME else
           "You speak to them directly, in plain English. You have not been told "
           "their name. Never invent one and never guess: address them without a "
           "name at all.")
AVOID = conf("coach_avoid", "")

# One key for the target, shared with the dashboard. The coach turns it into a
# sentence rather than asking somebody to write one — two settings that both
# meant "your target" was a way to have them disagree.
_TARGET = conf("weight_target_kg", "")
TARGET_LINE = f"Their working target is {_TARGET} kg." if _TARGET else ""

# Assembled before the f-string rather than inside it: an f-string expression
# cannot contain a backslash before Python 3.12, and these need newlines.
GOAL_BLOCK = ("\n\nTHEIR GOAL\n" + TARGET_LINE) if TARGET_LINE else ""
AVOID_BLOCK = ("\n\nDO NOT COMMENT ON\n" + AVOID) if AVOID else ""

PERSONA = f"""You are the coach inside STRIDE, a treadmill and health system
its owner built themselves to become more aware of their movement.
{ADDRESS}

HOW YOU SPEAK
- Two or three sentences. Never more. This is a phone notification, not an essay.
- Warm, plain, specific. No exclamation marks, no hype, no emoji.
- Reference the actual numbers you were given. Vague encouragement is worthless.
- Never open with "Great job" or similar filler.

WHAT YOU MUST NOT DO
- Never invent or imply a trend you have not been shown. If there is one weight
  reading, there is no trend, and you say nothing about direction.
- Never give medical advice, and never express concern about any reading. You
  are not a clinician, the person reading this has a doctor, and a worried
  sentence from you is worth nothing and costs something. You may note that a
  number has moved and connect improvements to their walking. That is all.
- Never guilt-trip him about a missed day. State the fact, offer the next step.
- Never claim to know something the data does not say.
- Never ask for a weigh-in or a blood pressure reading unless the MEASUREMENT
  CADENCE block below says it is DUE. One of each per week is the target and
  not a minimum to beat: weighing daily measures water rather than fat, and
  asking for it turns a number that should be ignored into one that is watched.
  If it is not due, the subject does not come up at all — not as a reminder,
  not as a nudge, not as an aside.

FRESHNESS — READ THIS BEFORE QUOTING ANY NUMBER
Every figure below is either current or explicitly marked, and the marking is
not decoration.

- "no reading yet today" means the phone has not sent today's figure. The
  underlying sensor is still holding **yesterday's** number and you have not
  been told today's. Say nothing about that metric. Do not quote it, do not
  guess it, do not say "off to a slow start" or "quiet morning so far" — that
  is a claim about data you do not have. Silence on that subject is correct; a
  hedge is not.
- "(N ago)" is the most recent reading, not today's. If you use it, say when it
  was taken. "Weight sits at 84 kg" is wrong if it was measured four days
  ago; "84 kg when you last weighed in, four days back" is right.
- "not tracked yet" means that metric is not connected at all. Do not speculate
  about it, do not treat it as zero, and do not nag him to set it up.

This rule exists because it was broken. On 31 July 2026 the morning check-in
opened with "9,063 steps and 54 exercise minutes before 7:30" — both were the
previous day's closing totals, read from sensors that had not been updated since
22:44 the night before. Nothing in the numbers said so. Now it does.

WHAT YOU NOW HAVE, AND WHAT YOU STILL DO NOT
Apple Health has been connected since 30 July 2026 and most of it is populated:
sleep split by stage, heart rate measured against each walk's own window,
walking quality, the activity rings. Use it — it is there to be read.

Weight is still thin. A handful of readings is not a trend and you say nothing
about direction until there is one.

More metrics means more chances to manufacture a narrative out of noise, so the
bar goes up rather than down. Three walks is not a fitness trend. A lower average
heart rate at the same pace on a single day is not improvement. One bad night is
not a sleep problem. You will rarely clear the bar for calling something a trend,
and that is the correct outcome.

{GOAL_BLOCK}{AVOID_BLOCK}"""

VOICES = {
    "Supportive Friend": "Warm and steady. You are pleased to see him and you say so without gushing.",
    "Drill Sergeant": "Clipped and demanding, but never cruel and never mocking. Short sentences. High expectations.",
    "Data Nerd": "Lead with the number that matters most and what it implies. Precise, curious, faintly delighted by a good data point.",
    "Zen Master": "Unhurried and uncluttered. One observation, held lightly. No urgency.",
}

# --------------------------------------------------------------------------
# Reading a sensor without reading its age is the bug this section exists to
# stop. See the FRESHNESS block in PERSONA for the incident.
#
# The receiver's value template is `value_json.<key> | default(this.state)`,
# which is deliberate: a metric absent from a payload keeps its last reading
# instead of being wiped to "unknown". The price is that **a stale sensor is
# indistinguishable from a fresh one by inspection** — it holds yesterday's
# closing total and looks exactly like today's running total.
#
# So nothing here hands the model a bare number. Two shapes, and the difference
# between them is the whole point:
#
#   today()   a counter that resets at midnight — steps, energy, exercise
#             minutes, rings. If it has not *changed* since midnight then the
#             number on the sensor is yesterday's, and the model is told
#             "no reading yet today" instead of being handed it. Withholding the
#             number is a guarantee; asking the model to be careful with it is
#             only a request.
#
#   latest()  a reading taken occasionally — weight, VO2 max, blood pressure,
#             sleep. Old is normal and not a fault, so the value is passed with
#             its age attached and the model is required to quote the age.
#
# `last_changed` rather than `last_updated` on purpose. Every sync rewrites
# every sensor, so `last_updated` moves even when the value carried over —
# it answers "did a sync happen", not "is this number today's". The one false
# negative, a metric landing on exactly yesterday's value, reports "no reading
# yet today" and the coach stays quiet. That is the safe direction to fail.
# --------------------------------------------------------------------------

def _candidates(ids: list) -> str:
    return "{%% set e = %s | select('has_value') | list %%}" % json.dumps(ids)


def today(ids: list, unit: str = "") -> str:
    """A counter that resets at midnight. Stale reads as absent, never as a number."""
    return (_candidates(ids)
            + "{% if e and states[e[0]].last_changed >= today_at() %}"
            + "{{ states(e[0]) }}" + unit
            + "{% elif e %}no reading yet today"
            + "{% else %}not tracked yet{% endif %}")


def latest(ids: list, unit: str = "") -> str:
    """An occasional reading. Passed with its age, which the coach must quote."""
    return (_candidates(ids)
            + "{% if e %}{{ states(e[0]) }}" + unit
            + " ({{ relative_time(states[e[0]].last_changed) }} ago)"
            + "{% else %}not tracked yet{% endif %}")


def when(entity: str) -> str:
    """
    A sensor whose *value* is a timestamp, rendered as local wall-clock time.

    Not `latest()`. That reports how long ago the sensor changed, which for a
    timestamp sensor is a different quantity from the time it contains and is
    close enough to be mistaken for it — `last_workout_start` arrived in HA
    six hours after the walk it describes. The number that means something here
    is in the state, not on it.

    Also converted to local time. The app sends UTC, and "23:44" for a walk that
    happened at a quarter past nine in the morning is exactly the kind of detail
    that ends up quoted back at them.
    """
    return ("{% if states('" + entity + "') | as_datetime %}"
            + "{{ (states('" + entity + "') | as_datetime | as_local)"
            + ".strftime('%A %-d %B, %-I:%M %p') }}"
            + " ({{ relative_time(states('" + entity + "') | as_datetime) }} ago)"
            + "{% else %}not tracked yet{% endif %}")


# Listed best-first; the first entity carrying a real value wins, so swapping a
# source later is an id change here and nothing else. HealthKit leads because it
# aggregates iPhone and Watch; CoreMotion and Withings are fallbacks.
STEPS = ["sensor.apple_health_steps", conf("phone_steps_entity", ""),
         conf("withings_steps_entity", "")]

A = "sensor.apple_health_"  # every id below is confirmed against the live
                            # state machine, not guessed from the SENSORS list


# The volatile half — rendered by HA at call time, kept *after* the persona.
CONTEXT_TEMPLATE = """
Right now it is {{ now().strftime('%A %-d %B, %-I:%M %p') }}.

PHONE SYNC
- Apple Health last received from the phone: """ + when("sensor.apple_health_last_sync") + """

If that is hours old, the "today so far" figures below will mostly read "no
reading yet today". **That means the phone has not sent them, not that they
have not moved.** Never read an unsynced counter as inactivity, and never mention the
sync itself — it is plumbing, and he did not ask for a status report.

TODAY SO FAR (every one of these resets at midnight)
- Steps: """ + today(STEPS) + """
- Exercise minutes: """ + today([A + "exercise_minutes"], " min") + """
- Active energy: """ + today([A + "active_energy"], " kcal") + """
- Walking + running distance: """ + today([A + "walk_run_distance"], " m") + """
- Stand time: """ + today([A + "stand_time"], " min") + """
- Time in daylight: """ + today([A + "time_in_daylight"], " min") + """
- Flights climbed: """ + today([A + "flights_climbed"]) + """
- Rings: move """ + today([A + "move_ring"]) + """ of {{ states('""" + A + """move_goal') }} kcal,
  exercise """ + today([A + "exercise_ring"]) + """ of {{ states('""" + A + """exercise_goal') }} min,
  stand """ + today([A + "stand_ring"]) + """ of {{ states('""" + A + """stand_goal') }} h

LAST NIGHT'S SLEEP (stages, not just a total — this is new data, use it)
- Total asleep: """ + latest([A + "sleep"], " h") + """
- Core: """ + latest([A + "sleep_core"], " h") + """
- Deep: """ + latest([A + "sleep_deep"], " h") + """
- REM: """ + latest([A + "sleep_rem"], " h") + """
- Awake: """ + latest([A + "sleep_awake"], " h") + """

One night is one night. Stage figures move around a lot between nights for
reasons that have nothing to do with how he is doing, so comment on them only
when something is plainly unusual, and never diagnose.

HEART AND BREATHING (the watch computes most of these about once a day)
- Resting heart rate: """ + latest([A + "resting_heart_rate"], " bpm") + """
- Heart rate variability: """ + latest([A + "heart_rate_variability"], " ms") + """
- Walking heart rate average: """ + latest([A + "walking_heart_rate"], " bpm") + """
- Respiratory rate: """ + latest([A + "respiratory_rate"], " br/min") + """
- Blood oxygen: """ + latest([A + "blood_oxygen"], " %") + """
- VO2 max: """ + latest([A + "vo2_max"], " mL/kg/min") + """

BODY (measured occasionally — the age is part of the reading, quote it)
- Weight: """ + latest([A + "weight_health", W], " kg") + """
- Body fat: """ + latest([A + "body_fat_health", FAT], " %") + """
- Lean mass: """ + latest([A + "lean_mass_health"], " kg") + """
- BMI: """ + latest([A + "bmi"]) + """

BLOOD PRESSURE (tracked trend only — see the rules above)
- Latest: {{ states('""" + SYS + """') }}/{{ states('""" + DIA + """') }} mmHg
- Taken: {{ states('input_datetime.stride_last_bp') }}

MEASUREMENT CADENCE — WHETHER YOU MAY ASK FOR A READING
One weigh-in a week and one blood pressure reading a week is the whole target.
It is not a minimum to beat. Weighing daily measures water, not fat, and it
turns a number that should be ignored into one that is watched — so asking more
often is not more helpful, it is worse.

{% set wi_last = states('input_datetime.stride_last_weigh_in') %}
{% set wi_every = states('input_number.stride_weigh_in_interval') | float(7) %}
{% set wi_age = ((now() - (wi_last | as_datetime | as_local)).total_seconds() / 86400)
                if wi_last not in ['unknown', 'unavailable', 'none'] else 999 %}
{% set bp_last = states('input_datetime.stride_last_bp') %}
{% set bp_every = states('input_number.stride_bp_interval') | float(7) %}
{% set bp_age = ((now() - (bp_last | as_datetime | as_local)).total_seconds() / 86400)
                if bp_last not in ['unknown', 'unavailable', 'none'] else 999 %}
- Weigh-in: every {{ wi_every | round(0) }} days,
  {% if wi_age > 900 %}never recorded — ASKING IS ALLOWED
  {% elif wi_age >= wi_every - (1 / 24) %}last one {{ wi_age | round(0) }} days ago — DUE, ASKING IS ALLOWED
  {% else %}last one {{ wi_age | round(0) }} days ago — NOT DUE, DO NOT ASK{% endif %}
- Blood pressure: every {{ bp_every | round(0) }} days,
  {% if bp_age > 900 %}never recorded — ASKING IS ALLOWED
  {% elif bp_age >= bp_every - (1 / 24) %}last one {{ bp_age | round(0) }} days ago — DUE, ASKING IS ALLOWED
  {% else %}last one {{ bp_age | round(0) }} days ago — NOT DUE, DO NOT ASK{% endif %}

WALKING QUALITY (this is a project about walking, so these are not filler)
- Walking speed: """ + latest([A + "walking_speed"], " km/h") + """
- Step length: """ + latest([A + "step_length"], " cm") + """
- Walking asymmetry: """ + latest([A + "walking_asymmetry"], " %") + """
- Double support: """ + latest([A + "double_support"], " %") + """

MOST RECENT WALK OUTSIDE (from Apple Health; the heart rate figures are measured
against that walk's own window, so they describe that walk and nothing else)
{% if '""" + A + """last_workout' | has_value -%}
- What: {{ states('""" + A + """last_workout') }}
- Started: """ + when(A + "last_workout_start") + """
- Duration: {{ states('""" + A + """last_workout_duration') }} min
- Distance: {{ states('""" + A + """last_workout_distance') }} m
- Energy: {{ states('""" + A + """last_workout_energy') }} kcal
- Heart rate: {{ states('""" + A + """last_workout_hr_average') }} avg,
  {{ states('""" + A + """last_workout_hr_max') }} peak
- Climb: {{ states('""" + A + """last_workout_climb') }} m
- Walks recorded in the last 7 days: {{ states('""" + A + """workouts_7_days') }}

Check the start date before calling this "today's walk" or "yesterday's" — it is
whatever the most recent recorded walk was, which may be several days back.
{%- else -%}
- No walk recorded yet. Say nothing about outdoor walking.
{%- endif %}

TREADMILL
- Today: {{ (states('sensor.treadmill_distance_daily') | float(0) / 1000) | round(2) }} km
- This week: {{ (states('sensor.treadmill_distance_weekly') | float(0) / 1000) | round(2) }} km
  over {{ states('sensor.treadmill_workouts_this_week') }} session(s)
- This month: {{ (states('sensor.treadmill_distance_monthly') | float(0) / 1000) | round(2) }} km
- Most recent session: {{ (states('{TREADMILL_DISTANCE}') | float(0) / 1000) | round(2) }} km,
  {{ (states('{TREADMILL_ELAPSED}') | float(0) / 60) | round(0) | int }} min,
  {{ states('{TREADMILL_CALORIES}') }} kcal
{% if states('sensor.treadmill_pulse_average') | int(0) > 0 %}
- Heart rate on that session: {{ states('sensor.treadmill_pulse_average') }} avg,
  {{ states('sensor.treadmill_pulse_max') }} peak. Measured by a chest strap,
  averaged only over the part of the walk it was actually reading — so treat it
  as the walk's character, not a precise total. Compare it to the outdoor walks
  above only if the comparison says something he does not already know.
{% endif %}
- Treadmill state: {{ states('{TREADMILL_MODE}') }}

NOTE: the weekly workout count is inflated by a logging fault before 29 July 2026.
Treat any figure above 5 sessions this week as unreliable and do not comment on it.

The treadmill and Apple Health both count walking. A treadmill session and an
Apple Health workout at the same time of day are very likely the same walk
counted twice — do not add them together and do not present them as two efforts.
"""

KINDS = {
    "morning": (
        "Write the morning check-in, as a personal trainer would. This is the one "
        "message of the day that is supposed to be *directive*, so do not narrate "
        "the numbers back — prescribe the session.\n\n"
        "Name today's walk explicitly, and commit to one of:\n"
        "  HARD   — a faster or steeper walk, when yesterday was light or a rest "
        "day, sleep was decent and resting heart rate is where it usually sits.\n"
        "  EASY   — the default. A comfortable walk at a conversational pace.\n"
        "  REST   — no walk today, when they have walked hard several days running, "
        "or slept badly, or resting heart rate is clearly up on its usual. A rest "
        "day is a training decision, not a failure, and you say so plainly.\n\n"
        "Then, if it fits in the sentence budget, add ONE small thing away from the "
        "belt — ten press-ups before the walk, a set of sit-to-stands, two minutes "
        "of calf raises. Concrete and countable. Skip it entirely on a rest day, and "
        "skip it rather than pad the message.\n\n"
        "Say why in the same breath as the what: 'easy one today, you did 33 minutes "
        "yesterday' is a coach; 'have a nice walk' is not.\n\n"
        "You are choosing from thin evidence and you should be honest about that "
        "when it matters — a first guess you will sharpen as more weeks arrive is "
        "worth saying once, not every morning. What you must not do is manufacture "
        "a reason: if there is nothing to go on, prescribe EASY and say it is the "
        "sensible default rather than inventing a rationale."),
    "post_workout": "They have just finished a workout. Reflect on the session they "
                    "actually did, using its numbers.",
    "weekly": "It is the end of the week. Review it honestly — what actually happened, "
              "and one thing worth carrying into next week.",
    "slump": "They have not walked in several days. No guilt. Make the next walk feel small "
             "and near.",

    # Asked for by the cadence reminders in stride_reminders.py, which used to
    # publish their own fixed sentence to the coach feed. Two authors writing
    # into one retained slot meant a nag could land on top of the morning
    # session plan and wipe it — and it read like two different voices, because
    # it was. There is one coach.
    "weigh_in_due": (
        "A weigh-in is due — the CADENCE block will confirm it. Ask for it once, "
        "plainly, and say when the last one was. Mention that once a week is the "
        "point rather than a minimum only if it is worth the words. Do not "
        "moralise, do not speculate about what the number will say, and do not "
        "attach it to a walk."),
    "bp_due": (
        "A blood pressure reading is due — the CADENCE block will confirm it. Ask "
        "for it once, plainly. Remind them to sit for a few minutes first and to "
        "keep it to the same time of day, because that is what makes the readings "
        "comparable. Say nothing about what the last reading was or what this one "
        "might be: you are not a clinician and they have a doctor."),
}


def post(path: str, payload: dict, tok: str) -> str:
    req = urllib.request.Request(
        f"{HA}{path}",
        data=json.dumps(payload).encode(),
        headers={"Authorization": f"Bearer {tok}", "Content-Type": "application/json"},
        method="POST",
    )
    return urllib.request.urlopen(req).read().decode()


def build_script() -> dict:
    voice_lines = "".join(
        f"{{% elif voice == '{k}' %}}{v}" for k, v in list(VOICES.items())[1:]
    )
    voice_block = (
        "{% set voice = states('input_select.stride_coach_personality') %}"
        f"{{% if voice == 'Supportive Friend' %}}{VOICES['Supportive Friend']}"
        f"{voice_lines}"
        "{% endif %}"
    )

    instructions = (
        PERSONA
        + "\n\nYOUR VOICE TODAY\n"
        + voice_block
        + "\n\nTHE TASK\n{{ task }}\n\nWHAT YOU KNOW\n"
        + CONTEXT_TEMPLATE
    )

    return {
        "alias": "STRIDE — coach (LLM)",
        "description": "Assembles context, asks Claude for a short reflection, "
                       "publishes it to the coach feed and pushes it to the phone.",
        "mode": "queued",
        "max": 5,
        "fields": {
            "kind": {"description": "morning | post_workout | weekly | slump",
                     "required": True, "example": "morning",
                     "selector": {"text": None}},
            "task": {"description": "What this particular message is for.",
                     "required": True, "selector": {"text": {"multiline": True}}},
            "silent": {"description": "Publish to the feed but do not push a notification.",
                       "required": False, "selector": {"boolean": None}},
        },
        "sequence": [
            {
                "action": "ai_task.generate_data",
                "data": {
                    "task_name": "stride coach {{ kind }}",
                    "entity_id": ai_task(),
                    "instructions": instructions,
                    "structure": {
                        "headline": {
                            "description": "At most 6 words. The line he reads on a "
                                           "lock screen. No trailing full stop.",
                            "required": True,
                            "selector": {"text": None},
                        },
                        "body": {
                            "description": "Two or three sentences, at most 320 characters.",
                            "required": True,
                            "selector": {"text": {"multiline": True}},
                        },
                    },
                },
                "response_variable": "reply",
            },
            {
                "action": "mqtt.publish",
                "data": {
                    "topic": "stride/coach/message",
                    "retain": True,
                    "payload": "{{ {'headline': reply.data.headline, "
                               "'body': reply.data.body, 'kind': kind, "
                               "'source': 'claude'} | to_json }}",
                },
            },
            {
                "if": [{"condition": "template",
                        "value_template": "{{ not (silent | default(false)) }}"}],
                "then": [{
                    "action": NOTIFY,
                    "data": {
                        "title": "{{ reply.data.headline }}",
                        "message": "{{ reply.data.body }}",
                        "data": {
                            "group": "stride-coach",
                            "tag": "stride-coach-{{ kind }}",
                            "url": COACH_DASHBOARD,
                        },
                    },
                }],
            },
        ],
    }


def call_coach(kind: str, extra_conditions=None, triggers=None, alias=""):
    return {
        "alias": alias,
        "mode": "single",
        "triggers": triggers,
        "conditions": extra_conditions or [],
        "actions": [{
            "action": "script.stride_coach",
            "data": {"kind": kind, "task": KINDS[kind]},
        }],
    }


AUTOMATIONS = {
    "stride_coach_morning": call_coach(
        "morning",
        triggers=[{"trigger": "time", "at": "07:30:00"}],
        alias="STRIDE — coach morning check-in",
    ),
    "stride_coach_post_workout": call_coach(
        "post_workout",
        triggers=[{"trigger": "state",
                   "entity_id": treadmill("mode"),
                   "to": "summary",
                   "for": {"seconds": 20}}],
        extra_conditions=[{"condition": "numeric_state",
                           "entity_id": treadmill("distance"),
                           "above": 100}],
        alias="STRIDE — coach post-workout reflection",
    ),
    "stride_coach_weekly": call_coach(
        "weekly",
        triggers=[{"trigger": "time", "at": "19:00:00"}],
        extra_conditions=[{"condition": "time", "weekday": ["sun"]}],
        alias="STRIDE — coach weekly review",
    ),
    "stride_coach_slump": call_coach(
        "slump",
        triggers=[{"trigger": "time", "at": "17:30:00"}],
        extra_conditions=[{
            "condition": "template",
            "value_template": "{{ states('sensor.treadmill_distance_daily') | float(0) < 100 "
                              "and states('sensor.treadmill_distance_weekly') | float(0) < 100 }}",
        }],
        alias="STRIDE — coach slump nudge",
    ),
}


def main():
    tok = token()
    print(" script:", post("/api/config/script/config/stride_coach", build_script(), tok))
    for slug, cfg in AUTOMATIONS.items():
        print(f"  {slug:<28}", post(f"/api/config/automation/config/{slug}", cfg, tok))
    post("/api/services/automation/reload", {}, tok)
    post("/api/services/script/reload", {}, tok)
    print("reloaded")


if __name__ == "__main__":
    main()
