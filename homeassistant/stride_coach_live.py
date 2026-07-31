#!/usr/bin/env python3
"""
Install the live, in-workout half of the STRIDE coach.

The console decides *when* to speak — it sees the session at 5 Hz and knows
exactly when a kilometre went by (see `Coach.kt`). This side decides *what* is
said, because that is the only part that needs a language model, and the console
cannot reach one: its WAN was blocked for most of this project's life, and even
with that lifted the API key has no business living on a treadmill.

    console  --MQTT stride/treadmill/event-->  here
    here     --MQTT stride/coach/live------->  console (text + an mp3 URL)

Latency, measured on this HA instance: 4.0 s for a realistic prompt through
`ai_task.claude_ai_task` with structured output, plus TTS and two MQTT hops.
Roughly five seconds from crossing a kilometre to hearing about it, which is
still the same moment. That is why this reuses the same opus-5 entity as the
reflective coach rather than adding a faster, plainer one — the words matter
more than two seconds, and one voice means one persona.

SPEECH NEEDS ONE PIECE OF YAML. Home Assistant has no `tts.get_url` *service* —
only `tts.speak`, which insists on a media_player entity, and the console cannot
be one. So an automation cannot produce the mp3 URL on its own. The alternative
was putting a long-lived HA token in the APK so the console could fetch its own
audio; that token grants full access to every device in the house, on the least
trusted machine on the network, which is a bad trade for a sentence about
running. Six lines of `configuration.yaml` keep the credential where it belongs:

    rest_command:
      stride_tts:
        url: "http://127.0.0.1:8123/api/tts_get_url"
        method: POST
        headers:
          authorization: !secret stride_ha_token
        content_type: "application/json"
        payload: '{"engine_id": {{ engine | to_json }}, "message": {{ message | to_json }}}'

with `stride_ha_token: "Bearer eyJ..."` in `secrets.yaml`. `to_json` on both,
not quotes: coach lines contain apostrophes and an unescaped one breaks the
payload.

Two things that cost an hour the first time. A first-time `rest_command` needs a
full HA **restart**, not a YAML reload — `rest_command.reload` only exists once
the integration has been set up once, so it cannot bootstrap itself. And TTS is
not ready for the first ~30 s after a restart: a call in that window fails with
`Provider ... not found` and a 500 that looks exactly like a config error.

Run:  python3 ha/stride_coach_live.py
"""
import json
import os
import re
import sys
import urllib.request

from stride_config import HA, ai_task, conf, mqtt, optional, token, treadmill

# The persona is imported rather than restated: two coaches drifting into two
# different personalities is exactly the failure this avoids.
from stride_coach_llm import HA, PERSONA, VOICES, token

# No default: see stride_config.ai_task for why.
AI_TASK = ai_task()
EVENT_TOPIC = "stride/treadmill/event"
LIVE_TOPIC = "stride/coach/live"

# Google Translate is a satnav, but it is what works here: `tts.cloud_say` is
# registered and every synthesis 500s, so that subscription is not active. A
# Piper add-on would be the upgrade and would change only this line.
TTS_ENGINE = "tts.google_translate_en_com"

W = conf("weight_entity", "")

# What each moment is for. The console sends the kind; this turns it into intent.
KINDS = {
    "warmup_done": "The warm-up just ended and the workout proper has begun. "
                   "Hand the session over to him.",
    "milestone": "He has just passed a distance marker. Mark it without ceremony.",
    "cooldown": "He has ended the workout and the belt is easing down. This is "
                "the last thing he hears while still moving.",
    "resumed": "He paused and has started moving again. Acknowledge the restart, "
               "not the pause.",
    "pace_drop": "His pace has been below his session average for a while. He may "
                 "be tiring or may have chosen to ease off — you do not know which, "
                 "so do not assume. Never tell him to speed up.",
    "steady": "He has held a steady pace for several minutes. Notice the rhythm.",
    "checkin": "Nothing in particular has happened. He is still going. Say "
               "something small, or something about the session so far.",
    "segment": "The ground is about to change. Say what is coming and what to do "
               "with it — this is heard a few seconds BEFORE it arrives, so speak "
               "in the future, not the past. Never announce a hill he is already "
               "climbing.",
    "summary": "The walk is ending. This one line sits on the summary screen, "
               "beside the distance, time, calories and climb, which are all "
               "already printed there in large numbers. "
               "DO NOT REPEAT ANY OF THEM. "
               "\"Good work, 3 km in 5 minutes\" is worthless — he is looking at "
               "the 3 km. "
               "Say the thing the screen cannot: what the walk was like, what he "
               "did well in it, what held up or did not, what it means next to "
               "the walks before it. If nothing stands out, one plain sentence "
               "is better than a manufactured observation. This is read on a "
               "screen, not aloud, so it can be a touch longer than a spoken "
               "line — but two sentences at most.",
}

