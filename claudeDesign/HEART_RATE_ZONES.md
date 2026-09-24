# Dynamic heart-rate zone tracking — implementation state and plan

**Branch:** `heart-rate-zones` (off `main` at `38ae7f9`)
**Started:** 21 September 2026
**Phases 1, 2 and 3 of 6 complete, deployed, and tested on the treadmill.**
Phase 3 was walked, retuned on what the walk said, and walked again.

This file is the handoff. It exists because the work spans more sessions than
one context window holds, and because the decisions behind it are worth more
than the diff. Read it before touching anything.

---

## Where this stands

| | |
|---|---|
| Done | **Phase 1** — the model, birthdays, resting rates, the settings UI, the guest age prompt. Owner-walked. |
| Done | **Phase 2** — live zone state on the frame, the in-memory HR trace, the shared zone-coloured graph, the zone-coloured BPM box, the grey zone 0, the max-HR override. Owner-walked 21 September 2026 and reported good. |
| Done | **Phase 3** — the zone-targeting control loop. `ZoneControl.kt` + 25 unit tests, wired into the poll loop, the ZONE ribbon and the state badge in `original.html`, the coach silenced on pace nudges, `SAFETY.md` brought up to date. **Owner-walked 23 September 2026: all eleven gate items passed.** The ramp felt slow both ways, so the step was made proportional to zone distance, and the retune was **re-walked the same day and reported good**. |
| **Next** | **Phase 4** — zone-targeted presets, and zone targeting for custom and route runs. The first phase to reuse `ZoneControl` rather than build it. |
| Then | Phase 5 summary analytics · Phase 6 the other four interfaces |

**The branch now commands belt speed.** Everything through phase 2 was
read-only with respect to the motor. Phase 3 is not: with a target zone chosen,
the console raises and lowers the belt on its own. It is **off unless switched
on** — `zoneTarget` starts at 0 on every walk, is never persisted, and is
cleared by `resetSession()` — so a walk on which nobody touches the ZONE
control behaves exactly as it does on `main`.

### Next steps, in order

**Phase 4**, and nothing is blocking it. Its own section below has the detail;
the shape of the work is:

1. **`Plan.kt`** — templates that target a zone rather than a grade. A
   `Segment` carries `incline` and a `paceDelta` today, so a zone-targeted
   segment needs either a nullable `targetZone` on `Segment` or a parallel
   shape. Decide that first, because everything else follows it.
2. **The guided picker** — `SHAPES` in `stride-core.js` mirrors
   `Plan.TEMPLATES` for the sparklines, and a zone preset has no grade profile
   to draw, so it needs a different card treatment.
3. **A zone-targeting toggle on Custom and My Route runs.** Same
   `ZoneControl`, same override and resume — the loop is built and walked, so
   this phase wires it up rather than writing it.
4. **Walk it:** at least one preset and one zone-targeted route. The phase 3
   gate list is kept below and still applies to the loop's behaviour.

**The max-HR question is closed.** Jeff set their own maximum to **183** on
the console on 23 September 2026, which puts the ladder at
**122 / 134 / 146 / 158 / 171**. Nothing wrote that automatically —
`Bridge.setPersonMaxHr` is the only path that touches the field and it refuses
while the belt is moving, precisely because under Karvonen it moves all five
boundaries at once and would move the belt with them. The loop follows
whatever is stored; the walker owns the number.

---
## The one thing to read first

This feature **removes a documented safety invariant**, deliberately and on the
owner's explicit instruction.

`Plan.kt`, `README.md`, `SAFETY.md` and `CONTRIBUTING.md` all used to state
some form of:

> **Incline is driven, speed is suggested.** The deck has no interlock and
> cannot run away underneath anyone, so the plan moves it. The belt can, so the
> plan only ever proposes a pace and the person on the treadmill decides.

Zone targeting requires the console to command belt speed to hold a heart-rate
zone. That is the opposite of the sentence above.

The owner was asked directly, with four options ranging from "hold the zone
with incline only, keep the invariant" to "auto speed, unconditional". **They
chose auto speed, unconditional** — no separate opt-in beyond choosing the
mode, and the invariant goes. That decision is made. Do not re-litigate it,
do not quietly reintroduce a gate they declined, and do not implement a weaker
version than they asked for.

**The documents have been rewritten — done on 21 September 2026, ahead of the
code and on the owner's instruction.** `SAFETY.md` has a section named after
the change that says plainly the guarantee is withdrawn and why; the README
banner, `CONTRIBUTING.md`'s belt section, `Plan.kt`'s header and the five
interface comments that asserted the rule all follow it.

Ahead of the code on purpose. A safety document that grants a guarantee it is
about to take away is worse than one that never gave it, so the withdrawal is
recorded now rather than at the moment the loop lands. `SAFETY.md` says
explicitly where things stand — tracking live, loop not built — and **that
paragraph is the one thing Phase 3 has to update**, because it is the only
sentence in the tree that will become false when the loop goes in.

`CONTRIBUTING.md` also tells contributors that the rule is not one to restore,
and that a comment still claiming it is a leftover to fix rather than something
to code to.

What is **not** negotiable and is not part of that decision:

* **Board clamps stay.** `paceKph()` in `MainActivity.kt` coerces every
  commanded speed into the board's own reported `minKph..maxKph`. That is a
  machine-limits rule, not the suggest-only invariant, and it predates this
  work. On this console the range is **0.8–19.31 km/h**.
* **Gradual ramping stays**, because the owner asked for it: adjust, then wait
  15–30 s for heart rate to actually respond before adjusting again.
* **The 0-bpm hold stays**, because the owner asked for it: a pulse of 0 is a
  monitor disconnect, not a resting heart. Hold the current speed, change
  nothing, until a valid signal returns.
* **The physical safety key is still the stop of record.** Nothing in software
  gets to be the last line. See `SAFETY.md`.

---

## Deployment state

| | |
|---|---|
| Console | NordicTrack C 1750, FitPro board, Android 7.0, **Chromium 51 WebView** |
| Reachable at | `192.168.10.10:5555` over adb-over-TCP |
| Installed | `app-debug.apk`, 3.0M, built from `5da658e` — the proportional ramp |
| Installed at | 2026-09-23 20:39 |
| Signer SHA-256 | `f274956772b6606c3fca0264dd84f870df2addecbe4e56d13925b5447997d677` |
| Board limits | speed 0.8–19.31 km/h, incline −3.0 to +15.0 % |
| Units | miles (`units: mi`) — so a 0.2 km/h step reads as about 0.1 mph |
| Monitor | "Galaxy Watch7 (64HA)", `hr_source: auto` |
| Jeff's maximum | **183**, set by hand 23 September 2026. Ladder 122/134/146/158/171 |
| Lauren's maximum | none set — Tanaka's 179. Ladder 120/131/143/155/167 |
| Errors in log | none |

The signer has not changed since phase 1, so every deploy so far has been a
plain `install -r` rather than `--replace`, and no walker data has been wiped.

