package dev.stride.hud

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Everything about *this* installation, rather than about treadmills.
 *
 * The console shipped with a household baked into it. Not as configuration
 * anyone had chosen to inline, but as the ordinary residue of building
 * something for one house: four names in an HTML array, a broker in
 * `local.properties`, kilometres everywhere, and — the one that actually
 * changed behaviour — a live coach gated on `walker == "Sam"`, so a second
 * person in the same house silently got a different product.
 *
 * Three rules this file exists to hold:
 *
 * * **A default must be a working treadmill.** Nothing here is required. With
 *   no broker set and no people added, the deck still runs, still counts, still
 *   coaches from its built-in lines. Home Assistant is an addition, never a
 *   dependency — so a fresh install is usable before it is configured.
 *
 * * **Preferences are not permissions.** Anything the *board* reports —
 *   incline range, speed range — is read from the machine and is not in here.
 *   Storing a max incline someone typed would be storing a way to ask the deck
 *   for something it cannot do.
 *
 * * **Per-person things belong to the person.** Coaching and publishing are
 *   properties of a walker, not of the console, which is the whole fix for the
 *   name comparison.
 *
 * Stored in SharedPreferences because it is a handful of scalars and one small
 * list, read at start-up and on every settings save. A database would be a
 * database to maintain.
 */
