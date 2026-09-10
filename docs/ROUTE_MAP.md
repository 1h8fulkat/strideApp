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
3. **Its clock and trust store can agree with a tile server.** See below; this
   is the part that actually bites.

Nothing here can stop a walk. If the tiles do not arrive the route still draws
on blank ground, the dot still moves, and the strip is unaffected.

---

## The two things that go wrong on an old console

Both are the machine's, not the tile server's, and they look identical from the
outside: no tiles. `adb logcat -s StrideTiles` says which.

**The clock reads 2022.** Certificates are then "not yet valid" and every HTTPS
fetch fails. The console's clock is also why the coach's audio has to be plain
HTTP. Fix it properly if you can:

```
adb shell settings put global auto_time 1
```

**The trust store predates Let's Encrypt's root.** Android shipped ISRG Root X1
from 7.1.1; a console on 7.0 has no anchor for most of the web,
OpenStreetMap included.

[`Tiles.kt`](../console/stride/app/src/main/java/dev/stride/hud/Tiles.kt)
handles both. It tries the platform's own trust manager first and uses it
whenever it is happy — the ordinary, fully-checked path. Only if that refuses
does it re-validate the chain itself, against the system anchors plus a bundled
copy of ISRG Root X1, at a date taken from the certificate instead of from the
console's idea of now.

What that gives up, stated plainly: **a genuinely expired certificate is also
accepted**, because nothing on this machine can tell that case apart from a
clock that is wrong. Signatures, the chain, and the hostname are all still
checked. It applies to map tiles and to nothing else in the app, and the worst
a forged tile server can do is draw the wrong hill behind the route.

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

**Disk.** 120 MB by default, adjustable from 16 to 512, oldest fetched dropped
first. `Settings → My routes` shows what is in use and can empty it.

---

## What goes over the wire

`stride_gpx.py` adds three fields to each route, alongside the `segments` the
deck has always driven from:

| | |
|---|---|
| `track` | `[lat, lon, metres]` per vertex, simplified to stay within a point budget. No point is ever moved and no distance recomputed — simplifying only ever drops a vertex, because the distance column is what places the dot. |
| `elev` | `[metres travelled, metres above sea level]`, evenly spaced. |
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
is **not** authenticated: anyone who can reach Home Assistant can read them.
That was true before this feature and is worth knowing either way, because the
shape of a route usually starts at your front door.
