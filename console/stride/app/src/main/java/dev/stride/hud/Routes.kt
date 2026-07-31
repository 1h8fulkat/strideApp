package dev.stride.hud

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONException
import java.io.File

/**
 * Routes converted from real outdoor walks, cached on the console.
 *
 * These arrive over MQTT from Home Assistant, which got them from the phone.
 * They are **cached to disk on arrival**, and that is the whole reason this
 * class exists rather than a `@Volatile` field like the one holding the person
 * registry: the console can boot with no network at all. A route fetched at
 * workout time would not be there, and the failure would arrive at the worst
 * possible moment — standing on the belt, having chosen a walk.
 *
 * MQTT retains the topic, so a console that boots *with* a network gets the
 * routes from the broker without Home Assistant needing to be up. The disk copy
 * covers the case where the broker is unreachable too.
 *
 * There is no GPS here and never will be. What arrives is a gradient profile:
 * `[startM, endM, incline]` triples against distance travelled. The phone
 * discards the track before sending — see `iOS.md`.
 */
class Routes(context: Context) {

    private val file = File(context.filesDir, "routes.json")

    @Volatile
    private var raw: String = "[]"

    init {
        raw = try {
            if (file.exists()) file.readText() else "[]"
        } catch (e: Exception) {
            Log.w(TAG, "cache unreadable (${e.message})")
            "[]"
        }
        Log.i(TAG, "loaded ${count()} route(s) from disk")
    }

    /**
     * A payload from Home Assistant.
     *
     * Validated before it replaces anything. A malformed payload that
     * overwrote the cache would take the routes away until the phone next
     * synced, and the console would have thrown away a good copy to store a bad
     * one — so a parse failure keeps what is already here and says so.
     */
    fun accept(payload: String) {
        val text = payload.trim().ifBlank { "[]" }
        try {
            JSONArray(text)
        } catch (e: JSONException) {
            Log.w(TAG, "ignoring unparseable payload (${e.message})")
            return
        }
        raw = text
        try {
            file.writeText(text)
            Log.i(TAG, "cached ${count()} route(s)")
        } catch (e: Exception) {
            // Held in memory regardless. Losing the cache costs the routes on
            // the next cold boot; refusing the update would cost them now.
            Log.w(TAG, "cannot write cache (${e.message})")
        }
    }

    /** The array as it will be handed to the UI. */
    fun json(): String = raw

    fun count(): Int = try { JSONArray(raw).length() } catch (e: Exception) { 0 }

    /**
     * One route by id, or null.
     *
     * Kept here rather than in JavaScript because Kotlin has to resolve the
     * chosen route into plan steps anyway, and two parsers for one format is
     * one more than the format deserves.
     */
    fun byId(id: String): Route? {
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                if (o.optString("id") != id) continue
                val segs = o.optJSONArray("segments") ?: return null
                val out = ArrayList<Route.Segment>(segs.length())
                for (j in 0 until segs.length()) {
                    val t = segs.optJSONArray(j) ?: continue
                    if (t.length() < 3) continue
                    out.add(Route.Segment(t.getDouble(0), t.getDouble(1),
                                          t.getDouble(2)))
                }
                if (out.isEmpty()) return null
                return Route(id = id,
                             name = o.optString("name", "Route"),
                             distanceM = o.optDouble("distance_m", out.last().endM),
                             climbM = o.optDouble("climb_m", 0.0),
                             segments = out)
            }
        } catch (e: Exception) {
            Log.w(TAG, "cannot read route $id (${e.message})")
        }
        return null
    }

    companion object { private const val TAG = "StrideRoutes" }
}

/**
 * A route as the deck will walk it.
 *
 * Segments are in **metres travelled**, not seconds elapsed, and that is the
 * substantive difference from `Plan.Template`. Replaying a route on a clock
 * while walking slower indoors than out means hitting the hill late and never
 * finishing the route; driving it by distance means the ground arrives where it
 * did outdoors however fast you take it.
 */
data class Route(
    val id: String,
    val name: String,
    val distanceM: Double,
    val climbM: Double,
    val segments: List<Segment>,
) {
    data class Segment(val startM: Double, val endM: Double, val incline: Double)

    /** The incline at a given distance into the walk. */
    fun inclineAt(metres: Double): Double {
        if (segments.isEmpty()) return 0.0
        for (s in segments) if (metres < s.endM) return s.incline
        return segments.last().incline
    }

    /** How far through, 0..1. Used for the progress the HUD already shows. */
    fun progressAt(metres: Double): Double =
        if (distanceM <= 0) 0.0 else (metres / distanceM).coerceIn(0.0, 1.0)
}
