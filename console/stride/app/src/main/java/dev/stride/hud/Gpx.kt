package dev.stride.hud

import java.io.ByteArrayInputStream
import java.math.BigDecimal
import java.math.RoundingMode
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * A recorded outdoor walk, turned into something a treadmill can drive.
 *
 * This is a port of the conversion half of `homeassistant/stride_gpx.py`, and
 * it is deliberately a *port* rather than a fresh implementation: the numbers
 * coming out of here move a deck under somebody, and the Python has been walked
 * on for months. Every function below is the same function under the same name,
 * in the same order, with the same constants — so the two can be diffed by eye
 * when one of them changes, and `GpxTest` diffs them by machine against three
 * real routes.
 *
 * Why it is here at all: the console used to be handed finished routes over
 * retained MQTT, which meant a script had to be run on a third machine every
 * time a `.gpx` changed. It was not, for weeks, and two routes simply never
 * arrived — the console was holding a snapshot from before a file was renamed.
 * A console that reads Home Assistant itself has no such gap: the answer is
 * whatever is in the folder at the moment it asks. See [RouteSync].
 *
 * Nothing here knows where the treadmill is, and the port does not change that.
 * What comes out is a gradient profile against distance travelled, plus
 * geometry for drawing. The dot on the map is still placed by metres of belt.
 */
object Gpx {

    /*
     * Gradient is measured over this much ground, quantised to this, and held
     * for at least this far. Same three numbers as the Python, and they are not
     * tuneable here on purpose: a route converted on the console and the same
     * route converted by the script must walk identically, or "it felt
     * different today" becomes unanswerable.
     */
    private const val WINDOW = 150.0
    private const val MIN_SEGMENT = 120.0
    private const val STEP = 0.5

    /** What the map needs, which is not what the deck needs. */
    private const val TRACK_TOLERANCE = 4.0
    private const val TRACK_MAX = 1200
    private const val ELEV_STEP = 10.0
    private const val ELEV_MAX = 600

    /**
     * Metres of ground the elevation is averaged over before it is used.
     *
     * GPS altitude is the noisiest number in the file and both things that draw
     * it amplify the hash. Deliberately short: this removes hash, not hills.
     * The gradient the deck drives is not smoothed here and does not need to be
     * — it has a 150 m window of its own, five times longer.
     */
    private const val ELEV_SMOOTH = 30.0

    private const val MIN_DISTANCE_M = 50.0

    /** A trackpoint that carried all three of the things we need. */
    private data class Point(val lat: Double, val lon: Double, val ele: Double)

    /** Distance along the walk, and where that is on the ground. */
    private data class Fix(val d: Double, val ele: Double,
                           val lat: Double, val lon: Double)

    /**
     * One stretch of held gradient.
     *
     * Mutable, and that is not laziness: [segments] merges a short stretch into
     * the one before it by extending that one's end, exactly as the Python does
     * by assigning into a list it has already appended.
     */
    private class Raw(val startM: Double, var endM: Double, val grade: Double)

    /** What a route is once converted, ready for [json]. */
    class Route(
        val id: String,
        val name: String,
        val distanceM: Double,
        val climbM: Double,
        val segments: List<DoubleArray>,
        val track: List<DoubleArray>,
        val elev: List<DoubleArray>,
        val bounds: DoubleArray,
        /** Not published. For the log line that says what the import cost. */
        val flattenedM: Double,
        val rawClimbM: Double,
        val points: Int,
    )

    class Unusable(message: String) : Exception(message)

    // --- the geometry ------------------------------------------------------

    /**
     * Every trkpt in a GPX document, in file order.
     *
     * Namespace-agnostic, like the Python: GPX in the wild carries a different
     * xmlns per exporter and matching on the tag suffix is the only thing that
     * reads all of them. The factory is left namespace-*unaware* so the suffix
     * is all there is to match on, which is the same position ElementTree's
     * `tag.endswith` is in.
     */
    private fun trackpoints(xml: ByteArray): List<Point> {
        val doc = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            // A GPX file is data from a phone. It has no business naming
            // entities or pulling in a DTD from the network.
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            isExpandEntityReferences = false
        }.newDocumentBuilder().parse(ByteArrayInputStream(xml))

