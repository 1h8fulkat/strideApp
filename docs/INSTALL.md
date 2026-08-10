# Installing STRIDE

> 🚧 **DRAFT.** Part 1 now follows the route the author actually took, but
> nobody has yet walked this page end to end on a factory-reset machine. Part 2
> onwards is still unverified by anyone. Expect gaps and please
> [report them](https://github.com/keranm/strideApp/issues).

> # ⛔ Before you touch anything: never accept the Gen 7 / iFit 2.0 update
>
> If your console offers an update to Gen 7 or iFit 2.0, **decline it, and keep
> declining it.** That update is understood to close the privileged-mode route
> this entire process depends on. Take it and you are locked out permanently —
> there is no known way back, and a factory reset will not help you.
>
> **Modify first. Update never.** If your machine is already on Gen 7, nothing
> in this guide will work for you.

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

The three parts are independent. Stop after Part 1 and you have a treadmill
with no subscription, which is the main event.

---

## Step 0. Is my treadmill supported?

Nobody knows except for one machine, which is:

| | |
|---|---|
| Generation | **Gen 6** (the "CLASSIC" embedded console) |
| GlassOS | 8.51.7.1070 |
| System version | EKA2_20221110 |
| Firmware | 84.121 |
| Motor board | FitPro, device id `0x04` |
| Ranges reported | incline −3 to +12 %, speed 1.6 to 20 km/h |

- **Likely to work:** other Gen 6 NordicTrack and ProForm consoles on the same
  FitPro board. The protocol is a family, not a model.
- **Will not work:** Gen 7 / iFit 2.0. See the warning above.
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

### 1.1 Factory reset the console

Takes five to ten minutes, and means nothing left over from iFit is in the
way.

Find and hold the **pinhole reset button** while you switch the treadmill on at
the power switch. You should see *System recovery* in blue text. Let it finish.

> **The pinhole is in a different place on different machines** — the side
> panel on some, the top of the console on others. Look for a single small hole
> that is plainly not a screw or a vent.

> **Do not connect it to Wi-Fi in iFit afterwards.** Leave it offline until
> you have finished step 1.3. A console that reaches the internet is a console
> that can be offered the update that locks you out.

### 1.2 Enable privileged mode

On the iFit welcome screen:

1. Tap a **blank area** of the screen **ten times**.
2. **Count to seven.** Nothing happens during this pause — that is the
   sequence, not you having got it wrong.
3. Tap **the same spot** ten more times.

A message at the bottom confirms privileged mode is on. **Swipe up from the
bottom** to get the Android home button, and from there Settings.

> **Some consoles ask for a code here** — a challenge number expecting a
> response. Either ring NordicTrack support and ask for one, or use a
> third-party calculator like <https://getresponsecode.com> (no connection to
> this project).

### 1.3 Stop iFit locking you back out — **do not skip this**

Skip this and everything else you do gets undone at the next reboot.

In the console's Android settings there are two apps to deal with. They appear
as **iFit Admin** and **iFit**:

**iFit Admin** — the one that re-asserts the lock.

- **Force stop** it.
- Turn off **Draw over other apps**.
- Turn off **Modify system settings**.
- You **cannot uninstall it**; it is a system app. Stopping it and taking those
  two permissions away is the most that can be done from here.

**iFit** — the app itself.

- **Force stop** it.
- **Uninstall it if you can.** This one usually can be removed, and removing it
  is the cleanest way to stop it starting up and taking the screen back.

Leave iFit Admin those permissions and it switches privileged mode off again
at the next boot, with nothing on screen to say why.

[`tools/unchain.sh`](../tools/unchain.sh) does this properly over ADB later,
and kills iFit Admin first for the same reason. This is the manual version, and
you need it now because you don't have ADB yet.

### 1.4 Enable ADB over Wi-Fi

1. **Settings → About tablet → Build number**, tapped **seven times**, unlocks
   Developer Options.
2. **Settings → Developer Options → Enable USB debugging.**
3. Pull down the notification panel, long-press **Wi-Fi**, and join your
   network.
4. **Settings → About tablet → Status** gives you the console's IP address.

From your computer:

```sh
adb connect <console-ip>:5555
adb devices
```

✅ **Checkpoint:** the console appears in `adb devices` as `device` — not
`unauthorized` or `offline`.

### 1.5 Back up the original software first

Before changing anything, take a copy of what is on there:

```sh
./protocol/grab-ifit.sh
```

Pulls the console's APKs onto your computer. They're from a machine you own,
so keep them to yourself. Handy if something goes wrong and you'd rather not
rely on a factory reset.

### 1.6 Understand what you are about to run

`tools/unchain.sh` does five things, in this order:

1. **Disables `com.ifit.eru`.** This is the piece that re-locks the console on
   every boot. It goes first, before anything else, because it is the thing
   that will undo the rest.
2. Disables the remaining iFit packages.
3. Turns on developer settings and keeps the screen awake while you work.
4. Installs a launcher, so there is something to go back to.
5. Installs STRIDE — if you have built it — and makes it the console's home
   app, so a power cycle comes back to the treadmill rather than to a grid of
   icons. Run it with `STRIDE_KIOSK=0` to skip that last part; the script
   prints the one command that undoes it either way.

Read it before you run it. It operates on your treadmill.

> **Your remote access does not survive a reboot; the console does.** Enabling
> ADB over Wi-Fi sets a property that is wiped at every boot, and making it
> permanent needs root that a locked console will not give you. After a power
> cycle, plug the USB cable back in and run `adb tcpip 5555` to get back in
> remotely. STRIDE itself needs none of this — it starts on its own.

### 1.7 Build and install

```sh
cd console/stride
./run.sh                 # builds, installs, launches, and tails the log
```

The build needs the Android SDK. `run.sh` finds it via `ANDROID_HOME`, or the
default location under `~/Library/Android/sdk`.

✅ **Checkpoint:** the console shows the STRIDE welcome screen, and the log
prints `limits: <min>..<max> km/h, <min>..<max> %` — those numbers came from
your motor board, which means the USB link works.

### 1.8 Set it up, on the console

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

Every key is documented in the file. Leave anything blank and that subject is
just absent.

**`coach_ai_task`** has to be set — it's the AI entity that writes the coach's
lines, and which one you have depends on your integration. Find it in Developer
Tools → States under `ai_task.`.

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

## Credit

Steps 1.1–1.4 follow a community *NordicTrack Gen 6 modding guide* — that's
how ADB got onto this console in the first place. That guide heads towards QZ
Companion, which is a perfectly good place to end up; STRIDE goes a different
way after 1.4 and replaces the console software.

If you know who wrote it, [tell us](https://github.com/keranm/strideApp/issues)
and we'll credit them.

---

## If it goes wrong

1. **Pull the safety key.** Always first — it is hardware.
2. Switch the treadmill off at the wall.
3. To restore the manufacturer's software: **factory reset** from the console's
   own recovery. You will set up iFit again from scratch, and you will be back
   where you started.

---

## Testing this guide

If you're following this on a factory-reset treadmill and a fresh Home
Assistant, these are the bits most likely to be wrong:

- **Steps 1.1–1.4** are the route the author took, written up afterwards. Nobody
  has followed *this page* through them on a freshly reset machine.
- **Step 2.2**, the package file, has never been installed on a clean Home
  Assistant.
- **Every ✅ checkpoint** is a claim. If one does not happen, that is the bug.
- **Anything you had to know that is not written down.** The author cannot see
  these, because he already knows them.

Open an issue with whatever you hit. "Stuck at 1.1 on a 2019 Commercial 1750"
is genuinely useful.
