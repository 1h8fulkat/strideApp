#!/usr/bin/env python3
"""
Receiving end for Apple Health data, without paying for a sync service.

iOS Shortcuts can read HealthKit natively ("Find Health Samples") and POST
anywhere, so nothing third-party is needed: a daily Personal Automation runs a
Shortcut, the Shortcut POSTs a small JSON blob here, and this republishes it to
MQTT where HA discovery turns it into real sensors.

Health Auto Export's Premium tier only buys *background* sync. A Shortcut fired
by a time-of-day automation gets the same data for nothing; the trade is that it
runs when the automation runs rather than continuously, which for daily metrics
like sleep and resting heart rate is no trade at all.

Expected payload (every field optional — send what you have):

    {"steps": 8412, "resting_hr": 58, "hrv": 42, "sleep_hours": 6.75,
     "exercise_minutes": 22, "active_energy": 431}

Run:  python3 ha/stride_health_webhook.py
"""
import json
import os
import re
import subprocess
import sys
import urllib.request

from stride_config import HA, conf, mqtt, token


STATE_TOPIC = "stride/health/state"
DEVICE_ID = "stride_health"

# Every key the iOS app can send. **This list is a contract with
# ios/StrideHealth/Metrics.swift** — the app names a field, this publishes an
# MQTT discovery config for it, and HA turns it into a sensor. A key with no
# sensor here is published to MQTT and silently ignored; a sensor here with no
# key keeps its last value forever. `ha/check_health_contract.py` diffs the two
# so the drift is caught rather than discovered months later.
#
# The original six are first and unchanged, so anything already recording keeps
# its history.
SENSORS = [
    # HealthKit steps beat the companion app's: CoreMotion is phone-only, this
    # aggregates iPhone and Watch.
    ("steps", "Steps", "steps", "mdi:shoe-print", "measurement"),
    ("resting_hr", "Resting Heart Rate", "bpm", "mdi:heart-pulse", "measurement"),
    ("hrv", "Heart Rate Variability", "ms", "mdi:heart-flash", "measurement"),
    ("sleep_hours", "Sleep", "h", "mdi:sleep", "measurement"),
    ("exercise_minutes", "Exercise Minutes", "min", "mdi:run", "measurement"),
    ("active_energy", "Active Energy", "kcal", "mdi:fire", "measurement"),

    # --- activity ---
    ("distance_m", "Walk + Run Distance", "m", "mdi:map-marker-distance", "measurement"),
    ("flights", "Flights Climbed", None, "mdi:stairs-up", "measurement"),
    ("stand_minutes", "Stand Time", "min", "mdi:human-handsup", "measurement"),
    ("basal_energy", "Resting Energy", "kcal", "mdi:sleep", "measurement"),

    # --- rings. Goals are published too: a ring is only meaningful against the
    # target it was set for, and the target changes. ---
    ("ring_move", "Move Ring", "kcal", "mdi:circle-slice-5", "measurement"),
    ("ring_move_goal", "Move Goal", "kcal", "mdi:target", "measurement"),
    ("ring_exercise", "Exercise Ring", "min", "mdi:circle-slice-3", "measurement"),
    ("ring_exercise_goal", "Exercise Goal", "min", "mdi:target", "measurement"),
    ("ring_stand", "Stand Ring", "h", "mdi:circle-slice-2", "measurement"),
    ("ring_stand_goal", "Stand Goal", "h", "mdi:target", "measurement"),

    # --- heart ---
    ("walking_hr", "Walking Heart Rate", "bpm", "mdi:heart-pulse", "measurement"),
    ("hr_avg", "Heart Rate Average", "bpm", "mdi:heart", "measurement"),
    ("hr_min", "Heart Rate Low", "bpm", "mdi:heart-outline", "measurement"),
    ("hr_max", "Heart Rate High", "bpm", "mdi:heart-flash", "measurement"),
    ("vo2max", "VO2 Max", "mL/kg/min", "mdi:lungs", "measurement"),
    ("respiratory_rate", "Respiratory Rate", "br/min", "mdi:lungs", "measurement"),
    ("spo2", "Blood Oxygen", "%", "mdi:water-percent", "measurement"),

    # --- sleep stages ---
    ("sleep_core_h", "Sleep Core", "h", "mdi:sleep", "measurement"),
    ("sleep_deep_h", "Sleep Deep", "h", "mdi:sleep", "measurement"),
    ("sleep_rem_h", "Sleep REM", "h", "mdi:sleep", "measurement"),
    ("sleep_awake_h", "Sleep Awake", "h", "mdi:sleep-off", "measurement"),
    ("in_bed_h", "In Bed", "h", "mdi:bed", "measurement"),

    # --- body. Weight also comes from the Withings scale; these are the
    # HealthKit view of it and are deliberately separate entities, because
    # merging two sources into one sensor loses which one was on the mat. ---
    ("weight_kg", "Weight (Health)", "kg", "mdi:scale-bathroom", "measurement"),
    ("body_fat_pct", "Body Fat (Health)", "%", "mdi:percent", "measurement"),
    ("lean_mass_kg", "Lean Mass (Health)", "kg", "mdi:arm-flex", "measurement"),
    ("bmi", "BMI", None, "mdi:human", "measurement"),

    # --- walking quality. Not filler in a project about walking. ---
    ("walking_speed_kmh", "Walking Speed", "km/h", "mdi:walk", "measurement"),
    ("step_length_cm", "Step Length", "cm", "mdi:ruler", "measurement"),
    ("walking_asymmetry_pct", "Walking Asymmetry", "%", "mdi:scale-unbalanced", "measurement"),
    ("double_support_pct", "Double Support", "%", "mdi:shoe-print", "measurement"),
    ("stair_ascent_speed", "Stair Ascent Speed", "m/s", "mdi:stairs-up", "measurement"),

    # --- other ---
    ("daylight_minutes", "Time in Daylight", "min", "mdi:white-balance-sunny", "measurement"),
    ("physical_effort", "Physical Effort", "MET", "mdi:gauge", "measurement"),

    # --- the most recent workout, flattened. The full array lands on its own
    # topic; these exist so a dashboard can show "your last walk" without
    # parsing JSON in a Jinja template. No state_class on the text ones — a
    # string with `measurement` makes the recorder complain every hour. ---
    ("last_workout_type", "Last Workout", None, "mdi:run", None),
    ("last_workout_start", "Last Workout Start", None, "mdi:clock-start", None),
    ("last_workout_minutes", "Last Workout Duration", "min", "mdi:timer-outline", "measurement"),
    ("last_workout_distance_m", "Last Workout Distance", "m", "mdi:map-marker-distance", "measurement"),
    ("last_workout_energy", "Last Workout Energy", "kcal", "mdi:fire", "measurement"),
    ("last_workout_hr_avg", "Last Workout HR Average", "bpm", "mdi:heart", "measurement"),
    ("last_workout_hr_max", "Last Workout HR Max", "bpm", "mdi:heart-flash", "measurement"),
    ("last_workout_climb_m", "Last Workout Climb", "m", "mdi:stairs-up", "measurement"),
    ("workouts_7d", "Workouts (7 days)", None, "mdi:counter", "measurement"),
]