**Data backups.** Before the phase 1 migration:
`/data/docker/testing/.stride-build/stride-data-20260921-204913.tar` (1.5 MB) —
`shared_prefs` and `files` as they were under the pre-birthday schema. Before
the first phase 3 deploy, taken because it was the first build that commands
the belt: `stride-data-20260923-201747.tar` (1.5 MB). `stride-data-latest.tar`
is a copy of the most recent. Keep them until the feature is merged.

### Build and deploy on this machine

The repo's own `tools/docker-build.sh` and `console/stride/run.sh` assume macOS
paths. On this Debian box use the wrappers one level up instead:

```sh
cd /data/docker/testing
./stride-build.sh debug          # -> strideApp-main/console/.../app-debug.apk
./stride-deploy.sh               # install, launch, then tails logcat forever
./stride-deploy.sh --backup-only # save console app data first
```

`stride-deploy.sh` ends by tailing logcat and never returns, which will hang a
tool call. Either run it under `timeout`, or do the steps by hand:

```sh
ADB=/data/docker/testing/.stride-build/sdk/platform-tools/adb
D=192.168.10.10:5555
$ADB connect $D
$ADB -s $D install -r .../app-debug.apk
$ADB -s $D shell am start -n dev.stride.hud/.MainActivity
$ADB -s $D logcat -d -s Stride:I | tail -40      # -d, not a live tail
$ADB -s $D exec-out screencap -p > /tmp/shot.png
```

The unit tests need the same container the build uses; there is no `test`
variant in `stride-build.sh`, so invoke Gradle directly:

```sh
cd /data/docker/testing
docker run --rm -u "$(id -u):$(id -g)" -e HOME=/cache/home \
  -e ANDROID_USER_HOME=/cache/home/.android -e ANDROID_HOME=/cache/sdk \
  -e GRADLE_USER_HOME=/cache/gradle \
  -v "$PWD/.stride-build:/cache" -v "$PWD/.stride-build/passwd:/etc/passwd:ro" \
  -v "$PWD/strideApp-main:/src" -w /src/console/stride \
  eclipse-temurin:17-jdk ./gradlew --no-daemon --console=plain testDebugUnitTest
```

**Driving the UI by hand is viable and worth doing.** `input tap X Y` plus
`exec-out screencap` on a 1280×800 screen caught two layout faults this phase.
Useful coordinates: SETTINGS `1158 729`, Jeff's card `445 295`, welcome
profile cards at y≈492 (`x` 404 / 640 / 876), BACK `120 730`, settings CLOSE
`118 727`, scroll with `input swipe 780 700 780 300 400`.

---

## Hard constraints

1. **ES2015 ceiling.** Chromium 51. No `?.`, `??`, object spread,
   `async`/`await`, `padStart`, `Object.entries`/`values`, `**`, `Array.flat`,
   `.at()`, optional catch binding, `Promise.finally`. Enforced by
   `tools/ui-test.sh es`.
2. **CSS ceiling, and this is the one that bites.** Unsupported CSS is
   *silently ignored* — it does not throw the way a `?.` does, so a layout
   built on it looks right on the desktop and almost right on the treadmill.
   No flexbox `gap` (Chrome 84), `position:sticky` (56), `:is()`/`:where()`
   (88), `aspect-ratio` (88), `clamp()`/`min()`/`max()` (79), `inset` (87),
   `backdrop-filter` (76). Now enforced by the same `es` pass, which audits
   `<style>` blocks and the stylesheet `stride-settings.js` carries as a string
   array. `cluster.html` has three pre-existing `gap` uses grandfathered in
   `CSS_KNOWN`; they are cosmetic but real and deserve a pass of their own.
3. **`button{}` in `original.html` sets `height:76px` on every button in the
   document.** Anything taller must state its own height, or its content
   renders outside its own border. This caused the one visible bug of phase 1.
4. **Run the suite before deploying:** `tools/ui-test.sh all` — `es`, `zones`,
   `engine`, and `ui` in three modes. All four gates pass at `e1a6f07`.

---

## Decisions already taken

Asked and answered by the owner. Treat as settled.

| Question | Answer |
|---|---|
| How should zone targeting drive the machine? | **Auto speed, unconditional.** See the warning at the top. |
| How wide should the UI work go? | **`original.html` first, treadmill-test it, then port to the other four** as a separate phase. Shared drawing code goes in `stride-core.js` so the ports stay thin. |
| Where does the post-workout HR trace live? | **In memory, summary only.** Kotlin buffers HR+pace for the current walk and hands it to the summary. Lost when the walk ends. Not persisted to `History`. |
| Karvonen/Tanaka vs the existing `220 − age`? | **Replace globally.** One code path. Coach and History use the new numbers. |
| Karvonen or percent-of-max for the boundaries? | **Karvonen.** Asked and answered twice. On 21 September 2026 a request to move zone 1 down to 87 was misread as a request for percent-of-max and implemented; the owner corrected it and it was reverted. **They want Karvonen.** What they had actually asked for was the grey zone 0 below zone 1, plus a max-HR override for tuning the ladder to their own body. Do not re-derive the boundaries from a plain share of maximum. |
| Should the walker be able to override their maximum? | **Yes, per person, with the formula as the default.** Their watch measures 175 from real workout data and Tanaka says 178; a measured ceiling beats a regression. |
| Which maximum does the loop steer against? | **Whatever Settings holds for that walker** — their measured override when they gave one, Tanaka's estimate when they did not. Asked on 23 September 2026 with the 185/178/175 numbers on the table; the owner declined to pick a figure and chose the rule instead. `ZoneControl` is therefore handed a ladder and has no opinion about where it came from, which is also why `Bridge.setZoneTarget` refuses outright for a walker who has no ladder at all. |
| What does the coach say while the loop drives? | **Nothing about pace.** `zone_low` and `zone_high` are suppressed while `zoneAuto` is true; everything else the coach says is untouched. Asked 23 September 2026, with "narrate each adjustment" offered and declined. The nudges come back by themselves the moment the walker takes the belt by hand, which is exactly when the advice is theirs to act on again. |
| How hard should the loop chase a zone? | **Dwell 20 s**, chosen from three options on 23 September 2026 — the middle of the 15–30 s they had already specified. **Step 0.2 km/h per zone of distance, capped at three**, chosen from four options later the same day *after the walk*, which reported the original flat 0.2 as slow in both directions. Coarse far from the band, unchanged on the final approach. The up/down asymmetry that came with the flat step was an unasked-for default, was flagged as reversible, and went with it. |
| Zone names? | **The reference screenshot's set** — Low intensity, Weight control, Aerobic, Anaerobic, Maximum. The owner was offered the plainer effort labels and chose these. The trade-off is recorded in `HrZones.ZONE_NAMES`; don't undo it. |

There is **no `Directory structure.txt`** in the repo or workspace, despite the
original brief referring to one. The tree was mapped by hand.

---

## Where things live

