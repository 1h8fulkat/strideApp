package dev.stride.hud

import android.util.Log
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors
import org.json.JSONArray
import org.json.JSONObject

/**
 * Fetch the routes from Home Assistant, convert them here, and cache them.
 *
 * ### Why the console asks instead of being told
 *
 * Routes used to arrive on the retained `stride/routes` topic, published by
 * `stride_gpx.py` running on somebody's laptop. That works exactly as long as
 * the script is run, and on 2026-09-12 it had not been for weeks: the folder on
 * Home Assistant held three `.gpx` files and the console was offering two
 * routes, one of which was a file that had since been renamed. Nothing was
 * broken and nothing said so — a retained topic holds its last payload forever,
 * which is the property that lets the console boot with no network, and also
 * the property that lets it be quietly months out of date.
 *
 * So it asks. The listing comes from a Home Assistant `folder` sensor, the
 * files come from `/local/`, and [Gpx] does the conversion that used to happen
 * on the laptop. Adding a route is now putting a file in a folder.
 *
 * ### What it will not do
 *
 * **Never replace a good cache with nothing.** Every failure here — no network,
 * a wrong token, a renamed sensor, an unparseable file — leaves the routes
 * already on disk exactly where they are. The console must be able to offer a
 * walk to somebody standing on the belt with the house network down, and that
 * guarantee is older and more important than this class. It is the same
 * reasoning the script has at its own end: *"an empty list is more likely a
 * wrong path than an instruction to delete every route."*
 *
 * **Never block the boot.** This runs on its own thread and the HUD does not
 * wait for it. A console whose start-up depended on a reachable Home Assistant
 * would be a console that will not start a workout when Home Assistant is down.
 *
 * A partial answer *is* accepted: if four files are listed and one of them is
 * malformed, the other three are published and the failure is logged against
 * the file that caused it. Refusing all four would take away three working
 * routes to punish one broken one.
 */
