# STRIDE v0.7.0

The first published build. An installable APK, so replacing the console
software no longer means building it yourself.

**Read [SAFETY.md](https://github.com/keranm/strideApp/blob/main/SAFETY.md)
first.** This software drives a motorised treadmill — it sets belt speed and
moves the incline deck while somebody is standing on it. The safety key remains
the emergency stop; STRIDE is not what stops the belt.

---

## Install

Download `stride-v0.7.0.apk` below, then:

```
adb install -r stride-v0.7.0.apk
adb shell am start -n dev.stride.hud/.MainActivity
```

Getting adb onto the console in the first place is the long part, and it is
covered step by step in
[docs/INSTALL.md](https://github.com/keranm/strideApp/blob/main/docs/INSTALL.md).
If you have already jailbroken your console and can launch arbitrary apps from a
home screen, this APK is all you need.

To undo everything: factory reset the console.

## Configure

The build ships with **no broker credentials** — it is compiled without them on
purpose, so nothing personal to anyone else is inside it. Everything is set on
the console itself, in Settings:

- **MQTT broker, user, password** — optional. Without them STRIDE runs
  standalone: the console works, keeps its own workout history, and needs no
  network, no Home Assistant and no account.
- Home Assistant, the AI coach and Apple Health routes are all opt-in on top.

## What is confirmed

One machine, and it would be dishonest to imply otherwise:

| | |
|---|---|
| Model | NordicTrack C 1750 |
| Generation | Gen 6, "CLASSIC" embedded console |
| Motor board | FitPro, device id `0x04` |

Speed and incline limits are read off the board rather than compiled in, so
another FitPro machine has a reasonable chance of working. Nobody has tried it.
If you do, [say what happened](https://github.com/keranm/strideApp/issues) —
whether it worked or not.

**Gen 7 / iFit 2.0** reportedly closes the route used to install this. That is
one second-hand comment, untested. Declining the update costs nothing.

## New in this release

- **Belt telemetry.** The console now publishes what the belt is *doing*
  (`Belt Speed`, `Belt RPM`) alongside what it was *told*, and raises a
  `Belt Short Of Pace` alarm when the two disagree for more than a few seconds.
  Episodes are written to an on-console incident log that survives a reboot.
- Assorted fixes to deck levelling and the stop path.

## Known limits

- `Belt Short Of Pace` compares the setpoint against the odometer, and the
  odometer counts the drive roller. A belt slipping *over* that roller moves
  neither number, so this will not catch it. `Belt RPM` is being recorded to
  find out whether it can.
- The deck declines to −3% and no further; routes with steeper descents are
  flattened to that.
- Guided walks never set the belt speed on their own. Incline is automatic,
  pace is always yours.