```
console/stride/app/src/main/
  java/dev/stride/hud/
    HrZones.kt        NEW — all zone arithmetic. Single source of truth.
    HrTrace.kt        NEW — the walk's HR+pace trace, downsampled as it fills.
    ZoneControl.kt    NEW — phase 3. The loop that commands belt speed. Pure.
    Settings.kt       Person.birthday + restingHr; derived age/maxPulse/zoneFloors
    MainActivity.kt   4176 lines. Poll loop, Bridge (@JavascriptInterface),
                      zoneSecs, walkTrace
    Session.kt        Snapshot — the one frame sent to the page and to HA
    Coach.kt          Spoken coaching. Has its own softer ZONE_WORDS register.
    History.kt        Walk records on disk. Aggregates only, no time series.
    Plan.kt           Guided-walk templates. Phase 4 adds zone presets here.
    HeartRate.kt      BLE strap, standard 0x180D/0x2A37
  assets/ui/
    stride-core.js    2400+ lines. Shared helpers. ZONES, the page's zone math,
                      and hrGraph() — the shared zone-coloured trace renderer.
    stride-settings.js  The settings screen, shared by all five interfaces.
    original.html     The default interface, 2500+ lines. Build here first.
    cluster.html / ember.html / pacer.html / daylight.html   Port targets.
console/stride/app/src/test/java/dev/stride/hud/HrZonesTest.kt   NEW — 22 tests
console/stride/app/src/test/java/dev/stride/hud/HrTraceTest.kt   NEW — 13 tests
console/stride/app/src/test/java/dev/stride/hud/ZoneControlTest.kt NEW — 25 tests
tools/uitest/zones.js   NEW — holds the page's copy of the formulas to Kotlin's
tools/uitest/es.js      Extended with the CSS audit
```

### The deliberate duplication

The zone arithmetic exists **twice**: `HrZones.kt` and `zoneFloorsFor()` /
`zoneOf()` / `maxPulse()` / `vo2max()` in `stride-core.js`. This is intentional
and documented in both places. The page needs it because:

* the settings screen draws the zones of whichever *person is being edited*,
  who is usually not the one walking; and
* all five interfaces are developed standalone in a desktop browser with no
  bridge behind them.

It cannot call into Kotlin for a colour on every frame of a line graph.

`tools/uitest/zones.js` pins the two to the same numbers, reading
`ZONE_COLOURS`, `ZONE_NAMES` and `ASSUMED_AGE` straight out of the Kotlin
source so there is no third copy to forget. **If you change a boundary, change
it in three places** — `HrZones.kt`, `stride-core.js`, `HrZonesTest.kt` — and
the suite will tell you which one you missed.

---

## The model

**Max HR — Tanaka:** `208 − 0.7 × age`, rounded. Replaced `220 − age`, which
has no findable study behind it. The two differ by `0.3 × age − 12`, so they
agree at exactly 40 and diverge either side: `220 − age` runs *high* for the
young and *low* for the old. (I got this direction backwards twice while
writing phase 1; the unit test caught it. It is stated correctly here.)

**Zone floors — Karvonen when there is a resting rate:**
`floor(z) = resting + share(z) × (max − resting)`. Percent-of-max when there
is not. Shares are the textbook `0 / .50 / .60 / .70 / .80 / .90`.

The difference is large enough to matter: for a 40-year-old resting at 55, zone
2 starts at **108** on percent-of-max and **130** by Karvonen.

**Max HR override.** `floors`, `band` and `vo2max` all take an optional
`maxOverride`, and `Settings.Person.maxHr` carries it per person. Tanaka is a
regression over a population with ±10-12 bpm of spread around it — most of a
zone — so anybody who has watched their own ceiling on a watch or in a test has
better information than their birthday gives. It matters more here than it
would on a percent-of-max console: every Karvonen boundary is a share of
`max − resting`, so a few beats on the maximum moves the whole ladder. For a
43-year-old resting at 60, Tanaka's 178 gives 119/131/143/154/166 and a
measured 175 gives 118/129/141/152/164.

Stored as 0 for "use the formula" rather than as a copy of the formula's
answer, so an override stays distinguishable from a coincidence and so the
zones keep following a birthday as the walker ages. That is the same mistake
the plain `age` field made.

`HrZones.MAX_RANGE = 120..220` decides what is believed; outside it the formula
is used, the same way an implausible resting rate is disbelieved. **An override
works with no age at all**, which looks like a hole in "no age means no zones"
and is not one: that rule exists so the console never invents a number, and a
measured ceiling is the opposite of invented.

**Zone 0** is everything below the bottom of zone 1 — warm-up, cool-down,
standing on the rail. Counted separately so it cannot inflate zone 1. On the
live graph it is a **grey field at the foot of the plot** rather than a sixth
hairline: it is the one zone with no boundary beneath it, so there is no line
to draw, and it reaches down as far as a heart does. The field is present
whether or not the walk went there — `zeroBand`, 10 bpm minimum — because a
band that only appears once somebody's pulse falls is a new stripe arriving at
exactly the moment nobody is looking for an explanation. `floors[0]` is where
the walker's range *starts* and is not a boundary: the HUD draws zone 0 as
"under 119 bpm" rather than "60–118", because a pulse of 45 is zone 0 too.

**`zoneOf()` returns −1 for "no reading", never 0.** This is the single most
important invariant in the file and there is a test named after it. Zero is a
real answer meaning "below zone 1"; a *pulse* of 0 means the sensor said
nothing — the board's grip field sits at zero for an entire walk on a machine
with no grips wired in. A caller that conflates them paints a disconnected
strap as resting, and in Phase 3 the targeting loop would read it as "you have
stopped working" and speed the belt up.

**Nothing here invents a number.** No age means no zones — not default ones —
and the summary falls back to average and peak, which need nobody's age. The
one exception is SKIP on the welcome prompt, which proceeds on
`HrZones.ASSUMED_AGE = 40` so a form can never stand between somebody and a
walk. It is carried as a **separate `assumed` flag**, not folded into the
number, so anything drawing a zone built on it can say so. Honour that.

**Palette** (grey, blue, green, yellow, orange, red — matches the owner's
reference image):

| Zone | Name | Colour | |
|---|---|---|---|
| 0 | Resting | `#7e8b95` | was `#3a4348` |
| 1 | Low intensity | `#5a9ae0` | was `#5aa9c8` |
| 2 | Weight control | `#5fc08a` | |
| 3 | Aerobic | `#e0c264` | |
| 4 | Anaerobic | `#e08a4a` | |
| 5 | Maximum | `#d75d5d` | |

**Zones 0 and 1 were retuned in phase 2**, after the live graph reached the
console and the owner compared it against their own watch. Both are about the
colour being *identifiable*, which is not the same requirement as pleasant:

* Zone 1 was hue **194°, a cyan**. Every consumer device draws low intensity at
  around 211°, and so does the owner's watch — sampled off their screenshot,
  its bars are `#59a3ee`, `#49ce1b`, `#ffed0d`, `#ff7013`, `#e21502`. On this
  console the old value had a second problem: `--accent` in `original.html` is
  `#39e0ff`, also cyan, so the one zone colour that had to read as a category
  read as a piece of the UI chrome instead.
