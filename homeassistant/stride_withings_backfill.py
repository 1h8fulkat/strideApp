#!/usr/bin/env python3
"""
Backfills the Withings Health Mate export into Home Assistant's long-term
statistics.

HA holds no Withings history from before the integration was linked (2026-07-01)
— the integration only pushes new readings, and the recorder is the only archive.
`recorder/import_statistics` can write history retroactively, so a Health Mate
data export gives the dashboard, and the coach, fourteen years of trend instead
of one month.

Two kinds of target, chosen per metric:

*Body composition and blood pressure* are written straight onto the **existing
Withings entities**. Those statistics are mean/min/max only — no running sum —
so old hours simply appear alongside the live ones and the weight graph goes
back to 2012. Nothing is recomputed, nothing can drift.

*Everything else* becomes an **external statistic** under `stride:`. The daily
counters (`..._today`) carry a cumulative `sum` that the recorder maintains from
the moment it first saw the sensor; splicing fourteen thousand steps of history
underneath that would make the live series read as one enormous reset. External
series are ours alone, so the sum is consistent by construction — and
`--clear` removes them again without touching a single live reading.

The two halves of the activity story therefore meet at the export date: history
lives in `stride:steps_daily`, today lives in
`<withings_prefix>steps_today`.

Timestamps: the `raw_*` files carry a real UTC offset (Europe/Paris — Withings
is French) so they convert exactly. `weight.csv` and `bp.csv` are naive local
time and are read as Australia/Adelaide; the pre-2022 rows were really recorded
in Melbourne, an hour out, which does not matter to a daily trend.

Needs `websocket-client` (statistics have no REST endpoint):  pip3 install websocket-client

    python3 ha/stride_withings_backfill.py --dry-run    # parse and report, write nothing
    python3 ha/stride_withings_backfill.py              # import
    python3 ha/stride_withings_backfill.py --clear      # drop the stride:* series
"""
import argparse
import ast
import csv
import datetime as dt
import json
import os
import re
import sys
import time
from collections import defaultdict
from zoneinfo import ZoneInfo

import websocket

from stride_config import HA, conf, mqtt, token

DATA = os.path.join(os.path.dirname(__file__), "..", "withings_data")

LOCAL = ZoneInfo("Australia/Adelaide")
UTC = dt.timezone.utc

# The 2012–13 rows contain someone else's weigh-ins (19–31 kg) on the same
# account. Rather than blacklisting dates, every metric declares the range a
# reading has to fall in to be believed; anything outside is dropped and
# counted. A coach told to find a trend must never be handed a foreign number.
BANDS = {
    "weight": (40, 250), "fat_mass": (2, 120), "bone_mass": (0.5, 10),
    "muscle_mass": (10, 150), "fat_ratio": (2, 70), "fat_free_mass": (20, 200),
    "systolic": (60, 260), "diastolic": (30, 170), "pulse": (25, 220),
    "heart_rate": (25, 220), "hrv": (1, 500), "spo2": (60, 100),
    "sleep_hours": (0, 20), "sleep_hr": (25, 220),
}

# key -> (statistic_id, display name, unit). An entity id means the row is
# merged into that live sensor's own statistics; a `stride:` id is ours.
MEAN_SERIES = {
    "weight":        (conf("weight_entity", ""), None, "kg"),
    "fat_mass":      (PREFIX + "fat_mass", None, "kg"),
    "bone_mass":     (PREFIX + "bone_mass", None, "kg"),
    "muscle_mass":   (PREFIX + "muscle_mass", None, "kg"),
    "fat_ratio":     (conf("body_fat_entity", ""), None, "%"),
    "fat_free_mass": (PREFIX + "fat_free_mass", None, "kg"),
    "systolic":      (conf("systolic_entity", ""), None, "mmHg"),
    "diastolic":     (conf("diastolic_entity", ""), None, "mmHg"),
    "pulse":         (PREFIX + "heart_pulse", None, "bpm"),
    "heart_rate":    ("stride:heart_rate", "Withings Heart Rate", "bpm"),
    "hrv":           ("stride:hrv_sdnn", "Withings HRV (SDNN)", "ms"),
    "spo2":          ("stride:spo2", "Withings SpO2", "%"),
    "sleep_hours":   ("stride:sleep_duration", "Withings Sleep Duration", "h"),
    "sleep_hr":      ("stride:sleep_heart_rate", "Withings Sleep Heart Rate", "bpm"),
}