        val all = doc.getElementsByTagName("*")
        val out = ArrayList<Point>(all.length / 4)
        for (i in 0 until all.length) {
            val el = all.item(i) as? Element ?: continue
            if (!suffix(el.tagName).equals("trkpt", true)) continue
            val lat = el.getAttribute("lat").toDoubleOrNull() ?: continue
            val lon = el.getAttribute("lon").toDoubleOrNull() ?: continue
            // No elevation, no point: the whole output is a gradient.
            val ele = childText(el, "ele")?.toDoubleOrNull() ?: continue
            out.add(Point(lat, lon, ele))
        }
        return out
    }

    /** The part of `ns:tag` after the colon, or the whole thing. */
    private fun suffix(tag: String): String = tag.substringAfterLast(':')

    private fun childText(el: Element, want: String): String? {
        val kids = el.childNodes
        for (i in 0 until kids.length) {
            val kid = kids.item(i) as? Element ?: continue
            if (suffix(kid.tagName).equals(want, true)) return kid.textContent?.trim()
        }
        return null
    }

    /**
     * Haversine on the mean Earth radius. Good to a few cm at these lengths,
     * which is far past what the elevation data deserves.
     */
    private fun metres(a: Point, b: Point): Double {
        val r = 6371008.8
        val p1 = Math.toRadians(a.lat)
        val p2 = Math.toRadians(b.lat)
        val dp = p2 - p1
        val dl = Math.toRadians(b.lon - a.lon)
        val h = Math.sin(dp / 2) * Math.sin(dp / 2) +
                Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2)
        return 2 * r * Math.asin(Math.sqrt(h))
    }

    /**
     * Distance, elevation and position along the track.
     *
     * Steps shorter than half a metre are dropped: editors repeat a vertex
     * where a route doubles back, and a zero-length step is a division waiting
     * to happen for ground nobody walks.
     *
     * Note which point the next step is measured *from*. The Python walks
     * consecutive pairs of the raw list, so a dropped step does not re-anchor
     * the comparison — the next step is still measured between the next two
     * recorded points, not from the last one that was kept. Re-anchoring would
     * accumulate the sub-half-metre wander this exists to discard, and would
     * make the total distance disagree with every route already imported.
     */
    private fun walked(pts: List<Point>): List<Fix> {
        val out = ArrayList<Fix>(pts.size)
        out.add(Fix(0.0, pts[0].ele, pts[0].lat, pts[0].lon))
        var d = 0.0
        for (i in 0 until pts.size - 1) {
            val step = metres(pts[i], pts[i + 1])
            if (step < 0.5) continue
            d += step
            out.add(Fix(d, pts[i + 1].ele, pts[i + 1].lat, pts[i + 1].lon))
        }
        return out
    }

    private fun elevationAt(prof: List<Fix>, x: Double): Double {
        if (x <= prof[0].d) return prof[0].ele
        val last = prof[prof.size - 1]
        if (x >= last.d) return last.ele
        for (i in 0 until prof.size - 1) {
            val a = prof[i]
            val b = prof[i + 1]
            if (x <= b.d) {
                val span = b.d - a.d
                return if (span <= 0) a.ele else a.ele + (b.ele - a.ele) * ((x - a.d) / span)
            }
        }
        return last.ele
    }

    /**
     * The profile as stretches of held gradient, and the metres of drop the
     * deck cannot give you.
     *
     * That second number is measured *before* the clamp so the caller can say
     * so, rather than quietly delivering a gentler walk than the one recorded.
     */
    private fun segments(prof: List<Fix>, floor: Double, ceiling: Double):
            Pair<List<Raw>, Double> {
        val total = prof[prof.size - 1].d
        val raw = ArrayList<Raw>()
        var lost = 0.0
        var x = 0.0
        while (x < total - 1e-9) {
            val end = Math.min(x + WINDOW, total)
            val span = end - x
            val grade = (elevationAt(prof, end) - elevationAt(prof, x)) / span * 100
            // `Math.round` would be wrong here, and wrong in a way that moves
            // the deck. Python's bare `round` is half to *even*, so a window
            // coming out at exactly 0.25% quantises to 0.0 there and would
            // quantise to 0.5 with a half-up rounding — a whole step of
            // gradient, on ground that is a tie by definition. Ties are not
            // hypothetical at a 0.5% quantum.
            var held = halfEven(grade / STEP) * STEP
            if (held < floor) {
                lost += (floor - held) / 100 * span
                held = floor
            }
            held = Math.min(held, ceiling)
            raw.add(Raw(x, end, held))
            x = end
        }

        val merged = ArrayList<Raw>()
        merged.add(raw[0])
        for (i in 1 until raw.size) {
            val s = raw[i]
            val prev = merged[merged.size - 1]
            if (Math.abs(s.grade - prev.grade) < 1e-9) prev.endM = s.endM else merged.add(s)
        }

        val kept = ArrayList<Raw>()
        for (s in merged) {
            if (kept.isNotEmpty() && (s.endM - s.startM) < MIN_SEGMENT) {
                // Too short to be worth moving the deck for.
                kept[kept.size - 1].endM = s.endM
            } else {
                kept.add(s)
            }
        }
        return Pair(kept, lost)
    }

    /**
     * Ramer-Douglas-Peucker over the walk, tolerance in metres.
     *
     * Iterative rather than recursive, like the Python — and for a better
     * reason here than stack depth: this runs on the console at boot, and a
     * recorded walk is tens of thousands of points.
     *
     * Distances are measured on a local flat projection centred on the walk.
     * Over a few kilometres that is accurate to well under the tolerance, and
     * it keeps trigonometry out of the inner loop of the one function here that
     * is not linear.
     */
    private fun simplify(walk: List<Fix>, tolerance: Double): List<Fix> {
        val n = walk.size
        if (n < 3) return ArrayList(walk)

        val kx = 111320.0 * Math.cos(Math.toRadians(walk[n / 2].lat))
        val ky = 110540.0

        val keep = BooleanArray(n)
        keep[0] = true
        keep[n - 1] = true
        val stack = ArrayList<IntArray>()
        stack.add(intArrayOf(0, n - 1))
        while (stack.isNotEmpty()) {
            val (lo, hi) = stack.removeAt(stack.size - 1)
            if (hi - lo < 2) continue
            val ax = walk[lo].lon * kx
            val ay = walk[lo].lat * ky
            val bx = walk[hi].lon * kx
            val by = walk[hi].lat * ky
            val dx = bx - ax
            val dy = by - ay
            val span = Math.hypot(dx, dy)
            var worst = -1.0
            var at = lo
            for (i in lo + 1 until hi) {
                val px = walk[i].lon * kx
                val py = walk[i].lat * ky
                val far = if (span <= 0) {
                    // A leg that doubles back has a zero-length chord, and
                    // every point on it is infinitely far from a line through
                    // the ends. Measure to the point instead.
                    Math.hypot(px - ax, py - ay)
                } else {
                    val t = Math.min(1.0, Math.max(0.0,
                        ((px - ax) * dx + (py - ay) * dy) / (span * span)))
                    Math.hypot(px - (ax + dx * t), py - (ay + dy * t))
                }
                if (far > worst) {
                    worst = far
                    at = i
                }
            }
            if (worst > tolerance) {
                keep[at] = true
                stack.add(intArrayOf(lo, at))
                stack.add(intArrayOf(at, hi))
            }
        }
        val out = ArrayList<Fix>()
        for (i in 0 until n) if (keep[i]) out.add(walk[i])
        return out
    }

    /**
     * The route as [lat, lon, metres] triples, inside the point budget.
     *
     * The distance column is the whole point, and simplifying must not disturb
     * it. Dropping a vertex is therefore all this ever does — nothing is moved
     * and no distance is recomputed.
     */
    private fun mapTrack(walk: List<Fix>): List<DoubleArray> {
        var tol = TRACK_TOLERANCE
        var out = simplify(walk, tol)
        while (out.size > TRACK_MAX && tol < 256) {
            tol *= 2
            out = simplify(walk, tol)
        }
        return out.map { doubleArrayOf(round(it.lat, 6), round(it.lon, 6), round(it.d, 1)) }
    }

    /**
     * Elevation as [metres travelled, metres above sea level].
     *
     * Absolute altitude, not rise from the start: it is the number the map
     * beside it shows, and a rise reconstructed from the published segments
     * could not agree with it anyway — those are quantised and clamped.
     */
    private fun elevSamples(prof: List<Fix>): List<DoubleArray> {
        val total = prof[prof.size - 1].d
        val step = Math.max(ELEV_STEP, total / ELEV_MAX)
        val xs = ArrayList<Double>()
        var ys = ArrayList<Double>()
        var x = 0.0
        while (x < total) {
            xs.add(x)
            ys.add(elevationAt(prof, x))
            x += step
        }
        xs.add(total)
        ys.add(prof[prof.size - 1].ele)

        // A box filter, with the window shrinking at both ends so the first and
        // last altitudes are the ones actually recorded there — those two are
        // the labels on the axis, and a smoothed endpoint is a wrong one.
        val half = (ELEV_SMOOTH / step / 2).toInt()
        if (half > 0) {
            val smooth = ArrayList<Double>(ys.size)
            for (i in ys.indices) {
                val k = Math.min(half, Math.min(i, ys.size - 1 - i))
                var sum = 0.0
                var count = 0
                for (j in i - k..i + k) {
                    sum += ys[j]
                    count++
                }
                smooth.add(sum / count)
            }
            ys = smooth
        }

        val out = ArrayList<DoubleArray>(xs.size)
        for (i in xs.indices) out.add(doubleArrayOf(round(xs[i], 1), round(ys[i], 1)))
        return out
    }

    /**
     * [south, west, north, east], so the console can frame the route without
     * walking the whole track to find out how big it is.
     */
    private fun boundsOf(tk: List<DoubleArray>): DoubleArray {
        var s = tk[0][0]
        var w = tk[0][1]
        var n = tk[0][0]
        var e = tk[0][1]
        for (p in tk) {
            if (p[0] < s) s = p[0]
            if (p[0] > n) n = p[0]
            if (p[1] < w) w = p[1]
            if (p[1] > e) e = p[1]
        }
        return doubleArrayOf(s, w, n, e)
    }

    /**
     * Stable across re-imports, so replacing a file replaces its route rather
     * than adding a second copy of it.
     *
     * Which is also why a *renamed* file arrives as a new route and the old one
     * goes. That is exactly how this console came to be offering a route whose
     * file had been renamed weeks earlier: the id went with the old name, and
     * nothing re-synced to notice.
     */
    fun routeId(filename: String): String {
        val stem = stem(filename).lowercase()
        val slug = stem.replace(Regex("[^a-z0-9]+"), "-").trim('-')
        return "gpx-" + slug.ifEmpty { "route" }
    }

    /**
     * The filename, tidied.
     *
     * Not the GPX's own <name>: exporters put "New file 1" in there, and a
     * filename is something you chose.
     */
    fun routeName(filename: String): String {
        val s = stem(filename).replace(Regex("[_-]+"), " ").trim()
        return if (s.isEmpty()) "Route"
               else s.substring(0, 1).uppercase() + s.substring(1)
    }

    private fun stem(filename: String): String {
        val base = filename.substringAfterLast('/').substringAfterLast('\\')
        val dot = base.lastIndexOf('.')
        return if (dot > 0) base.substring(0, dot) else base
    }

    /**
     * Round the way Python's `round` rounds — half to even, on the exact binary
     * value of the double.
     *
     * `Math.round` is half *up*, which would disagree with the script on any
     * value that lands exactly on a half. Rare, but the whole claim being made
     * here is that the two converters produce the same route, and "the same
     * except sometimes the last decimal" is a claim nobody can act on.
     */
    /** [round] to a whole number, as Python's one-argument `round` does. */
    private fun halfEven(v: Double): Double =
        BigDecimal(v).setScale(0, RoundingMode.HALF_EVEN).toDouble()

    private fun round(v: Double, places: Int): Double =
        BigDecimal(v).setScale(places, RoundingMode.HALF_EVEN).toDouble()

    /**
     * @param floor   the deck's shallowest grade, from the board itself
     * @param ceiling the steepest. Both come from what this board reports
     *                rather than a config file, which is the one advantage the
     *                console has over the script — see [Bridge.boardLimits].
     */
    fun convert(filename: String, xml: ByteArray, floor: Double, ceiling: Double): Route {
        val pts = trackpoints(xml)
        if (pts.size < 2) throw Unusable("no usable trackpoints (needs lat, lon and ele)")
        val walk = walked(pts)
        val total = walk[walk.size - 1].d
        if (total < MIN_DISTANCE_M) throw Unusable("only ${"%.0f".format(total)} m long")

        val (segs, flattened) = segments(walk, floor, ceiling)
        var climb = 0.0
        for (s in segs) if (s.grade > 0) climb += (s.endM - s.startM) * s.grade / 100
        val tk = mapTrack(walk)
        val elev = elevSamples(walk)

        var rawClimb = 0.0
        for (i in 0 until walk.size - 1) {
            rawClimb += Math.max(0.0, walk[i + 1].ele - walk[i].ele)
        }

        return Route(
            id = routeId(filename),
            name = routeName(filename),
            distanceM = round(total, 1),
            climbM = round(climb, 1),
            segments = segs.map {
                doubleArrayOf(round(it.startM, 1), round(it.endM, 1), round(it.grade, 1))
            },
            track = tk,
            elev = elev,
            bounds = boundsOf(tk),
            flattenedM = flattened,
            rawClimbM = rawClimb,
            points = pts.size,
        )
    }

    // --- the payload -------------------------------------------------------

    /**
     * The array the page and [Routes] already read, built by hand.
     *
     * By hand because the scale of every number matters and a generic
     * serialiser does not know it: a distance is one decimal place, a
     * coordinate is six, and `difficulty` is the literal 1.0 the script has
     * always written. Going through org.json would mean formatting doubles by
     * whatever `Double.toString` felt like, which is how "5021.400000000001"
     * gets into a file.
     *
     * The field names and their order are the script's. `segments` is the only
     * one the deck reads; everything after it is for drawing.
     */
    fun json(routes: List<Route>): String {
        val b = StringBuilder("[")
        for ((i, r) in routes.withIndex()) {
            if (i > 0) b.append(',')
            b.append("{\"id\":").append(quote(r.id))
            b.append(",\"name\":").append(quote(r.name))
            b.append(",\"distance_m\":").append(num(r.distanceM, 1))
            b.append(",\"climb_m\":").append(num(r.climbM, 1))
            b.append(",\"difficulty\":1.0")
            b.append(",\"segments\":").append(triples(r.segments, 1, 1, 1))
            b.append(",\"track\":").append(triples(r.track, 6, 6, 1))
            b.append(",\"elev\":").append(pairs(r.elev, 1, 1))
            b.append(",\"bounds\":[")
                .append(num(r.bounds[0], 6)).append(',').append(num(r.bounds[1], 6))
                .append(',').append(num(r.bounds[2], 6)).append(',')
                .append(num(r.bounds[3], 6)).append(']')
            b.append('}')
        }
        return b.append(']').toString()
    }

    private fun triples(rows: List<DoubleArray>, a: Int, b2: Int, c: Int): String {
        val b = StringBuilder("[")
        for ((i, r) in rows.withIndex()) {
            if (i > 0) b.append(',')
            b.append('[').append(num(r[0], a)).append(',').append(num(r[1], b2))
                .append(',').append(num(r[2], c)).append(']')
        }
        return b.append(']').toString()
    }

    private fun pairs(rows: List<DoubleArray>, a: Int, c: Int): String {
        val b = StringBuilder("[")
        for ((i, r) in rows.withIndex()) {
            if (i > 0) b.append(',')
            b.append('[').append(num(r[0], a)).append(',').append(num(r[1], c)).append(']')
        }
        return b.append(']').toString()
    }

    /**
     * A JSON number with a fixed number of decimals, trailing zeros and all.
     *
     * `-0.0` is normalised to `0.0`: a flat stretch that rounds to a negative
     * zero is still flat, and "-0.0" in the payload reads as a fault.
     */
    private fun num(v: Double, places: Int): String {
        val d = BigDecimal(v).setScale(places, RoundingMode.HALF_EVEN)
        return if (d.signum() == 0) BigDecimal.ZERO.setScale(places).toPlainString()
               else d.toPlainString()
    }

    private fun quote(s: String): String {
        val b = StringBuilder("\"")
        for (c in s) when {
            c == '"' -> b.append("\\\"")
            c == '\\' -> b.append("\\\\")
            c == '\n' -> b.append("\\n")
            c == '\r' -> b.append("\\r")
            c == '\t' -> b.append("\\t")
            c < ' ' -> b.append("\\u").append("%04x".format(c.code))
            else -> b.append(c)
        }
        return b.append('"').toString()
    }
}