* Zone 0 was a grey at 28% value, which on a `#050b1f` panel is not a grey but
  an absence — a warm-up drawn in it looked like a gap in the line rather than
  like time below zone 1. It is a mid grey now. That makes the least important
  zone more visible than it was, which is the trade, and it is the right way
  round: a colour nobody can see is not a quiet colour, it is a missing one.

**Zones 2–5 are deliberately left in the muted register they were drawn in.**
The reference's are fully saturated, which is right on a phone's pure black and
would be three glowing bars on a navy HUD. If the owner asks for the rest to
match, that is a one-line change in the same three places.

**VO2 max:** Uth–Sørensen–Overgaard–Pedersen, `15.3 × max / resting`. Needs
both numbers. An estimate built on an estimate — good for watching move over
months, worthless against somebody else's.

---

## Phase 1 — complete, deployed, treadmill-tested

Commits, newest first:

* `fbcb00a` The age prompt, laid out in sizes this console actually honours
* `a2be912` A resting rate you can unset without thirty-one presses
* `c332390` The zone names people already know, rather than the ones that describe effort
* `3a1ff1c` Zones a walker can actually be given: a birthday, a resting rate, Karvonen

1681 insertions, 109 deletions across 10 files.

### What was built

* **`HrZones.kt`** — Tanaka, Karvonen, floors, `zoneOf`, `band`, `ageOn` (strict
  ISO parse, rejects 31 February), `birthdayForAge` (migration), `vo2max`,
  names, palette.
* **`Settings.Person`** — `age: Int` became `birthday: String` (ISO) plus
  `restingHr: Int`; `age`, `maxPulse` and `zoneFloors` are now derived. An age
  typed once is right for a year and then wrong for ever, because nothing on a
  treadmill increments it.
* **Migration, on read, without a write.** Entries carrying the old `age` get a
  birthday synthesised at **1 July** of the year that makes them that age — a
  January date would age half the household by a year the moment it landed. The
  next save persists it and the fallback stops firing. `HrZonesTest` round-trips
  every age in 13..100 at three points in the calendar.
* **Bridge:** `setPersonBirthday`, `setPersonRhr`, `setGuestAge(age, assumed)`,
  `hrZones()`. `setPersonAge` is gone.
* **Caching:** `walkerHrMax` joined by `walkerZoneFloors`, `walkerAge`,
  `walkerRhr`, `guestAge`, `guestAgeAssumed`, all refreshed by
  `refreshWalkerZones()`. The poll loop builds a `Snapshot` five times a second
  and must not parse JSON out of SharedPreferences to do it.
* **Settings UI** — Who walks → per-person **Date of birth** (three steppers +
  CLEAR), **Resting heart rate** (stepper + CLEAR), and a live **Zones** row
  showing the real bpm bands in the zone colours.
* **Guest age prompt** — welcome step 4 in `original.html`. Stepper, six decade
  chips, SKIP. It sits in front of the *belt*, not the picker, so it is asked
  once whichever of the three routes through the welcome screen got there.
* **`MainActivity.zoneIndex` deleted**, its callers moved to `HrZones.zoneOf`.

### Deliberate scope choices

* The prompt fires for **anyone with no birthday**, not only guests, and on
  **every walk type** — not just quick-play as the brief said. The coloured BPM
  box, the live graph and the time-in-zone summary all want zones and they are
  on every walk; gating the ask on picking a zone workout would mean the casual
  walk that is most of the use never gets any of it.
* A guest's age is held **in memory for one walk only** and never written to
  Settings. Tapping GUEST is not agreeing to be remembered — the same reasoning
  that keeps a guest's walk off Home Assistant.

### Verified on the treadmill

Owner ran the full pass and reported everything good except one layout fault,
now fixed. Confirmed by screenshot and by reading the stored prefs:

* Migration correct and persisted — `age:42` → `birthday:"1984-07-01"` deriving
  back to 42; same for the second walker.
* Birthday picker fits the 302 px settings rail. Day clamps when the month
  changes under it (31 Jan → Feb gives 28).
* Tanaka reads 179 for a 42-year-old where `220 − age` read 178.
* Karvonen engages — "on a reserve of 119 bpm", moving Low intensity from
  90–106 to 120–130.
* Guest prompt reachable, chips highlight and drive the value box, **BACK
  returns without arming the belt** (confirmed in logcat).
* Named walker with a birthday is **not** prompted.
* One ordinary walk behaved exactly as before. Safety-key alarm still fires.

### Both walkers as currently configured

Useful as a fixture — these are the live numbers on the console.

```
Jeff    b. 1983-03-28  age 43  rhr 60  max 185 MEASURED  reserve 125  VO2 47.2
  Z0 Resting        under 123    Z3 Aerobic    148-159
  Z1 Low intensity    123-134    Z4 Anaerobic  160-172
  Z2 Weight control   135-147    Z5 Maximum    173-185

Lauren  b. 1984-04-11  age 42  rhr 60  max 179 Tanaka    reserve 119  VO2 45.6
  Z0 Resting        under 120    Z3 Aerobic    143-154
  Z1 Low intensity    120-130    Z4 Anaerobic  155-166
  Z2 Weight control   131-142    Z5 Maximum    167-179
```

Both were given a resting rate of 60 by the owner during phase 1 testing, so
**Karvonen is the live path** — percent-of-max is only exercised by a guest or
by clearing an RHR.

**Jeff's maximum is an override, not the formula.** 185 was set on the console
on 21 September 2026 while the new stepper was being tried; Tanaka gives 178
and their watch measures 175. Under Karvonen that moves every boundary — zone 1
starts at 123 rather than 119 or 118 — and phase 3 will drive a belt at
whichever number is in force, so it is worth confirming it is deliberate. See
the next steps at the top.

Zone 0 is written as "under 123" rather than "60-122" because that is what it
is: `floors[0]` is where the range starts, not a boundary, and a pulse of 45 is
zone 0 too.

### Faults found and fixed in this phase, worth learning from

1. **Deployed a stale APK.** Built, then changed the zone names, then committed
   — and never rebuilt. The console showed the old names and it looked like the
   change had failed. Always rebuild after the last edit.
2. **`gap` silently ignored.** Used flexbox `gap` in four places. Chromium 51
   does not have it, does not complain, and the layout came out spaced by
   `button{margin:0 8px}` instead. Now caught by `es`; verified the gate fails
   by reintroducing it.
3. **Caption outside its button.** The value box inherited `height:76px` from
   the global `button{}` rule and its two-line caption rendered below its own
   border, on top of the row beneath. Read as overlapping buttons.
4. **RHR unsettable without 31 taps.** Range floors at 30, first tap lands at
   60, and clearing meant stepping all the way down. Found by entering a test
   value on the console and having no way back.
5. **Tanaka direction backwards** in a doc comment and a test assertion. Caught
   by the unit test.

---

## Phase 2 — live zone state and the in-workout UI

**Built, deployed, and driven by hand with the belt cold. The treadmill gate is
still to walk.** Touches no belt command: the belt behaves exactly as it did at
`fbcb00a`.

Commit: `c55452b` The zone a walker is in, and the line that shows how they got
there — 979 insertions across 8 files.

### What was built