class RouteSync(
    private val cfg: Settings,
    private val routes: Routes,
    /**
     * The deck's grade limits, asked for at the moment of converting rather
     * than passed in once.
     *
     * A lambda because the board is what knows, and the board may not have
     * answered yet when this runs at boot — [MainActivity.minGrade] starts at
     * the conservative pair and is replaced by what the machine reports. Asking
     * late means a sync that happens to land after the handshake clamps to the
     * real deck, and one that lands before it clamps to something this deck can
     * certainly do. Capturing the values at construction would have frozen the
     * guess.
     */
    private val limits: () -> Pair<Double, Double>,
) {

    /** One attempt's outcome, for the settings screen to show. */
    @Volatile
    var status: String = "not tried yet"
        private set

    @Volatile
    private var running = false

    /**
     * One thread, so two taps on REFRESH cannot interleave two listings and
     * publish the loser. Not a pool: the work is one HTTP round trip per route
     * on a console with one user.
     */
    private val worker = Executors.newSingleThreadExecutor()

    /**
     * Go and look, unless a look is already in progress.
     *
     * @param why goes in the log so a sync at boot can be told from one
     *            somebody asked for on the settings screen.
     */
    fun refresh(why: String) {
        if (running) {
            Log.i(TAG, "$why — a fetch is already running, leaving it to finish")
            return
        }
        running = true
        worker.execute {
            try {
                fetch(why)
            } catch (e: Throwable) {
                // Broad on purpose. This is a background thread on an appliance
                // with a motor: an uncaught throw here would take the process
                // down for the sake of a route list.
                status = "failed: ${e.message ?: e.javaClass.simpleName}"
                Log.w(TAG, "$why — $status")
            } finally {
                running = false
            }
        }
    }

    private fun fetch(why: String) {
        val base = cfg.haUrl()
        val token = cfg.haToken()
        if (base.isEmpty() || token.isEmpty()) {
            status = "not configured"
            Log.i(TAG, "$why — no Home Assistant URL or token; keeping the cache")
            return
        }

        val names = listing(base, token)
        if (names.isEmpty()) {
            // Distinguished from a failure in the log, but treated the same:
            // the cache stays. An empty folder is far more often a wrong path
            // or a sensor that has not updated than a decision to own no
            // routes, and the cost of being wrong is a walk that cannot start.
            status = "the folder listed no .gpx files — cache kept"
            Log.w(TAG, "$why — ${cfg.haRoutesSensor()} listed no .gpx; " +
                    "keeping ${routes.count()} cached route(s)")
            return
        }

        // The board's own limits, not a config file's. This is the one thing
        // the console can do better than the script: it knows what this deck
        // will actually give you, so the segments are clamped to the walk you
        // will get rather than to `deck_max_grade` in somebody's stride.conf.
        //
        // chooseRoute clamps again on the way to the deck regardless, and that
        // stays — this only decides what `climb_m` and the drawn profile claim.
        val (floor, ceiling) = limits()

        val built = ArrayList<Gpx.Route>(names.size)
        val failed = ArrayList<String>()
        for (name in names) {
            try {
                val body = get("$base/local/${cfg.haGpxPath()}/${encode(name)}", null)
                val r = Gpx.convert(name, body, floor, ceiling)
                built.add(r)
                Log.i(TAG, "route: ${r.name} — ${"%.2f".format(r.distanceM / 1000)} km, " +
                        "${"%.0f".format(r.climbM)} m climb, ${r.segments.size} segments, " +
                        "${r.track.size} map points" +
                        if (r.flattenedM > 0.5)
                            ", ${"%.0f".format(r.flattenedM)} m of drop the deck cannot give"
                        else "")
            } catch (e: Exception) {
                // Named, because "one route failed" is not actionable and
                // "the-hill.gpx — no usable trackpoints" is.
                failed.add(name)
                Log.w(TAG, "$name — ${e.message ?: e.javaClass.simpleName}")
            }
        }

        if (built.isEmpty()) {
            status = "none of ${names.size} file(s) converted — cache kept"
            Log.w(TAG, "$why — nothing converted; keeping ${routes.count()} cached route(s)")
            return
        }

        routes.accept(Gpx.json(built))
        status = "${built.size} route(s)" +
                if (failed.isEmpty()) "" else ", ${failed.size} failed (${failed.joinToString()})"
        Log.i(TAG, "$why — $status")
    }

    /**
     * The `.gpx` basenames a Home Assistant `folder` sensor is listing.
     *
     * `file_list` holds absolute paths on the Home Assistant box, and only the
     * basename is any use here: the file is fetched over `/local/` rather than
     * read off that disk. Sorted, so the picker's order does not depend on how
     * a filesystem felt about enumerating a directory.
     */
    private fun listing(base: String, token: String): List<String> {
        val url = "$base/api/states/${encode(cfg.haRoutesSensor())}"
        val body = String(get(url, token))
        val attrs = JSONObject(body).optJSONObject("attributes")
            ?: throw Exception("${cfg.haRoutesSensor()} has no attributes")
        val list = attrs.optJSONArray("file_list")
            ?: throw Exception("${cfg.haRoutesSensor()} has no file_list — " +
                    "is it a folder sensor?")
        return basenames(list)
    }

    private fun basenames(list: JSONArray): List<String> {
        val out = ArrayList<String>(list.length())
        for (i in 0 until list.length()) {
            val path = list.optString(i, "")
            if (!path.lowercase().endsWith(".gpx")) continue
            val base = path.substringAfterLast('/').substringAfterLast('\\')
            if (base.isNotEmpty()) out.add(base)
        }
        out.sort()
        return out
    }

    /**
     * One GET, with a bearer token when one is given.
     *
     * Deliberately small and deliberately not shared with [Tiles]: that one has
     * a disk cache, an eviction policy and a tile-shaped idea of failure. This
     * wants a byte array or an exception.
     */
    private fun get(url: String, token: String?): ByteArray {
        val parsed = try {
            URL(url)
        } catch (e: Exception) {
            throw Exception("bad url: $url")
        }
        var conn: HttpURLConnection? = null
        try {
            conn = (parsed.openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                requestMethod = "GET"
                setRequestProperty("Accept-Encoding", "identity")
                if (token != null) setRequestProperty("Authorization", "Bearer $token")
            }
            val code = conn.responseCode
            if (code == 401 || code == 403) {
                // Worth its own words: this is the one failure a person can fix
                // in thirty seconds, and "HTTP 401" does not say how.
                throw Exception("HTTP $code — Home Assistant refused the token")
            }
            if (code != 200) throw Exception("HTTP $code")
            val buf = ByteArrayOutputStream()
            conn.inputStream.use { input ->
                val chunk = ByteArray(16 * 1024)
                var total = 0
                while (true) {
                    val n = input.read(chunk)
                    if (n < 0) break
                    total += n
                    // A recorded walk is tens of kilobytes. Anything this size
                    // is a wrong URL answering with something else — an error
                    // page, a redirect to a login, a video — and reading it all
                    // into a console's heap would be the actual failure.
                    if (total > MAX_BYTES) throw Exception("over ${MAX_BYTES / 1024} kB")
                    buf.write(chunk, 0, n)
                }
            }
            return buf.toByteArray()
        } finally {
            try { conn?.disconnect() } catch (e: Exception) { /* nothing to do */ }
        }
    }

    /** Percent-encoding for one path or query segment, leaving `/` alone. */
    private fun encode(s: String): String =
        URLEncoder.encode(s, "UTF-8").replace("+", "%20").replace("%2F", "/")

    companion object {
        private const val TAG = "Stride"
        private const val TIMEOUT_MS = 15_000
        private const val MAX_BYTES = 8 * 1024 * 1024
    }
}
