package dev.stride.hud

import android.util.Log
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence

/**
 * Carries treadmill telemetry to Home Assistant over MQTT, and coaching back.
 *
 * Uses HA's MQTT discovery so entities create themselves — no YAML to maintain.
 * All telemetry rides one retained JSON state topic; each discovery config picks
 * its field out with a value_template. That keeps publishes to one per poll
 * rather than one per metric.
 *
 * The return path exists because the console cannot reach a language model
 * itself: the WAN block is on, and its clock reads 2022 so TLS would fail even
 * if it were lifted. HA does the thinking and the speaking; this brings back the
 * words.
 */
class MqttPublisher(
    private var broker: String = "",
    private var user: String = "",
    private var pass: String = "",
    private var prefix: String = "stride",
) {

    /**
     * Topics, built from a configurable prefix rather than compiled in.
     *
     * These were `const val`s reading "stride/...". A prefix is the one piece
     * of the topic tree that belongs to whoever owns the broker: `stride/` may
     * already mean something else on somebody's, and two treadmills in one
     * house need to not be the same device. Everything under the prefix is
     * ours and stays fixed.
     */
    val stateTopic get() = "$prefix/treadmill/state"
    val availTopic get() = "$prefix/treadmill/availability"

    /** A coaching moment, on its way to HA to be turned into words. */
    val eventTopic get() = "$prefix/treadmill/event"

    /** Per-person workout records live under here, one device each. */
    val personTopic get() = "$prefix/person"

    /** The words coming back. Not retained — a line from last week's walk
     *  reappearing at the start of the next one would be worse than silence. */
    val coachTopic get() = "$prefix/coach/live"

    /**
     * Which of the console's interfaces to show. Publish a UI name here and
     * the console switches to it, so a walk can be set up from a phone before
     * anyone is standing on the belt.
     *
     * Retained state on the sibling topic, so an HA `select` can show which
     * one is up rather than guessing.
     */
    /** Home Assistant's person registry, published by ha/stride_persons.py. */
    val personsTopic get() = "$prefix/persons"

    val uiTopic get() = "$prefix/ui/set"
    val uiStateTopic get() = "$prefix/ui/state"

    /**
     * Routes converted from real outdoor walks, published by the iOS app via
     * `homeassistant/stride_health_webhook.py`. Retained, so a console that
     * boots with a network gets them from the broker whether or not Home
     * Assistant is up — and cached to disk by [Routes] for when it boots with
     * neither.
     *
     * Not under `treadmill/`: a route belongs to the household, not to this
     * machine, and a second treadmill on the same broker should see the same
     * ones.
     */
    val routesTopic get() = "$prefix/routes"

    /**
     * Apply settings changed on the console, without a restart.
     *
     * Tears the connection down first: Paho holds the broker URI for the life
     * of the client, so "reconnecting to a different broker" is a new client,
     * not a reconnect.
     */
    fun reconfigure(broker: String, user: String, pass: String, prefix: String) {
        close()
        this.broker = broker
        this.user = user
        this.pass = pass
        this.prefix = prefix
    }
    companion object {
        const val TAG = FitProConnection.TAG

        const val CLIENT_ID = "stride-treadmill"
        const val DEVICE_ID = "stride_treadmill"

        /** field key -> (display name, unit, device_class or null, icon or null) */
        private val SENSORS = listOf(
            // **Not** prefixed "Treadmill". Home Assistant builds an entity_id
            // from the device name plus the entity name, and this device is
            // called "Treadmill" — so "Treadmill Speed" produced
            // `sensor.treadmill_treadmill_speed`, the word twice, in every
            // entity, forever. Exactly the fault that was found and fixed on
            // the per-person device; this one had been sitting next to it the
            // whole time.
            Sensor("speed", "Speed", "km/h", "speed", null),
            Sensor("incline", "Incline", "%", null, "mdi:angle-acute"),
            Sensor("distance", "Distance", "m", "distance", null),
            Sensor("elapsed", "Elapsed", "s", "duration", null),
            Sensor("pulse", "Pulse", "bpm", null, "mdi:heart-pulse"),
            // The session's own figures, so the post-workout coach can talk
            // about the walk that just happened rather than only about the
            // outdoor walks Apple Health knows the heart rate for.
            Sensor("avgPulse", "Pulse Average", "bpm", null, "mdi:heart-pulse"),
            Sensor("maxPulse", "Pulse Max", "bpm", null, "mdi:heart-pulse"),
            Sensor("calories", "Calories", "kcal", null, "mdi:fire"),
            Sensor("mode", "Mode", null, null, "mdi:state-machine"),
            Sensor("workout", "Workout", null, null, "mdi:walk"),
        )
    }

    data class Sensor(
        val key: String,
        val name: String,
        val unit: String?,
        val deviceClass: String?,
        val icon: String?,
    )

    private var client: MqttClient? = null
    @Volatile var connected = false
        private set

    /** topic -> what to do with a payload that arrives on it. */
    private val handlers = LinkedHashMap<String, (String) -> Unit>()

    /**
     * Register interest in a topic. Call before [connect]; the subscription is
     * re-established on every reconnect, since a clean session forgets them.
     */
    fun onMessage(topic: String, handler: (String) -> Unit) {
        handlers[topic] = handler
    }

    /** Connect and publish discovery. Safe to call repeatedly. */
    fun connect(): String? {
        // An attempt that threw leaves its client assigned — see below, where
        // the client is deliberately stored before connecting. Building another
        // on top of it strands the first, and the retry loop that exists for a
        // broker which is not up yet does exactly this every few seconds until
        // it is. Closing a client that never connected is a no-op.
        try { client?.close() } catch (_: Exception) { }
        client = null
        return try {
            val c = MqttClient(broker, CLIENT_ID, MemoryPersistence())
            c.setCallback(object : MqttCallbackExtended {
                // Fires on the first connect and on every automatic reconnect,
                // which is the only place a clean session's subscriptions can
                // be put back.
                override fun connectComplete(reconnect: Boolean, serverURI: String) {
                    connected = true
                    subscribeAll()
                    if (reconnect) {
                        publishRaw(availTopic, "online", retained = true)
                        Log.i(TAG, "mqtt reconnected")
                    }
                }

                override fun connectionLost(cause: Throwable?) {
                    connected = false
                    Log.w(TAG, "mqtt connection lost: ${cause?.message}")
                }

                override fun messageArrived(topic: String, message: MqttMessage) {
                    val payload = String(message.payload)
                    // A handler throwing here would kill the MQTT thread and
                    // take telemetry down with it — coaching is never worth that.
                    try {
                        handlers[topic]?.invoke(payload)
                    } catch (e: Exception) {
                        Log.w(TAG, "mqtt handler for $topic failed: ${e.message}")
                    }
                }

                override fun deliveryComplete(token: IMqttDeliveryToken?) {}
            })

            val opts = MqttConnectOptions().apply {
                userName = user
                password = pass.toCharArray()
                isCleanSession = true
                isAutomaticReconnect = true
                connectionTimeout = 5
                keepAliveInterval = 30
                // Tell HA we're gone if the app dies or the console sleeps.
                setWill(availTopic, "offline".toByteArray(), 1, true)
            }
            // Assigned before connecting: connectComplete fires from inside
            // connect() and needs the client to subscribe with.
            client = c
            c.connect(opts)
            connected = true
            subscribeAll()
            publishDiscovery()
            publishRaw(availTopic, "online", retained = true)
            Log.i(TAG, "mqtt connected to $broker")
            null
        } catch (e: Exception) {
            connected = false
            Log.w(TAG, "mqtt connect failed: ${e.message}")
            e.message ?: "unknown error"
        }
    }

    private fun subscribeAll() {
        for (topic in handlers.keys) {
            try {
                client?.subscribe(topic, 0)
                Log.i(TAG, "mqtt subscribed to $topic")
            } catch (e: Exception) {
                Log.w(TAG, "mqtt subscribe to $topic failed: ${e.message}")
            }
        }
    }

    /** One retained discovery config per sensor, all under a single HA device. */
    private fun publishDiscovery() {
        val device = """
            "device":{"identifiers":["$DEVICE_ID"],"name":"Treadmill",
            "manufacturer":"NordicTrack","model":"C1750","sw_version":"STRIDE"}
        """.trimIndent().replace("\n", "")

        for (s in SENSORS) {
            val parts = ArrayList<String>()
            parts += "\"name\":\"${s.name}\""
            parts += "\"unique_id\":\"${DEVICE_ID}_${s.key}\""
            parts += "\"state_topic\":\"$stateTopic\""
            parts += "\"availability_topic\":\"$availTopic\""
            parts += "\"value_template\":\"{{ value_json.${s.key} }}\""
            s.unit?.let { parts += "\"unit_of_measurement\":\"$it\"" }
            s.deviceClass?.let { parts += "\"device_class\":\"$it\"" }
            s.icon?.let { parts += "\"icon\":\"$it\"" }
            // Speed/incline are instantaneous; totals shouldn't be state_class total
            // because the board resets them per workout.
            // Distance and calories climb through a session and reset to zero at
            // the next — that is total_increasing, and it is what lets HA sum
            // them into daily/weekly/monthly totals.
            when (s.key) {
                "distance", "calories" -> parts += "\"state_class\":\"total_increasing\""
                "mode", "workout" -> {}
                else -> parts += "\"state_class\":\"measurement\""
            }
            parts += device

            val topic = "homeassistant/sensor/$DEVICE_ID/${s.key}/config"
            publishRaw(topic, "{${parts.joinToString(",")}}", retained = true)
        }
        Log.i(TAG, "mqtt discovery published for ${SENSORS.size} sensors")
    }

    /**
     * Publish one telemetry frame — the same snapshot the HUD is showing, so
     * `mode` is our session state rather than the board's. The board never
     * leaves `running`, which had HA convinced the treadmill was in use all day.
     */
    fun publishState(s: Snapshot) {
        if (!connected) return
        publishRaw(stateTopic, s.toJson(), retained = true)
    }

    /** Which interface the console is currently showing. Retained: HA should
     *  know the answer without waiting for the next switch. */
    fun publishUi(name: String) {
        if (!connected) return
        publishRaw(uiStateTopic, name, retained = true)
    }

    /** Entity ids have to be stable and boring, whatever someone is called. */
    private fun slug(name: String) =
        name.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')

    /** People whose discovery configs have already gone out this run. */
    private val announced = HashSet<String>()

    /**
     * Record a workout against the person who did it.
     *
     * The live telemetry topic stays what it is — there is one treadmill and it
     * has one speed — but a *workout* belongs to whoever was standing on it. So
     * each person gets their own HA device, and only the person currently
     * walking has their state published; everyone else keeps their last value.
     *
     * Distance, time and calories are `total_increasing`: they climb through a
     * session and drop to zero at the start of the next, which is exactly the
     * shape HA needs to sum them into daily, weekly and monthly totals per
     * person.
     *
     * Discovery is published the first time someone actually walks rather than
     * for a fixed list, so the console never invents entities for people who
     * have never used it.
     */
    fun publishPerson(person: Settings.Person, s: Snapshot) {
        if (!connected) return
        val id = person.id
        if (id.isEmpty()) return
        if (announced.add(id)) publishPersonDiscovery(person)

        val payload = buildString {
            append("{")
            append("\"distance\":${"%.0f".format(s.distance)},")
            append("\"elapsed\":${"%.0f".format(s.elapsed)},")
            append("\"calories\":${"%.0f".format(s.calories)},")
            append("\"workout\":\"${s.workout}\",")
            append("\"plan\":\"${if (s.plan.isEmpty()) "casual" else s.plan}\",")
            // Carried in the payload so Home Assistant can join a walk to its
            // person without anyone having to match on a slugged name. Empty
            // for somebody who exists only on the console.
            append("\"ha_person\":\"${person.haPerson}\"")
            append("}")
        }
        publishRaw("$personTopic/$id/state", payload, retained = true)
    }

    private fun publishPersonDiscovery(person: Settings.Person) {
        val who = person.name
        val id = person.id
        val deviceId = "stride_person_$id"
        val device = """
            "device":{"identifiers":["$deviceId"],"name":"STRIDE — $who",
            "manufacturer":"STRIDE","model":"Treadmill workouts"}
        """.trimIndent().replace("\n", "")

        // **Not** prefixed with the person's name. Home Assistant builds an
        // entity_id from the device name and the entity name, so "STRIDE —
        // Sam" plus "Sam Treadmill Distance" produced
        // `sensor.stride_<person>_<person>_treadmill_distance` — the name twice, in
        // every entity, forever. The device already says who this is.
        val sensors = listOf(
            Sensor("distance", "Treadmill Distance", "m", "distance", null),
            Sensor("elapsed", "Treadmill Time", "s", "duration", null),
            Sensor("calories", "Treadmill Calories", "kcal", null, "mdi:fire"),
            Sensor("plan", "Last Walk", null, null, "mdi:map-marker-path"),
        )
        for (sensor in sensors) {
            val parts = ArrayList<String>()
            parts += "\"name\":\"${sensor.name}\""
            parts += "\"unique_id\":\"${deviceId}_${sensor.key}\""
            parts += "\"state_topic\":\"$personTopic/$id/state\""
            parts += "\"value_template\":\"{{ value_json.${sensor.key} }}\""
            sensor.unit?.let { parts += "\"unit_of_measurement\":\"$it\"" }
            sensor.deviceClass?.let { parts += "\"device_class\":\"$it\"" }
            sensor.icon?.let { parts += "\"icon\":\"$it\"" }
            if (sensor.key != "plan") parts += "\"state_class\":\"total_increasing\""
            parts += device
            publishRaw("homeassistant/sensor/$deviceId/${sensor.key}/config",
                       "{${parts.joinToString(",")}}", retained = true)
        }
        Log.i(TAG, "mqtt discovery published for $who")
    }

    /**
     * Announce a coaching moment. Not retained: this is a thing that happened at
     * an instant, and HA either hears it now or the moment has passed.
     */
    fun publishEvent(json: String) {
        if (!connected) {
            Log.i(TAG, "coach: mqtt down, event not sent")
            return
        }
        publishRaw(eventTopic, json, retained = false)
    }

    private fun publishRaw(topic: String, payload: String, retained: Boolean = false) {
        try {
            val msg = MqttMessage(payload.toByteArray()).apply {
                qos = 0
                isRetained = retained
            }
            client?.publish(topic, msg)
        } catch (e: Exception) {
            Log.w(TAG, "mqtt publish failed on $topic: ${e.message}")
            connected = false
        }
    }

    fun close() {
        try {
            publishRaw(availTopic, "offline", retained = true)
            client?.disconnect()
            client?.close()
        } catch (_: Exception) {
        }
        connected = false
        client = null
    }
}
