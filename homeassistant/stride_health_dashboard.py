#!/usr/bin/env python3
"""
Install the STRIDE health dashboard into Home Assistant.

Creates a *new* dashboard at /stride-health and leaves `stride-console` exactly
where it is. That one proved the plumbing — live treadmill state, the coach,
some body numbers bolted on the end — and it is still the right place to stand
while a walk is happening. This is the other thing: what the numbers have been
doing over years, which is a different question and wants a different page.

Run:  python3 ha/stride_health_dashboard.py
      python3 ha/stride_health_dashboard.py --dry-run    # print, change nothing
      python3 ha/stride_health_dashboard.py --remove     # take it away again

WHAT THIS IS BUILT AROUND
-------------------------
Three facts about the data, all checked against the recorder rather than
assumed, because a dashboard is only as honest as its worst card.

* **The body history is deep and the rest is not.** Weight goes back to
  October 2012 (61 months of statistics), body fat to 2012 (45), blood pressure
  to January 2015 (28), muscle mass to 2021 (30). The daily activity series
  backfilled out of the Withings export cover 24 months. Everything else is
  days old at best.

* **Sleep, HRV, SpO2 and resting heart rate stop in April 2026.** The watch
  came off around then. It is back on now, but the readings still have no route
  into HA until the Shortcut below exists, so the line will stay stopped for a
  while yet. Drawn anyway — seventeen months of sleep is worth looking at — but
  the cards say why the tail is flat, because a gap you cannot explain looks
  like a habit you did not have.

* **Apple Health is not connected yet.** The webhook and its five sensors are
  installed and every one of them is `unknown`; the iPhone Shortcut that would
  POST to them is future work. Those five get a card of their own that says so.
  An empty slot you can see is a to-do list. An empty slot you cannot see is a
  bug you will chase later.

THE TWO RULES THAT ARE NOT NEGOTIABLE
-------------------------------------
Both are carried over from the coach, for the same reasons, and they constrain
the layout as much as they constrain the words:

* **Blood pressure is a tracked trend, never an alarm.** Sam has
  readings here are trends, not diagnoses. So the BP cards use no severity
  colour, no thresholds, no red. Today's 143/95 is plotted on the same axis as
  2015 and left to speak for itself. A dashboard that turns amber every morning
  teaches you to stop looking at it.

* **Never invent a trend.** Arithmetic against a target is a fact and is fine
  ("5.0 kg to go"). Direction-of-travel language over a handful of readings is
  not, and is absent. Where a series is too thin to say anything, the card says
  how thin it is instead.

The working weight target comes from `weight_target_kg` in stride.conf. `withings_weight_goal`
says 83.0; that is the app's number and chasing fifteen would just sound
relentless, so it appears nowhere on this dashboard.
"""
import json
import os
import re
import sys
import urllib.request

from stride_config import HA, conf, mqtt, token


URL_PATH = "stride-health"
TITLE = "Health"

# --- entities ---------------------------------------------------------------
WEIGHT = "sensor.withings_weight"
FAT_RATIO = "sensor.withings_fat_ratio"
FAT_MASS = "sensor.withings_fat_mass"
LEAN = "sensor.withings_fat_free_mass"
MUSCLE = "sensor.withings_muscle_mass"
BONE = "sensor.withings_bone_mass"
SYS = "sensor.withings_systolic_blood_pressure"
DIA = "sensor.withings_diastolic_blood_pressure"
PULSE = "sensor.withings_heart_pulse"

STEPS_TODAY = "sensor.withings_steps_today"
DIST_TODAY = "sensor.withings_distance_travelled_today"
CALS_TODAY = "sensor.withings_active_calories_burnt_today"
ACTIVE_TODAY = "sensor.withings_active_time_today"

COACH = "sensor.stride_coach"
LAST_WEIGH = "input_datetime.stride_last_weigh_in"
LAST_BP = "input_datetime.stride_last_bp"

# Optional. Blank means the dashboard simply does not draw a target line.
# Console walkers to show tiles for. Empty is fine: the entities exist
# either way and can be found under the STRIDE devices.
PEOPLE = [p for p in conf("console_people", "").split(",") if p]