# Daily totals, imported as a cumulative sum so HA can report change per
# day/week/month. Withings' "calories" are kcal.
SUM_SERIES = {
    "steps_daily":     ("stride:steps_daily", "Withings Steps (daily)", "steps",
                        "aggregates_steps.csv"),
    "distance_daily":  ("stride:distance_daily", "Withings Distance (daily)", "m",
                        "aggregates_distance.csv"),
    "calories_daily":  ("stride:calories_daily", "Withings Active Calories (daily)", "kcal",
                        "aggregates_calories_earned.csv"),
    "elevation_daily": ("stride:elevation_daily", "Withings Elevation (daily)", "m",
                        "aggregates_elevation.csv"),
}


# ---------------------------------------------------------------- parsing


def num(raw):
    try:
        return float(raw)
    except (TypeError, ValueError):
        return None


def data(name):
    return os.path.join(DATA, name)


class Dropped:
    """Counts everything thrown away, so the run can say what it ignored."""

    def __init__(self):
        self.out_of_band = defaultdict(int)
        self.duplicates = defaultdict(int)

    def report(self):
        for key in sorted(set(self.out_of_band) | set(self.duplicates)):
            bits = []
            if self.duplicates[key]:
                bits.append(f"{self.duplicates[key]} duplicate rows")
            if self.out_of_band[key]:
                lo, hi = BANDS[key]
                bits.append(f"{self.out_of_band[key]} outside {lo}–{hi}")
            print(f"    {key}: dropped {', '.join(bits)}")


DROPPED = Dropped()


def keep(key, value):
    if value is None:
        return False
    lo, hi = BANDS[key]
    if lo <= value <= hi:
        return True
    DROPPED.out_of_band[key] += 1
    return False


def load_weight(samples):
    """weight.csv — plus the two metrics HA derives rather than stores."""
    seen = set()
    with open(data("weight.csv")) as fh:
        for row in csv.DictReader(fh):
            sig = tuple(row.values())
            if sig in seen:
                DROPPED.duplicates["weight"] += 1
                continue
            seen.add(sig)

            when = dt.datetime.strptime(row["Date"], "%Y-%m-%d %H:%M:%S").replace(tzinfo=LOCAL)
            weight = num(row["Weight (kg)"])
            # Body composition from a foreign weigh-in is foreign too, so the
            # whole row lives or dies on the weight being plausible.
            if not keep("weight", weight):
                continue
            samples["weight"].append((when, weight))

            fat = num(row["Fat mass (kg)"])
            for key, value in (("fat_mass", fat),
                               ("bone_mass", num(row["Bone mass (kg)"])),
                               ("muscle_mass", num(row["Muscle mass (kg)"])),
                               ("fat_ratio", 100 * fat / weight if fat else None),
                               ("fat_free_mass", weight - fat if fat else None)):
                if keep(key, value):
                    samples[key].append((when, value))


def load_bp(samples):
    seen = set()
    with open(data("bp.csv")) as fh:
        for row in csv.DictReader(fh):
            sig = tuple(row.values())
            if sig in seen:
                DROPPED.duplicates["systolic"] += 1
                continue
            seen.add(sig)

            when = dt.datetime.strptime(row["Date"], "%Y-%m-%d %H:%M:%S").replace(tzinfo=LOCAL)
            for key, value in (("systolic", num(row["Systolic"])),
                               ("diastolic", num(row["Diastolic"])),
                               ("pulse", num(row["Heart rate"]))):
                if keep(key, value):
                    samples[key].append((when, value))


