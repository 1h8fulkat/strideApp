# Installing STRIDE

> 🚧 **This guide has not yet been followed by anyone but its author, on
> hardware that was already set up.** It is written to be tested — see
> [Testing this guide](#testing-this-guide) at the end. If you are reading it
> before that has happened, expect to find gaps, and please
> [say so](https://github.com/keranm/strideApp/issues).

**Read [SAFETY.md](../SAFETY.md) first.** This software drives a motorised
treadmill.

**This is reversible.** A factory reset restores the manufacturer's software.
Verified on the machine STRIDE was built against. You are changing the screen
on the front, not the motor controller, the belt or the deck.

---

## What you need

| | |
|---|---|
| A treadmill | NordicTrack or ProForm with a FitPro motor board and an Android console. See [Is my treadmill supported](#step-0-is-my-treadmill-supported). |
| A computer | macOS or Linux, with `adb` and Python 3.9+. |
| Home Assistant | **Optional.** With an MQTT broker, if you want the treadmill in it. |
| An iPhone | **Optional.** Only for the Apple Health companion app. |

Time: about an hour for the console, ten minutes for Home Assistant.

The three parts are independent. **The console is the only one that matters** —
stop after Part 1 and you have a treadmill with no subscription, which is most
of the point.

---

## Step 0. Is my treadmill supported?

Honestly: nobody knows except for one machine. What is known is:

- **Proven:** a NordicTrack whose board reports device id `0x04`, incline
  −3 to +12 %, speed 1.6 to 20 km/h.
- **Likely:** other NordicTrack and ProForm consoles on the same FitPro board.
- **Required regardless:** a console running Android that you can reach with
  `adb`.

Before changing anything, find out what you have. `console/usbprobe` is a
read-only app that asks the board what it is and reports back — it writes
nothing and moves nothing.

**Whatever it tells you, please [open an
issue](https://github.com/keranm/strideApp/issues) with the result** — working
or not. A list of known-good and known-bad boards is the most valuable thing
this project could have and it does not exist yet.

---

## Part 1 — The console

### 1.1 Get developer access to the console

This is the part that varies most between models and years, and it is the part
this guide is least able to help with, because it depends on your console's
firmware rather than on STRIDE.

On the machine this was built for: hold the iFit logo on the welcome screen,
enter privilege mode, and enable ADB over the network.

> **Gap:** the exact sequence differs by model and firmware. If yours differs,
> please contribute what worked.

Check it from your computer:

```sh
adb connect <console-ip>:5555
adb devices
```

✅ **Checkpoint:** the console appears in `adb devices` as `device` — not
`unauthorized` or `offline`.

### 1.2 Understand what you are about to run

`tools/unchain.sh` does four things, in this order:

1. **Disables `com.ifit.eru`.** This is the piece that re-locks the console on
   every boot. It goes first, before anything else, because it is the thing
   that will undo the rest.
2. Disables the remaining iFit packages.
3. Installs a launcher, so there is something to go back to.
4. Installs STRIDE and sets it as home.

Read it before you run it. It is 80 lines and it operates on your treadmill.

### 1.3 Build and install

```sh
cd console/stride
./run.sh                 # builds, installs, launches, and tails the log
```

The build needs the Android SDK. `run.sh` finds it via `ANDROID_HOME`, or the
default location under `~/Library/Android/sdk`.

✅ **Checkpoint:** the console shows the STRIDE welcome screen, and the log
prints `limits: <min>..<max> km/h, <min>..<max> %` — those numbers came from
your motor board, which means the USB link works.

### 1.4 Set it up, on the console

Everything is on the treadmill's own screen. There are no config files.

**Settings → Who walks.** Add yourself. Nobody is pre-configured, so the
welcome screen offers *Guest* and *Add people* until you do.

**Settings → Treadmill.** Units, warm-up, cool-down, how fast a guided walk may
move the deck. The incline and speed ranges shown are read from your board and
cannot be raised — see [SAFETY.md](../SAFETY.md).

**Settings → Interface.** Five interfaces. *Original* is the one with real
miles on it; the other four are less tested.

✅ **Checkpoint:** walk on it. Clip the safety key, choose a workout, and check
the belt starts and the distance climbs.

**Stop here if you do not use Home Assistant.** Everything below is optional.

---

## Part 2 — Home Assistant

### 2.1 The treadmill registers itself

You need the [MQTT integration](https://www.home-assistant.io/integrations/mqtt/)
set up and a broker running. Nothing from this repository is installed in Home
Assistant for this step.

On the console: **Settings → Home Assistant** → broker host, port, username,
password → **Test**.

✅ **Checkpoint:** a **Treadmill** device appears in Home Assistant under
Settings → Devices, with speed, incline, distance, elapsed, pulse, calories,
mode and workout. A second device appears per person the first time they walk.

That is the whole integration. **Build whatever dashboard you like from those
entities** — there is nothing you have to accept, and no custom component to
install.

### 2.2 Optional: weekly and monthly totals

The console publishes a *session* counter that resets at the start of every
walk. Correct for a session, useless for "how far this week", so the
accumulation happens in Home Assistant.

Copy [`homeassistant/packages/stride.yaml`](../homeassistant/packages/stride.yaml)
into your Home Assistant config as `packages/stride.yaml`, and make sure
`configuration.yaml` has:

```yaml
homeassistant:
  packages: !include_dir_named packages
```

Restart Home Assistant.

✅ **Checkpoint:** `sensor.treadmill_distance_weekly` exists and is not
`unavailable`. If it is unavailable, its source is wrong — check whether your
treadmill device is named something other than `Treadmill`, and set
`treadmill_prefix` in `stride.conf` plus the `source:` lines in the package to
match.

> **Gap:** this file has been validated as YAML and mirrors a configuration
> known to work, but has not itself been installed on a clean Home Assistant.

### 2.3 Optional: the coach and reminders

```sh
cd homeassistant
cp stride.conf.example stride.conf
```

Fill it in. Every key is documented in the file, including what leaving it
blank does — which is always "that subject is absent", never an error.

One key has no default and must be set: **`coach_ai_task`**, the AI entity that
writes the coach's lines. Which one you have depends entirely on which
integration you set up; find yours in Developer Tools → States under `ai_task.`.

```sh
python3 stride_coach_llm.py      # the coach, and the automations that fire it
python3 stride_reminders.py      # weigh-in and blood-pressure reminders
python3 stride_persons.py        # publishes your HA people to the console
```

✅ **Checkpoint:** `script.stride_coach` exists. Run it with
`kind: morning` and a `task`, and a coach message appears on
`sensor.stride_coach`.

After `stride_persons.py`, the console's **Settings → Who walks** offers your
Home Assistant people under *From Home Assistant*. Adding somebody that way
links them, so their walks line up with the person Home Assistant already
knows.

---

## Part 3 — Apple Health (iPhone)

Optional, and only if you want Apple Health data in your own Home Assistant.

```sh
cd homeassistant
python3 stride_health_webhook.py    # creates the sensors and the webhook
```

Then build the app:

```sh
cd ios
./build.sh open                     # generates the project and opens Xcode
```

Run it on your phone. On first launch, paste the webhook URL — printed by
`stride_health_webhook.py --show-url`.

> **Read that URL off your own screen.** The webhook id is the only thing
> protecting that endpoint, so do not paste it into a chat window, an issue, or
> a screenshot.

✅ **Checkpoint:** press **Sync now**. `sensor.apple_health_steps` gets a value
and `sensor.apple_health_last_sync` shows the current time.

Set **Sync at least** to how fresh you want the data. Hourly is roughly
twenty-four background wakes a day against one; the app says so under the
picker.

---

## If it goes wrong

1. **Pull the safety key.** Always first — it is hardware.
2. Switch the treadmill off at the wall.
3. To restore the manufacturer's software: **factory reset** from the console's
   own recovery. You will set up iFit again from scratch, and you will be back
   where you started.

---

## Testing this guide

If you are following this as a new user — especially on a factory-reset
treadmill and a fresh Home Assistant — the following are the things most likely
to be wrong, and knowing either way is worth more than any feature right now:

- **Step 1.1**, privilege mode, is model-specific and barely documented here.
- **Step 2.2**, the package file, has never been installed on a clean Home
  Assistant.
- **Every ✅ checkpoint** is a claim. If one does not happen, that is the bug.
- **Anything you had to know that is not written down.** The author cannot see
  these, because he already knows them.

Please open an issue with what you hit. "I got stuck at 1.1 on a 2019 Commercial
1750" is more useful than it sounds.