class Settings(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)

    companion object {
        // --- people ---
        const val PEOPLE = "people"
        const val DEFAULT_WALKER = "default_walker"
        const val ALLOW_GUEST = "allow_guest"

        /** Sentinel for "do not assume, ask on the welcome screen". */
        const val ASK = ""

        // --- units ---
        const val UNITS = "units"           // "km" | "mi"

        // --- home assistant ---
        const val HA_ENABLED = "ha_enabled"
        const val MQTT_HOST = "mqtt_host"
        const val MQTT_PORT = "mqtt_port"
        const val MQTT_USER = "mqtt_user"
        const val MQTT_PASS = "mqtt_pass"
        const val MQTT_TLS = "mqtt_tls"
        const val MQTT_PREFIX = "mqtt_prefix"

        // --- routes, read straight off home assistant ---
        //
        // Separate from the MQTT block above and deliberately not gated on it.
        // Routes used to arrive on the retained `stride/routes` topic, which
        // meant a script on a third machine had to be run by hand every time a
        // .gpx changed — and when it was not, the console went on offering a
        // list from before the change. These four let it ask instead. See
        // [RouteSync].
        const val HA_URL = "ha_url"
        const val HA_TOKEN = "ha_token"
        const val HA_ROUTES_SENSOR = "ha_routes_sensor"
        const val HA_INDEX = "ha_index"
        const val HA_GPX_PATH = "ha_gpx_path"
        const val ROUTE_FETCH = "route_fetch"

        // --- deck ---
        const val WARMUP_MIN = "warmup_min"
        const val COOLDOWN_MIN = "cooldown_min"
        const val WARMUP_KPH = "warmup_kph"
        const val COOLDOWN_KPH = "cooldown_kph"
        const val INCLINE_RATE = "incline_rate"   // "gentle" | "normal" | "quick"
        const val OPEN_LAP_MIN = "open_lap_min"
        const val CARRY_WARMUP = "carry_warmup"

        // --- coach ---
        const val COACH_ON = "coach_on"
        const val COACH_TALK = "coach_talk"       // "sparing" | "normal" | "chatty"
        const val COACH_VOICE = "coach_voice"
        const val COACH_REMOTE = "coach_remote"
        const val COACH_MILESTONES = "coach_milestones"

        // --- heart rate ---
        const val HR_SOURCE = "hr_source"         // "auto" | "strap" | "grips"
        const val HR_ADDR = "hr_addr"
        const val HR_NAME = "hr_name"

        // --- routes and the map ---
        /**
         * What the hero draws while a recorded route is being walked:
         * `"path"` for the perspective path this interface has always drawn,
         * `"map"` for the route on a real map with a dot on it.
         *
         * Only ever consulted for routes. A template has no coordinates, so
         * there is nothing for a map to show and the path is the only answer.
         */
        const val ROUTE_VIEW = "route_view"       // "path" | "map"
        /**
         * Where map tiles come from, as an `{z}/{x}/{y}` template.
         *
         * A setting rather than a constant because the right basemap for a
         * route is not the one that suits a city: OpenTopoMap draws contours
         * and footpaths, which is what you want behind a hill profile, and
         * swapping to it should not need a rebuild. `{s}` is not supported —
         * the fetcher is one connection at a time and subdomain sharding is a
         * workaround for a browser limit this does not have.
         */
        const val MAP_TILE_URL = "map_tile_url"
        /** Printed in the corner of the map. Tile terms generally require it. */
        const val MAP_ATTRIBUTION = "map_attribution"
        /** Megabytes of tiles kept on disk before the oldest are dropped. */
        const val MAP_CACHE_MB = "map_cache_mb"

        /** OpenStreetMap's own tiles, and the credit their terms ask for. */
        const val DEFAULT_TILE_URL = "https://tile.openstreetmap.org/{z}/{x}/{y}.png"
        const val DEFAULT_ATTRIBUTION = "© OpenStreetMap contributors"

        // --- display ---
        const val SLEEP_MIN = "sleep_min"
        const val CLOCK_24 = "clock_24"
        const val BRIGHTNESS = "brightness"       // "dim" | "normal" | "bright"
        const val KEEP_AWAKE = "keep_awake"

        /**
         * How fast a guided walk may move the deck, as three named presets
         * rather than two millisecond fields.
         *
         * `INCLINE_STEP` and `INCLINE_EVERY_MS` are a safety-shaped pair: they
         * are the reason the biggest jump in any template takes half a minute
         * to arrive rather than landing under someone mid-stride. Exposed as
         * raw numbers, somebody eventually sets five percent every five hundred
         * milliseconds. Named presets keep the reasoning attached.
         */
        val INCLINE_RATES = mapOf(
            "gentle" to Pair(0.5, 4000L),
            "normal" to Pair(1.0, 3000L),
            "quick" to Pair(1.0, 1500L),
        )
    }

    // --- people ---------------------------------------------------------------

    /**
     * One walker.
     *
     * @param coached  may the live coach speak to them
     * @param publish  do their walks leave the console for Home Assistant
     *
     * Both default true for someone added deliberately, and both are the
     * replacement for the name comparison. Coaching is a preference; publishing
     * is closer to consent, and it is the reason a household member can use the
     * treadmill without appearing on somebody else's dashboard.
     */
    data class Person(
        val id: String,
        val name: String,
        val coached: Boolean,
        val publish: Boolean,
        /** `person.jane_doe`, or empty for somebody who exists only here. */
        val haPerson: String = "",
        /**
         * Years, or 0 for "has not said" — which is the default and stays the
         * default. It buys one thing: heart-rate zones, via the usual
         * 220-minus-age. That formula is crude enough (±10-12 bpm between two
         * people of the same age) that the coach talks in its terms rather than
         * quoting it, and a walker who leaves this alone still gets effort
         * coaching measured against their own session average instead. Nobody
         * has to tell a treadmill their age to be coached by it.
         *
         * Per person, not per console: there is a person registry here because
         * more than one person walks on this machine, and one shared age would
         * be wrong for all but one of them.
         */
        val age: Int = 0,
    ) {
        fun json(): JSONObject = JSONObject()
            .put("id", id).put("name", name)
            .put("coached", coached).put("publish", publish)
            .put("ha_person", haPerson)
            .put("age", age)

        /**
         * Maximum heart rate, or 0 if unknown.
         *
         * Clamped to an age this formula means anything for. Below about 13 and
         * above about 100 it is extrapolation, and a zone floor built on it
         * would be a number the console had made up.
         */
        val maxPulse: Int get() = if (age in 13..100) 220 - age else 0
    }

    /**
     * A stable identity, fixed when somebody is added and never derived again.
     *
     * The id used to *be* the name — `slug(who)` at publish time — which meant
     * a rename silently orphaned every reading taken under the old spelling.
     * "Kez" becoming "Sam" started a second person's history and left the
     * first one frozen in Home Assistant looking like somebody who had stopped
     * walking.
     *
     * The name is now a label. The id is the thing, and it is deliberately
     * still the slug *at creation*, so existing devices — `stride_person_sam`
     * and `stride_person_alex`, with real history behind them — keep working
     * untouched rather than needing a migration.
     */
    private fun freshId(name: String, taken: Set<String>): String {
        val base = name.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')
            .ifBlank { "walker" }
        if (base !in taken) return base
        var n = 2
        while ("${base}_$n" in taken) n++
        return "${base}_$n"
    }

    fun addPerson(name: String, haPerson: String = ""): Person? {
        val clean = name.trim().take(20)
        if (clean.isBlank()) return null
        val list = people().toMutableList()
        if (list.any { it.name.equals(clean, ignoreCase = true) }) return null
        val p = Person(
            id = freshId(clean, list.map { it.id }.toSet()),
            name = clean,
            coached = true,
            // Somebody who is not in Home Assistant has nothing there to be
            // recorded against, so it starts off. It can be turned on — it
            // simply creates a device with no matching person, which is a
            // reasonable thing to want for a housemate with no HA account.
            publish = haPerson.isNotEmpty(),
            haPerson = haPerson,
        )
        list += p
        savePeople(list)
        return p
    }

    /**
     * Empty by default, and deliberately so.
     *
     * A console that ships with four strangers' names on the welcome screen is
     * worse than one that asks. When the list is empty the UI offers a single
     * guest walk and a way to add somebody.
     */
    fun people(): List<Person> {
        val raw = prefs.getString(PEOPLE, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val name = o.getString("name")
                Person(
                    // Older entries predate stable ids; fall back to the slug
                    // they were already being published under.
                    id = o.optString("id").ifBlank {
                        name.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')
                    },
                    name = name,
                    coached = o.optBoolean("coached", true),
                    publish = o.optBoolean("publish", true),
                    haPerson = o.optString("ha_person"),
                    // Absent for everyone who existed before zones did, which
                    // is the same as declining to say — see Person.age.
                    age = o.optInt("age", 0),
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun savePeople(list: List<Person>) {
        prefs.edit().putString(
            PEOPLE, JSONArray().apply { list.forEach { put(it.json()) } }.toString()
        ).apply()
        // A default that no longer names anybody would silently start every
        // walk as a guest. Drop it and let the welcome screen ask instead.
        if (list.none { it.name == defaultWalker() }) {
            prefs.edit().putString(DEFAULT_WALKER, ASK).apply()
        }
    }

    fun person(name: String): Person? = people().firstOrNull { it.name == name }
    fun personById(id: String): Person? = people().firstOrNull { it.id == id }

    /**
     * May the coach speak to this walker, and may their walk be published.
     *
     * **Unknown walkers get neither.** A guest is someone who has not agreed to
     * anything, so their walk stays on the console. That is the opposite of the
     * old behaviour, where an unrecognised name simply meant "not Sam" and
     * fell through to no coaching but full publishing.
     */
    fun coachedFor(name: String): Boolean = person(name)?.coached ?: false
    fun publishFor(name: String): Boolean = person(name)?.publish ?: false

    fun defaultWalker(): String = prefs.getString(DEFAULT_WALKER, ASK) ?: ASK
    fun allowGuest(): Boolean = prefs.getBoolean(ALLOW_GUEST, true)

    // --- units ----------------------------------------------------------------

    fun units(): String = prefs.getString(UNITS, "km") ?: "km"
    fun metric(): Boolean = units() == "km"

    // --- routes and the map ---------------------------------------------------

    /**
     * Defaults to the path, which is what every console did before there was a
     * map. A basemap needs a network, a clock the certificates agree with and a
     * tile server that will have us; the path needs none of those and has never
     * failed to draw. So the map is opted into, not out of.
     */
    fun routeView(): String = prefs.getString(ROUTE_VIEW, "path") ?: "path"
    fun mapTileUrl(): String =
        (prefs.getString(MAP_TILE_URL, DEFAULT_TILE_URL) ?: DEFAULT_TILE_URL)
            .trim().ifBlank { DEFAULT_TILE_URL }
    fun mapAttribution(): String =
        prefs.getString(MAP_ATTRIBUTION, DEFAULT_ATTRIBUTION) ?: DEFAULT_ATTRIBUTION

    /**
     * A short stable name for the basemap in use.
     *
     * One string, two jobs, and both of them are about a tile being identified
     * by *where it came from* and not only by its z/x/y:
     *
     *  * it is the directory the tiles are cached under, so switching basemap
     *    cannot serve the old one's pictures for the same coordinates — which
     *    is exactly what happened, and why Topo and Cycle appeared to change
     *    nothing at all;
     *  * it is part of the URL the page asks for, so the WebView's own cache
     *    cannot do the same thing one layer higher up. Those responses are
     *    deliberately given a year of `max-age`, which is right for a tile and
     *    wrong for a URL that quietly means something else than it did.
     *
     * Host plus a digest rather than a digest alone: `find files/tiles` should
     * be readable by whoever is wondering where their basemap went.
     */
    fun mapTilesTag(): String {
        val url = mapTileUrl()
        val host = try {
            java.net.URL(url).host?.replace(Regex("[^A-Za-z0-9.-]"), "_") ?: "tiles"
        } catch (e: Exception) { "tiles" }
        val digest = try {
            java.security.MessageDigest.getInstance("SHA-256")
                .digest(url.toByteArray())
                .take(3).joinToString("") { "%02x".format(it) }
        } catch (e: Exception) { "000000" }
        return "$host-$digest"
    }
    /**
     * Clamped: a cache big enough to fill the console's storage is not a
     * setting, it is a fault waiting for the walk you care about.
     *
     * 128 rather than a rounder 120 so that it lands on the settings stepper's
     * ladder, which moves in sixteens. Off the ladder the stepper snapped the
     * display to the nearest rung and showed 128 beside a cache row that said
     * "of 120 MB" — two numbers for one setting, and the stepper's own promise
     * is that the number on screen is the number kept.
     */
    fun mapCacheMb(): Int = prefs.getInt(MAP_CACHE_MB, 128).coerceIn(16, 512)

    // --- home assistant -------------------------------------------------------

    /**
     * Off until somebody sets a broker.
     *
     * The old build compiled credentials in from `local.properties`, so a clone
     * without those keys produced an app that could not connect — which the
     * comment there called "the right way to fail", and was, for one house. For
     * anyone else it is an app that ships someone else's password or does not
     * work. Runtime configuration is the only version of this that can be
     * released.
     */
    fun haEnabled(): Boolean = prefs.getBoolean(HA_ENABLED, false) && mqttHost().isNotBlank()

    fun mqttHost(): String = prefs.getString(MQTT_HOST, "") ?: ""
    fun mqttPort(): Int = prefs.getInt(MQTT_PORT, 1883)
    fun mqttUser(): String = prefs.getString(MQTT_USER, "") ?: ""
    fun mqttPass(): String = prefs.getString(MQTT_PASS, "") ?: ""
    fun mqttTls(): Boolean = prefs.getBoolean(MQTT_TLS, false)
    fun mqttPrefix(): String = (prefs.getString(MQTT_PREFIX, "stride") ?: "stride")
        .trim().trim('/').ifBlank { "stride" }

    /**
     * Home Assistant's base URL, with any trailing slash taken off so callers
     * can concatenate without thinking about it.
     */
    fun haUrl(): String =
        (prefs.getString(HA_URL, "") ?: "").trim().trimEnd('/')

    /**
     * A long-lived access token. **Optional**, and worth leaving empty.
     *
     * The only thing it buys is the folder *listing*: with a token the console
     * asks [haRoutesSensor] what is in the directory, which means adding a
     * route is dropping a file in it and nothing else. Without one it reads
     * [haIndex] instead — a plain list of filenames sitting beside the `.gpx`
     * files — and the console needs no credential of any kind.
     *
     * The files themselves never need it either way. They are served from
     * `/local/`, which Home Assistant hands out unauthenticated.
     *
     * Which is also the reason this is stored *here* and cannot be stored over
     * there. A file under `www/` is readable by anything that can reach Home
     * Assistant, and this token is the whole of its API — not one topic on one
     * broker, the whole thing. Putting it in the route folder so the console
     * could fetch it would publish it to the network, and a credential that
     * needs no credential to read is not a credential.
     */
    fun haToken(): String = (prefs.getString(HA_TOKEN, "") ?: "").trim()

    /** The `folder` sensor whose `file_list` is the route folder. */
    fun haRoutesSensor(): String =
        (prefs.getString(HA_ROUTES_SENSOR, "sensor.routes") ?: "sensor.routes")
            .trim().ifBlank { "sensor.routes" }

    /**
     * The listing file to read when there is no token, beside the `.gpx` files.
     *
     * A JSON array, and the same one `stride_gpx.py` already accepts — bare
     * names, or objects with a `file` key so a generated index can carry more
     * later:
     *
     *     ["rolling-loop-5k.gpx", "the-hill.gpx"]
     *
     * The trade this makes is worth saying out loud: it costs no credential and
     * it costs a line per route. Forgetting that line is the same shape of
     * failure as forgetting to run the sync script was — a route that exists
     * and does not appear, with nothing saying why. The settings screen names
     * what it found for that reason.
     */
    fun haIndex(): String =
        (prefs.getString(HA_INDEX, "index.json") ?: "index.json")
            .trim().trim('/').ifBlank { "index.json" }

    /** Where the files are under `www/`, matching `ha_gpx_path` in stride.conf. */
    fun haGpxPath(): String =
        (prefs.getString(HA_GPX_PATH, "treadmill/routes") ?: "treadmill/routes")
            .trim().trim('/').ifBlank { "treadmill/routes" }

    /**
     * Whether to go and look on start-up.
     *
     * On by default, but [routeFetchReady] is what actually decides: with no
     * URL and no token there is nothing to ask, and a console that has never
     * been told about Home Assistant should not spend its boot timing out.
     */
    fun routeFetch(): Boolean = prefs.getBoolean(ROUTE_FETCH, true)

    /**
     * Enough to go and look. The address, and nothing else required.
     *
     * The token is deliberately not part of this: without one the console reads
     * [haIndex] over `/local/`, which needs no credential — see [haToken].
     */
    fun routeFetchReady(): Boolean = routeFetch() && haUrl().isNotEmpty()

    fun brokerUri(): String =
        "${if (mqttTls()) "ssl" else "tcp"}://${mqttHost()}:${mqttPort()}"

    /**
     * First run only: adopt whatever was compiled in, then never look again.
     *
     * Moving the broker from `BuildConfig` to settings would otherwise take a
     * working console offline the moment it was updated — Home Assistant would
     * simply stop hearing from a treadmill that had been publishing happily for
     * a week, with nothing on screen to say why. That is a bad way to ship an
     * improvement.
     *
     * So `local.properties` becomes a *seed* rather than the source. A dev
     * build carries it in and this copies it to settings once, where it can
     * then be edited on the console like everything else. A public build has no
     * such keys, so `secret()` yields empty strings, nothing is seeded, and
     * Home Assistant stays off until somebody fills it in — which is the
     * behaviour that made the compiled-in version defensible in the first
     * place, now without shipping anyone's password.
     *
     * Keyed on a marker rather than on the fields being blank, so clearing the
     * broker on purpose is not undone at the next start-up.
     */
    fun seedFromBuildConfig() {
        if (prefs.getBoolean("seeded", false)) return
        prefs.edit().putBoolean("seeded", true).apply()

        val uri = BuildConfig.MQTT_BROKER
        if (uri.isBlank()) return

        // "tcp://host:1883" — the shape build.gradle.kts writes.
        val bare = uri.substringAfter("://")
        val host = bare.substringBefore(":")
        val port = bare.substringAfter(":", "1883").toIntOrNull() ?: 1883
        if (host.isBlank()) return

        prefs.edit()
            .putString(MQTT_HOST, host)
            .putInt(MQTT_PORT, port)
            .putString(MQTT_USER, BuildConfig.MQTT_USER)
            .putString(MQTT_PASS, BuildConfig.MQTT_PASS)
            .putBoolean(MQTT_TLS, uri.startsWith("ssl"))
            .putBoolean(HA_ENABLED, true)
            .apply()
    }

    /**
     * The same idea for the route folder, on a marker of its own.
     *
     * Its own marker and not `"seeded"`, which is the part worth reading. That
     * flag was set the first time this console ever started, so anything added
     * to [seedFromBuildConfig] afterwards would be skipped forever on exactly
     * the consoles that are already running — the ones that would most like a
     * new field filled in for them. A new seed needs a new marker, or it is not
     * a seed, it is a thing that works only on a fresh install.
     *
     * The URL alone is enough to be worth adopting: the `.gpx` files are served
     * unauthenticated, so a console with the address and no token can still be
     * pointed at a folder by hand. Both are adopted when both are there.
     */
    fun seedRoutesFromBuildConfig() {
        if (prefs.getBoolean("seeded_ha_routes", false)) return
        prefs.edit().putBoolean("seeded_ha_routes", true).apply()

        val url = BuildConfig.HA_URL.trim().trimEnd('/')
        if (url.isBlank()) return
        val e = prefs.edit().putString(HA_URL, url)
        if (BuildConfig.HA_TOKEN.isNotBlank()) e.putString(HA_TOKEN, BuildConfig.HA_TOKEN)
        e.apply()
    }

    // --- deck -----------------------------------------------------------------

    fun warmupMs(): Long = prefs.getInt(WARMUP_MIN, 2) * 60_000L
    fun cooldownMs(): Long = prefs.getInt(COOLDOWN_MIN, 2) * 60_000L
    fun warmupKph(): Double = prefs.getFloat(WARMUP_KPH, 2.0f).toDouble()

    /**
     * Whether the warm-up's time and distance count towards the workout.
     *
     * Off, which is what most people mean by "I walked for half an hour": the
     * warm-up is how you get on the belt, not part of what you came to do, and
     * a workout that opens at 2:00 and 180 m has already spent some of itself
     * before the first stride that counted.
     *
     * On is what this console did for its whole life before the setting
     * existed — one continuous clock from the moment the belt first moved.
     * Worth having, because it is the honest number if you are broadcasting
     * to something that expects distance only ever to go up. See beginWorkout
     * in MainActivity for what the two answers actually do.
     */
    fun carryWarmup(): Boolean = prefs.getBoolean(CARRY_WARMUP, false)

    /**
     * The pace a cool-down eases back to.
     *
     * Its own setting rather than [warmupKph], which is what the cool-down used
     * to borrow. The two are not the same decision: a warm-up speed is where you
     * want to *start* walking, and a cool-down speed is a deliberate amble that
     * wants to be slower than that. Sharing one number meant a console set up to
     * start at 3.1 mph also finished at 3.1 mph, which is not cooling down.
     *
     * The default is 3.2 km/h — 2.0 mph, near enough that the imperial stepper
     * lands on a round number.
     */
    fun cooldownKph(): Double = prefs.getFloat(COOLDOWN_KPH, 3.2f).toDouble()
    fun openLapMin(): Int = prefs.getInt(OPEN_LAP_MIN, 20)

    fun inclineStep(): Double =
        INCLINE_RATES[prefs.getString(INCLINE_RATE, "normal")]?.first ?: 1.0

    fun inclineEveryMs(): Long =
        INCLINE_RATES[prefs.getString(INCLINE_RATE, "normal")]?.second ?: 3000L

    // --- coach ----------------------------------------------------------------

    fun coachOn(): Boolean = prefs.getBoolean(COACH_ON, true)
    fun coachRemote(): Boolean = prefs.getBoolean(COACH_REMOTE, true)
    fun coachMilestones(): Boolean = prefs.getBoolean(COACH_MILESTONES, true)
    fun coachVoice(): String = prefs.getString(COACH_VOICE, "Supportive Friend")
        ?: "Supportive Friend"

    /** Multiplies every gap in [Coach]. Sparing talks about half as often. */
    fun coachGapScale(): Double = when (prefs.getString(COACH_TALK, "normal")) {
        "sparing" -> 2.0
        "chatty" -> 0.6
        else -> 1.0
    }

    // --- heart rate -----------------------------------------------------------

    fun hrSource(): String = prefs.getString(HR_SOURCE, "auto") ?: "auto"
    fun hrAddr(): String = prefs.getString(HR_ADDR, "") ?: ""
    fun hrName(): String = prefs.getString(HR_NAME, "") ?: ""

    fun saveStrap(addr: String, name: String) {
        prefs.edit().putString(HR_ADDR, addr).putString(HR_NAME, name).apply()
    }

    fun forgetStrap() {
        prefs.edit().remove(HR_ADDR).remove(HR_NAME).apply()
    }

    // --- display --------------------------------------------------------------

    fun sleepMs(): Long = prefs.getInt(SLEEP_MIN, 5) * 60_000L
    fun clock24(): Boolean = prefs.getBoolean(CLOCK_24, true)
    fun keepAwake(): Boolean = prefs.getBoolean(KEEP_AWAKE, true)

    fun brightness(): Float = when (prefs.getString(BRIGHTNESS, "normal")) {
        "dim" -> 0.35f
        "bright" -> 1.0f
        else -> 0.7f
    }

    // --- the whole thing, for the settings screen ------------------------------

    /**
     * One object the settings UI renders from, so the screen cannot drift from
     * the store by holding its own idea of a default.
     */
    fun json(): JSONObject = JSONObject()
        .put("people", JSONArray().apply { people().forEach { put(it.json()) } })
        .put(DEFAULT_WALKER, defaultWalker())
        .put(ALLOW_GUEST, allowGuest())
        .put(UNITS, units())
        .put(ROUTE_VIEW, routeView())
        .put(MAP_TILE_URL, mapTileUrl())
        .put(MAP_ATTRIBUTION, mapAttribution())
        .put("map_tiles_tag", mapTilesTag())
        .put(MAP_CACHE_MB, mapCacheMb())
        .put(HA_ENABLED, prefs.getBoolean(HA_ENABLED, false))
        .put(MQTT_HOST, mqttHost())
        .put(MQTT_PORT, mqttPort())
        .put(MQTT_USER, mqttUser())
        // Deliberately not the password.
        //
        // This object is handed to the settings page, and anything that can
        // read the page can read it — which on a debug build is anyone with
        // the DevTools socket. The screen only ever needed to know *whether*
        // a password is stored, so that is all it gets. The key is absent
        // rather than empty on purpose: a reader that wants the password now
        // gets `undefined` and fails loudly, where "" would quietly read as
        // "no password set". See field() in stride-settings.js for the other
        // half — an untouched box does not clear what it cannot see.
        .put("mqtt_pass_set", mqttPass().isNotEmpty())
        .put(MQTT_TLS, mqttTls())
        .put(MQTT_PREFIX, mqttPrefix())
        .put(HA_URL, haUrl())
        // Not the token, for the reason the password is not here either — it
        // is the same secret in the same place. `ha_token_set` is all the
        // screen ever needed, and the key for the token itself is absent
        // rather than empty so a reader that wants it fails loudly.
        .put("ha_token_set", haToken().isNotEmpty())
        .put(HA_ROUTES_SENSOR, haRoutesSensor())
        .put(HA_INDEX, haIndex())
        .put(HA_GPX_PATH, haGpxPath())
        .put(ROUTE_FETCH, routeFetch())
        .put(WARMUP_MIN, prefs.getInt(WARMUP_MIN, 2))
        .put(COOLDOWN_MIN, prefs.getInt(COOLDOWN_MIN, 2))
        .put(WARMUP_KPH, prefs.getFloat(WARMUP_KPH, 2.0f).toDouble())
        .put(COOLDOWN_KPH, prefs.getFloat(COOLDOWN_KPH, 3.2f).toDouble())
        .put(INCLINE_RATE, prefs.getString(INCLINE_RATE, "normal"))
        .put(OPEN_LAP_MIN, openLapMin())
        .put(CARRY_WARMUP, carryWarmup())
        .put(COACH_ON, coachOn())
        .put(COACH_TALK, prefs.getString(COACH_TALK, "normal"))
        .put(COACH_VOICE, coachVoice())
        .put(COACH_REMOTE, coachRemote())
        .put(COACH_MILESTONES, coachMilestones())
        .put(HR_SOURCE, hrSource())
        .put(HR_ADDR, hrAddr())
        .put(HR_NAME, hrName())
        .put(SLEEP_MIN, prefs.getInt(SLEEP_MIN, 5))
        .put(CLOCK_24, clock24())
        .put(BRIGHTNESS, prefs.getString(BRIGHTNESS, "normal"))
        .put(KEEP_AWAKE, keepAwake())

    /**
     * Save one value, typed by what the key expects rather than by what
     * JavaScript happened to send. A WebView bridge hands everything over as a
     * string, and `getInt` on a key written as a String throws at read time —
     * a long way from the settings screen that caused it.
     */
    fun set(key: String, value: String) {
        val e = prefs.edit()
        when (key) {
            ALLOW_GUEST, HA_ENABLED, MQTT_TLS, COACH_ON, COACH_REMOTE,
            COACH_MILESTONES, CLOCK_24, KEEP_AWAKE, CARRY_WARMUP,
            ROUTE_FETCH ->
                e.putBoolean(key, value == "true" || value == "1")

            MQTT_PORT, WARMUP_MIN, COOLDOWN_MIN, OPEN_LAP_MIN, SLEEP_MIN,
            MAP_CACHE_MB ->
                e.putInt(key, value.toIntOrNull() ?: 0)

            WARMUP_KPH ->
                e.putFloat(key, value.toFloatOrNull() ?: 2.0f)

            COOLDOWN_KPH ->
                e.putFloat(key, value.toFloatOrNull() ?: 3.2f)

            else -> e.putString(key, value)
        }
        e.apply()
    }

    /** Settings only. People, totals and history are not touched. */
    fun resetSettings() {
        val keep = prefs.getString(PEOPLE, null)
        val ui = prefs.getString(MainActivity.PREF_UI, MainActivity.DEFAULT_UI)
        prefs.edit().clear()
            .putString(PEOPLE, keep)
            .putString(MainActivity.PREF_UI, ui)
            .apply()
    }
}
