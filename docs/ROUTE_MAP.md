# The route map

A walk imported from a GPX file can be drawn two ways while you walk it:

* **Path ahead** — the perspective path this console has always drawn. The
  ground comes towards you, hills rise before you reach them, and it needs no
  network at all.
* **Map** — the route itself on OpenStreetMap, with a dot for where you are on
  it.

Either way, **the ground you are walking runs along the bottom of the screen**
with a line showing how far through you are, and the altitude beside it.

`Settings → My routes → While you walk` chooses. It applies only to routes,
because only a route has coordinates: a guided template is a shape in time and
there is nothing for a map to show, so it always gets the path.

---

## There is still no GPS in the treadmill

Worth being clear about, because a moving dot on a map implies otherwise.

The console knows one thing about where you are: **how many metres of belt have
gone past.** Every point in an imported route carries the distance at which it
is reached, so placing the dot is a lookup, not a measurement. Walk slower and
the dot moves slower. Stop and it stops. It is exactly as accurate as the
odometer and not one bit more.

The same number places the line on the elevation strip, which is why the two
can never disagree with each other.

---

## What has to be true for the map to draw

1. **The route was imported with `stride_gpx.py` from this version or later.**
   Older routes carry only the gradient profile — the converter used to discard
   the coordinates — so they walk normally and fall back to the path view.
   Re-run `python3 stride_gpx.py sync` to pick up the geometry.
2. **The console can reach the internet.** Only for tiles. Everything else —
   the route, its elevation, the incline the deck drives — is already on the
   console and works with the network down.
3. **Its clock and trust store can agree with a tile server.** Both were
   measured on this machine — see below.

Nothing here can stop a walk. If the tiles do not arrive the route still draws
on blank ground, the dot still moves, and the strip is unaffected.

---

## What this console actually is

Measured, not assumed. Worth re-checking on any other machine, because two of
these differ from the console upstream was written on.

| | |
|---|---|
| Android | 7.0, API 24 (`argon_20180915`) |
| WebView | `com.android.webview` **51.0.2704.91** — AOSP, no Play Store to update it |
| Clock | correct, and `auto_time` is already `1` |
| Trust store | 148 system CAs, **no ISRG root** |

```
adb shell getprop ro.build.version.release
adb shell dumpsys package com.android.webview | grep versionName
adb shell date ; adb shell settings get global auto_time
```

**Chromium 51 is the real ceiling, not the 83 the comments used to claim.** So
the interface may use ES2015 and nothing above it: no `?.`, no `??`, no object
spread, no `async`/`await`, no `Object.entries`, no `**`, no `String.padStart`,
no CSS `:has()` / `inset` / flexbox `gap`. Array spread of an iterable is fine.
Leaflet 1.9.4 is clean at that level — its UMD wrapper reaches for `globalThis`
but only behind a `typeof` guard, so on 51 it falls back to `this`. Verified by
loading the whole interface with `globalThis` deleted.

---

## Certificates: what is actually needed here

The clock on this console is **right**, so the "not yet valid" failure the rest
of this app works around does not arise for tiles. What does is the trust
store: it has no ISRG root, and Let's Encrypt is most of the web.

Each provider's real chain, validated against the 148 certificates pulled off
this machine — `adb pull /system/etc/security/cacerts` — with the host's own
store explicitly disabled (`openssl verify -no-CApath -no-CAstore`):

| provider | CA | as shipped | + bundled X1 |
|---|---|---|---|
| `tile.openstreetmap.org` | GlobalSign | **OK** | OK |
| `tile.opentopomap.org` | Let's Encrypt | fails | **OK** |
| `a.tile-cyclosm.openstreetmap.fr` | Let's Encrypt | fails | **OK** |

So the default basemap needs no help at all, and the two alternatives need the
bundled root. X1 is what both Let's Encrypt chains terminate at today, because
the servers send the cross-signed path; **X2 alone is not enough** for CyclOSM.
Both are bundled, X2 against the day the cross-sign goes away.

