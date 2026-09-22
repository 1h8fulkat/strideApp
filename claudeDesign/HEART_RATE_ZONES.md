# Dynamic heart-rate zone tracking — implementation state and plan

**Branch:** `heart-rate-zones` (off `main` at `38ae7f9`)
**Started:** 21 September 2026
**Phase 1 of 6 complete, deployed, and tested on the treadmill.**
**Phase 2 built, deployed, and driven by hand with the belt cold. Its
treadmill gate — walk with the strap on — has not been done yet.**

This file is the handoff. It exists because the work spans more sessions than
one context window holds, and because the decisions behind it are worth more
than the diff. Read it before touching anything.

---

## The one thing to read first

This feature **removes a documented safety invariant**, deliberately and on the
owner's explicit instruction.

`Plan.kt`, `README.md`, `SAFETY.md` and `CONTRIBUTING.md` all state some form
of:

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

What must still happen, in Phase 3, is that the docs stop claiming something
the code no longer does. `Plan.kt`'s header, the README bullet and `SAFETY.md`
all have to be rewritten to describe the console as it will actually behave.
Leaving a safety document describing a guarantee that has been removed is worse
than never having written it.

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
| Installed | `app-debug.apk`, 3.0M, built from `7a24752` |
| Installed at | 2026-09-21 22:03 |
| Signer SHA-256 | `f274956772b6606c3fca0264dd84f870df2addecbe4e56d13925b5447997d677` |
| Board limits | speed 0.8–19.31 km/h, incline −3.0 to +15.0 % |
| Units | miles (`units: mi`) |
| Monitor | "Galaxy Watch7 (64HA)", `hr_source: auto` |
| Errors in log | none |

**Data backup before the migration:**
`/data/docker/testing/.stride-build/stride-data-20260921-204913.tar` (1.5 MB)
— also copied to `stride-data-latest.tar`. It holds `shared_prefs` and `files`
(history, routes, tiles) as they were under the pre-birthday schema. Keep it
until the feature is merged.

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
   `engine`, and `ui` in three modes. All four gates pass at `7a24752`.

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
Jeff    b. 1983-03-28  age 43  rhr 60  max 178  reserve 118  VO2 45.4
  Z1 Low intensity   119-130     Z4 Anaerobic  154-165
  Z2 Weight control  131-142     Z5 Maximum    166-178
  Z3 Aerobic         143-153

Lauren  b. 1984-04-11  age 42  rhr 60  max 179  reserve 119  VO2 45.6
  Z1 Low intensity   120-130     Z4 Anaerobic  155-166
  Z2 Weight control  131-142     Z5 Maximum    167-179
  Z3 Aerobic         143-154
```

Note both were given a resting rate of 60 by the owner during testing, so
**Karvonen is the live path** — percent-of-max is now only exercised by a guest
or by clearing an RHR.

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

### Still to do — the treadmill gate

**Walk with the strap on and watch the line change colour at the boundaries.**
Cross-check the live zone against the bands in the table above. The belt must
behave exactly as it does today.

The one link that a cold console cannot exercise is `walkTrace.add` in
`accumulate()`, which only runs while the session is moving — so the trace has
been driven from synthetic samples, and the first real walk is the first time
Kotlin's own buffer fills. Worth a specific look at: the line appearing within
the first five or ten seconds, the axis filling in rather than stretching over
the first five minutes, and the trace surviving a pause and resume.

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

**The dangerous one.** Do not merge it without a full walk.

* **New `ZoneControl.kt`.** Own file. Given a target zone, the current pulse,
  the current setpoint and the board's limits, it returns the next commanded
  speed — or "no change". Pure and unit-testable; keep the Android out of it so
  `ZoneControlTest.kt` can drive it through scripted heart-rate traces.
* **Behaviour the owner specified:**
  * Adjust speed gradually. **Small bounded step**, then **wait 15–30 s** for HR
    to respond before the next adjustment, so the reading being acted on is a
    settled average rather than a transient.
  * **A pulse of 0 is a disconnect.** Do not adjust. Hold the current speed
    until a valid signal returns. `HrZones.zoneOf` returns −1 for this — use it,
    do not test `bpm == 0` in a second place.
  * **Override:** a manual speed press takes the belt back for the rest of the
    walk. Model it on `inclineAuto` in `MainActivity`, which already does
    exactly this for the deck.
  * **Resume:** an explicit control to hand the belt back to the loop.
* Wire into the poll loop. New bridge methods for on/off, override, resume.
  Controls and a clear state indicator in `original.html` — the walker must be
  able to tell at a glance whether the belt is steering itself.
* **Rewrite the docs.** `Plan.kt`'s header, the README bullet, `SAFETY.md`.
  They currently promise speed is only ever suggested. After this they must
  describe what the console actually does, and say plainly that the belt can
  now change speed on its own.
* Consider what the coach says. `Coach.kt` nudges pace verbally on zone drift;
  with the loop running, that advice is redundant or contradictory.

**Treadmill gate: mandatory, and walk the whole thing.** Confirm the ramp is
gentle, the dwell is real, override takes effect immediately, resume works, and
**removing the strap mid-walk holds the belt steady rather than accelerating.**
Test the safety key during an auto-adjusting walk.

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
2. **What does the coach say while the loop is driving?** Phase 3 question.
3. **Should the HR trace ever be persisted?** They chose in-memory-only for
   now. Phase 5 may make a trend view tempting; it would need downsampling and
   a size budget against `History.KEEP = 750`.