TARGET = float(conf("weight_target_kg", "0") or 0)
# Backfilled out of the Health Mate export. Daily counters carry a running sum,
# so these chart as sums; the body and heart series carry a mean.
S_STEPS = "stride:steps_daily"
S_DIST = "stride:distance_daily"
S_CALS = "stride:calories_daily"
S_ELEV = "stride:elevation_daily"
S_HR = "stride:heart_rate"
S_HRV = "stride:hrv_sdnn"
S_SPO2 = "stride:spo2"
S_SLEEP = "stride:sleep_duration"
S_SLEEP_HR = "stride:sleep_heart_rate"

# The whole Apple Health surface, grouped the way the iOS app groups it. The
# dashboard does not hardcode which of these are live — see COVERAGE_CARD.
APPLE_GROUPS = [
    ("Heart", [
        ("sensor.apple_health_resting_heart_rate", "Resting heart rate"),
        ("sensor.apple_health_heart_rate_variability", "HRV"),
        ("sensor.apple_health_walking_heart_rate", "Walking heart rate"),
        ("sensor.apple_health_heart_rate_average", "Heart rate, average"),
        ("sensor.apple_health_heart_rate_low", "Heart rate, low"),
        ("sensor.apple_health_heart_rate_high", "Heart rate, high"),
        ("sensor.apple_health_blood_oxygen", "Blood oxygen"),
        ("sensor.apple_health_respiratory_rate", "Respiratory rate"),
        ("sensor.apple_health_vo2_max", "VO2 max"),
    ]),
    ("Sleep", [
        ("sensor.apple_health_sleep", "Asleep"),
        ("sensor.apple_health_sleep_core", "Core"),
        ("sensor.apple_health_sleep_deep", "Deep"),
        ("sensor.apple_health_sleep_rem", "REM"),
        ("sensor.apple_health_sleep_awake", "Awake"),
        ("sensor.apple_health_in_bed", "In bed"),
    ]),
    ("Activity", [
        ("sensor.apple_health_steps", "Steps"),
        ("sensor.apple_health_walk_run_distance", "Walk + run distance"),
        ("sensor.apple_health_exercise_minutes", "Exercise"),
        ("sensor.apple_health_stand_time", "Stand time"),
        ("sensor.apple_health_active_energy", "Active energy"),
        ("sensor.apple_health_resting_energy", "Resting energy"),
        ("sensor.apple_health_flights_climbed", "Flights climbed"),
        ("sensor.apple_health_time_in_daylight", "Time in daylight"),
        ("sensor.apple_health_physical_effort", "Physical effort"),
    ]),
    ("Rings", [
        ("sensor.apple_health_move_ring", "Move"),
        ("sensor.apple_health_exercise_ring", "Exercise"),
        ("sensor.apple_health_stand_ring", "Stand"),
    ]),
    ("Walking quality", [
        ("sensor.apple_health_walking_speed", "Walking speed"),
        ("sensor.apple_health_step_length", "Step length"),
        ("sensor.apple_health_walking_asymmetry", "Walking asymmetry"),
        ("sensor.apple_health_double_support", "Double support"),
        ("sensor.apple_health_stair_ascent_speed", "Stair ascent speed"),
    ]),
]

RINGS = [
    ("sensor.apple_health_move_ring", "sensor.apple_health_move_goal", "Move", "kcal"),
    ("sensor.apple_health_exercise_ring", "sensor.apple_health_exercise_goal", "Exercise", "min"),
    ("sensor.apple_health_stand_ring", "sensor.apple_health_stand_goal", "Stand", "h"),
]

LAST_WORKOUT = [
    ("sensor.apple_health_last_workout", "Type"),
    ("sensor.apple_health_last_workout_duration", "Duration"),
    ("sensor.apple_health_last_workout_distance", "Distance"),
    ("sensor.apple_health_last_workout_energy", "Energy"),
    ("sensor.apple_health_last_workout_hr_average", "Heart rate, average"),
    ("sensor.apple_health_last_workout_hr_max", "Heart rate, peak"),
    ("sensor.apple_health_last_workout_climb", "Climbed"),
]

