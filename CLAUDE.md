# STRIDE — notes for Claude

## Active work

**Branch `heart-rate-zones`** — dynamic heart-rate zone tracking, 6 phases.
Phases 1 and 2 are complete, deployed and owner-walked. **Phase 3 — the
zone-targeting control loop — is written and every gate passes, but it has
not been deployed or walked.** That walk is the next thing that happens, and
phase 4 does not start before it. **Read
[claudeDesign/HEART_RATE_ZONES.md](claudeDesign/HEART_RATE_ZONES.md) before
touching anything on this branch.** It holds the full plan, the decisions the
owner has already made, the deployment state, and the faults found so far.

**The branch now commands belt speed** — `ZoneControl.kt`, driven from
`driveZone()` in the poll loop. It is off unless switched on: no target zone
is set on any walk and none is ever persisted, so a walk where nobody touches
the ZONE control behaves exactly as it does on `main`.

The short version of the one thing that matters: this feature **deliberately
removes** the project's "incline is driven, speed is suggested" safety
invariant. The owner was asked directly and chose auto speed control,
unconditional. That decision is made — don't re-litigate it or quietly build a
weaker version.

**The safety documents have already been rewritten to drop it** — `SAFETY.md`,
`README.md`, `CONTRIBUTING.md`, `Plan.kt`'s header and the interface comments
that asserted it. Done ahead of the code on purpose, because a safety document
granting a guarantee it is about to withdraw is worse than one that never gave
it. If you find a comment still claiming speed is only suggested, it is a
leftover: fix it, don't code to it.

## This machine

The repo's `tools/docker-build.sh` and `console/stride/run.sh` assume macOS.
On this Debian box use the wrappers one directory up:

```sh
cd /data/docker/testing
./stride-build.sh debug     # build the APK
./stride-deploy.sh          # install + launch, then tails logcat forever
```

`stride-deploy.sh` never returns — run it under `timeout`, or do the adb steps
by hand. Console is at `192.168.10.10:5555`. Unit tests need Gradle in the
build container; there is no `test` variant in the wrapper. Both commands are
written out in the plan document.

## Before you deploy anything

```sh
tools/ui-test.sh all
```

`es` (language + CSS ceiling), `zones` (Kotlin/JS formula parity), `engine`
(Chromium 51 quirks), `ui` (original.html driven headlessly). It is the pass to
run before deploying, not instead of walking on the thing afterwards.

## The console is older than you think

Android 7.0, **Chromium 51** WebView, no Play Store to update it.

- **ES2015 ceiling.** No `?.`, `??`, object spread, `async`/`await`,
  `padStart`, `Object.entries`, `**`, `Array.flat`, `.at()`.
- **CSS ceiling, and this is the one that bites.** Unsupported CSS is silently
  ignored rather than failing, so a layout built on it looks right on your
  desktop and almost right on the treadmill. No flexbox `gap`,
  `position:sticky`, `:is()`, `aspect-ratio`, `clamp()`, `inset`,
  `backdrop-filter`.
- **`button{}` in `original.html` sets `height:76px` on every button.**
  Anything taller must state its own height or its content renders outside its
  own border.

Both ceilings are enforced by `tools/ui-test.sh es`. Add to it rather than
working around it.

## Anything that moves the belt

Read [SAFETY.md](SAFETY.md) first. All the judgement about *when* the belt
moves on its own lives in `ZoneControl.kt`, which is pure and has 25 tests —
put changes there rather than in the poll loop, and add the scripted trace
that shows the new behaviour. The physical safety key is the stop of
record and nothing in software gets to be the last line. Every commanded speed
is clamped to the board's own reported range in Kotlin — that rule is about
machine limits and is not the invariant the zone feature removes.

## Style

Commit messages carry the reasoning, in prose, including what was tried and
rejected — look at the log for the shape. **Say what you tested on, every
time.** Comments explain why, not what: the valuable facts here are all of the
form "the board reports 0.00 at every speed" and are invisible from the code.