[`Tiles.kt`](../console/stride/app/src/main/java/dev/stride/hud/Tiles.kt) tries
the platform's own trust manager first and uses it whenever it is happy — the
ordinary, fully-checked path, and the one the default basemap takes. Only if
that refuses does it re-validate the chain itself, against the system anchors
plus the bundled roots, at a date taken from the certificate rather than from
the console's idea of now.

What that gives up, stated plainly: **a genuinely expired certificate is also
accepted**, because nothing on this machine can tell that case apart from a
clock that is wrong. Signatures, the chain to a real anchor, and the hostname
are all still checked. It applies to map tiles and to nothing else in the app,
and the worst a forged tile server can do is draw the wrong hill behind the
route.

`adb logcat -s StrideTiles` reports every refusal and which stage refused.

---

## Tiles

The page asks for `https://tiles.stride/{z}/{x}/{y}.png`. That host does not
exist and is never resolved — `Tiles.kt` answers it from the WebView's
`shouldInterceptRequest`, serving from a disk cache and only then from the
network. So:

* A route walked twice costs the network once.
* Wi-Fi dropping mid-walk leaves the tiles already on screen where they are.
* The TLS handshake happens in Kotlin, where the two problems above are
  fixable. Inside a WebView they are a choice between trusting everything and
  drawing nothing.

**Choosing a basemap.** `Settings → My routes` offers Standard, Topo and Cycle
as one tap each, and a free-text `{z}/{x}/{y}` template for anything else,
including a keyed provider. Topo draws contours and footpaths, which read
better behind a route than the standard style does.

All three presets are volunteer-run servers with their own terms. This console
is a polite client by construction: it sends an identifying user agent, fetches
one tile at a time, caches everything it draws, and **never pre-fetches ground
you have not walked** — which is the thing OpenStreetMap's tile usage policy
specifically rules out. If you want a whole route's corridor fetched up front,
use a provider whose terms allow it.

**Disk.** 128 MB by default, adjustable from 16 to 512 in steps of 16, oldest
fetched dropped first. `Settings → My routes` shows what is in use and can empty it.

---

## What goes over the wire

`stride_gpx.py` adds three fields to each route, alongside the `segments` the
deck has always driven from:

| | |
|---|---|
| `track` | `[lat, lon, metres]` per vertex, simplified to stay within a point budget. No point is ever moved and no distance recomputed — simplifying only ever drops a vertex, because the distance column is what places the dot. |
| `elev` | `[metres travelled, metres above sea level]`, evenly spaced, averaged over 30 m. A phone without a barometer takes altitude from GPS, which is the noisiest number in the file — a couple of metres of hash on a flat road is normal, and both things that draw it amplify it. 30 m at walking pace is twenty seconds; no real gradient changes inside it. |
| `bounds` | `[south, west, north, east]`, so the console can frame the route without walking the track to find out how big it is. |

A 2.5 km walk comes to about 5 KB of JSON. `segments` is unchanged, byte for
byte, which is why a route imported before any of this walks identically.

**The elevation strip plots `elev`, not `segments`,** and that is a correction
rather than a refinement. By the time a gradient reaches the deck it has been
averaged over 150 m, quantised to half a percent and clamped at the deck's −3%
floor — so a 6% descent and a 3% descent are the same instruction. Drawing that
put a different hill on the screen from the one that was walked. The strip
draws the ground; the deck does what the deck can.

---

## Privacy, since it is now a real question

Your walk coordinates did not use to leave the phone. Now they travel to the
MQTT broker, sit in a retained topic, and are cached in `routes.json` on the
console.

If Home Assistant serves your GPX files from `config/www/`, note that `/local/`
is **not** authenticated. Checked on this install: a plain
`curl http://<ha>:8123/local/treadmill/routes/<name>.gpx` with no token returns
**200 and the file**. A directory request returns 403, so a filename has to be
known or guessed, which is not the same as being protected.

That was already true before any of this — it is how the converter reads them —
and the route cached on the console is app-private (`-rw-------`, owned by the
app's uid, readable only via `run-as` on a debug build). But the shape of a
route usually starts at your front door, so it is worth deciding about rather
than discovering.