* **`Snapshot`** gained `zone`, `zoneFloors` and the band in force
  (`zoneFrom`/`zoneTo`). The zone is computed in Kotlin, where the floors are
  already cached, because Phase 3's loop reads the same answer and two sides
  each dividing a pulse by a maximum is how a belt ends up speeding up to
  reach a zone the summary says it was already in.
* **`HrTrace.kt`** — the walk's heart rate and pace, bucketed by time, merging
  every neighbouring pair and doubling the bucket when it runs out of room.
  360 points at 5 s covers half an hour; the first merge an hour; the walk
  never has to say in advance how long it will be. **Halved in place, not
  trimmed from the front** — a trace that forgot its first thirty minutes
  would draw an hour of effort as a flat half hour.
* **`STRIDE.hrGraph`** in `stride-core.js` — the shared canvas renderer. Splits
  a segment **at the boundary it crosses** and draws each piece in its own
  colour. Breaks the line over a bpm of 0. Returns a description of what it
  drew (range, pieces, gaps, and the bpm/second of every split), which is
  nothing the page wants and is how the headless suite sees a canvas that
  produces no pixels.
* **`original.html`** — the trace in the coach's band, and the live BPM box
  bordered in the zone's colour.
* **`tools/uitest/ui.js`** — 19 new checks, in all three modes.
* **`tools/hud_drive.py`** pushed `pulse: 0`, so every design screenshot it
  has ever taken was of the HUD in its no-reading state. It carries Jeff's
  real zones and a pulse of 148 now.

### Two departures from the plan above, and why

1. **The trace buffer is its own file, not inline in `MainActivity`.** The
   merge-and-double arithmetic is the kind of thing worth driving through
   scripted traces, and `HrTraceTest` does — two hours at 5 Hz, a ramp that
   must still read as a ramp after two halvings, and the dropout cases. The
   instance lives in `MainActivity` and is cleared in `resetSession()`, so the
   trace still belongs to one walk and is still in memory only.
2. **The page pulls the trace; it is not pushed on the frame.** `Stride.hrTrace()`
   returns `{"bucket":5,"samples":[[t,bpm,kph],…]}`. The Snapshot goes out five
   times a second and the trace gains a point every five seconds, so putting it
   on the frame would make the largest thing crossing the bridge the one thing
   that had not changed. The page asks once a second, keyed off the **session**
   clock rather than the wall clock, so a walk starting over redraws on its
   first frame.

### Where the graph actually went, and why it is not under the track

The plan said "below the track display". There is no band there. The hero owns
172–576 and the oval's bottom arc reaches y=550, so a strip laid under the
track the way the elevation strip is laid under the path would cross the ring.

The band below it — 588–668, the coach's — is empty most of the time and
already had two tenants, which is the argument for a third rather than against
it. The fan menu (z-index 45) lays over the trace and that is fine: five
buttons over a line, and the plan allows it.

**The coach is not fine, and this was found on the console.** Its background is
`rgba(10,21,51,.96)` — deliberately short of opaque so a ghost of the walk
shows through, which was written when the band was empty. With the trace behind
it, an uppercase `ZONE 4 · ANAEROBIC · 154–165 BPM` and a heart-rate line came
through the middle of the coach's sentence, and the caption read as a second
line of coaching. The trace hides itself while the coach is up and is back the
frame after it goes; `renderZones` checks the ribbon's class every frame rather
than hooking `window.coach`, so the dismiss tap, the timeout and `showScreen`
clearing it all put the trace back for free. There is a headless check for it.

### Faults found and fixed in this phase

1. **The coach ghosting through the trace**, above. Only visible on the
   machine, because it is a 4% alpha difference.
2. **Filled zone bands were a muddy gradient.** They are what went to the
   console first: five translucent fills over a 56px strip do not read as five
   zones, they read as one vertical smear, and they compete with the line that
   carries the same information legibly. The renderer draws a hairline at each
   floor instead, in the colour of the zone that starts there. `bands:true`
   still fills, for whatever draws this tall enough for the fills to separate —
   Phase 5's summary plot is the candidate.
3. **The head dot was clipped in half** at the right-hand edge — drawn centred
   on `x=W` inside a box with `overflow:hidden`, which reads as the trace
   running off the edge of its own container. Both ends are inset by the dot's
   radius now.
4. **The border on one metric box knocked the row out of line.** `.strip` is
   `align-items:center`, so each column centres against its own height and a
   border on the heart-rate box alone dropped that column's label four pixels
   below the other three. All four carry the border, transparent; only the
   heart rate ever gets a colour in it. It only looks crooked once the strap
   connects, which is the sort of thing that ships.
5. **A trailing-lambda test helper bound to the wrong parameter.** `walk(t,
   9.9) { 120 }` attached the lambda to `kph`, not `bpm`, because `kph` was
   last. Caught by the compiler, not by a wrong answer, but the same shape has
   produced silent wrong answers before: the varying argument goes last.

### Verified on the console, belt cold

Driven through DevTools with the real renderer parked (`window._real`), on the
NordicTrack at `192.168.10.10:5555`:

* Zone 3 and zone 4 frames — caption, band and border all correct and in the
  zone's colour.
* **Strap off** — caption reads "waiting for a reading", the border comes
  *off* the box rather than going grey or staying on the last zone, and the
  number reads `—`.
* **Zone 0** — names itself "Resting · 60–118 bpm" and keeps the zone-0 grey.
  Visibly a different state from no reading at all, which is the whole point.
* **Zone 5** — drawn open-ended, "166+ bpm", rather than as a ceiling
  somebody has broken.
* **Assumed age through the real bridge** — `Stride.setGuestAge(40, true)`,
  then `hrZones()` returned
  `{"floors":[0,90,108,126,144,162],"max":180,"assumed":true}` — percent-of-max
  for a walker with no resting rate — and the HUD showed "zones assumed · no
  age given".
* Warm-up: the phase panel takes the hero and the trace sits below it.
* A 25-minute synthetic trace with an 80-second dropout in it: 285 pieces, 16
  gaps, the colour changing along the line and the break clearly a break.
* 48 unit tests pass, 13 of them new. All four `ui-test.sh` gates pass.
* No errors or warnings in logcat across install, launch and restart.

### Verified on the treadmill

**Walked by the owner on 21 September 2026, who reported everything good.**
That closes the one link a cold console cannot exercise: `walkTrace.add` in
`accumulate()` only runs while the session is moving, so until the first real
walk the trace had only ever been driven from synthetic samples and Kotlin's
own buffer had never filled.

The owner also used the max-HR stepper on the console during the same session —
23 presses are in the log, walking Jeff's override up to 185 — which is the
control working under a real finger rather than under `input tap`.

### Retuned after the owner saw it, same session

Two changes, both from the owner looking at the deployed graph beside their
watch:

1. **Zone 0's grey and zone 1's blue**, above. Three places, as the palette
   always is: `HrZones.ZONE_COLOURS`, `ZONES` in `stride-core.js`, and the
   table above. `tools/uitest/zones.js` reads the Kotlin and holds the page to
   it, so two of the three cannot drift. The settings screen's Zones row picks
   the palette up from `core().ZONES` and needed no change.
