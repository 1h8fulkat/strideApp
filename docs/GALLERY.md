# What it looks like

Real screenshots off a real console, at its native 1280×800.

All of them are **fabricated state on a cold belt** — `tools/hud_drive.py` parks
the renderer and injects a frame, so every screen can be reviewed without
anyone standing on a treadmill. The numbers are invented; the pixels are not.

The people shown are made up, and the Home Assistant screen is left out on
purpose: it contains a broker address and a password field.

---

## Five interfaces, one treadmill

They are not themes. Each is a separate document that owns its whole layout —
switching one for another changes what the screen is made of. The treadmill
behaves identically in all of them, and nothing in any of them can move the
belt.

### Original

The one with real miles on it. A 400 m oval, the lap count in the middle, and
the two numbers that matter flanking it.

![Original](screenshots/oval-original.png)

### Ember

Warm near-black, hairlines, one amber light. Built for 6am.

![Ember](screenshots/oval-ember.png)

### Cluster

Machined dials and mono figures — an instrument panel rather than a dashboard.

![Cluster](screenshots/oval-cluster.png)

### Daylight

The light one. Warm paper and ink, for a bright room or older eyes.

![Daylight](screenshots/oval-daylight.png)

### Pacer

Violet and lime, chunky pills, plain-spoken. Hard to misread mid-stride.

![Pacer](screenshots/oval-pacer.png)

---

## A guided walk

On a guided walk the oval is replaced by the ground ahead: the profile of the
walk, where you are on it, and what the next segment is called. The dot is your
position; the dashed line is what is still to come.

**The incline is driven and the pace is only suggested** — "lift to 6.4 km/h"
is a proposal, and the belt does not act on it. That asymmetry is deliberate
and is enforced in the console, not in the plan. See [SAFETY.md](../SAFETY.md).

![Original, guided walk](screenshots/ui-original.png)

The same walk in Pacer, which draws the profile as a lane rather than a
landscape:

![Pacer, guided walk](screenshots/ui-pacer.png)

---

## Settings

Eight sections, on the treadmill's own screen. There are no configuration files
on the console and nothing needs rebuilding to change.

### Who walks

Everyone who uses the treadmill, each with their own totals. **Coaching and
recording are per person** — a walker who has not opted in gets neither, and a
guest gets neither by definition.

Someone here can be linked to a Home Assistant `person`, so their walks line up
with the person Home Assistant already knows. Somebody who exists only on the
console is marked *console only* rather than silently looking the same.

![Who walks](screenshots/settings-who-walks.png)

### Heart rate

The handlebar grips work but need both hands on the bar, so they drop out on
exactly the hills where the number is worth having. A Bluetooth chest strap
reads continuously.

Any strap using the standard heart rate service — the scan filters on the
service UUID rather than on brand names, so a strap nobody has heard of works
on the day it is unboxed.

![Heart rate](screenshots/settings-heart-rate.png)

### About

Version, and what the console is actually talking to. The board line is read
over USB at start-up, so it is evidence rather than configuration.

![About](screenshots/settings-about.png)

---

## The welcome screen

Nobody is pre-configured. Until somebody is added it offers a guest walk and a
way to add people — a treadmill that will not start until you have filled in a
form is a worse treadmill.

![Welcome](screenshots/welcome.png)