APPLE = [e for _, rows in APPLE_GROUPS for e in rows]


# --- card helpers -----------------------------------------------------------
def heading(text, icon=None, style="title"):
    c = {"type": "heading", "heading": text, "heading_style": style}
    if icon:
        c["icon"] = icon
    return c


def grid(cards, span=1):
    g = {"type": "grid", "cards": cards}
    if span > 1:
        g["column_span"] = span
    return g


def tile(entity, name, icon=None, colour=None, features=None):
    c = {"type": "tile", "entity": entity, "name": name}
    if icon:
        c["icon"] = icon
    if colour:
        c["color"] = colour
    if features:
        c["features"] = features
        c["features_position"] = "bottom"
    return c


def markdown(content):
    return {"type": "markdown", "content": content}


def stats(entities, days, period, types, chart="line", title=None):
    """A long-term-statistics chart. Takes statistic ids as well as entities,
    which is how the backfilled `stride:` series get drawn at all."""
    c = {
        "type": "statistics-graph",
        "entities": entities,
        "days_to_show": days,
        "period": period,
        "stat_types": types,
        "chart_type": chart,
        "hide_legend": False,
    }
    if title:
        c["title"] = title
    return c


# --- Today ------------------------------------------------------------------
# One screen that answers "where am I". Nothing here is a chart: charts are for
# the other three views, and the point of this one is the current number and
# how old it is.
COACH_CARD = markdown(
    "### {{ states('" + COACH + "') }}\n\n"
    "{{ state_attr('" + COACH + "','body') }}\n\n"
    "<small>{{ state_attr('" + COACH + "','source') }} · "
    "{{ relative_time(states." + COACH + ".last_changed) }} ago</small>"
)

# Arithmetic against the target, which is a fact. No direction-of-travel
# language: three months of readings is not a trend and saying so would be
# the exact failure mode the coach is forbidden.
WEIGHT_CARD = markdown(
    "{% set w = states('" + WEIGHT + "') | float(0) %}"
    "{% set togo = (w - " + str(TARGET) + ") | round(1) %}"
    "## {{ w | round(1) }} kg\n\n"
    "Working target **" + str(TARGET) + " kg** — "
    "{% if togo > 0 %}**{{ togo }} kg** to go"
    "{% else %}**{{ (togo * -1) }} kg** under it{% endif %}.\n\n"
    "<small>Last weighed "
    "{{ relative_time(states." + LAST_WEIGH + ".state | as_datetime | as_local) }}"
    " ago.</small>"
)

# No thresholds, no colour, no adjective. See the header.
BP_CARD = markdown(
    "## {{ states('" + SYS + "') | int(0) }}/{{ states('" + DIA + "') | int(0) }}"
    " <small>mmHg</small>\n\n"
    "Resting pulse {{ states('" + PULSE + "') | int(0) }} bpm.\n\n"
    "<small>Last taken "
    "{{ relative_time(states." + LAST_BP + ".state | as_datetime | as_local) }}"
    " ago. Tracked as a trend — see Heart &amp; rest.</small>"
)