2. **The plot is 92px, not 56px, and the legend moved beside it.** The owner
   called the zone boundaries tight and they were — seven pixels apart. Two
   things were wrong and both are fixed:
   * The legend was a 22px caption strip across the top of the box, costing the
     plot a quarter of its height for one line of text. It is a column to the
     left of the plot now: zone number, zone name in the zone's colour, the
     band in bpm, and the assumed-age note under it. Nothing is drawn over the
     line any more, so no label needs a halo and none can land on the red band.
   * The box grew from 588–668 to **582–676**, taking the gutters either side.
     It is taller than the coach's band, which is only allowed because the two
     are never on screen together. The limits are the hero and the elevation
     strip, which both end at 576, and the control buttons, which start at 684
     — there is a headless check on that arithmetic, and another that the
     canvas's pixel size matches its CSS box, because Chromium scales one to
     the other silently and a mismatch is a blurred line slightly out of
     register with the rules behind it.
   * **`hrGraph` no longer takes `max`.** The top of the vertical range was the
     formula maximum, which reserved room above the last rule for a boundary
     that does not exist — the top of zone 5 is open. It is zone 5's *floor*
     now, widened by the data as before, so a reading above the maximum is
     still drawn. The ladder went from 63% of the plot to 85%.

Net effect on the boundaries: ~7px apart to ~16px.

3. **The grey zone 0, and a max-HR override — after one wrong turn.** The owner
   said the resting band ran from 60 to over 100 and that they wanted it to
   stop around 87 with low intensity running 87 to 105. Those are their watch's
   percent-of-max numbers, so percent-of-max was built, deployed and shown.

   **That was a misreading and it was reverted.** What they wanted was
   Karvonen kept, a grey zone 0 covering everything below zone 1's floor, and a
   **max-HR override** so the ladder can be tuned to their own body. The lesson
   is the cheap one: a request phrased as two bpm numbers was read as a request
   for the model that produces those numbers, when it was a request about the
   drawing plus a control. Four questions up front changed the shape of this
   whole feature — see the working agreement — and one would have saved a
   revert here.

   `b19775c` and `b2223a1` were dropped with `git reset --hard 298af02` and are
   still in the reflog if anything in them is ever wanted. Two things were
   salvaged from them because they are independent of the zone model:

   * **The BPM box was reading a zone it derived itself.** It coloured its
     border from `STRIDE.zoneColour(pulse, floors)` while the legend beside it
     read the frame's `zone` — two answers to one question, from two copies of
     the same arithmetic. A test frame carrying a pulse of 148 with zone 3
     stamped on it painted the box orange under a caption reading Aerobic,
     which is how it was found. The box reads the frame now, and that
     inconsistent frame is kept as a check. Phase 3 steers the belt off the
     same field.
   * A test naming the fact that a pulse **under** `floors[0]` is still zone 0
     and not unknown, which is what the grey field depends on.

   What was then built: the grey zone-0 field (see **The model**), the override
   (ditto), and the HUD drawing both open-ended zones as open-ended — "under
   119 bpm" at the bottom and "166+ bpm" at the top.

   **Found on the console while testing the override:** three taps on − landed
   as one. Its `onChange` called `drawPeople()` as well as `drawZones()`, which
   rebuilds the pane and replaces the stepper's own buttons mid-press, so taps
   two and three hit DOM that had just been thrown away. The birthday picker
   does call `drawPeople` — an age is printed on the cards — but a maximum is
   not, and the resting rate beside it never did. Only reachable by pressing
   the same button three times quickly, which is exactly how somebody adjusts a
   number by three.

Verified on the console by sampling the framebuffer rather than by eye — the
line came back grey `#7e8b95`, then blue `#5a9ae0`, then green, yellow, orange
and red, in that order, with the palette values exact where the line is flat
enough not to be antialiased.

### Notes for Phase 5

The trace already carries `kph` on every point, so the pace overlay needs no
new plumbing — only a second series in `hrGraph`. `bands:true` is there for the
taller summary plot. `bucket` comes back with the samples, so anything that
wants to say what it is showing can.

---

## Phase 3 — the zone-targeting control loop

**Complete. Written, deployed and owner-walked on 23 September 2026 — all
eleven gate items passed — then retuned on what the walk said and walked
again the same day, reported good.**

### What was built

* **`ZoneControl.kt`** — the loop, as a pure class with no Android in it, so
  `ZoneControlTest.kt` can drive it through scripted heart-rate traces at the
  poll loop's real 5 Hz. It is handed a time, a pulse, the walker's ladder, the
  current setpoint and the board's limits, and answers with one speed or with
  nothing. It has no clock, no board and no preferences of its own.
* **`ZoneControlTest.kt`** — 25 tests in three groups: the holds, the ramp, the
  override handshake. The ones that matter are the holds.
* **Wired into the poll loop** as `driveZone()`, sitting next to `driveIncline()`
  and written to the same shape. ACTIVE only, never in the warm-up or the
  cool-down.
* **`Bridge.setZoneTarget(zone)` and `Bridge.zoneResume()`**, plus the override
  handshake folded into `Bridge.speed()` and `Bridge.setSpeed()`.
* **Three new `Snapshot` fields** — `zoneTarget`, `zoneAuto`, `zoneAtLimit` —
  and their JSON.
* **The ZONE ribbon and the state badge in `original.html`**, plus
  `STRIDE.zoneAutoNote()` in `stride-core.js` so the Phase 6 ports inherit the
  wording rather than re-inventing it.
* **The coach silenced on pace nudges** while the belt is steering.
* **`SAFETY.md`'s pending paragraph rewritten.** It now describes a loop that
  exists, with the actual numbers in it. That was the one sentence in the tree
  the phase-2 document rewrite deliberately left false-on-arrival.

### The behaviour, and why each piece is the way it is

| | |
|---|---|
| Step | **0.2 km/h per zone of distance, capped at three** — 0.6 three or more zones out, 0.4 at two, 0.2 for the last one. Was a flat 0.2; see "Retuned after the walk". |
| Dwell | **20 s**, the middle of the 15–30 s they specified. |
| Reading acted on | **A 10 s rolling mean**, not the latest frame. "A settled average rather than a transient" was the instruction, and a single arm-swing frame at 200 bpm must not move a treadmill. |
| Disconnect | `HrZones.zoneOf` returning −1, **read from the instantaneous pulse** and not from the mean — a strap that came off two seconds ago has to stop the loop now, not when the window finally empties ten seconds later. |
| Direction | **Symmetric.** Distance sets the size the same way up and down. The original asymmetry — one step up however far below, up to two down — was an unasked-for safety-side default, was flagged here for reversal, and the walk reversed it. |
| Stopped belt | Setpoint below `minKph` means there is no walk to steer. The loop **never starts a belt and never stops one.** |
| Pause, warm-up, cool-down, safety key | Not steerable, and each such frame **re-arms the dwell** — so RESUME on a paused walk gets a full twenty seconds before anything moves. Without it the dwell expires while the belt stands still and the first frame back acts on a mean gathered from somebody standing on the side rail: genuinely below the target zone, and genuinely nothing to do with the pace they were walking at. |
| Board limits | Clamped in `ZoneControl` *and* again through `paceKph()`, which is the pre-existing machine-limits rule and is not the invariant this feature removed. |
| Worst case | A pulse that never answers leaves the loop permanently far from the zone and therefore permanently at its coarsest: **up to 1.8 km/h a minute, ~6 km/h over five unanswered minutes.** Bounded, visible on the gauge, ended by one press of SPEED. Quoted in `SAFETY.md` and pinned by a test — change it in all three places. |
| Off by default | `zoneTarget = 0` on every walk, never persisted, cleared in `resetSession()`. |