def load_raw(samples, key, filename):
    """
    A `raw_*` file: `start,duration,value` where duration and value are equal
    length lists of consecutive samples, each running for its own duration.
    """
    with open(data(filename)) as fh:
        for row in csv.DictReader(fh):
            start = dt.datetime.fromisoformat(row["start"])
            durations = ast.literal_eval(row["duration"])
            values = ast.literal_eval(row["value"])
            offset = 0
            for i, value in enumerate(values):
                when = start + dt.timedelta(seconds=offset)
                offset += durations[i] if i < len(durations) else 0
                if keep(key, float(value)):
                    samples[key].append((when, float(value)))


def load_sleep(samples):
    """
    sleep.csv — one row per night. 69 of the 272 nights predate stage tracking
    and record only time awake, so total sleep falls back to the length of the
    night minus that. The row is stamped at wake-up, which is the day the night
    belongs to.
    """
    with open(data("sleep.csv")) as fh:
        for row in csv.DictReader(fh):
            woke = dt.datetime.fromisoformat(row["to"])
            stages = sum(int(row[f"{s} (s)"] or 0) for s in ("light", "deep", "rem"))
            if not stages:
                night = (woke - dt.datetime.fromisoformat(row["from"])).total_seconds()
                stages = max(0.0, night - int(row["awake (s)"] or 0))
            if keep("sleep_hours", stages / 3600):
                samples["sleep_hours"].append((woke, stages / 3600))
            # 97 of the nights predate the wrist heart-rate sensor and write a
            # flat 0. That is "not measured", not a reading worth reporting on.
            average_hr = num(row["Average heart rate"])
            if average_hr and keep("sleep_hr", average_hr):
                samples["sleep_hr"].append((woke, average_hr))


def load_all():
    samples = defaultdict(list)
    load_weight(samples)
    load_bp(samples)
    load_raw(samples, "heart_rate", "raw_hr_hr.csv")
    # HR RMS SD is present too but reads -1 throughout; SD NN is the SDNN that
    # everything else, Apple Health included, calls "HRV".
    load_raw(samples, "hrv", "raw_heart_rate_variability_HR SD NN.csv")
    load_raw(samples, "spo2", "raw_spo2_auto_spo2.csv")
    load_sleep(samples)
    return samples


# ---------------------------------------------------------------- bucketing


def hourly(samples):
    """
    Statistics rows sit on the hour. Several readings in one hour collapse to a
    single row carrying their mean, min and max — which is exactly what the
    recorder would have written had it been running at the time.
    """
    buckets = defaultdict(list)
    for when, value in samples:
        buckets[when.astimezone(UTC).replace(minute=0, second=0, microsecond=0)].append(value)
    return [{"start": start.isoformat(),
             "mean": sum(vals) / len(vals), "min": min(vals), "max": max(vals)}
            for start, vals in sorted(buckets.items())]


def daily(filename):
    """
    One row per day, stamped at local noon rounded down to the hour so it
    cannot fall into the neighbouring day, with `sum` running cumulatively —
    that is what HA differentiates to answer "how far did I walk last week".
    """
    with open(data(filename)) as fh:
        days = sorted((dt.date.fromisoformat(r["date"]), float(r["value"]))
                      for r in csv.DictReader(fh))
    rows, total = [], 0.0
    for day, value in days:
        total += value
        noon = dt.datetime.combine(day, dt.time(12), LOCAL)
        start = noon.astimezone(UTC).replace(minute=0, second=0, microsecond=0)
        rows.append({"start": start.isoformat(),
                     "state": round(value, 3), "sum": round(total, 3)})
    return rows


# ---------------------------------------------------------------- HA client


