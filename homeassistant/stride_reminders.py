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
import os
import re
import sys
import urllib.request

from stride_config import HA, conf, mqtt, token


WEIGHT = conf("weight_entity", "")
SYSTOLIC = conf("systolic_entity", "")
DIASTOLIC = conf("diastolic_entity", "")
NOTIFY = conf("notify_service", "persistent_notification.create")
COACH_DASHBOARD = "/stride-health/today"


def post(path: str, payload: dict, tok: str):
    req = urllib.request.Request(
        f"{HA}{path}",
        data=json.dumps(payload).encode(),
        headers={"Authorization": f"Bearer {tok}", "Content-Type": "application/json"},
        method="POST",
    )
    return urllib.request.urlopen(req).read().decode()


def coach_message(headline: str, body: str, kind: str) -> dict:
    """Every coach utterance goes to the same retained topic, whoever wrote it."""
    return {
        "action": "mqtt.publish",
        "data": {
            "topic": "stride/coach/message",
            "retain": True,
            "payload": (
                "{{ %s | to_json }}"
                % json.dumps(
                    {"headline": headline, "body": body, "kind": kind, "source": "rules"}
                ).replace('"', "'")
            ),
        },
    }


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
           headline: str, body: str) -> dict:
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
        "actions": [
            coach_message(headline, body, f"{slug}_due"),
            {
                "action": NOTIFY,
                "data": {
                    "title": headline,
                    "message": body,
                    # Tapping lands on the coach dashboard rather than wherever
                    # the companion app was last. See COACH_DASHBOARD in
                    # stride_coach_llm.py for why.
                    "data": {
                        "group": "stride-coach",
                        "tag": f"stride-{slug}-due",
                        "url": COACH_DASHBOARD,
                    },
                },
            },
        ],
    }


AUTOMATIONS = {
    "stride_stamp_weigh_in": stamp("weigh-in", WEIGHT, "input_datetime.stride_last_weigh_in"),
    "stride_stamp_bp": stamp("BP", SYSTOLIC, "input_datetime.stride_last_bp"),
    "stride_remind_weigh_in": remind(
        "weigh_in", "weigh-in", "input_datetime.stride_weigh_in_time",
        "input_datetime.stride_last_weigh_in", "input_number.stride_weigh_in_interval",
        "Time to weigh in",
        "Same time each morning is what makes the trend mean anything. "
        "Last reading {{ relative_time(states.input_datetime.stride_last_weigh_in.state "
        "| as_datetime | as_local) }} ago.",
    ),
    "stride_remind_bp": remind(
        "bp", "BP", "input_datetime.stride_bp_time",
        "input_datetime.stride_last_bp", "input_number.stride_bp_interval",
        "Blood pressure check due",
        "Sit for a few minutes first, and keep it to the same time of day so the "
        "readings are comparable.",
    ),
}


def main():
    tok = token()
    for slug, cfg in AUTOMATIONS.items():
        print(f"  {slug:<26} {post(f'/api/config/automation/config/{slug}', cfg, tok)}")
    post("/api/services/automation/reload", {}, tok)
    print("reloaded")


if __name__ == "__main__":
    main()