# The full array of workouts, kept off the state topic. Different cardinality
# and a different lifetime from a daily aggregate — see iOS.md.
WORKOUT_TOPIC = "stride/health/workouts"

# Saved routes, converted on the phone from an outdoor walk's GPS track. The
# track itself never arrives here — only the gradient profile, which is the
# privacy decision recorded in the iOS notes. The console subscribes to this
# topic and caches what it finds to disk, because it can boot with no network.
ROUTE_TOPIC = "stride/routes"

# `synced_at` is metadata rather than a metric, so it is not in SENSORS and
# stays in `check_health_contract.py`'s NOT_SENSORS. It gets a sensor anyway,
# because without it **nothing in Home Assistant can tell a fresh number from a
# stale one**, and that is not hypothetical:
#
#   On 2026-07-31 at 07:30 the coach opened with "9,063 steps and 54 exercise
#   minutes before 7:30". Both were the previous day's closing totals. The
#   phone had not synced since 22:44 the night before, and every sensor here
#   uses `default(this.state)` — by design, so an absent key keeps its reading
#   rather than being wiped. The cost of that design is that a sensor never
#   *looks* stale: it holds yesterday's total and reads exactly like today's.
#
# A timestamp sensor is the missing signal. It is also what makes the iOS
# background-delivery work verifiable over a full day from Home Assistant
# instead of from the phone.
SYNC_SENSOR = ("synced_at", "Last Sync", "mdi:cloud-upload")


BROKER = conf("mqtt_broker").replace("tcp://", "").split(":")[0]
WEBHOOK_ID = conf("stride_webhook_id")
EXTERNAL_URL = conf("ha_external_url")