# Computed at render time, never hardcoded.
#
# The first version of this card named six sensors and asserted all six were
# waiting on the phone. Within an hour of the iOS app landing three of them
# were live and the card was lying — the precise failure it was written to
# prevent, just pointed the other way. A card that states which data exists has
# to *ask*, because which data exists is the thing that changes.
#
# The wider principle, and it reaches well past this card: people share what
# they are willing to share. Health permissions are per-category and revocable,
# a watch is worn some days and not others, and a metric can go quiet for a
# month and come back. Anything here that assumes a fixed shape of data will be
# wrong by the weekend.
# Built by concatenating onto a `namespace`, which looks clumsier than
# appending to a list and is the only option: Home Assistant's Jinja sandbox
# rejects `list.append` outright as unsafe. A namespace attribute is the
# sanctioned way to carry state across loop iterations here.
# A metric that has *never* arrived is a missing device, not a missing reading,
# and listing it every day is noise about something that will never change.
# Not everyone owns hardware that reports VO2 max, In Bed or stair ascent
# speed, and for them "nothing yet from VO2 max" is a permanent line on a live
# dashboard about a device they will never have.
#
# **`unknown` is a reliable signal here, and only because of how the receiver
# works.** Every Apple Health sensor uses `value_json.<key> | default(this.state)`,
# so once a metric has arrived even once it keeps its last value forever and can
# never fall back to `unknown`. Therefore `unknown` means "has never once been
# received" — it cannot be produced by a sync outage, a locked phone or a flat
# watch battery. Hiding on it cannot hide a fault.
#
# The one case that must stay loud is *everything* going quiet, because that is
# not an absent device — that is the pipeline being broken. So the naming
# behaviour is kept for that, and only that.
COVERAGE_CARD = markdown(
    "{% set ns = namespace(live=0, total=0, quiet=0, out='') %}"
    + "".join(
        "{% set g = namespace(names='') %}"
        + "".join(
            "{% set ns.total = ns.total + 1 %}"
            "{% if states('" + ent + "') in ['unknown','unavailable','none'] %}"
            "{% set ns.quiet = ns.quiet + 1 %}"
            "{% set g.names = g.names ~ (', ' if g.names else '') ~ '" + name + "' %}"
            "{% else %}{% set ns.live = ns.live + 1 %}{% endif %}"
            for ent, name in rows
        )
        + "{% if g.names %}{% set ns.out = ns.out ~ '**" + title
        + "** — nothing yet from ' ~ g.names ~ '.\n\n' %}{% endif %}"
        for title, rows in APPLE_GROUPS
    )
    # Nothing at all: a fault, and the only case worth shouting about.
    + "{% if ns.live == 0 %}"
    "**No Apple Health data is arriving.**\n\n"
    "Every metric is empty, which is a broken pipeline rather than missing "
    "hardware. Check the app has the webhook URL and that Health access was "
    "granted."
    # Most of them quiet: also a fault — a whole category declined in the
    # permission sheet looks exactly like this — so name them.
    "{% elif ns.quiet * 2 > ns.total %}"
    "**{{ ns.live }} of {{ ns.total }} Apple Health metrics arriving.**\n\n"
    "{{ ns.out }}"
    "<small>That is most of them quiet at once, which usually means a whole "
    "category was left switched off in the Health permission sheet rather than "
    "a device that is missing.</small>"
    # A handful quiet against a healthy majority: hardware this setup does not
    # have. Counted, not named, and never listed again.
    "{% elif ns.quiet > 0 %}"
    "**{{ ns.live }} Apple Health metrics arriving.**\n\n"
    "<small>{{ ns.quiet }} others have never reported once and are hidden. They "
    "need hardware or settings this setup does not have, so they are treated as "
    "absent rather than missing — they will appear on their own if that ever "
    "changes.</small>"
    "{% else %}"
    "**{{ ns.live }} Apple Health metrics arriving.**\n\n"
    "Everything the app asks for is coming through."
    "{% endif %}"
)

# The rings as text, not three gauges. A ring only means something against the
# goal it was set for, and the goal moves.
RINGS_CARD = markdown(
    "".join(
        "{% if states('" + val + "') not in ['unknown','unavailable'] %}"
        "**" + label + "** {{ states('" + val + "') | round(0) }}"
        "{% if states('" + goal + "') not in ['unknown','unavailable'] %}"
        " / {{ states('" + goal + "') | round(0) }}{% endif %} " + unit + "  \n"
        "{% endif %}"
        for val, goal, label, unit in RINGS
    )
    + "{% if states('sensor.apple_health_move_ring') in "
      "['unknown','unavailable'] %}No rings recorded yet today.{% endif %}"
)

