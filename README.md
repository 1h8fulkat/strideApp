# STRIDE

**A NordicTrack treadmill, free of its iFit subscription prison, talking to Home Assistant.**

> # 🚧 Work in progress — don't run this yet
>
> It works, but on one machine, set up by the person who wrote it. The install
> guide is unfinished and nobody knows how it behaves on other hardware.
>
> Nothing here is supported — use it at your own risk. A factory reset does
> restore the original software if it goes wrong.
>
> Read the code, take the [protocol notes](protocol/FITPRO_PROTOCOL.md), tell
> me what board your treadmill has. Just don't point it at a machine you care
> about yet.

> ⚠️ **This software drives a motorised treadmill.** Read [SAFETY.md](SAFETY.md)
> before installing anything. The physical safety key remains the stop of
> record; incline is driven, speed is only ever suggested.

> **Gen 7 / iFit 2.0.** Second-hand reports say this update closes the
> privileged-mode route everything here depends on. Nobody involved has seen it
> happen. Declining it costs nothing, so decline it until somebody knows either
> way.

STRIDE replaces the software on a treadmill console with something you own. The
belt and deck work without a login, without a subscription, and without an
internet connection — and everything the machine knows appears in Home
Assistant as ordinary entities.

**v0.6**, in daily use. Still to do for v1.0: HACS packaging, a better
dashboard, the iOS app on the App Store, and an install guide somebody other
than the author has followed.

![STRIDE on a NordicTrack console](docs/screenshots/oval-original.png)

**[More screenshots →](docs/GALLERY.md)**

---

## What it is, in three parts

| | |
|---|---|
| **`console/`** | An Android app for the treadmill's own screen. Talks to the motor board over USB, drives incline, runs guided walks, and offers five completely different interfaces. |
| **`homeassistant/`** | Scripts that install the coach, the reminders and a health dashboard. **Optional** — the treadmill registers itself in Home Assistant without any of it. |

There used to be a third part, `ios/`. It is now its own product,
**[AH for HA](https://github.com/keranm/ah-for-ha)** — Apple Health into Home
Assistant, with no treadmill in it at all. It grew to 39 metrics, rings, sleep
stages and workouts, none of which are about walking on a belt, and most people
who want that will never own a treadmill.

STRIDE keeps the half that matters to it. An outdoor walk can still become a
gradient profile the deck replays, because that arrives over MQTT as a published
contract and the console has never known who publishes it. Swap the publisher
and it cannot tell.

Plus [`protocol/`](protocol/FITPRO_PROTOCOL.md) — the FitPro serial protocol,
decoded and written down. Probably the most useful thing here if you're working
on a different machine.

---

## Home Assistant needs nothing installed

The console publishes its own MQTT discovery. Put your broker details into
Settings → Home Assistant on the treadmill and a **Treadmill** device shows up
with speed, incline, distance, elapsed, pulse, calories, mode and workout, plus
a device per person as they walk.

No integration, no YAML, no custom component. Build whatever dashboard you
like from it — there's no STRIDE dashboard you have to accept. A shipped one
may come via HACS later.

The `homeassistant/` scripts are for the extras: the coach, the reminders, and
an opinionated health dashboard if you want a starting point.

---

## Does this work on my treadmill?

Proven on one Gen 6 NordicTrack with a FitPro board — device id `0x04`, incline
−3 to +12 %, speed 1.6 to 20 km/h. Probably fine on other Gen 6 NordicTrack and
ProForm consoles on the same board, but nobody has tried.

Gen 7 / iFit 2.0 is reported not to work, but nobody here has tested it. If you
know better, please let us know.

[`console/usbprobe`](console/usbprobe) reads your board and reports what it is,
without changing anything. It is an app you install on the console, so it needs
ADB and the iFit software disabled first, which is step 6 above rather than
something you can run today. **Please open an issue with what it says,** working
or not. A list of known-good boards would be the most useful thing this project
could have, and it doesn't exist.

You also need a console you can reach with `adb`, which is the part that varies
most by model and year.

---

## Installing

**[docs/INSTALL.md](docs/INSTALL.md)** — but read the banner above first. That
guide has not yet been followed by anyone who did not already know the answers,
and it says so at the top and marks the places it is weakest.

In outline:

1. **Factory reset the console.** Stay offline afterwards.
2. **Enable privileged mode.** Tap ten times, wait seven seconds, tap ten more.
3. **Force stop iFit Admin** (`com.ifit.eru`) and take away its two
   permissions, then force stop and uninstall iFit (`com.ifit.standalone`).
4. **Enable ADB over Wi-Fi.** Tap Build number seven times, turn on USB
   debugging, join your network.
5. **Run [`tools/unchain.sh`](tools/unchain.sh).** Disables the iFit packages
   and installs the launcher.
6. **Identify your board** with [`console/usbprobe`](console/usbprobe), which
   reads and changes nothing. Report what it says.
7. **Build and install the console app** with `console/stride/run.sh`, then set
   it up on its own screen.
8. **Optional:** enter broker details on the console to get the treadmill into
   Home Assistant, then add the coach.

Stop after step 7 and you have a treadmill with no subscription. Steps 1 to 7
need nothing installed on the Home Assistant side.

---

## Configuration

**The console configures itself.** Broker details, who walks, units, warm-up,
coach, heart rate, display — all in Settings on the treadmill's own screen. No
config files, no rebuild.

**The Home Assistant side reads one file:**

```sh
cp homeassistant/stride.conf.example homeassistant/stride.conf
```

Every key is documented in the example. Leave anything blank and that subject
is simply absent — no scale, no blood-pressure cuff, no phone. The coach says
nothing about what it wasn't given rather than guessing.

`coach_ai_task` has no default, because which AI entity you have depends on
which integration you set up.

**For weekly and monthly totals**, copy
[`homeassistant/packages/stride.yaml`](homeassistant/packages/stride.yaml) into
your Home Assistant config. The console's distance counter resets every walk, so
the accumulating has to happen in HA. That file also creates the helpers the
coach and reminders need.

---

## Design decisions worth knowing

- **Kotlin owns the treadmill, HTML owns the screen.** The five interfaces are
  HTML in a WebView, so they can be designed in a browser. The buttons do what
  buttons do — tap SPEED+ and the belt speeds up — but every request goes
  through Kotlin, which clamps it to what the board says it can do. A bad
  layout can't outrun the machine.
- **The coach decides when to speak; Home Assistant decides what it says.**
  With HA unreachable it still marks your kilometres from a set of built-in
  lines.
- **A stale number is worse than a missing one.** Every health metric carries
  its age, and anything that hasn't changed since midnight is withheld rather
  than shown as today's. Learned the hard way, from a coach cheerfully
  reporting yesterday's step count at 7am.
- **Coaching and recording are per person**, not per console. A guest gets
  neither.

---

## Licence

[Apache-2.0](LICENSE). No warranty — see [SAFETY.md](SAFETY.md).

STRIDE is an independent project, built for compatibility with hardware its
authors own. Not affiliated with or endorsed by iFIT, ICON Health & Fitness,
NordicTrack, ProForm, Apple or Home Assistant. Trademarks belong to their
owners and are used here only to say what this works with.

This repository contains no code from any of those companies.