# The rules that only apply mid-workout. The persona covers the rest.
LIVE_RULES = """
YOU ARE A COACH, NOT A COMMENTATOR
- He can see the numbers. Reading them back to him is worthless. Do not narrate
  what just happened — he already knows, he is standing on it.
- Coach him: ask for a little more or a little less, tell him what this stretch
  is for, tell him what to do with his body, or say the thing that gets him
  through the next two minutes.
- A suggestion beats an observation. "See if you can hold this to the top" beats
  "you are at four percent".
- Use his name almost never. Once in a session at most, and only if it lands.
  Every line starting with "Sam" is what a newsreader sounds like.

YOU ARE SPEAKING OUT LOUD, MID-WORKOUT
- This is read aloud through a speaker while Sam is walking or running. It is
  heard once and cannot be re-read.
- ONE sentence. At most 14 words. Shorter is better.
- Say numbers the way a person says them: "one kilometre", "twenty minutes".
  Never "1000 m" or "6.4 km/h".
- At most one number in the sentence. He cannot hold two while moving.
- No greeting, no sign-off, no question he has to answer.
- Never tell him to change the speed or the incline. You may observe; he decides
  and presses the button himself. This is a safety rule, not a stylistic one.
- Never repeat anything in ALREADY SAID, in wording or in substance.
- Never open two consecutive lines the same way.
"""


def post(path: str, payload: dict, tok: str) -> str:
    req = urllib.request.Request(
        f"{HA}{path}", data=json.dumps(payload).encode(),
        headers={"Authorization": f"Bearer {tok}", "Content-Type": "application/json"},
        method="POST")
    return urllib.request.urlopen(req).read().decode()


def get(path: str, tok: str):
    req = urllib.request.Request(
        f"{HA}{path}", headers={"Authorization": f"Bearer {tok}"})
    return json.loads(urllib.request.urlopen(req).read().decode())


def voice_block() -> str:
    """The same voice selector the reflective coach uses."""
    rest = "".join(f"{{% elif voice == '{k}' %}}{v}" for k, v in list(VOICES.items())[1:])
    return ("{% set voice = states('input_select.stride_coach_personality') %}"
            f"{{% if voice == 'Supportive Friend' %}}{VOICES['Supportive Friend']}"
            f"{rest}{{% endif %}}")


def kind_block() -> str:
    """Turn the event's `kind` into the intent for this particular line."""
    items = list(KINDS.items())
    out = "{% set k = trigger.payload_json.kind %}"
    out += f"{{% if k == '{items[0][0]}' %}}{items[0][1]}"
    for key, text in items[1:]:
        out += f"{{% elif k == '{key}' %}}{text}"
    out += "{% else %}" + KINDS["checkin"] + "{% endif %}"
    return out