# The last workout the *watch* recorded — which is not the last treadmill
# session, and is deliberately not merged with it. Two different machines
# measuring two different walks.
LAST_WORKOUT_CARD = markdown(
    "{% set t = states('sensor.apple_health_last_workout') %}"
    "{% if t in ['unknown','unavailable'] %}Nothing recorded yet.{% else %}"
    "### {{ t }}\n\n"
    "{% set d = states('sensor.apple_health_last_workout_duration') %}"
    "{% set m = states('sensor.apple_health_last_workout_distance') %}"
    "{% set kc = states('sensor.apple_health_last_workout_energy') %}"
    "{% set hr = states('sensor.apple_health_last_workout_hr_average') %}"
    "{% set hx = states('sensor.apple_health_last_workout_hr_max') %}"
    "{% set cl = states('sensor.apple_health_last_workout_climb') %}"
    "{% if d not in ['unknown','unavailable'] %}{{ d }} min{% endif %}"
    "{% if m not in ['unknown','unavailable'] %} · "
    "{{ (m | float / 1000) | round(2) }} km{% endif %}"
    "{% if kc not in ['unknown','unavailable'] %} · {{ kc }} kcal{% endif %}\n\n"
    "{% if hr not in ['unknown','unavailable'] %}Heart rate {{ hr }} average"
    "{% if hx not in ['unknown','unavailable'] %}, {{ hx }} peak{% endif %}. "
    "{% endif %}"
    "{% if cl not in ['unknown','unavailable'] %}Climbed {{ cl }} m."
    "{% endif %}\n\n"
    "<small>{{ relative_time(states('sensor.apple_health_last_workout_start') "
    "| as_datetime | as_local) }} ago · "
    "{{ states('sensor.apple_health_workouts_7_days') }} in the last seven days"
    "</small>{% endif %}"
)

TODAY = {
    "title": "Today",
    "path": "today",
    "icon": "mdi:white-balance-sunny",
    "type": "sections",
    "max_columns": 3,
    "sections": [
        grid([heading("Coach", "mdi:account-voice"), COACH_CARD]),
        grid([
            heading("Weight", "mdi:scale-bathroom"),
            WEIGHT_CARD,
            tile(WEIGHT, "Weight",
                 features=[{"type": "trend-graph", "hours_to_show": 2160}]),
        ]),
        grid([
            heading("Blood pressure", "mdi:heart-pulse"),
            BP_CARD,
        ]),
        grid([
            heading("Movement today", "mdi:walk"),
            tile(STEPS_TODAY, "Steps", "mdi:shoe-print", "green"),
            tile(DIST_TODAY, "Distance", "mdi:map-marker-distance", "blue"),
            tile(ACTIVE_TODAY, "Active time", "mdi:timer-outline", "purple"),
            tile(CALS_TODAY, "Active calories", "mdi:fire", "orange"),
        ], span=2),
        grid([
            heading("On the treadmill", "mdi:run-fast", "subtitle"),
            tile("sensor.treadmill_distance_daily", "Today"),
            tile("sensor.treadmill_distance_weekly", "This week"),
            tile("sensor.treadmill_workouts_this_week", "Walks this week"),
            markdown("[Live console →](/stride-console/workout)"),
        ]),
        grid([
            heading("Last workout", "mdi:watch", "subtitle"),
            LAST_WORKOUT_CARD,
        ]),
        grid([
            heading("Rings", "mdi:circle-slice-5", "subtitle"),
            RINGS_CARD,
        ]),
        grid([
            heading("Apple Health coverage", "mdi:heart-plus", "subtitle"),
            COVERAGE_CARD,
        ], span=2),
    ],
}

