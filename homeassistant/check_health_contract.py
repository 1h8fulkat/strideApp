#!/usr/bin/env python3
"""
Diff the iOS app's metric keys against the webhook's sensor list.

These two files have to agree and nothing enforces it at runtime:

    ios/StrideHealth/Metrics.swift   names a field in the JSON payload
    ha/stride_health_webhook.py      publishes an MQTT sensor for that field

A key the app sends with no sensor here is republished to MQTT and silently
ignored — no error anywhere, the data just never appears. A sensor with no key
keeps its last value forever, which is worse, because a stale number looks
exactly like a real one. Both failures are invisible, which is why this exists.

Run:  python3 ha/check_health_contract.py
Exit: 0 if they agree, 1 if they do not.
"""
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
SWIFT = os.path.join(HERE, "..", "ios", "StrideHealth", "Metrics.swift")
WEBHOOK = os.path.join(HERE, "stride_health_webhook.py")

# Keys the app sends that are deliberately not sensors. `workouts` is an array
# routed to its own topic; the other two are metadata about the sync itself.
NOT_SENSORS = {"workouts", "synced_at", "source"}


def swift_keys() -> list:
    src = open(SWIFT).read()
    return re.findall(r'Metric\(key:\s*"([a-z0-9_]+)"', src)


def webhook_keys() -> list:
    src = open(WEBHOOK).read()
    block = src[src.index("SENSORS = ["):src.index("WORKOUT_TOPIC")]
    return re.findall(r'^\s*\("([a-z0-9_]+)"', block, re.M)


def main():
    app = swift_keys()
    ha = webhook_keys()

    # The app also flattens the most recent workout into top-level keys, which
    # are built in Syncer.swift rather than declared in the metric table.
    flattened = {k for k in ha if k.startswith("last_workout_")} | {"workouts_7d"}
    sent = set(app) | flattened

    missing = sorted(sent - set(ha) - NOT_SENSORS)   # sent, nowhere to land
    orphan = sorted(set(ha) - sent)                  # sensor, never fed

    dupes = sorted({k for k in app if app.count(k) > 1})

    print(f"  app metrics      : {len(app)}")
    print(f"  workout keys     : {len(flattened)}")
    print(f"  webhook sensors  : {len(ha)}")

    ok = True
    if dupes:
        ok = False
        print(f"\n  DUPLICATE keys in Metrics.swift: {', '.join(dupes)}")
    if missing:
        ok = False
        print(f"\n  SENT BUT NO SENSOR ({len(missing)}) — these arrive and vanish:")
        for k in missing:
            print(f"      {k}")
    if orphan:
        ok = False
        print(f"\n  SENSOR BUT NEVER SENT ({len(orphan)}) — these will hold a stale value:")
        for k in orphan:
            print(f"      {k}")

    print("\n  contract holds" if ok else "\n  CONTRACT BROKEN")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
