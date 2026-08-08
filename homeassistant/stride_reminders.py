#!/usr/bin/env python3
"""
Install STRIDE's measurement-cadence automations into Home Assistant.

Four automations, two pairs:

  *_stamp      record when a real reading actually arrived
  *_remind     nudge when one is due

The stamps exist because `last_changed` on the Withings sensors is not
trustworthy for this: it resets on every HA restart, which would silently
suppress reminders for a day. An input_datetime survives restarts, so the
cadence is anchored to when a measurement genuinely landed.

Cadence is deliberately configurable rather than baked in — Sam is on BP
medication and his GP's advice outranks any default we pick.

Run:  python3 ha/stride_reminders.py
"""
import json
import urllib.request

from stride_coach_llm import KINDS
from stride_config import HA, conf, token


WEIGHT = conf("weight_entity", "")
SYSTOLIC = conf("systolic_entity", "")
DIASTOLIC = conf("diastolic_entity", "")


def post(path: str, payload: dict, tok: str):
    req = urllib.request.Request(
        f"{HA}{path}",
        data=json.dumps(payload).encode(),
        headers={"Authorization": f"Bearer {tok}", "Content-Type": "application/json"},
        method="POST",
    )
    return urllib.request.urlopen(req).read().decode()



def stamp(slug: str, entity: str, target: str) -> dict:
    return {
        "alias": f"STRIDE — stamp {slug}",
        "description": "Records that a real measurement arrived, so reminders "
        "survive an HA restart.",
        "mode": "single",
        "triggers": [{"trigger": "state", "entity_id": entity}],
        "conditions": [
            {
                "condition": "template",
                "value_template": "{{ trigger.to_state.state not in "
                "['unknown','unavailable','none'] and "
                "trigger.from_state.state != trigger.to_state.state }}",
            }
        ],
        "actions": [
            {
                "action": "input_datetime.set_datetime",
                "target": {"entity_id": target},
                "data": {"datetime": "{{ now().strftime('%Y-%m-%d %H:%M:%S') }}"},
            }
        ],
    }


def remind(slug: str, label: str, at: str, last: str, interval: str,
           task: str) -> dict:
    return {
        "alias": f"STRIDE — {label} reminder",
        "description": "Nudges only when one is actually due. A fixed cadence is "
        "the point: it stops readings being taken at random, which makes the "
        "trend meaningless.",
        "mode": "single",
        "triggers": [{"trigger": "time", "at": at}],
        "conditions": [
            {
                "condition": "template",
                "value_template": (
                    "{%% set last = states('%s') %%}"
                    "{%% set days = states('%s') | float(1) %%}"
                    "{{ last in ['unknown','unavailable'] or "
                    "(now() - (last | as_datetime | as_local)).total_seconds() "
                    ">= days * 86400 - 3600 }}" % (last, interval)
                ),
            }
        ],
        # There is one coach, and it speaks in one voice to both the dashboard
        # card and the phone. This used to publish its own fixed sentence
        # straight to the coach feed and send its own notification, which meant
        # two authors writing into one retained slot: a nag would land on top of
        # the morning session plan and wipe it, in noticeably different prose.
        #
        # Now the reminder decides *that* something is due and the coach decides
        # what to say about it. The script publishes the message and sends the
        # push itself, so this stays a trigger rather than a second author.
        "actions": [
            {
                "action": "script.stride_coach",
                "data": {"kind": f"{slug}_due", "task": task},
            },
        ],
    }


AUTOMATIONS = {
    "stride_stamp_weigh_in": stamp("weigh-in", WEIGHT, "input_datetime.stride_last_weigh_in"),
    "stride_stamp_bp": stamp("BP", SYSTOLIC, "input_datetime.stride_last_bp"),
    "stride_remind_weigh_in": remind(
        "weigh_in", "weigh-in", "input_datetime.stride_weigh_in_time",
        "input_datetime.stride_last_weigh_in", "input_number.stride_weigh_in_interval",
        KINDS["weigh_in_due"],
    ),
    "stride_remind_bp": remind(
        "bp", "BP", "input_datetime.stride_bp_time",
        "input_datetime.stride_last_bp", "input_number.stride_bp_interval",
        KINDS["bp_due"],
    ),
}


def main():
    tok = token()
    # An unconfigured entity would post a stamp automation watching nothing,
    # which Home Assistant rejects with a message that names neither the
    # automation nor the missing setting. Say which, and leave whatever is
    # already installed alone rather than replacing it with something broken.
    missing = [k for k, v in (("weight_entity", WEIGHT),
                              ("systolic_entity", SYSTOLIC)) if not v]
    if missing:
        print("  not configured, skipping the stamps that need them: "
              + ", ".join(missing))

    for slug, cfg in AUTOMATIONS.items():
        if slug.startswith("stride_stamp_") and not cfg["triggers"][0].get("entity_id"):
            continue
        print(f"  {slug:<26} {post(f'/api/config/automation/config/{slug}', cfg, tok)}")
    post("/api/services/automation/reload", {}, tok)
    print("reloaded")


if __name__ == "__main__":
    main()