# --- Body -------------------------------------------------------------------
# The one place with real depth. Three ranges of the same series, because a
# fourteen-year line and a ninety-day line answer completely different
# questions and neither substitutes for the other.
BODY = {
    "title": "Body",
    "path": "body",
    "icon": "mdi:scale-bathroom",
    "type": "sections",
    "max_columns": 2,
    "sections": [
        grid([
            heading("Weight, all of it", "mdi:chart-line"),
            markdown(
                "Monthly means back to October 2012. The gap in the middle is "
                "real — there was no scale in the house for a while."
            ),
            stats([WEIGHT], 5200, "month", ["mean", "min", "max"]),
        ], span=2),
        grid([
            heading("This year", "mdi:calendar", "subtitle"),
            stats([WEIGHT], 365, "day", ["mean"]),
        ]),
        grid([
            heading("Last 90 days", "mdi:calendar-week", "subtitle"),
            stats([WEIGHT], 90, "day", ["mean"]),
        ]),
        grid([
            heading("What it is made of", "mdi:human"),
            tile(WEIGHT, "Weight"),
            tile(FAT_MASS, "Fat mass"),
            tile(LEAN, "Fat-free mass"),
            tile(MUSCLE, "Muscle"),
            tile(BONE, "Bone"),
            tile(FAT_RATIO, "Body fat"),
        ]),
        grid([
            heading("Composition over time", "mdi:chart-areaspline", "subtitle"),
            stats([FAT_RATIO], 5200, "month", ["mean"], title="Body fat %"),
            stats([FAT_MASS, LEAN, MUSCLE], 1825, "month", ["mean"],
                  title="Fat, lean and muscle (kg)"),
        ]),
    ],
}

# --- Heart & rest -----------------------------------------------------------
HEART = {
    "title": "Heart & rest",
    "path": "heart",
    "icon": "mdi:heart-pulse",
    "type": "sections",
    "max_columns": 2,
    "sections": [
        grid([
            heading("Blood pressure", "mdi:heart-pulse"),
            markdown(
                "Plotted, not judged. This is a line to watch over months — "
                "there is no threshold colouring on this page and there will "
                "not be. Single readings move around with the time of day, the "
                "cuff and the five minutes beforehand; the shape of a year is "
                "the thing worth reading."
            ),
            stats([SYS, DIA], 4000, "month", ["mean"]),
        ], span=2),
        grid([
            heading("Latest", "mdi:clipboard-pulse", "subtitle"),
            tile(SYS, "Systolic"),
            tile(DIA, "Diastolic"),
            tile(PULSE, "Resting pulse"),
        ]),
        grid([
            heading("Last 12 months", "mdi:calendar", "subtitle"),
            stats([SYS, DIA], 365, "day", ["mean"]),
        ]),
        grid([
            heading("Sleep", "mdi:sleep"),
            markdown(
                "**This line stops in April 2026** — that is where the Health "
                "Mate export ends and where the watch came off. It is back on "
                "now; the readings resume here once the Shortcut is posting. "
                "The flat tail is missing data, not a settled habit."
            ),
            stats([S_SLEEP], 1825, "month", ["mean"], title="Hours a night"),
            stats([S_SLEEP_HR], 1825, "month", ["mean"], title="Sleeping heart rate"),
        ]),
        grid([
            heading("Resting signals", "mdi:heart-outline"),
            markdown(
                "Same export, same April stop, same reason. HRV has four "
                "monthly points in it — far too few to read as anything, and "
                "it is here to be filled in rather than interpreted."
            ),
            stats([S_HR], 1825, "month", ["mean"], title="Heart rate"),
            stats([S_SPO2], 1825, "month", ["mean"], title="SpO₂"),
            stats([S_HRV], 1825, "month", ["mean"], title="HRV (SDNN)"),
        ]),
    ],
}

