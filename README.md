# STRIDE

**A NordicTrack treadmill, free of its subscription, talking to Home Assistant.**

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

> ⛔ **Never accept the Gen 7 / iFit 2.0 update on your console.** It is
> understood to close the privileged-mode route everything here depends on,
> permanently and with no way back. Modify first, update never. If your machine
> is already on Gen 7, none of this will work.

STRIDE replaces the software on a treadmill console with something you own. The
belt and deck work without a login, without a subscription, and without an
internet connection — and everything the machine knows appears in Home
Assistant as ordinary entities.

**v0.5**, in daily use. What's missing for v1.0 isn't features — it's install
guides with photos, HACS packaging, a better dashboard, and the iOS app on the
App Store.

![STRIDE on a NordicTrack console](docs/screenshots/oval-original.png)

**[More screenshots →](docs/GALLERY.md)**

---

## What it is, in three parts

| | |
|---|---|
| **`console/`** | An Android app for the treadmill's own screen. Talks to the motor board over USB, drives incline, runs guided walks, and offers five completely different interfaces. |
| **`homeassistant/`** | Scripts that install the coach, the reminders and a health dashboard. **Optional** — the treadmill registers itself in Home Assistant without any of it. |
| **`ios/`** | A companion app that syncs Apple Health into your own Home Assistant. No account, no third-party server, no subscription. |

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

Gen 7 / iFit 2.0 won't work at all.

[`console/usbprobe`](console/usbprobe) reads your board and reports what it is,
without changing anything. **Please open an issue with what it says** — working
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

1. **Check your board** with [`console/usbprobe`](console/usbprobe), which
   reads and changes nothing — and please report what it says.
2. **Get into the console's privilege mode** — a tap sequence and a
   challenge-response code — and enable ADB.
3. **Run [`tools/unchain.sh`](tools/unchain.sh)** — disables the iFit software,
   `com.ifit.eru` first, because that is what re-locks the console on every
   boot.
4. **Build and install the console app**, then set it up on its own screen.
5. **Optional:** broker details on the console and the treadmill appears in
   Home Assistant. Then the coach, and the iOS app for Apple Health.

Stop after step 4 and you have a treadmill with no subscription, which is most
of the point. Steps 1–5 need nothing installed on the Home Assistant side.

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