**It does not aim for the middle of the zone.** It stops the moment the zone is
right, so it settles near whichever boundary it arrived from and will drift
back and forth across it over a long walk, bounded by one step per dwell. The
alternative — steering to a bpm inside the band — is false precision with a
motor attached: the band is a band because the underlying formula is a ±10-12
bpm smear. **This was expected to be the thing the walk argued with. It was
not** — the walk reported no hunting and no overshoot. What it argued with was
the opposite, and the fix is below.

### What the walker sees

* **A badge under the speed gauge** — `AUTO Z3`, `MANUAL`, `HOLDING`,
  `AT LIMIT` — in the *target* zone's colour, which is the only element on the
  HUD whose colour means "where this is taking you" rather than "where you
  are". Absolutely positioned out of the gauge's flow so it cannot push the
  tick column into the trace band at 582; the UI suite asserts the geometry
  (it lands at 538–572) because Chromium 51 would not have complained.
* **A ZONE ribbon** in the control row, the fan menu's twin: OFF, Z1–Z5 and
  RESUME. Ten seconds rather than the fan's five, because it is a decision
  somebody reads the options for. Chips lit in their own zone's colour. Each
  states `height:52px` explicitly — `button{}` sets 76px on every button in the
  document, which was the one visible bug of phase 1, and there is now a test
  for it rather than a comment.
* **A legend line** in the heart-rate band saying what the belt is doing. It
  outranks "zones assumed", which is still shown on every walk with no target.
* **Nothing at all** for a walker with no zone ladder — no badge, no ZONE
  button. `Bridge.setZoneTarget` refuses the call too, so the page is not the only
  lock on that door.

### Verified in the container, belt cold

* 25 `ZoneControlTest` cases, plus the existing `HrZonesTest` (22) and
  `HrTraceTest` (13), green.
* 18 new checks in `tools/uitest/ui.js` covering the badge, the ribbon
  geometry, the chip heights, RESUME's muting and the wording, green.
* `tools/ui-test.sh all` — `es`, `zones`, `engine`, `ui` — all four green.
* `./stride-build.sh debug` builds, 3.0M.

**Two tests were written wrong first and the fix was in the code, not the
test.** They asserted one adjustment in the first 30 s and got two, because a
freshly constructed `ZoneControl` acted as soon as it had ten samples: the
"first move is a dwell away" promise held only if the caller remembered to call
`reset()`. That is far too load-bearing to leave in the calling code, so the
dwell is now armed by the *first reading* — see `ZoneControl.armed`. Worth
recording because the failing test was right and the instinct to relax it would
have shipped a belt that could move two seconds after the loop was switched on.

### Verified on the treadmill, 23 September 2026

Walked by the owner against the eleven-item plan, on the `ed79169` build,
Jeff's profile, maximum 185 (ladder 123/135/148/160/173), target zone 2.
**All eleven passed.** Recorded individually because the value of this list is
that somebody can see which of them has actually been stood on:

1. Off is off — an untargeted walk behaved exactly as it does on `main`. ✓
2. Switching on: chip lit, badge `AUTO Z2`, nothing moved for the first 20 s. ✓
3. The ramp stepped about 0.1 mph at a time, roughly every 20 s. ✓
4. It settled and stopped when the zone was reached. **No overshoot reported**,
   which is the notable one — see below. ✓
5. Override was immediate on the press; the loop did not come back by itself. ✓
6. RESUME was not felt through the belt. ✓
7. **Strap removed mid-walk: the belt held, badge `HOLDING`.** ✓
8. Pause and resume: nothing moved for 20 s afterwards. ✓
9. Safety key during an auto-adjusting walk. ✓
10. The coach said nothing about pace while steering, and the nudges came back
    after the override. ✓
11. COOL DOWN: the loop let go. ✓

### Retuned after the walk, same day

**The owner's report: everything passed, but the ramp felt slow in both
directions.** That is the reverse of what this section had predicted. The
overshoot warning written above turned out to be wrong in an instructive way —
the dwell is 20 s and a heart lags 30-60 s, so the loop *should* have been able
to stack steps and sail past the band, and it did not. The reason is that it
could only ever stack 0.2 km/h at a time: the flat step was slow enough that
the heart kept up with it, and the price of that was four minutes of creeping
to climb from zone 0 to zone 2. A console that takes four minutes to visibly
do anything does not read as gentle, it reads as broken.

**The fix is proportional, not simply larger, and the shape is the point.**
The step is now one 0.2 km/h increment *per zone of distance*, capped at three:

| Distance from the target zone | Step |
|---|---|
| 3 zones or more | 0.6 km/h |
| 2 zones | 0.4 km/h |
| 1 zone — the approach that lands you in the band | **0.2 km/h, unchanged** |
| in the zone | nothing |

Making every step bigger was the obvious alternative and it was rejected: it
buys the same speed by spending it on the final approach, which is exactly
where a large step is what throws you past the band before your pulse has
answered. Coarse far away and fine close in gets the walker there quickly and
then stops hard, which is what the dwell was protecting in the first place.
Offered as one of four options and chosen by the owner over a shorter dwell
(15 s) and a flat larger step (0.3).

This also **removed the up/down asymmetry**. One step up however far below, up
to two down, was a safety-side default nobody had asked for; it was recorded
here as reversible for exactly this reason, and the walk reversed it. Distance
now sets the size identically in both directions.

**What this costs, and it is written into `SAFETY.md` rather than left
implicit.** A pulse that never answers — a strap on somebody else, a reading
stuck low — keeps the loop permanently far from the zone and therefore
permanently at its coarsest: up to 1.8 km/h a minute, about 6 km/h over five
unanswered minutes, against 3 km/h under the flat step. That is the ceiling on
how fast this can run away from a walker. It is bounded, it is on the gauge in
front of them, one press of SPEED ends it, and the safety key still stops the
machine. The figure is pinned by `a five minute climb with no answer stays
bounded` in `ZoneControlTest` and quoted in `SAFETY.md`; if it ever moves,
move it in all three places.

**Walked and confirmed, 23 September 2026, same day.** The owner re-walked the
proportional ramp and reported it works well. So the shape is right: coarse
far from the band, tapering to the original 0.2 on the last zone, reaching the
zone in about two and a half minutes instead of four, and still stopping
cleanly rather than sailing past. **Phase 3 is closed.**