class HA:
    def __init__(self):
        with open(ENV) as fh:
            match = re.search(r"ha_api_token:\s*(\S+)", fh.read())
        if not match:
            sys.exit("no ha_api_token in env.txt")
        self.ws = websocket.create_connection(WS, timeout=60)
        self.ws.recv()
        self.ws.send(json.dumps({"type": "auth", "access_token": match.group(1)}))
        if json.loads(self.ws.recv())["type"] != "auth_ok":
            sys.exit("HA rejected the token")
        self.next_id = 0

    def call(self, message):
        self.next_id += 1
        message["id"] = self.next_id
        self.ws.send(json.dumps(message))
        while True:
            reply = json.loads(self.ws.recv())
            if reply.get("id") != self.next_id:
                continue
            if not reply.get("success", True):
                sys.exit(f"HA rejected {message['type']}: {reply.get('error')}")
            return reply.get("result")

    def imp(self, statistic_id, name, unit, rows, has_sum):
        # An entity id means "these belong to that sensor" and HA demands the
        # recorder as their source; anything else is an external series.
        source = "recorder" if statistic_id.startswith("sensor.") else statistic_id.split(":")[0]
        meta = {"has_mean": not has_sum, "has_sum": has_sum, "name": name,
                "source": source, "statistic_id": statistic_id,
                "unit_of_measurement": unit}
        # Chunked because a decade of hourly heart rate is a big frame, and a
        # rejected frame tells you nothing about which row was wrong.
        for i in range(0, len(rows), 2000):
            self.call({"type": "recorder/import_statistics",
                       "metadata": meta, "stats": rows[i:i + 2000]})

    def span(self, statistic_id):
        result = self.call({"type": "recorder/statistics_during_period",
                            "start_time": "2000-01-01T00:00:00+00:00",
                            "statistic_ids": [statistic_id], "period": "month",
                            "types": ["mean", "min", "max", "state", "sum"]})
        return result.get(statistic_id, [])


def stamp(ms):
    return dt.datetime.fromtimestamp(ms / 1000, UTC).astimezone(LOCAL).date().isoformat()


def local_day(iso):
    """Rows are stamped in UTC; report them as the day they happened here."""
    return dt.datetime.fromisoformat(iso).astimezone(LOCAL).date().isoformat()


# ---------------------------------------------------------------- main


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dry-run", action="store_true",
                    help="parse and report, write nothing")
    ap.add_argument("--clear", action="store_true",
                    help="delete the stride:* series (live entities untouched)")
    ap.add_argument("--only", action="append", default=[],
                    help="import just these keys (repeatable)")
    args = ap.parse_args()

    if args.clear:
        ids = [sid for sid, _, _ in MEAN_SERIES.values() if sid.startswith("stride:")]
        ids += [sid for sid, _, _, _ in SUM_SERIES.values()]
        HA().call({"type": "recorder/clear_statistics", "statistic_ids": ids})
        print("cleared:", ", ".join(ids))
        print("\nBody composition and blood pressure were merged into the live "
              "Withings entities and cannot be unpicked from their own history.")
        return

    print("reading the export")
    samples = load_all()
    DROPPED.report()

    plan = []
    for key, (sid, name, unit) in MEAN_SERIES.items():
        if samples.get(key):
            plan.append((key, sid, name, unit, hourly(samples[key]), False))
    for key, (sid, name, unit, filename) in SUM_SERIES.items():
        plan.append((key, sid, name, unit, daily(filename), True))
    if args.only:
        plan = [p for p in plan if p[0] in args.only]

    print(f"\n{'metric':16} {'rows':>6}  {'first':10}   {'last':10}  target")
    for key, sid, _, _, rows, _ in plan:
        first, last = (local_day(r["start"]) for r in (rows[0], rows[-1]))
        print(f"{key:16} {len(rows):6}  {first} → {last}  {sid}")
    total = sum(len(rows) for *_, rows, _ in plan)
    print(f"{'':16} {total:6}  statistics rows in {len(plan)} series")

    if args.dry_run:
        print("\ndry run — nothing written")
        return

    print()
    ha = HA()
    for key, sid, name, unit, rows, has_sum in plan:
        ha.imp(sid, name, unit, rows, has_sum)
        print(f"  imported {len(rows):6} rows → {sid}")

    # import_statistics only queues the rows; the recorder writes them on its
    # own thread and fifteen thousand of them take a while, so wait for each
    # series to actually appear rather than declaring failure early.
    print("\nverifying")
    for key, sid, *_ in plan:
        for attempt in range(30):
            months = ha.span(sid)
            if months:
                break
            time.sleep(2)
        if not months:
            print(f"  {key:16} NOTHING READ BACK after 60s — check the HA log")
            continue
        print(f"  {key:16} {len(months):3} months, {stamp(months[0]['start'])}"
              f" → {stamp(months[-1]['end'])}")


if __name__ == "__main__":
    main()