INSTRUCTIONS = (
    PERSONA
    + "\n" + LIVE_RULES
    + "\nYOUR VOICE TODAY\n" + voice_block()
    + "\n\nWHAT JUST HAPPENED\n"
      "{{ trigger.payload_json.detail }}\n"
      + kind_block()
    + """

THE SESSION SO FAR
- Workout: {{ trigger.payload_json.workout }}
- Time so far: {{ (trigger.payload_json.elapsed / 60) | round(0) | int }} minutes
- Distance so far: {{ trigger.payload_json.distance }} m
- Pace right now: {{ trigger.payload_json.speed }} km/h (session average
  {{ trigger.payload_json.avg_speed }})
- Incline: {{ trigger.payload_json.incline }} %
- Calories: {{ trigger.payload_json.calories }}
{% if trigger.payload_json.pulse | int > 0 %}
- Heart rate: {{ trigger.payload_json.pulse }} bpm
{% else %}
- Heart rate: not measured — no strap is paired. Say nothing about it.
{% endif %}

HOW THIS COMPARES
- Treadmill distance today, including this session so far:
  {{ states('sensor.treadmill_distance_daily') | float(0) | round(0) | int }} m
- Treadmill distance this week:
  {{ states('sensor.treadmill_distance_weekly') | float(0) | round(0) | int }} m
- Treadmill distance this month:
  {{ states('sensor.treadmill_distance_monthly') | float(0) | round(0) | int }} m
  These are running totals, not single sessions. Do not describe any of them as
  one walk, and do not call anything a record — you have not been shown one.
- He weighs {{ states('""" + W + """') }} kg, working towards 5 kg down.
  Only mention weight if the moment genuinely calls for it, which is rarely.

ALREADY SAID THIS SESSION — do not repeat any of these
{% for line in trigger.payload_json.said %}- "{{ line }}"
{% else %}- (nothing yet; this is the first thing he will hear)
{% endfor %}
"""
)


def automation(with_voice: bool) -> dict:
    """
    The TTS step is included only when `rest_command.stride_tts` actually
    exists.

    It cannot be guarded at runtime instead: `continue_on_error` does not
    suppress a missing service, so an automation referencing one that is not
    configured aborts at that step and never publishes the line at all — the
    coach loses its voice *and* its words. Deciding at install time means a
    missing rest_command costs exactly the audio, which is the whole point of
    the fallback.
    """
    speak = [{
        "action": "rest_command.stride_tts",
        "data": {"engine": TTS_ENGINE, "message": "{{ reply.data.line }}"},
        "response_variable": "audio",
    }] if with_voice else []

    return {
        "alias": "STRIDE — coach live (in-workout)",
        "description": "Turns a treadmill moment into one spoken sentence and "
                       "sends it back to the console.",
        # Queued rather than single: two moments can legitimately land close
        # together, and dropping one silently is worse than answering late.
        "mode": "queued",
        "max": 3,
        "triggers": [{"trigger": "mqtt", "topic": EVENT_TOPIC}],
        "actions": [
            {
                "action": "ai_task.generate_data",
                "data": {
                    "task_name": "stride live coach",
                    "entity_id": AI_TASK,
                    "instructions": INSTRUCTIONS,
                    "structure": {
                        "line": {
                            "description": "The single sentence, spoken aloud. "
                                           "At most 14 words.",
                            "required": True,
                            "selector": {"text": None},
                        },
                    },
                },
                "response_variable": "reply",
            },
            *speak,
            {
                "action": "mqtt.publish",
                "data": {
                    "topic": LIVE_TOPIC,
                    # Never retained: a line from last week reappearing at the
                    # start of the next walk would be worse than silence.
                    "retain": False,
                    "payload": "{{ {'line': reply.data.line, "
                               "'kind': trigger.payload_json.kind, "
                               "'url': (audio.content.url "
                               "if audio is defined and audio.content is defined "
                               "else '')} | to_json }}",
                },
            },
        ],
    }


def main():
    tok = token()
    services = get("/api/services", tok)
    with_voice = any(d["domain"] == "rest_command" and "stride_tts" in d["services"]
                     for d in services)

    print(post("/api/config/automation/config/stride_coach_live",
               automation(with_voice), tok))
    post("/api/services/automation/reload", {}, tok)
    print("  automation installed and reloaded")

    if with_voice:
        print(f"  rest_command.stride_tts found — the coach speaks, via {TTS_ENGINE}")
    else:
        print("\n  rest_command.stride_tts is NOT configured, so the TTS step was")
        print("  left out. The coach appears on the HUD and stays silent. Add the")
        print("  YAML from the top of this file to configuration.yaml, restart HA,")
        print("  and run this again to give it a voice.")


if __name__ == "__main__":
    main()