def publish_discovery():
    device = {"identifiers": [DEVICE_ID], "name": "Apple Health",
              "manufacturer": "Apple", "model": "HealthKit via STRIDE iOS app"}
    for key, name, unit, icon, state_class in SENSORS:
        cfg = {
            "name": name,
            "unique_id": f"{DEVICE_ID}_{key}",
            "state_topic": STATE_TOPIC,
            # Absent keys must not become "unknown" and wipe yesterday's value.
            "value_template": "{{ value_json.%s | default(this.state) }}" % key,
            "icon": icon,
            "device": device,
        }
        # Omit rather than send null: a unit of None on a numeric sensor is
        # fine, but `state_class: null` makes HA log a warning on every config.
        if unit:
            cfg["unit_of_measurement"] = unit
        if state_class:
            cfg["state_class"] = state_class
        mqtt(f"homeassistant/sensor/{DEVICE_ID}/{key}/config", json.dumps(cfg))

    # The sync timestamp. `device_class: timestamp` rather than a plain string
    # so templates can do arithmetic on it — `now() - states(...)` is the whole
    # point of having it. No state_class: a timestamp is not a measurement and
    # the recorder complains if you claim it is.
    key, name, icon = SYNC_SENSOR
    mqtt(f"homeassistant/sensor/{DEVICE_ID}/{key}/config", json.dumps({
        "name": name,
        "unique_id": f"{DEVICE_ID}_{key}",
        "state_topic": STATE_TOPIC,
        "value_template": "{{ value_json.%s | default(this.state) }}" % key,
        "icon": icon,
        "device_class": "timestamp",
        "device": device,
    }))
    print(f"  published discovery for {len(SENSORS) + 1} sensors")


def automation() -> dict:
    return {
        "alias": "STRIDE — Apple Health intake",
        "description": "Receives a JSON blob from an iOS Shortcut and republishes it "
                       "to MQTT so HA discovery can turn it into sensors. Free "
                       "alternative to a health-sync subscription.",
        "mode": "queued",
        "max": 10,
        "triggers": [{
            "trigger": "webhook",
            "webhook_id": WEBHOOK_ID,
            "allowed_methods": ["POST"],
            # Reachable from outside, because the phone is not always home.
            "local_only": False,
        }],
        "actions": [
            {
                "action": "mqtt.publish",
                "data": {
                    "topic": STATE_TOPIC,
                    "retain": True,
                    "payload": "{{ trigger.json | to_json }}",
                },
            },
            {
                # The workout array to its own topic, and only when one is
                # present — republishing an empty list would retain over the
                # last real set of walks.
                "if": [{"condition": "template",
                        "value_template": "{{ trigger.json.workouts is defined }}"}],
                "then": [{
                    "action": "mqtt.publish",
                    "data": {
                        "topic": WORKOUT_TOPIC,
                        "retain": True,
                        "payload": "{{ trigger.json.workouts | to_json }}",
                    },
                }],
            },
            {
                # Saved routes, same treatment and the same reasoning as the
                # workout array: a nested list with its own cardinality, kept
                # off the scalar state topic. Retained, because the console
                # subscribes on boot and must find the routes already there —
                # it can start with no network at all and there is nobody to
                # ask for them.
                #
                # Absent-means-unchanged, exactly as with workouts. The phone
                # only sends `routes` when the set has changed, so republishing
                # an empty list here would wipe the console's routes on the
                # first ordinary sync after one was saved.
                "if": [{"condition": "template",
                        "value_template": "{{ trigger.json.routes is defined }}"}],
                "then": [{
                    "action": "mqtt.publish",
                    "data": {
                        "topic": ROUTE_TOPIC,
                        "retain": True,
                        "payload": "{{ trigger.json.routes | to_json }}",
                    },
                }],
            },
            {
                "action": "logbook.log",
                "data": {
                    "name": "Apple Health",
                    "message": "received {{ trigger.json | list | join(', ') }}",
                },
            },
        ],
    }


def main():
    tok = token()
    publish_discovery()
    req = urllib.request.Request(
        f"{HA}/api/config/automation/config/stride_health_intake",
        data=json.dumps(automation()).encode(),
        headers={"Authorization": f"Bearer {tok}", "Content-Type": "application/json"},
        method="POST",
    )
    print("  automation:", urllib.request.urlopen(req).read().decode())
    urllib.request.urlopen(urllib.request.Request(
        f"{HA}/api/services/automation/reload", data=b"{}",
        headers={"Authorization": f"Bearer {tok}", "Content-Type": "application/json"},
        method="POST"))
    # The id is the only thing protecting this endpoint, so the ordinary run
    # does not print it. It was echoed into a session transcript twice on
    # 2026-07-30 — once by a diagnostic and once by here — which is what forced
    # the 2026-07-31 rotation. Masked by default; `--show-url` when it genuinely
    # needs to be read off the screen to type into the phone.
    print("\n  POST health JSON to:")
    if "--show-url" in sys.argv:
        print(f"    {EXTERNAL_URL}/api/webhook/{WEBHOOK_ID}")
    else:
        print(f"    {EXTERNAL_URL}/api/webhook/{WEBHOOK_ID[:4]}…{WEBHOOK_ID[-2:]}"
              f"  ({len(WEBHOOK_ID)} chars)")
        print("    re-run with --show-url to see it in full")


if __name__ == "__main__":
    main()
