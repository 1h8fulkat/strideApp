# Safety

Read this before you install anything.

STRIDE replaces the software on a treadmill console and then drives the
machine. It sets the belt speed and moves the incline deck while somebody is
standing on it.

---

## What can hurt you

**The belt and deck can move without you touching anything.** A guided walk
changes the incline on its own, a warm-up ramps the belt up when a workout
starts, and heart-rate zone targeting changes the belt speed throughout a walk
— see [The belt speed is the console's](#the-belt-speed-is-the-consoles) below,
which is new and which replaces a promise this project used to make.

**The incline deck moves under load** — flat to nine percent shifts real weight
while you're on it.

**Everything that was dangerous about the treadmill before still is.** The
motor, belt and deck are unchanged.

---

## The belt speed is the console's

**This project used to promise that it would never choose your pace.** The rule
was "incline is driven, speed is suggested": a guided walk moved the deck on
its own, but the belt only ever did what you asked it to, and a plan that
wanted you going faster put a number on the screen and left the decision with
you. It was stated in this file, in the README, in `CONTRIBUTING.md` and in the
header of `Plan.kt`.

**That guarantee is withdrawn.** It was dropped deliberately, by the owner of
the machine STRIDE was built on, so the console can hold a heart-rate zone —
which it cannot do without commanding belt speed, because the belt is what
changes your heart rate. If you are here because you liked that promise: it is
gone, and nothing replaces it.

What it means in practice:

* **The console already set belt speed without being asked, at the edges.**
  START takes the belt to your warm-up pace, the warm-up and cool-down ramp it,
  and ending a walk eases it down.
* **Zone targeting drives it continuously.** Give the console a target zone and
  it raises and lowers the belt for as long as the walk lasts to keep your pulse
  in a band. It moves in small steps and then waits fifteen to thirty seconds
  for your heart to answer before moving again, so it is not chasing a
  transient. A pulse of zero is read as a monitor that has come off rather than
  as a resting heart, and the belt holds its speed until a real reading returns.
* **A manual speed press takes the belt back** for the rest of the walk, with a
  control to hand it back deliberately.

**Where this actually stands, so this file does not promise something that is
not built.** As of this commit the targeting loop is *not in the code*. Zone
tracking is live — the console knows which zone you are in, draws it, and
records time in each — and the belt is still driven only at the edges described
above. The loop is the next piece of work. The guarantee is recorded as
withdrawn here rather than at the moment the loop lands, because a safety
document that grants a guarantee it is about to take away is worse than one
that never gave it.

**What has not changed, and was never part of that decision:**

* The **safety key is still the emergency stop**, and it is hardware.
* **Every commanded speed is still clamped** to the range the board reports for
  itself — on the console this was built against, 0.8 to 19.31 km/h. No
  interface, plan, zone or setting can raise it.
* Nothing in software is the last line. See below.

---

## What the software does about it

**The safety key is the emergency stop, not the software.** Pull it and the
hardware cuts the belt, independently of anything STRIDE is doing. STRIDE
notices and shows a screen, but it isn't what stopped the belt.

**The board's limits win.** Incline and speed ranges are read from the machine
at startup and every plan is clamped to them. Nothing in Settings can raise
them.

**Incline moves gradually** — a percent every three seconds by default, so the
biggest jump in any built-in plan takes about half a minute rather than landing
under you mid-stride. Settings offers Gentle, Normal and Quick rather than raw
numbers.

---

## A belt that will not stop

**This has happened, twice, on the machine STRIDE was built on.** It is the one
failure worth reading about in advance, because the thing that fixes it is you.

On 31 July a guided walk finished, the console showed the summary, and the belt
kept running for thirty-five seconds until somebody pressed the physical stop.
One dropped USB frame and the command was simply gone.

On 12 September it happened again and worse: the belt ran at 4.3 km/h for two
minutes after a walk ended, through six hundred stop commands. The board
*accepted* one of them and reported itself paused — and its motor went on
turning. A board that lies about its own motor cannot be argued out of it.

**What the console does now.** It watches the belt's real speed rather than
trusting that its own command landed, and while a belt is moving with no workout
running it will:

* rotate through three different stop commands rather than repeating one the
  board has already refused or ignored — `Pause`, then `Idle`, then a speed;
* after five seconds of a refused zero, command the board's own **minimum**
  speed instead. That looks wrong in a stop routine and is deliberate: on this
  board zero is below the minimum and is refused every time it is sent, so it
  achieves nothing, while 0.8 km/h is accepted and is a shuffle you can step
  off. A command that is obeyed and helps beats one that is refused because its
  number is lower;
* **put a full-screen alarm on every interface**, saying the belt is still
  moving, how fast, and to use the red stop button or the safety key. Until
  September this was reported only to a log file, which is not where somebody
  standing on a moving belt is looking;
* raise a runaway alarm in Home Assistant and write the incident to disk.

**None of that is a guarantee, and it is not the emergency stop.** The physical
stop button and the safety key are. If a belt is moving and you did not ask it
to, use them — do not wait for the console to win the argument, because
sometimes it does not.

---

## What this isn't

STRIDE is not a medical device and is not certified as anything.

The coach is a language model given some numbers and told to be brief. It is
instructed never to give medical advice or express concern about a reading, and
there is a setting to take a subject off the table entirely. It can still be
wrong.

Bluetooth straps, and handlebar grips on machines that have them, are consumer
heart rate sensors. If a number looks alarming, believe your body and a doctor.

---

## What you're doing to your treadmill

- **Your warranty is almost certainly void.** Assume it is.
- **You lose iFit** — subscription workouts, the app, the account.
- **You can make the console unbootable.** Read the whole install guide before
  starting, not while stuck halfway.

**It's reversible.** A factory reset restores the manufacturer's software,
verified on the machine STRIDE was built against. You are replacing the screen
on the front, not the motor controller.

That is not the same as supported — nothing here is supported by anybody, and a
reset that worked on one console is not a promise about yours. But the
realistic worst case is "reset it and start again", not "you now own a broken
treadmill".

Don't do this to a treadmill you don't own.

---

## If something goes wrong

1. **Pull the safety key.**
2. Step off.
3. Switch off at the wall.

Both still work whatever the console is doing. STRIDE can't disable either.

To get the treadmill back as it was, factory reset from the console's recovery
and set up iFit again.

---

## Reporting a safety problem

Please open a
[security advisory](https://github.com/keranm/strideApp/security/advisories/new)
rather than a public issue, so it can be looked at before it is widely known.

Anything that moves the belt or deck in a way the person on it didn't expect is
a safety problem, not a bug.
