# Safety

**Read this before you install anything.**

STRIDE replaces the software on a treadmill console and then drives the
machine: it sets the belt speed and it moves the incline deck, under a person
standing on it. That is not the same class of project as a dashboard.

None of what follows is boilerplate. Every line of it is a decision that is
enforced in the code, and the reasons are worth knowing before you trust it.

---

## What can hurt you

**The belt can move without you touching the console.** A guided walk changes
the incline on its own, and a warm-up ramps the belt up when a workout starts.
Both are deliberate. Both mean the machine can do something you did not just
ask it to do, this second.

**The incline deck moves under load.** Going from flat to nine percent moves
real mass while you are standing on it.

**A treadmill without its manufacturer's software is still a treadmill.** The
motor controller, the belt and the deck are unchanged. Everything that was
physically dangerous about the machine before is still true.

---

## The rules this software follows

These are not suggestions in a document. They are how it is built.

**The physical safety key is the stop of record.** Pull it and the hardware
cuts the belt, below and independent of anything STRIDE is doing. STRIDE
notices and shows a screen, but it is not the thing that stopped the belt. If
you take one thing from this page: *the key is the emergency stop, not the
software.*

**Incline is driven, speed is only ever suggested.** A guided walk moves the
deck, because the deck cannot run away underneath anyone. It never sets your
pace — it proposes one and you decide. This asymmetry is deliberate and is
enforced in `MainActivity`, not left to the plan.

**Every automatic finish decelerates.** The first guided walk ended by cutting
the belt from 4.2 km/h to zero the instant the plan expired, which was enough
to catch someone off guard; at a run it would have put them on the floor.
Automatic stops now ramp down at 2 km/h per second. **The STOP button is
deliberately exempt** — that is a person deciding, and it needs to be
immediate.

**The machine's own limits always win.** Incline and speed ranges are read from
the board at connect. A plan is a proposal and is clamped to what the hardware
reports. Nothing in the settings screen can raise those limits, on purpose:
storing a maximum incline somebody typed would be storing a way to ask the deck
for something it cannot do.

**Incline moves gradually.** At most one percent every three seconds by
default, so the largest jump in any built-in plan takes about half a minute to
arrive rather than landing under you mid-stride. The setting offers Gentle,
Normal and Quick rather than raw numbers, so that reasoning stays attached to
the choice.

---

## What this is not

**STRIDE is not a medical device.** It is not certified as anything, by anyone.

**The coach is not medical advice.** It is a language model given some numbers
and told to be brief. It is explicitly instructed never to give medical advice
and never to express concern about a reading, and there is a setting to take a
subject off the table entirely. It can still be wrong, and it does not know
anything about you that you did not give it.

**Heart rate here is not clinical.** Handlebar grips and Bluetooth chest straps
are consumer sensors. If a number looks alarming, believe your body and a
doctor, not a treadmill.

---

## What you are doing to your treadmill

Installing this means disabling the manufacturer's software on the console.

- **Your warranty is almost certainly void.** Assume it is.
- **You will lose the manufacturer's features** — subscription workouts, their
  app, their account, whatever else the console did.
- **You are working with the console's operating system.** It is possible to
  make it unbootable. Read the install guide fully before starting, not while
  stuck halfway.

**This is reversible.** A factory reset from the console's own recovery restores
the manufacturer's software, and that has been verified on the machine STRIDE
was built against. Installing STRIDE is not a one-way door, and it does not
touch the motor controller, the belt or the deck — only the screen on the front.

Reversible is not the same as supported. Nothing here is supported by anybody,
and a factory reset verified on one console is not a promise about yours. But
the honest risk is "you may have to reset the console and start again", not
"you may end up with a treadmill you cannot use".

Still: do not do this to a treadmill you do not own.

---

## If something goes wrong

1. **Pull the safety key.** Always first. It is hardware.
2. Step off.
3. Switch the treadmill off at the wall.

If the console misbehaves — a screen that will not respond, a plan that does
something unexpected — the key and the wall switch are both still there and
both still work. STRIDE cannot disable either.

**If you want the treadmill back as it was**, a factory reset from the
console's recovery restores the manufacturer's software. Verified on the
machine this was built against. You will have to set up iFit again from
scratch, and you will be back where you started — which is the point.

---

## Reporting a safety problem

If you find something that could hurt somebody, please raise it directly rather
than in a public issue first: open a
[security advisory](https://github.com/keranm/strideApp/security/advisories/new)
so it can be looked at before it is widely known.

Anything that moves the belt or the deck in a way the person on it did not
expect is a safety problem, not a bug.