# --- Movement ---------------------------------------------------------------
# Sum series, so these are bars: a month of steps is a total, not an average,
# and drawing it as a line would invite reading the gradient as a trend.
MOVEMENT = {
    "title": "Movement",
    "path": "movement",
    "icon": "mdi:walk",
    "type": "sections",
    "max_columns": 2,
    "sections": [
        grid([
            heading("Steps", "mdi:shoe-print"),
            markdown(
                "Two years, out of the Health Mate export. Today's count lives "
                "on the live sensor and the two meet at the export date — see "
                "the backfill note in `ha-treadmill.md` for why they are "
                "separate series."
            ),
            stats([S_STEPS], 1825, "month", ["sum"], chart="bar"),
            stats([S_STEPS], 90, "day", ["sum"], chart="bar", title="Last 90 days"),
        ], span=2),
        grid([
            heading("Distance walked", "mdi:map-marker-distance"),
            stats([S_DIST], 1825, "month", ["sum"], chart="bar"),
        ]),
        grid([
            heading("Climbed", "mdi:stairs-up"),
            stats([S_ELEV], 1825, "month", ["sum"], chart="bar"),
        ]),
        grid([
            heading("The treadmill", "mdi:run-fast"),
            markdown(
                "Started this month, so there is not much of it yet. It is the "
                "only series here that will be complete from the first day."
            ),
            tile("sensor.treadmill_workouts_this_week", "Walks this week",
                 "mdi:counter", "green"),
            tile("sensor.treadmill_time_walked_this_week", "Time walked",
                 "mdi:clock-outline", "blue"),
            tile("sensor.treadmill_distance_weekly", "Distance this week",
                 "mdi:map-marker-distance", "purple"),
            tile("sensor.treadmill_calories_weekly", "Calories this week",
                 "mdi:fire", "red"),
            tile("sensor.treadmill_distance_monthly", "Distance this month"),
        ]),
        grid([
            heading("Who has been on it", "mdi:account-group", "subtitle"),
            # Per-person treadmill entities appear as people actually walk —
            # the console publishes discovery for somebody the first time they
            # use it, so there is no fixed list to hardcode here.
            *[tile(f"sensor.stride_{p}_treadmill_distance", f"{p.title()} · distance")
              for p in PEOPLE],
            tile("sensor.stride_alex_alex_treadmill_distance", "Alex · distance"),
            tile("sensor.stride_alex_alex_last_walk", "Alex · last walk"),
        ]),
        grid([
            heading("Energy", "mdi:fire"),
            stats([S_CALS], 1825, "month", ["sum"], chart="bar",
                  title="Calories burnt, monthly"),
        ], span=2),
    ],
}

CONFIG = {"views": [TODAY, BODY, HEART, MOVEMENT]}


# --- install ----------------------------------------------------------------


class Socket:
    """The dashboard API is websocket-only; there is no REST equivalent."""

    def __init__(self, tok):
        import websocket
        self.ws = websocket.create_connection(WS, timeout=30)
        self.ws.recv()
        self.ws.send(json.dumps({"type": "auth", "access_token": tok}))
        if json.loads(self.ws.recv())["type"] != "auth_ok":
            sys.exit("home assistant rejected the token")
        self.n = 0

    def call(self, msg):
        self.n += 1
        msg["id"] = self.n
        self.ws.send(json.dumps(msg))
        while True:
            r = json.loads(self.ws.recv())
            if r.get("id") == self.n:
                if not r.get("success", True):
                    sys.exit(f"{msg['type']}: {r.get('error')}")
                return r.get("result")


def main():
    dry = "--dry-run" in sys.argv
    remove = "--remove" in sys.argv

    if dry:
        print(json.dumps(CONFIG, indent=1))
        views = ", ".join(v["title"] for v in CONFIG["views"])
        print(f"\n-- dry run: {len(CONFIG['views'])} views ({views}), nothing written")
        return

    s = Socket(token())
    existing = {d["url_path"]: d for d in s.call({"type": "lovelace/dashboards/list"})}

    if remove:
        if URL_PATH not in existing:
            print(f"{URL_PATH} is not there")
            return
        s.call({"type": "lovelace/dashboards/delete",
                "dashboard_id": existing[URL_PATH]["id"]})
        print(f"removed /{URL_PATH}")
        return

    if URL_PATH in existing:
        print(f"/{URL_PATH} exists — updating its config")
    else:
        s.call({"type": "lovelace/dashboards/create",
                "url_path": URL_PATH, "title": TITLE,
                "icon": "mdi:heart-pulse", "mode": "storage",
                "show_in_sidebar": True, "require_admin": False})
        print(f"created /{URL_PATH}")

    s.call({"type": "lovelace/config/save", "url_path": URL_PATH, "config": CONFIG})
    for v in CONFIG["views"]:
        print(f"  {v['title']:<14} {len(v['sections'])} sections")
    print(f"\n{HA}/{URL_PATH}/today")
    print("sam-workout left untouched.")


if __name__ == "__main__":
    main()