Two things are therefore settled by a person rather than by a test, and both
are worth knowing before anybody retunes this again. The dwell of 20 s is
right — it survived both walks untouched and neither produced hunting or
overshoot. And the *taper* is the load-bearing part, not the step size: the
flat 0.2 and the proportional version have the same step on the final
approach, and the only thing that changed is how fast the walker gets there.
If a future walk complains about the ramp again, the first thing to reach for
is the cap in [MAX_STEPS], not [STEP_KPH] and not [DWELL_MS].

### The treadmill gate, kept for phase 4

Phase 4 puts this same loop behind zone presets and behind custom and route
runs, so it will want walking again. This is the list, and it is the one both
phase 3 walks were run against.

1. **The ramp is gentle** far from the zone and **gentler still** approaching it.
2. **The dwell is real** — roughly twenty seconds between adjustments.
3. **Override is immediate**; the loop does not come back on its own.
4. **RESUME works**, and pressing it is not felt through the belt.
5. **Pull the strap mid-walk.** The belt must hold.
6. **Pull the safety key during an auto-adjusting walk.**
7. Check `logcat -s Stride:I | grep zone:` — every adjustment logs its reason
   and the settled bpm it acted on.

---

## Phase 4 — zone-targeted presets, and zone targeting for custom and route runs

* **`Plan.kt`:** new templates targeting a zone rather than a grade — e.g.
  "Zone 2 Aerobic Base", "Zone 5 HIIT Interval". A `Segment` currently carries
  `incline` and a `paceDelta`; a zone-targeted segment needs a target zone
  instead, so either extend `Segment` with a nullable `targetZone` or add a
  parallel shape. Templates may drive **incline and/or speed** to hold the zone.
* Presets appear in the guided picker. `SHAPES` in `stride-core.js` mirrors
  `Plan.TEMPLATES` for the sparklines — a zone preset has no grade profile to
  draw, so it needs a different card treatment.
* **Zone-targeting toggle on Custom and My Route runs.** Walker picks a target
  zone; speed is steered to hold it. Same `ZoneControl` loop, same override and
  resume.

**Treadmill gate.** At least one preset and one zone-targeted route, walked.

---

## Phase 5 — post-workout analytics

All on the summary screen.

* **Time-in-zone breakdown.** `zoneRows()` in `stride-core.js` already turns
  `zoneSecs` into drawable rows and drops zone 0 and empty zones. Currently
  rendered by **`cluster.html` only**. Render it in `original.html` as a stacked
  bar or itemised list with exact **minutes and seconds**, zone-coloured.
* **Estimated VO2 max.** `HrZones.vo2max` / `STRIDE.vo2max`. Show it only when
  the walker gave a resting rate **and the walk reached real exertion** — the
  gate is the caller's job, deliberately not in `HrZones`. A ratio of two
  maxima says nothing about a stroll, and printing a fitness score after one
  invites the reading that the stroll earned it. Label it as an estimate.
* **HR line graph with pace overlay.** Two series, one time axis, from the
  Phase 2 trace buffer. HR coloured by zone, pace as a second line — the
  owner's reference image has speed in cyan against a zone-coloured HR line.
* Fall back to average and peak when there are no zones. That path already
  exists; keep it working.

**Treadmill gate:** walk, then check the numbers add up — time in zones should
sum to about the walk length minus the unread gaps.

---

## Phase 6 — port to the other four interfaces

`cluster.html`, `ember.html`, `pacer.html`, `daylight.html`. The five share no
palette or variable names by design — Daylight is a *light* theme — so each
port is a real piece of work even with the drawing code shared. Anything
generic belongs in `stride-core.js`, not copied five times.

Note the other four use `STRIDE.flow()` for the welcome sequence, which already
has the `age` step, `needsAge()`, `setAge()` and `skipAge()` wired in.
`original.html` has its own step machine (`showStep`, `STEPS`, `armWalk`) and
does **not** use `flow()`. The Phase 1 age prompt was implemented twice for
this reason — once in `flow()` for the four, once in `original.html`.

While in `cluster.html`, fix its three grandfathered `gap` declarations and
drop the `CSS_KNOWN` entry in `tools/uitest/es.js`.

**Treadmill gate:** spot-check each interface.

---

## Working agreement the owner set

* Work in **functional logical phases**, and **test on the treadmill before
  proceeding** to the next.
* **Clarify ambiguity before building.** They meant it; asking four questions
  up front changed the whole shape of this work.
* Commit messages carry the reasoning — look at the log for the shape. The
  house style is long, prose, and explains *why*, including what was tried and
  rejected. **Say what you tested on**, every time.
* Comments explain why, not what. The valuable facts in this codebase are all
  of the form "the board reports 0.00 at every speed" and are invisible from
  the code.
* Attribution line on commits:
  `Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>`

---

## Open questions for the owner

Not blocking, but worth asking when they come up.

1. **Does Tanaka match their own device?** *Answered, and the answer is a
   control rather than a formula change.* Their Galaxy Watch7 measures a max of
   **175** from real workout data; Tanaka gives **178** at 43. Phase 2 added a
   per-person override for exactly this — Settings → Who walks → Maximum heart
   rate — so the walker can put their own number in and the Karvonen ladder
   follows it.

   **Their watch's bands are percent-of-max and this console's are Karvonen,
   and that difference is settled and deliberate.** The watch draws Z1 87–105
   on a maximum of 175; Karvonen on a reserve of 115 draws Z1 118–128. The two
   will not agree at any maximum, because they are not the same model. The
   owner was asked, in effect, on 21 September 2026 — a percent-of-max console
   was built, shown, and rejected — and they want the reserve. Nothing is open
   here any more; it is recorded so it is not reopened by whoever notices the
   console and the watch disagreeing.

   The override is still worth setting before Phase 3 drives a belt, since it
   moves all five boundaries.

   **Settled on 23 September 2026, and then set.** Asked which of 185 (what
   the console held), 178 (Tanaka) or 175 (the watch) the loop should drive
   against, the owner declined to name a figure and gave the rule instead:
   *always use the max HR defined for the walker in Settings, whether that is
   the calculated one or an override they set.* So there is no number in the
   code and there is not going to be one — `ZoneControl` is handed a ladder
   and follows it. **Jeff then set their own maximum to 183** around the phase
   3 walk, giving a ladder of 122/134/146/158/171. Nothing is open here.
2. **What does the coach say while the loop is driving?** *Answered on 23
   September 2026: nothing about pace.* `zone_low` and `zone_high` both end in
   a suggested pace, and both are answers to a question the loop is already
   answering with the motor — "a touch more pace if you have it", said while
   the console is itself adding a touch more pace, is at best redundant and at
   worst an instruction to fight it. They are suppressed while `zoneAuto` is
   true and return the moment the walker takes the belt by hand. Narrating
   each adjustment instead was offered and declined. Everything else the coach
   says — milestones, check-ins, `hr_climb`, the closing line — is untouched,
   because none of it asks anybody to change pace.
3. **Should the HR trace ever be persisted?** They chose in-memory-only for
   now. Phase 5 may make a trend view tempting; it would need downsampling and
   a size budget against `History.KEEP = 750`.
