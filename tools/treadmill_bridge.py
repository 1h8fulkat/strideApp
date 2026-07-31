#!/usr/bin/env python3
"""
Treadmill MQTT Bridge
Reads live workout data from NordicTrack Gen 6 via ADB + UI Automator
Publishes to Home Assistant MQTT broker
"""

import os
import subprocess
import xml.etree.ElementTree as ET
import time
import json
import sys
import re
from datetime import datetime

# CONFIGURATION — edit these for your setup
MQTT_BROKER = os.environ.get("MQTT_BROKER", "homeassistant.local")
MQTT_PORT = 1883
MQTT_USER = "treadmill"
MQTT_PASS = "c4tch4ll!"
MQTT_TOPIC_PREFIX = "qz/treadmill"
TREADMILL_IP = os.environ.get("STRIDE_DEVICE", "").split(":")[0]
POLL_INTERVAL = 1.0               # seconds

# Known text patterns on NordicTrack iFit screen during workout
PATTERNS = {
    "speed": re.compile(r"(\d+\.?\d*)\s*(km/h|mph)", re.IGNORECASE),
    "incline": re.compile(r"([+-]?\d+\.?\d*)\s*%"),
    "distance": re.compile(r"(\d+\.?\d*)\s*(km|mi)", re.IGNORECASE),
    "time": re.compile(r"(\d+):(\d+)", re.IGNORECASE),
    "calories": re.compile(r"(\d+)\s*(kcal|cal)", re.IGNORECASE),
    "heart_rate": re.compile(r"(\d+)\s*bpm", re.IGNORECASE),
}

def run_adb(cmd):
    """Run an adb command and return stdout."""
    full_cmd = f"adb -s {TREADMILL_IP}:5555 shell {cmd}"
    try:
        result = subprocess.run(full_cmd, shell=True, capture_output=True, text=True, timeout=10)
        return result.stdout, result.stderr, result.returncode
    except Exception as e:
        return "", str(e), 1

def dump_ui():
    """Dump the current screen UI tree via uiautomator."""
    stdout, stderr, rc = run_adb("uiautomator dump /sdcard/window_dump.xml")
    if rc != 0:
        return None
    stdout, stderr, rc = run_adb("cat /sdcard/window_dump.xml")
    if rc != 0:
        return None
    return stdout

def parse_ui(xml_string):
    """Parse the UI dump XML and extract text nodes."""
    if not xml_string:
        return []
    try:
        root = ET.fromstring(xml_string)
        texts = []
        for node in root.iter():
            text = node.get("text", "")
            if text and text.strip():
                texts.append(text.strip())
        return texts
    except ET.ParseError:
        return []

def extract_metrics(texts):
    """Extract workout metrics from UI text nodes."""
    metrics = {
        "speed": None,
        "incline": None,
        "distance": None,
        "elapsed_time": None,
        "calories": None,
        "heart_rate": None,
    }

    for text in texts:
        # Speed
        if "km/h" in text.lower() or "mph" in text.lower():
            m = PATTERNS["speed"].search(text)
            if m:
                metrics["speed"] = float(m.group(1))

        # Incline
        if "%" in text:
            m = PATTERNS["incline"].search(text)
            if m:
                metrics["incline"] = float(m.group(1))

        # Distance
        if "km" in text.lower() or "mi" in text.lower():
            m = PATTERNS["distance"].search(text)
            if m:
                metrics["distance"] = float(m.group(1))

        # Time (MM:SS format)
        if ":" in text and len(text) <= 8:
            m = PATTERNS["time"].search(text)
            if m:
                metrics["elapsed_time"] = text

        # Calories
        if "kcal" in text.lower() or "cal" in text.lower():
            m = PATTERNS["calories"].search(text)
            if m:
                metrics["calories"] = int(m.group(1))

        # Heart rate
        if "bpm" in text.lower():
            m = PATTERNS["heart_rate"].search(text)
            if m:
                metrics["heart_rate"] = int(m.group(1))

    return metrics

def publish_mqtt(topic, payload, retain=False):
    """Publish a message to MQTT using mosquitto_pub."""
    retain_flag = "-r" if retain else ""
    cmd = (
        f'mosquitto_pub -h {MQTT_BROKER} -p {MQTT_PORT} '
        f'-u {MQTT_USER} -P {MQTT_PASS} '
        f'-t "{topic}" -m "{payload}" {retain_flag}'
    )
    try:
        subprocess.run(cmd, shell=True, capture_output=True, timeout=5)
    except Exception:
        pass

def main():
    print("=" * 60)
    print("  Treadmill MQTT Bridge")
    print("  Connecting to treadmill at", TREADMILL_IP)
    print("  Publishing to MQTT broker at", MQTT_BROKER)
    print("=" * 60)
    print()

    # Ensure ADB is connected
    print("Checking ADB connection...")
    result = subprocess.run(f"adb connect {TREADMILL_IP}", shell=True, capture_output=True, text=True)
    print(result.stdout.strip())

    last_metrics = {}
    workout_active = False

    try:
        while True:
            xml = dump_ui()
            texts = parse_ui(xml)
            metrics = extract_metrics(texts)

            # Detect if a workout is running (speed > 0)
            is_running = metrics.get("speed") is not None and metrics["speed"] > 0

            if is_running:
                if not workout_active:
                    ts = datetime.now().strftime("%H:%M:%S")
                    print(f"[{ts}] Workout started!")
                    workout_active = True

                # Publish each metric that changed
                for key, value in metrics.items():
                    if value is not None and value != last_metrics.get(key):
                        topic = f"{MQTT_TOPIC_PREFIX}/{key}"
                        publish_mqtt(topic, str(value))
                        print(f"  {key}: {value}")

                last_metrics = metrics.copy()
            else:
                if workout_active:
                    ts = datetime.now().strftime("%H:%M:%S")
                    print(f"[{ts}] Workout ended")
                    workout_active = False
                    last_metrics = {}
                else:
                    ts = datetime.now().strftime("%H:%M:%S")
                    print(f"[{ts}] Waiting for workout...", end="\r")

            time.sleep(POLL_INTERVAL)

    except KeyboardInterrupt:
        print("\n\nBridge stopped.")

if __name__ == "__main__":
    main()