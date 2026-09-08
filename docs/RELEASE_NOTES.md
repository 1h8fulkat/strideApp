# STRIDE v0.8.0

Zwift can see your treadmill now.

**Read [SAFETY.md](https://github.com/keranm/strideApp/blob/main/SAFETY.md)
first.** This software drives a motorised treadmill. The safety key remains the
emergency stop; STRIDE is not what stops the belt.

---

## FTMS: pair the treadmill with Zwift

The console publishes the Bluetooth SIG's Fitness Machine Service, so it turns
up in Zwift, Kinomap and anything else that speaks FTMS, next to the commercial
machines. No phone in the middle, nothing to install anywhere else.

It advertises as **STRIDE Treadmill** and sends speed, incline, distance,
elapsed time and heart rate. In Zwift, pick **RUN**, then pair it under **Run
Speed**. Leave Cadence unpaired: this treadmill has no step sensor, and
inventing a cadence from speed would be a number with no source behind it.

**Telemetry only, deliberately.** FTMS also defines a control point that lets
the far end set speed and incline. It is not implemented. Nothing sets the belt
speed except the person standing on the belt, and handing that to software in
another room is a different decision.

**Your heart strap and Zwift compete for the radio.** This console cannot scan
for a strap and hold a Bluetooth connection at the same time, so STRIDE stops
hunting for a strap while something is paired. A strap that is already
connected is kept. Put it on before you pair and you get both.

Thanks to [ciarancoffey/nordictrack-ftms-bridge](https://github.com/ciarancoffey/nordictrack-ftms-bridge),
which showed this console can be a Bluetooth peripheral at all.

## Belt telemetry

The console publishes what the belt is *doing* (`Belt Speed`, `Belt RPM`)
alongside what it was *told*, and raises `Belt Short Of Pace` when they
disagree for more than a few seconds. Episodes are written to an on-console
incident log that survives a reboot.

## Units are on the Display page

Kilometres or miles. It was under Treadmill, where nobody found it.

---

## Install

Download `stride-v0.8.0.apk` below:

```
adb install -r stride-v0.8.0.apk
adb shell am start -n dev.stride.hud/.MainActivity
```

Upgrading from v0.7.0 keeps your settings. Getting adb onto the console in the
first place is covered in
[docs/INSTALL.md](https://github.com/keranm/strideApp/blob/main/docs/INSTALL.md).

Every release here is signed with the same certificate, SHA-256
`274a6f2e308df50e1e4219f5bd1f4aca5fda8ccd5feccc7c52579c0c8dd302e8`. Check with
`apksigner verify --print-certs stride-v0.8.0.apk`.

The build ships with no broker credentials. Everything is configured on the
console, and STRIDE runs standalone with no network at all.

## What is confirmed

One machine: NordicTrack C 1750, Gen 6 "CLASSIC" console, FitPro board
`0x04`. Speed and incline limits are read off the board rather than compiled
in, so another FitPro machine has a fair chance.
[Say what happened](https://github.com/keranm/strideApp/issues) either way.

## Known limits

- FTMS incline and heart rate work but have not been walked through a full
  session yet.
- `Belt Short Of Pace` compares the setpoint against the odometer, and the
  odometer counts the drive roller. A belt slipping over that roller moves
  neither number.
- The deck declines to −3% and no further.
