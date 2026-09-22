# Dynamic heart-rate zone tracking — implementation state and plan

**Branch:** `heart-rate-zones` (off `main` at `38ae7f9`)
**Started:** 21 September 2026
**Phase 1 of 6 complete, deployed, and tested on the treadmill.**

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
| Installed | `app-debug.apk`, 3.0M, built from `fbcb00a` |
| Installed at | 2026-09-21 21:03 |
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
   `engine`, and `ui` in three modes. All four gates pass at `fbcb00a`.

---

## Decisions already taken

Asked and answered by the owner. Treat as settled.

| Question | Answer |
|---|---|
| How should zone targeting drive the machine? | **Auto speed, unconditional.** See the warning at the top. |
| How wide should the UI work go? | **`original.html` first, treadmill-test it, then port to the other four** as a separate phase. Shared drawing code goes in `stride-core.js` so the ports stay thin. |
| Where does the post-workout HR trace live? | **In memory, summary only.** Kotlin buffers HR+pace for the current walk and hands it to the summary. Lost when the walk ends. Not persisted to `History`. |
| Karvonen/Tanaka vs the existing `220 − age`? | **Replace globally.** One code path. Coach and History use the new numbers. |
| Zone names? | **The reference screenshot's set** — Low intensity, Weight control, Aerobic, Anaerobic, Maximum. The owner was offered the plainer effort labels and chose these. The trade-off is recorded in `HrZones.ZONE_NAMES`; don't undo it. |

There is **no `Directory structure.txt`** in the repo or workspace, despite the
original brief referring to one. The tree was mapped by hand.

---

## Where things live

```
console/stride/app/src/main/
  java/dev/stride/hud/
    HrZones.kt        NEW — all zone arithmetic. Single source of truth.
    Settings.kt       Person.birthday + restingHr; derived age/maxPulse/zoneFloors
    MainActivity.kt   3961 lines. Poll loop, Bridge (@JavascriptInterface), zoneSecs
    Session.kt        Snapshot — the one frame sent to the page and to HA
    Coach.kt          Spoken coaching. Has its own softer ZONE_WORDS register.
    History.kt        Walk records on disk. Aggregates only, no time series.
    Plan.kt           Guided-walk templates. Phase 4 adds zone presets here.
    HeartRate.kt      BLE strap, standard 0x180D/0x2A37
  assets/ui/
    stride-core.js    2064+ lines. Shared helpers. ZONES + the page's zone math.
    stride-settings.js  The settings screen, shared by all five interfaces.
    original.html     The default interface, 2284+ lines. Build here first.
    cluster.html / ember.html / pacer.html / daylight.html   Port targets.
console/stride/app/src/test/java/dev/stride/hud/HrZonesTest.kt   NEW — 35 tests
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

**Zone 0** is everything below the bottom of zone 1 — warm-up, cool-down,
standing on the rail. Counted separately so it cannot inflate zone 1.

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

| Zone | Name | Colour |
|---|---|---|
| 0 | Resting | `#3a4348` |
| 1 | Low intensity | `#5aa9c8` |
| 2 | Weight control | `#5fc08a` |
| 3 | Aerobic | `#e0c264` |
| 4 | Anaerobic | `#e08a4a` |
| 5 | Maximum | `#d75d5d` |

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

**Not started.** Touches no belt command; the gate is visual.

* **Kotlin.** Add to `Snapshot` in `Session.kt`: current `zone` (−1 when there
  is no reading), the walker's `zoneFloors`, and the active zone's bpm band.
  `hrMax` and `zoneSecs` are already there. Extend `toJson()` and mirror the
  fields in `adapt()` in `stride-core.js`.
* **HR trace buffer.** A ring buffer in `MainActivity` of (elapsed, bpm, kph)
  samples for the current walk. **In memory, summary only** — the owner chose
  this. Downsample as you go; a 60-minute walk at 5 Hz is 18 000 samples and
  the graph needs a few hundred. Cleared in `resetSession()`.
* **Shared graph renderer in `stride-core.js`.** Canvas, not SVG — `profile()`
  and `sparkline()` in this project already draw to canvas, and the four ports
  in Phase 6 should be thin. The line colour must change **at the crossing
  point**, so a segment that spans a boundary is split, not painted with its
  endpoint's colour. Note the jsdom canvas stub in `tools/uitest/ui.js` —
  the renderer must survive a context that returns nothing useful.
* **`original.html`:** the graph goes **below the track display**. The fan
  panel is allowed to overlay it when open. The live BPM box in `.strip` gets
  a zone-coloured border or background, driven by `STRIDE.zoneColour(bpm,
  floors)` — which returns `''` for no reading, and that must mean "leave it
  alone", not "paint it grey".
* **Label assumed zones.** If `hrZones().assumed` is true the walker never gave
  an age. Say so somewhere on the HUD.

**Treadmill gate:** walk with the strap on and watch the line change colour at
the boundaries. Cross-check the live zone against the bands in the table above.
The belt must behave exactly as it does today.

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

1. **Does Tanaka match their own device?** Their Galaxy Watch7 reports a max of
   **175**; Tanaka gives **178** at 43. Their watch's zone bands (from the
   screenshot they supplied: Z5 158–175, Z4 141–157, Z3 123–140, Z2 106–122,
   Z1 87–105) are **percent-of-max**, not Karvonen, so the console will now
   read materially higher than their watch at every boundary. Worth confirming
   that is what they want before Phase 3 drives a belt at those numbers. A
   manual max-HR override was offered in Phase 1 and they chose "replace
   globally" without it — it may be worth re-offering once they have compared
   the two on a real walk.
2. **What does the coach say while the loop is driving?** Phase 3 question.
3. **Should the HR trace ever be persisted?** They chose in-memory-only for
   now. Phase 5 may make a trend view tempting; it would need downsampling and
   a size budget against `History.KEEP = 750`.
