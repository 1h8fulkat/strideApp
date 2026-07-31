package dev.stride.hud

import android.annotation.SuppressLint
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject
import kotlin.concurrent.thread

/**
 * STRIDE HUD.
 *
 * Kotlin owns the treadmill (USB + FitPro + MQTT) and the workout session; the
 * UI is an HTML page in a WebView so it can be designed and iterated in a
 * browser. State is pushed to JS at poll rate; button presses come back over
 * the `Stride` bridge.
 *
 * The physical safety key remains the stop of record.
 */
class MainActivity : Activity() {

    companion object {
        const val TAG = FitProConnection.TAG
        const val ACTION_USB_PERMISSION = "dev.stride.hud.USB_PERMISSION"
        const val POLL_MS = 200L
        const val MQTT_EVERY_MS = 1000L

        /**
         * The selectable interfaces, in the order the settings screen shows them.
         *
         * Each is one document in `assets/ui/`, owning its whole DOM and CSS and
         * implementing the same two entry points — `window.render(state)` and
         * `window.plan(p)`. They are not themes: switching one for another is
         * expected to change what the screen is made of, not just its colours.
         *
         * Anything named here but not yet present in the APK falls back to
         * [DEFAULT_UI], so this list can run ahead of the files.
         */
        val UIS = listOf("original", "ember", "cluster", "daylight", "pacer")
        const val DEFAULT_UI = "original"

        const val PREFS = "stride"
        const val PREF_UI = "ui"

        /** How briskly a ramp winds the belt up. */
        const val RAMP_KPH_PER_SEC = 1.0

        /**
         * How briskly the belt is allowed to wind *down* to a standstill.
         *
         * The first full guided walk ended by cutting the belt from 4.2 km/h to
         * zero the instant the plan expired, which was enough to catch someone
         * off guard — at a run it would have been enough to put them on the
         * floor. Every automatic finish now decelerates instead. Quick, but not
         * sudden: 2 km/h per second brings a brisk walk to a stop in about two
         * seconds and a hard run in five.
         *
         * Deliberately *not* applied to the STOP button. That is a deliberate
         * act by someone expecting it, and it needs to be immediate.
         */
        const val DECEL_KPH_PER_SEC = 2.0

        /**
         * Below this the belt counts as stopped. Not zero: the board reports
         * small non-zero speeds while the belt coasts to rest, and demanding
         * an exact zero would nag forever.
         */
        const val STOPPED_KPH = 0.3

        /** How close the derived speed must sit to the target before the readout
         *  stops following it — see accumulate(). */
        const val SETTLE_BAND_KPH = 0.45

        /**
         * Fallbacks only. The live values are in [Settings] — warm-up length,
         * cool-down length and warm-up speed are all set on the console now,
         * and these are what a fresh install starts from.
         */
        const val WARMUP_KPH = 2.0
        const val WARMUP_MS = 2 * 60 * 1000L
        const val COOLDOWN_MS = 2 * 60 * 1000L

        /** Window over which the odometer is differentiated — see beltSpeed(). */
        const val WINDOW_MS = 4000L
        const val MIN_SPAN_MS = 1500L

        /**
         * How fast a guided walk is allowed to move the deck.
         *
         * One percent at a time, no more often than every three seconds, so the
         * biggest jump in any template — flat to nine percent — takes about half
         * a minute to arrive rather than landing under someone mid-stride.
         */
        const val INCLINE_STEP = 1.0
        const val INCLINE_EVERY_MS = 3000L

        /**
         * How long one lap of an open-ended route is.
         *
         * "Open" means walk until you have had enough, and the ground repeats
         * rather than being invented — so a template becomes a circuit and you
         * go round it. Twenty minutes is long enough to have a shape and short
         * enough that you actually come round again; at thirty most walks would
         * never see the same hill twice, which is the same as not looping.
         */
        const val OPEN_LOOP_MIN = 20

        val TELEMETRY = listOf(
            FitPro.Field.ACTUAL_KPH,
            FitPro.Field.ACTUAL_INCLINE,
            FitPro.Field.WORKOUT_MODE,
            FitPro.Field.DISTANCE,
            FitPro.Field.CURRENT_TIME,
            FitPro.Field.PULSE,
            FitPro.Field.CALORIES,
        )
        val LIMITS = listOf(
            FitPro.Field.MIN_KPH, FitPro.Field.MAX_KPH,
            FitPro.Field.MIN_GRADE, FitPro.Field.MAX_GRADE,
        )

        /** KPH/GRADE are the board's *targets* — read so physical buttons don't desync us. */
        val READS = listOf(FitPro.Field.KPH, FitPro.Field.GRADE) + TELEMETRY

        /** Candidate sources for a real belt speed — see probeSpeed(). */
        val SPEED_PROBE = listOf(FitPro.Field.RPM, FitPro.Field.ACTUAL_KPH)
    }

    private lateinit var web: WebView

    private val prefs by lazy { getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    private val conn by lazy { FitProConnection(this) }
    private val mqtt = MqttPublisher()
    private val cfg by lazy { Settings(this) }
    private val strap by lazy { HeartRate(this) }
    private val voice by lazy { CoachVoice(this) }
    private val coach = Coach()

    @Volatile private var running = false
    @Volatile private var deviceId: Byte = FitPro.Dev.TREADMILL
    @Volatile private var targetKph = 0.0
    @Volatile private var targetGrade = 0.0
    @Volatile private var fanState = 0
    @Volatile private var pendingWrite: Map<FitPro.Field, Double>? = null

    /** Pace at the moment of pausing, and the speed the resume ramp is climbing
     *  towards. Zero for either means no ramp is in progress. */
    @Volatile private var pausedKph = 0.0
    @Volatile private var rampTo = 0.0
    /** "warmup", "resuming" or "cooldown" while a ramp is in flight. */
    @Volatile private var rampReason = ""

    /** Deadline for a timed phase (warm-up / cool-down), on the elapsedRealtime clock. */
    @Volatile private var phaseEndsAt = 0L

    // --- session (ours, not the board's) ------------------------------------
    @Volatile private var session = Session.WELCOME
    @Volatile private var workout = "none"
    @Volatile private var dmk = false

    /**
     * Whose walk this is. Set from the welcome screen, defaulting to whoever
     * Settings names — which is nobody on a fresh install, so the screen asks.
     */
    @Volatile private var walker = ""

    // --- guided walk ---------------------------------------------------------
    /** Empty on a casual walk. Set once at START and never rewritten mid-walk. */
    @Volatile private var planName = ""
    /**
     * The route being walked, if this is a route rather than a template.
     *
     * Held alongside `planSteps` rather than replacing it: the HUD's sparkline
     * and segment counter read the steps, so a route fills both — the steps for
     * anything that wants to draw the ground, and this for the thing that
     * decides which part of it you are standing on.
     */
    @Volatile private var route: Route? = null

    @Volatile private var planSteps: List<Plan.Step> = emptyList()
    @Volatile private var stepIndex = -1

    /**
     * An open-ended route: walk until you decide to stop, and the ground goes
     * round again rather than being invented. The template becomes a circuit
     * one [OPEN_LOOP_MIN] lap long, and nothing ever ends the walk except the
     * person on it.
     */
    @Volatile private var planLoops = false
    @Volatile private var planLap = 1
    /** Plan time, wrapped into the current lap. Equals elapsed when not looping. */
    @Volatile private var planElapsed = 0.0

    /**
     * The pace Sam settled at during the opening segment. Every suggestion is
     * relative to this, so a plan adapts to the day instead of assuming a number
     * that was true whenever the template was written.
     */
    @Volatile private var baselineKph = 0.0

    /**
     * Cleared the moment he touches INCLINE, and only restored at the next
     * segment. A plan that argues with the person on the treadmill about the
     * hill they are standing on is a plan that feels possessed.
     */
    @Volatile private var inclineAuto = false
    private var lastInclineMoveAt = 0L

    /** The coach's closing line, asked for near the end and shown on the summary. */
    @Volatile private var summaryLine = ""

    /** Its audio, held back until the summary is actually on screen. */
    @Volatile private var summaryUrl = ""
    @Volatile private var summarySpoken = false

    /** Last speed the *board* reported, as opposed to what we asked for. */
    @Volatile private var lastActualKph = 0.0

    /** How many times we have had to re-command a stop. Reset when it takes. */
    @Volatile private var stopNags = 0

    /** True while the belt is winding down towards an automatic finish. */
    @Volatile private var stopping = false

    /** Wall time is unusable here — the console's clock is years out and may
     *  jump if NTP ever reaches it. elapsedRealtime() only ever moves forward. */
    @Volatile private var activeSince = 0L
    @Volatile private var accumulatedMs = 0L

    /** The board's counters are lifetime-ish, so a session is a delta from
     *  wherever they happened to be when START was pressed. */
    @Volatile private var armBaseline = false
    @Volatile private var baseDistance = 0.0
    @Volatile private var baseCalories = 0.0
    @Volatile private var sessionDistance = 0.0
    @Volatile private var sessionCalories = 0.0

    // Rolling stats for the summary screen.
    @Volatile private var maxSpeed = 0.0
    @Volatile private var maxIncline = 0.0
    // Written by the poll thread, cleared from the WebView thread on START.
    @Volatile private var speedSum = 0.0
    @Volatile private var inclineSum = 0.0
    @Volatile private var samples = 0

    @Volatile private var maxKph = 19.0
    @Volatile private var minKph = 1.6
    @Volatile private var maxGrade = 12.0
    @Volatile private var minGrade = -3.0

    // Speed-probe bookkeeping — see probeSpeed().
    /** Last board mode we logged, so transitions are visible without spam. */
    @Volatile private var lastBoardMode: Int? = null

    /** Sticky safety-key alarm — see accumulate(). Cleared only by acknowledging. */
    @Volatile private var dmkLatched = false

    private var missingSpeedPolls = 0
    private var speedProbed = false

    // Odometer samples the belt speed is differentiated from — see beltSpeed().
    // Poll thread only.
    private val distanceTrail = ArrayDeque<Pair<Long, Double>>()
    private var smoothedKph = 0.0

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_USB_PERMISSION) return
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            if (granted && device != null) start(device) else Log.w(TAG, "usb permission denied")
        }
    }

    /** Exposed to the page as `Stride`. Every call lands on a WebView thread. */
    inner class Bridge {

        /**
         * Welcome screen → straight into a warm-up, the way iFit did it:
         * choosing a workout *is* starting it, and the belt eases up to
         * [WARMUP_KPH] rather than waiting for a separate START press.
         *
         * The belt moves as a direct result of this tap, so the welcome screen
         * says so.
         */
        @JavascriptInterface fun choose(type: String) {
            if (dmk) return
            workout = type
            clearPlan()
            resetSession()
            session = Session.WARMUP
            activeSince = SystemClock.elapsedRealtime()
            phaseEndsAt = activeSince + cfg.warmupMs()
            targetKph = 0.0
            rampTo = cfg.warmupKph()
            rampReason = "warmup"
            pendingWrite = mapOf(FitPro.Field.WORKOUT_MODE to FitPro.Mode.RUNNING.toDouble())
            Log.i(TAG, "workout chosen: $type — warming up to ${cfg.warmupKph()} km/h")
        }

        /**
         * Start a guided walk.
         *
         * Goes straight to ACTIVE rather than through the two-minute WARMUP
         * phase: every template opens with its own flat settle segment, and
         * having both would mean warming up twice. The plan's stated duration is
         * therefore the whole walk, closing segment included, which is what a
         * duration selector implies.
         */
        /**
         * @param minutes  the walk's length, or **0 for an open-ended route** —
         *                 a [OPEN_LOOP_MIN] circuit walked until STOP, with the
         *                 hills coming round again rather than running out.
         */
        @JavascriptInterface fun chooseGuided(templateId: String, minutes: Int) {
            if (dmk) return
            val template = Plan.byId(templateId) ?: run {
                Log.w(TAG, "no such template: $templateId")
                return
            }
            planLoops = minutes <= 0
            planLap = 1
            planElapsed = 0.0
            // Clamped against what this board actually reports, not what the
            // template hoped for.
            planSteps = Plan.resolve(template,
                if (planLoops) cfg.openLapMin() else minutes, minGrade, maxGrade)
            planName = template.name
            stepIndex = -1
            baselineKph = 0.0
            inclineAuto = true
            lastInclineMoveAt = 0L

            workout = "walk"
            resetSession()
            session = Session.ACTIVE
            activeSince = SystemClock.elapsedRealtime()
            phaseEndsAt = 0L
            targetKph = 0.0
            targetGrade = 0.0
            rampTo = cfg.warmupKph()
            rampReason = "warmup"
            pendingWrite = mapOf(
                FitPro.Field.GRADE to 0.0,
                FitPro.Field.WORKOUT_MODE to FitPro.Mode.RUNNING.toDouble(),
            )
            Log.i(TAG, "guided walk: ${template.name}, " +
                    (if (planLoops) "open (${cfg.openLapMin()} min circuit)" else "$minutes min") +
                    ", ${planSteps.size} segments, grade clamped to $minGrade..$maxGrade")
            // The ground is fixed for the whole walk — one lap of it, if this is
            // a circuit — so it goes over once rather than riding along with
            // every frame.
            pushPlan()
        }

        /**
         * The routes cached on this console, for the picker.
         *
         * Read from disk at boot, so this answers whether or not there is a
         * network — which is the whole reason [Routes] writes a file.
         */
        @JavascriptInterface fun routes(): String = routes.json()

        /**
         * Start a walk on a recorded route.
         *
         * No `minutes`, unlike [chooseGuided], and that is deliberate rather
         * than an omission: a route has its own length. Cutting it to fit a
         * time slot means never reaching the summit, which is the reason for
         * walking it again.
         */
        @JavascriptInterface fun chooseRoute(id: String) {
            if (dmk) return
            val r = routes.byId(id) ?: run {
                Log.w(TAG, "no such route: $id")
                return
            }

            route = r
            planLoops = false
            planLap = 1
            planElapsed = 0.0
            // Steps carry the same ground so the HUD can draw it, expressed in
            // metres. Nothing reads them as seconds while a route is active —
            // routeTick owns the position — but the sparkline and the segment
            // counter both want the shape.
            planSteps = r.segments.map {
                Plan.Step(startSec = it.startM, endSec = it.endM,
                          incline = it.incline.coerceIn(minGrade, maxGrade),
                          paceDelta = 0.0, label = r.name, note = "")
            }
            planName = r.name
            stepIndex = -1
            baselineKph = 0.0
            inclineAuto = true
            lastInclineMoveAt = 0L

            workout = "walk"
            resetSession()
            session = Session.ACTIVE
            activeSince = SystemClock.elapsedRealtime()
            phaseEndsAt = 0L
            targetKph = 0.0
            targetGrade = 0.0
            rampTo = cfg.warmupKph()
            rampReason = "warmup"
            pendingWrite = mapOf(
                FitPro.Field.GRADE to 0.0,
                FitPro.Field.WORKOUT_MODE to FitPro.Mode.RUNNING.toDouble(),
            )
            Log.i(TAG, "route: ${r.name}, ${"%.2f".format(r.distanceM / 1000)} km, " +
                    "${"%.0f".format(r.climbM)} m climb, ${r.segments.size} segments")
            pushPlan()
        }

        /** Straight to the workout proper, keeping whatever pace is set. */
        @JavascriptInterface fun skipWarmup() {
            if (session != Session.WARMUP) return
            session = Session.ACTIVE
            phaseEndsAt = 0L
            Log.i(TAG, "warm-up skipped")
        }

        /** Cool-down is the last thing standing between here and the summary. */
        @JavascriptInterface fun skipCooldown() {
            if (session != Session.COOLDOWN) return
            requestFinish()
        }

        /** Belt to a halt, timer held. The board's own Pause is the legal exit
         *  from Running — writing Idle while running is silently ignored. */
        @JavascriptInterface fun pause() {
            if (Session.isMoving(session)) {
                accumulatedMs += SystemClock.elapsedRealtime() - activeSince
                session = Session.PAUSED
                phaseEndsAt = 0L
            }
            // Remember the pace so RESUME can climb back to it.
            if (targetKph > 0.0) pausedKph = targetKph
            rampTo = 0.0
            rampReason = ""
            targetKph = 0.0
            pendingWrite = mapOf(
                FitPro.Field.KPH to 0.0,
                FitPro.Field.WORKOUT_MODE to FitPro.Mode.PAUSE.toDouble(),
            )
        }

        /**
         * Pressing RESUME means "I want to keep going", so getting back to pace
         * shouldn't be a dozen taps. But it must not lurch either — the belt
         * starts from a standstill and climbs to the old speed at [RAMP_KPH_PER_SEC],
         * driven from the poll loop. Touching SPEED cancels it.
         */
        @JavascriptInterface fun resume() {
            if (dmk) return
            if (session != Session.PAUSED) return
            session = Session.ACTIVE
            activeSince = SystemClock.elapsedRealtime()
            targetKph = 0.0
            rampTo = if (pausedKph > 0.0) pausedKph else cfg.warmupKph()
            rampReason = "resuming"
            pendingWrite = mapOf(FitPro.Field.WORKOUT_MODE to FitPro.Mode.RUNNING.toDouble())
            Log.i(TAG, "resuming — ramping back to ${"%.1f".format(rampTo)} km/h")
        }

        /**
         * "End workout" doesn't stop dead — it eases down. The belt ramps back
         * to walking pace, the deck flattens, and the clock keeps running for
         * [COOLDOWN_MS]. SKIP goes straight to the summary.
         */
        @JavascriptInterface fun end() {
            if (session == Session.SUMMARY) return
            // Ending from a pause means the belt is already stopped. Running a
            // cool-down from there spins it back up to 2 km/h only to walk it
            // down again, which is disconcerting and faintly alarming — the
            // workout was over the moment STOP was pressed.
            if (session == Session.PAUSED) {
                Log.i(TAG, "ended from a pause — no cool-down, belt already stopped")
                clearPlan()
                finishWorkout()
                return
            }
            val now = SystemClock.elapsedRealtime()
            // Coming out of a pause the clock is already stopped; restart it so
            // the cool-down is counted like any other part of the workout.
            if (!Session.isMoving(session)) activeSince = now
            session = Session.COOLDOWN
            phaseEndsAt = now + cfg.cooldownMs()
            pausedKph = 0.0
            rampTo = cfg.warmupKph()
            rampReason = "cooldown"
            // Put the machine back how you'd want to find it. Incline is the one
            // setting that persists otherwise, and starting the next walk on
            // yesterday's hill is a nasty surprise.
            targetGrade = 0.0
            pendingWrite = mapOf(
                FitPro.Field.GRADE to 0.0,
                FitPro.Field.WORKOUT_MODE to FitPro.Mode.RUNNING.toDouble(),
            )
            Log.i(TAG, "cooling down for ${cfg.cooldownMs() / 1000}s")
        }

        /**
         * Summary → welcome.
         *
         * The belt is commanded to a stop here as well as at the end of the
         * workout. It should already be stopped, and on 31 July it was not —
         * the console went back to the welcome screen with the belt still
         * running. `enforceStopped` is the thing that actually guarantees it;
         * this is the cheap second attempt at the moment the screen changes.
         */
        @JavascriptInterface fun home() {
            session = Session.WELCOME
            workout = "none"
            summaryLine = ""
            summaryUrl = ""
            summarySpoken = false
            clearPlan()
            resetSession()
            pendingWrite = mapOf(
                FitPro.Field.KPH to 0.0,
                FitPro.Field.WORKOUT_MODE to FitPro.Mode.IDLE.toDouble(),
            )
        }

        @JavascriptInterface fun speed(delta: Double) {
            if (!Session.isMoving(session) || dmk) return
            rampTo = 0.0; rampReason = ""   // manual control abandons the ramp
            var next = targetKph + delta
            // Below the machine's minimum there is no "slow", only stopped —
            // so stepping down out of the bottom of the range means stop, and
            // stepping up out of zero jumps straight to the minimum.
            next = if (next < minKph) { if (delta > 0) minKph else 0.0 } else next
            targetKph = next.coerceIn(0.0, maxKph)
            pendingWrite = mapOf(FitPro.Field.KPH to targetKph)
        }

        /**
         * Take the coach's suggested pace, by tapping it.
         *
         * The suggestion was previously read-only — "try 6.8 km/h" and then you
         * held the + button until you got there. Accepting an offer should be
         * one tap.
         *
         * Still a suggestion: this only runs because somebody touched it. The
         * plan never sets the belt speed on its own, which is the rule the
         * whole guided walk is built on.
         */
        @JavascriptInterface fun setSpeed(kph: Double) {
            if (!Session.isMoving(session) || dmk) return
            rampTo = 0.0; rampReason = ""   // as with any manual change
            targetKph = if (kph < minKph) 0.0 else kph.coerceIn(0.0, maxKph)
            pendingWrite = mapOf(FitPro.Field.KPH to targetKph)
            Log.i(TAG, "pace: took the suggestion, ${"%.1f".format(targetKph)} km/h")
        }

        /** Incline has no interlock — the board services it in any state. */
        @JavascriptInterface fun incline(delta: Double) {
            // Touching the hill takes it back from the plan until the next
            // segment. Same rule as speed abandoning its ramp: the person on the
            // treadmill outranks the schedule.
            if (inclineAuto) {
                inclineAuto = false
                Log.i(TAG, "guided: incline taken over by hand for this segment")
            }
            targetGrade = (targetGrade + delta).coerceIn(minGrade, maxGrade)
            pendingWrite = mapOf(FitPro.Field.GRADE to targetGrade)
        }

        /** Off → Low → Medium → High → Auto → Off */
        @JavascriptInterface fun fan() {
            fanState = (fanState + 1) % 5
            pendingWrite = mapOf(FitPro.Field.FAN_STATE to fanState.toDouble())
        }

        @JavascriptInterface fun setFan(level: Int) {
            fanState = level.coerceIn(0, 4)
            pendingWrite = mapOf(FitPro.Field.FAN_STATE to fanState.toDouble())
        }

        @JavascriptInterface fun setWalker(name: String) {
            walker = name
            Log.i(TAG, "walker: $name")
        }

        /** "I've put the key back." If it is still out, the next poll re-raises. */
        @JavascriptInterface fun ackDmk() {
            dmkLatched = false
        }

        /**
         * Dismiss the coach: shut it up mid-sentence and drop anything still in
         * flight. Silencing the speaker is the point of the tap — clearing the
         * words while the audio played on would be the worst of both.
         */
        @JavascriptInterface fun hushCoach() {
            voice.stop()
            coach.hush()
        }

        /**
         * Switch to another interface.
         *
         * Reloads the WebView rather than swapping the DOM about. Simpler, and a
         * 2 GB device would much rather parse one document than hold five. The
         * session survives it untouched — Kotlin owns all of that and repaints
         * the new page at the next poll, 200 ms later — and [onPageFinished]
         * re-sends the plan, which is the one thing that goes over only once.
         *
         * Refused while the belt is moving. The reload takes a beat, and during
         * that beat there is no STOP button on a running treadmill.
         */
        @JavascriptInterface fun setUi(name: String) = switchUi(name)

        @JavascriptInterface fun currentUi(): String = chosenUi()

        /** Only the ones actually in this build, so the settings screen cannot
         *  offer a blank page. */
        @JavascriptInterface fun availableUis(): String =
            org.json.JSONArray(available()).toString()

        // --- settings -------------------------------------------------------
        //
        // The settings screen is one shared document (assets/ui/stride-settings.js)
        // rendered into whichever interface is up, so all of this is read and
        // written through here rather than through five copies of it.

        @JavascriptInterface fun settingsJson(): String = cfg.json().toString()

        /**
         * Who Home Assistant knows about, minus anyone already added here.
         *
         * Filtered on the console rather than in the settings screen so the
         * "already added" test uses the same `haPerson` link the rest of the
         * app does, instead of matching names in JavaScript.
         */
        @JavascriptInterface fun haPeople(): String {
            val linked = cfg.people().map { it.haPerson }.filter { it.isNotEmpty() }.toSet()
            val taken = cfg.people().map { it.name.lowercase() }.toSet()
            val out = JSONArray()
            try {
                val arr = JSONArray(haPersons)
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    if (o.optString("entity_id") in linked) continue
                    if (o.optString("first").lowercase() in taken) continue
                    out.put(o)
                }
            } catch (e: Exception) {
                Log.w(TAG, "persons: cannot parse (${e.message})")
            }
            return out.toString()
        }

        /** Everything arrives as a string; [Settings.set] types it by key. */
        @JavascriptInterface fun saveSetting(key: String, value: String) {
            cfg.set(key, value)
            Log.i(TAG, "settings: $key = ${if (key == Settings.MQTT_PASS) "***" else value}")
            applySetting(key)
        }

        @JavascriptInterface fun addPerson(name: String): String {
            cfg.addPerson(name)?.let { Log.i(TAG, "settings: added ${it.name} (${it.id})") }
            return cfg.json().toString()
        }

        /** Add somebody from Home Assistant, linked rather than merely named. */
        @JavascriptInterface fun addHaPerson(entityId: String, first: String): String {
            cfg.addPerson(first, entityId)?.let {
                Log.i(TAG, "settings: added ${it.name} linked to $entityId")
            }
            return cfg.json().toString()
        }

        @JavascriptInterface fun removePerson(name: String): String {
            cfg.savePeople(cfg.people().filterNot { it.name == name })
            // Whoever is mid-walk keeps walking; only the list changed.
            Log.i(TAG, "settings: removed $name")
            return cfg.json().toString()
        }

        @JavascriptInterface fun setPersonFlag(name: String, flag: String, on: Boolean): String {
            cfg.savePeople(cfg.people().map {
                if (it.name != name) it
                else if (flag == "coached") it.copy(coached = on) else it.copy(publish = on)
            })
            return cfg.json().toString()
        }

        // --- heart rate strap ---
        @JavascriptInterface fun hrScan() {
            ensureScanPermission()
            strap.scan()
        }

        @JavascriptInterface fun hrFound(): String = JSONArray().apply {
            strap.found().forEach {
                put(JSONObject().put("address", it.address)
                    .put("name", it.name).put("rssi", it.rssi))
            }
        }.toString()

        @JavascriptInterface fun hrPair(address: String, name: String) {
            strap.stopScan()
            cfg.saveStrap(address, name)
            strap.connect(address)
            Log.i(TAG, "hr: pairing with $name")
        }

        @JavascriptInterface fun hrForget() {
            strap.disconnect()
            cfg.forgetStrap()
            Log.i(TAG, "hr: strap forgotten")
        }

        @JavascriptInterface fun hrStatus(): String = JSONObject()
            .put("available", strap.available)
            .put("scanning", strap.scanning)
            .put("connected", strap.connected)
            .put("bpm", strap.bpm())
            .put("battery", strap.battery)
            .put("name", cfg.hrName())
            .put("address", cfg.hrAddr())
            .toString()

        /**
         * Connect with whatever is currently saved and say what happened.
         *
         * Runs off the UI thread — Paho's connect blocks for up to the
         * connection timeout, and five seconds of frozen console is not a
         * diagnostic. The screen polls [mqttStatus] for the answer.
         */
        @JavascriptInterface fun mqttTest() {
            Thread {
                applyMqttSettings()
                Log.i(TAG, "settings: mqtt test — ${if (mqtt.connected) "connected" else "failed"}")
            }.start()
        }

        @JavascriptInterface fun mqttStatus(): String = JSONObject()
            .put("enabled", cfg.haEnabled())
            .put("connected", mqtt.connected)
            .put("broker", cfg.mqttHost())
            .toString()

        /** What the board says it can do. Reported, never set — see Settings. */
        @JavascriptInterface fun boardLimits(): String = JSONObject()
            .put("minGrade", minGrade).put("maxGrade", maxGrade)
            .put("minKph", minKph).put("maxKph", maxKph)
            .toString()

        @JavascriptInterface fun about(): String = JSONObject()
            .put("version", BuildConfig.VERSION_NAME)
            .put("build", BuildConfig.VERSION_CODE)
            .put("board", "FitPro · device 0x%02x".format(deviceId))
            .put("android", android.os.Build.VERSION.RELEASE)
            .toString()

        @JavascriptInterface fun resetSettings(): String {
            cfg.resetSettings()
            applySetting("")
            Log.i(TAG, "settings: reset to defaults")
            return cfg.json().toString()
        }

        /** Screen sleep. The panel is the only light in the room at 6am. */
        @JavascriptInterface fun dim(on: Boolean) {
            runOnUiThread {
                window.attributes = window.attributes.apply {
                    screenBrightness = if (on) 0.02f else
                        WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                }
            }
        }
    }

    /**
     * Which pulse to believe.
     *
     * The board's number comes from the handlebar grips and is real, but it is
     * only there while both hands are on the bar — so it drops out on exactly
     * the hills where it is worth having. A strap does not care what your arms
     * are doing.
     *
     * Automatic prefers the strap and falls back, and the fallback is on the
     * strap's *reading* rather than its connection: a strap can be connected
     * and still reporting nothing, having been put on dry or slipped. Zero from
     * either source means "no reading", never "no pulse".
     */
    private fun pulseNow(fromBoard: Int): Int = when (cfg.hrSource()) {
        "grips" -> fromBoard
        "strap" -> strap.bpm()
        else -> strap.bpm().takeIf { it > 0 } ?: fromBoard
    }

    // --- applying settings ----------------------------------------------------

    /**
     * Connect, reconnect or stay down, according to what is configured.
     *
     * Called at start-up and whenever a broker setting changes. **A console
     * with no broker set is a working treadmill, not a broken one** — it does
     * not retry, does not warn, and does not log an error every poll.
     */
    private fun applyMqttSettings() {
        if (!cfg.haEnabled()) {
            mqtt.close()
            Log.i(TAG, "mqtt: not configured, running standalone")
            return
        }
        mqtt.reconfigure(cfg.brokerUri(), cfg.mqttUser(), cfg.mqttPass(), cfg.mqttPrefix())
        // Re-registered because reconfigure() dropped the client, and a clean
        // session forgets subscriptions with it.
        mqtt.onMessage(mqtt.coachTopic, ::onCoachLine)
        mqtt.onMessage(mqtt.uiTopic, ::onUiCommand)
        mqtt.onMessage(mqtt.personsTopic, ::onPersons)
        mqtt.onMessage(mqtt.routesTopic, routes::accept)
        mqtt.connect()?.let { Log.w(TAG, "mqtt: $it") }
        if (mqtt.connected) mqtt.publishUi(chosenUi())
    }

    /**
     * React to one setting having changed, where it needs more than being
     * stored. Anything read at the point of use — warm-up length, incline rate,
     * units — needs nothing here and is deliberately absent.
     */
    private fun applySetting(key: String) {
        when (key) {
            Settings.HA_ENABLED, Settings.MQTT_HOST, Settings.MQTT_PORT,
            Settings.MQTT_USER, Settings.MQTT_PASS, Settings.MQTT_TLS,
            Settings.MQTT_PREFIX -> Thread { applyMqttSettings() }.start()

            Settings.BRIGHTNESS -> applyBrightness()

            Settings.HR_SOURCE, Settings.HR_ADDR -> applyStrap()

            // A reset changes everything at once.
            "" -> {
                Thread { applyMqttSettings() }.start()
                applyBrightness()
                applyStrap()
            }
        }
    }

    private fun applyBrightness() = runOnUiThread {
        window.attributes = window.attributes.apply { screenBrightness = cfg.brightness() }
    }

    /** Hold a connection only when a strap could actually be used. */
    private fun applyStrap() {
        if (cfg.hrSource() == "grips" || cfg.hrAddr().isBlank()) strap.disconnect()
        else strap.connect(cfg.hrAddr())
    }

    /**
     * A BLE scan on API 23–30 returns an empty list, not an error, without a
     * location permission. This app targets 28, so that is the rule that
     * applies — and an empty list is exactly what "no straps here" looks like,
     * which makes it the single most confusing way for this feature to fail.
     */
    private fun ensureScanPermission() {
        if (android.os.Build.VERSION.SDK_INT < 23) return
        val perm = android.Manifest.permission.ACCESS_COARSE_LOCATION
        if (checkSelfPermission(perm) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Log.i(TAG, "hr: asking for location permission so a BLE scan can return results")
            requestPermissions(arrayOf(perm), 1)
        }
    }

    // --- which interface ------------------------------------------------------

    /** The UIs named in [UIS] that are actually in this APK. */
    private fun available(): List<String> {
        val present = try {
            assets.list("ui")?.toSet() ?: emptySet()
        } catch (e: java.io.IOException) {
            Log.w(TAG, "ui: cannot list assets/ui (${e.message})")
            emptySet<String>()
        }
        return UIS.filter { present.contains("$it.html") }
    }

    /** Whatever was chosen last, if it still exists in this build. */
    private fun chosenUi(): String {
        val saved = prefs.getString(PREF_UI, DEFAULT_UI) ?: DEFAULT_UI
        return if (available().contains(saved)) saved else DEFAULT_UI
    }

    private fun assetFor(name: String) = "file:///android_asset/ui/$name.html"

    /** Both routes in — a tap on the settings screen and a publish from HA —
     *  land here, so neither can switch under conditions the other refuses. */
    private fun switchUi(name: String) {
        if (Session.isMoving(session)) {
            Log.i(TAG, "ui: not switching to $name while the belt is moving")
            return
        }
        if (!available().contains(name)) {
            Log.w(TAG, "ui: no such interface: $name")
            return
        }
        if (name == chosenUi()) return
        prefs.edit().putString(PREF_UI, name).apply()
        Log.i(TAG, "ui: switching to $name")
        mqtt.publishUi(name)
        runOnUiThread { web.loadUrl(assetFor(name)) }
    }

    /**
     * Home Assistant's people, retained on the broker by ha/stride_persons.py.
     *
     * Held rather than acted on. The console offers them under "Add from Home
     * Assistant" and somebody standing in front of it decides — a list arriving
     * over MQTT is not permission to create walkers.
     */
    /**
     * Routes from real walks. Unlike [haPersons] this is not a volatile field
     * holding whatever last arrived — it is cached to disk, because the console
     * can boot with no network and a route that is not already here cannot be
     * fetched at the moment somebody is standing on the belt choosing it.
     */
    private val routes by lazy { Routes(this) }

    @Volatile private var haPersons: String = "[]"

    private fun onPersons(payload: String) {
        haPersons = payload.trim().ifBlank { "[]" }
        Log.i(TAG, "persons: received ${haPersons.length} bytes from HA")
    }

    /** A UI name published from Home Assistant. */
    private fun onUiCommand(payload: String) {
        val name = payload.trim().lowercase()
        Log.i(TAG, "ui: requested \"$name\" over mqtt")
        switchUi(name)
    }

    /**
     * Finish, but bring the belt to a stop first.
     *
     * Every automatic route to the summary comes through here — the plan
     * expiring, the cool-down expiring, SKIP — so none of them can drop the belt
     * out from under someone. [decelStep] does the winding down and calls
     * [finishWorkout] once the belt has actually stopped.
     */
    private fun requestFinish() {
        if (targetKph <= 0.0 || !Session.isMoving(session)) {
            finishWorkout()
            return
        }
        stopping = true
        rampTo = 0.0
        rampReason = "stopping"
        Log.i(TAG, "winding down from ${"%.1f".format(targetKph)} km/h")
    }

    /**
     * One tick of the wind-down, or null if nothing is stopping.
     *
     * Below the machine's minimum there is no "slow", only stopped, so the last
     * step goes straight to zero rather than trying to hold 1.5 km/h.
     */
    private fun decelStep(): Map<FitPro.Field, Double>? {
        if (!stopping) return null
        val step = DECEL_KPH_PER_SEC * POLL_MS / 1000.0
        targetKph = (targetKph - step).coerceAtLeast(0.0)
        if (targetKph < minKph) targetKph = 0.0
        if (targetKph <= 0.0) {
            stopping = false
            rampTo = 0.0
            rampReason = ""
            // "Commanded zero", not "stopped". The belt takes a moment to come
            // to rest, and the write that tells it to may not even arrive —
            // see enforceStopped, which is what actually finishes the job.
            Log.i(TAG, "wind-down complete — commanding stop")
            finishWorkout()
            return null
        }
        return mapOf(FitPro.Field.KPH to targetKph)
    }

    /**
     * The belt must not be moving unless a workout is.
     *
     * This exists because it was not true. On 31 July a guided walk finished,
     * the console logged "belt stopped", showed the summary, and went back to
     * the welcome screen — while the belt kept running. It ran for another
     * thirty-five seconds until somebody hit the physical stop button.
     *
     * The stop was sent once, as a one-shot `pendingWrite`, and the log for
     * that exact moment shows `dropped frame (status:failed)`. One lost frame
     * and the command was simply gone. Nothing retried it and nothing checked,
     * because the code treated "I decremented my own target to zero" as
     * meaning the machine had stopped.
     *
     * So this asks the board what it is actually doing, every poll, and keeps
     * commanding a stop until it agrees. A treadmill running with nobody
     * driving it is the worst failure this project has, and one dropped USB
     * frame should not be able to cause it.
     */
    private fun enforceStopped(actualKph: Double): Map<FitPro.Field, Double>? {
        if (Session.isMoving(session)) return null
        if (actualKph < STOPPED_KPH) return null
        stopNags++
        if (stopNags == 1 || stopNags % 25 == 0) {
            Log.w(TAG, "belt still moving at ${"%.1f".format(actualKph)} km/h " +
                    "outside a workout — commanding stop again (attempt $stopNags)")
        }
        return mapOf(
            FitPro.Field.KPH to 0.0,
            FitPro.Field.WORKOUT_MODE to FitPro.Mode.PAUSE.toDouble(),
        )
    }

    /** Stop everything and show the recap. Reached by the cool-down expiring or SKIP. */
    private fun finishWorkout() {
        if (Session.isMoving(session)) {
            accumulatedMs += SystemClock.elapsedRealtime() - activeSince
        }
        session = Session.SUMMARY
        phaseEndsAt = 0L
        stopping = false
        targetKph = 0.0
        targetGrade = 0.0
        rampTo = 0.0
        rampReason = ""
        pausedKph = 0.0
        pendingWrite = mapOf(
            FitPro.Field.KPH to 0.0,
            FitPro.Field.GRADE to 0.0,
            FitPro.Field.WORKOUT_MODE to FitPro.Mode.PAUSE.toDouble(),
        )
        Log.i(TAG, "workout ended: ${"%.0f".format(sessionDistance)} m in " +
                "${accumulatedMs / 1000} s, ${"%.0f".format(sessionCalories)} kcal")
    }

    /** The segment after [idx], wrapping on a circuit and null at the end. */
    private fun nextStep(idx: Int): Plan.Step? {
        val steps = planSteps
        if (steps.isEmpty() || idx < 0) return null
        if (idx + 1 < steps.size) return steps[idx + 1]
        return if (planLoops) steps.firstOrNull() else null
    }

    private fun clearPlan() {
        route = null
        planName = ""
        planSteps = emptyList()
        stepIndex = -1
        baselineKph = 0.0
        inclineAuto = false
        planLoops = false
        planLap = 1
        planElapsed = 0.0
    }

    /** The segment the walk is currently in, or null on a casual walk. */
    private fun currentStep(): Plan.Step? {
        if (planSteps.isEmpty()) return null
        return planSteps.getOrNull(stepIndex.coerceAtLeast(0))
    }

    /**
     * Run the guided walk: advance the segment, then nudge the deck towards
     * whatever that segment asks for.
     *
     * Plan time is *session* time, which already excludes anything spent
     * paused — a walk interrupted for two minutes should still be the walk that
     * was chosen, not two minutes shorter.
     */
    /**
     * Run a route: the same job as [planTick], driven by **distance travelled**
     * rather than by the clock.
     *
     * This is the one substantive difference between a route and a template,
     * and it is not a detail. A route was recorded outdoors, where the hill
     * arrived at a certain point on the ground. Replay it on a timer while
     * walking slower indoors than out and the hill arrives late, the walk ends
     * before the route does, and the summit — the reason for choosing it — is
     * never reached. Driven by distance, the ground arrives where it did
     * outdoors however fast it is taken.
     */
    private fun routeTick(metres: Double) {
        val r = route ?: return

        // The HUD's position marker reads planElapsed against the plan steps,
        // and a route's steps are in metres — so this is metres too. Without
        // it the walker sat at the start line for the whole walk while the
        // ground moved under them, which is what the first route showed.
        planElapsed = metres

        if (metres >= r.distanceM) {
            if (!stopping) {
                Log.i(TAG, "route: ${r.name} complete at ${"%.0f".format(metres)} m")
                clearPlan()
                requestFinish()
            }
            return
        }

        val idx = r.segments.indexOfFirst { metres < it.endM }.coerceAtLeast(0)
        if (idx != stepIndex) {
            if (stepIndex == 0 && baselineKph <= 0.0) {
                baselineKph = if (targetKph > 0.0) targetKph else cfg.warmupKph()
                Log.i(TAG, "route: baseline pace ${"%.1f".format(baselineKph)} km/h")
            }
            stepIndex = idx
            inclineAuto = true
            Log.i(TAG, "route: segment ${idx + 1}/${r.segments.size} at " +
                    "${"%.0f".format(metres)} m — ${"%.1f".format(r.segments[idx].incline)}%")
        }
        driveIncline(r.inclineAt(metres))
    }

    private fun planTick(elapsed: Double) {
        val steps = planSteps
        if (steps.isEmpty()) return
        val lap = steps.last().endSec

        if (planLoops) {
            // Round again. The hills are the same hills — that is the point of
            // a circuit — so nothing is regenerated and nothing ends the walk
            // but STOP.
            if (lap > 0) {
                val was = planLap
                planLap = (elapsed / lap).toInt() + 1
                planElapsed = elapsed % lap
                if (planLap != was) Log.i(TAG, "guided: lap $planLap of the circuit")
            }
        } else {
            planElapsed = elapsed
            // The whole plan is done: stop here rather than running the belt on.
            if (elapsed >= lap) {
                if (!stopping) {
                    Log.i(TAG, "guided: plan complete — winding the belt down")
                    // The plan is deliberately *not* cleared here. Clearing it
                    // drops the guided view and the console falls back to the
                    // casual oval for the two or three seconds the belt takes
                    // to wind down — which reads as a cool-down screen that
                    // then vanishes. It is cleared on the way out of the
                    // summary instead.
                    requestFinish()
                }
                return
            }
        }

        val idx = steps.indexOfFirst { planElapsed < it.endSec }.coerceAtLeast(0)
        if (idx != stepIndex) {
            val step = steps[idx]
            // The opening segment is where he chooses a pace; everything after
            // is suggested relative to it.
            if (stepIndex == 0 && baselineKph <= 0.0) {
                baselineKph = if (targetKph > 0.0) targetKph else cfg.warmupKph()
                Log.i(TAG, "guided: baseline pace ${"%.1f".format(baselineKph)} km/h")
            }
            stepIndex = idx
            // A new segment hands the deck back, whatever happened in the last one.
            inclineAuto = true
            Log.i(TAG, "guided: segment ${idx + 1}/${steps.size} — ${step.label}, " +
                    "${"%.1f".format(step.incline)}%")
        }

        val step = steps.getOrNull(stepIndex) ?: return
        driveIncline(step.incline)
    }

    /**
     * Move the deck towards the segment's target, slowly.
     *
     * Only ever while the belt is genuinely moving and under the plan's control:
     * a paused walk, a pulled safety key or a manual override all stop the hill
     * dead where it is.
     */
    private fun driveIncline(target: Double) {
        if (!inclineAuto || dmk || session != Session.ACTIVE) return
        if (pendingWrite != null) return          // don't stamp on a queued write

        val goal = target.coerceIn(minGrade, maxGrade)
        if (Math.abs(targetGrade - goal) < 0.05) return

        val now = SystemClock.elapsedRealtime()
        if (now - lastInclineMoveAt < cfg.inclineEveryMs()) return
        lastInclineMoveAt = now

        val next = if (targetGrade < goal) {
            (targetGrade + cfg.inclineStep()).coerceAtMost(goal)
        } else {
            (targetGrade - cfg.inclineStep()).coerceAtLeast(goal)
        }
        targetGrade = next.coerceIn(minGrade, maxGrade)
        pendingWrite = mapOf(FitPro.Field.GRADE to targetGrade)
        Log.i(TAG, "guided: incline -> ${"%.1f".format(targetGrade)}% (goal ${"%.1f".format(goal)}%)")
    }

    /** Warm-up rolls into the workout; cool-down rolls into the summary. */
    private fun advancePhase() {
        if (phaseEndsAt == 0L || SystemClock.elapsedRealtime() < phaseEndsAt) return
        when (session) {
            Session.WARMUP -> {
                session = Session.ACTIVE
                phaseEndsAt = 0L
                Log.i(TAG, "warm-up complete")
            }
            Session.COOLDOWN -> requestFinish()
        }
    }

    private fun phaseLeftSec(): Double {
        if (phaseEndsAt == 0L) return 0.0
        return ((phaseEndsAt - SystemClock.elapsedRealtime()) / 1000.0).coerceAtLeast(0.0)
    }

    private fun resetSession() {
        accumulatedMs = 0L
        activeSince = SystemClock.elapsedRealtime()
        armBaseline = true
        sessionDistance = 0.0
        sessionCalories = 0.0
        maxSpeed = 0.0
        maxIncline = 0.0
        speedSum = 0.0
        inclineSum = 0.0
        samples = 0
    }

    private fun elapsedSec(): Double {
        val live = if (Session.isMoving(session))
            SystemClock.elapsedRealtime() - activeSince else 0L
        return (accumulatedMs + live) / 1000.0
    }

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Lets the HUD be inspected and driven from a laptop over adb, so
        // layout can be checked against fake state without arming the belt.
        WebView.setWebContentsDebuggingEnabled(true)

        cfg.seedFromBuildConfig()
        walker = cfg.defaultWalker()
        applyBrightness()
        applyStrap()

        val ui = chosenUi()
        Log.i(TAG, "ui: $ui (available: ${available().joinToString(", ")})")

        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            /* The launch screen's field colour, not Original's navy. The
               WebView paints this before the document does, so it is the one
               frame between the splash and the UI — matching it makes the
               handover invisible. Navy was only ever right for one of the five
               interfaces. */
            setBackgroundColor(0xFF100F0E.toInt())
            addJavascriptInterface(Bridge(), "Stride")
            /* A UI switch reloads the page, and the plan is the one thing sent
               once rather than at poll rate — so it has to go over again or the
               new document draws a guided walk with no ground ahead of it. */
            webViewClient = object : android.webkit.WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    if (planSteps.isNotEmpty()) pushPlan()
                }
            }
            loadUrl(assetFor(ui))
        }
        setContentView(web)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY

        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(permissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(permissionReceiver, filter)
        }

        acquireBoard()
    }

    /**
     * Wait for the control board to show up on the USB bus.
     *
     * It is not always there: it has been seen to drop off the bus entirely and
     * only re-enumerate after a console reboot. Failing once and giving up left
     * the HUD looking alive but permanently disconnected, so keep looking.
     */
    private fun acquireBoard() {
        thread(name = "usb-wait") {
            var announced = false
            while (running.not() && !isFinishing) {
                val device = conn.findDevice()
                if (device == null) {
                    if (!announced) {
                        Log.e(TAG, "control board not on the USB bus — waiting. " +
                                "(is com.ifit.eru enabled again? does the console need a reboot?)")
                        announced = true
                    }
                    Thread.sleep(5000)
                    continue
                }
                if (conn.hasPermission(device)) {
                    runOnUiThread { start(device) }
                    return@thread
                }
                val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_IMMUTABLE else 0
                val pi = PendingIntent.getBroadcast(
                    this, 0, Intent(ACTION_USB_PERMISSION).setPackage(packageName), flags
                )
                runOnUiThread {
                    (getSystemService(Context.USB_SERVICE) as UsbManager)
                        .requestPermission(device, pi)
                }
                return@thread
            }
        }
    }

    private fun start(device: UsbDevice) {
        val err = conn.open(device)
        if (err != null) { Log.e(TAG, "open failed: $err"); return }
        running = true
        thread(name = "fitpro-poll") { pollLoop() }
    }

    /**
     * A coach line has come back from Home Assistant: show it, speak it.
     *
     * Re-serialised through JSONObject rather than injected raw — a malformed
     * payload should produce nothing, not a syntax error that takes the whole
     * page's script down with it.
     */
    private fun onCoachLine(payload: String) {
        val obj = org.json.JSONObject(payload)
        val line = obj.optString("line").trim()
        if (line.isEmpty()) return
        val safe = org.json.JSONObject()
            .put("line", line)
            .put("kind", obj.optString("kind"))
            .toString()
        Log.i(TAG, "coach: \"$line\"")
        coach.heard(line)
        if (obj.optString("kind") == "summary") {
            // Held, both the words and the audio. This was asked for at 98% of
            // the plan, so it arrives while there is still walking to do —
            // showing or speaking it then would be the opposite of the point.
            // `speakSummary` releases it once the summary is actually up.
            summaryLine = line
            summaryUrl = obj.optString("url")
            Log.i(TAG, "coach: closing line ready" +
                    if (summaryUrl.isEmpty()) " (no audio)" else "")
            return
        }
        showCoach(safe)
        obj.optString("url").takeIf { it.isNotEmpty() }?.let { voice.play(it) }
    }

    /**
     * Say the closing line, once, and only while the summary is on screen.
     *
     * Waiting for the screen rather than firing on arrival: the line is asked
     * for at 98% of the plan and can come back with a minute of walking still
     * to go. Reading somebody their summary while they are still on the belt
     * would be worse than silence.
     *
     * Polled rather than triggered from `finishWorkout`, because the answer may
     * not have arrived yet when the belt stops — this catches it whichever way
     * round they happen.
     *
     * Latched on the *result*, not the attempt. `play` drops a line when
     * something else is still speaking, so latching on the call would silently
     * lose the closing line whenever the last segment's coaching ran long.
     */
    private fun speakSummary() {
        if (session != Session.SUMMARY || summarySpoken) return
        if (summaryUrl.isEmpty()) return
        if (voice.play(summaryUrl)) {
            summarySpoken = true
            Log.i(TAG, "coach: speaking the closing line")
        }
    }

    private fun showCoach(json: String) {
        runOnUiThread { web.evaluateJavascript("window.coach($json)", null) }
    }

    /**
     * One frame through the coach: emit a moment if there is one, and fall back
     * to the canned line if Home Assistant does not answer in time.
     *
     * The workout never waits on any of this — a moment that cannot be sent is
     * simply lost, which is the correct outcome for a thing that was only ever
     * going to be a sentence.
     */
    private fun coachTick(snap: Snapshot) {
        coach.observe(snap)?.let { moment ->
            Log.i(TAG, "coach moment: ${moment.kind} — ${moment.detail}")
            // The live-coach prompt in Home Assistant carries one person's
            // weight, blood pressure and target. Sending somebody else's walk
            // to it would put those numbers in front of whoever is on the belt,
            // so this has always been gated — but it was gated on the walker
            // being *named Sam*, which meant a second person in the same
            // house silently got a different product and no way to change it.
            //
            // It is now a property of the person, set in Settings, and an
            // unknown walker gets nothing rather than falling through to "not
            // Sam". Everyone else still gets the local canned lines, which
            // are generic and name nobody.
            if (cfg.coachedFor(walker)) {
                mqtt.publishEvent(coach.payload(moment, snap))
            } else {
                Log.i(TAG, "coach: $walker is not set up for coaching, keeping it local")
            }
        }
        coach.timedOut()?.let { line ->
            Log.i(TAG, "coach: no answer from HA, using \"$line\"")
            showCoach(org.json.JSONObject().put("line", line).put("kind", "local").toString())
        }
    }

    private fun pollLoop() {
        if (!resolveDeviceId()) { Log.e(TAG, "board did not answer on 4 or 2"); return }
        readLimits()
        mqtt.onMessage(mqtt.coachTopic, ::onCoachLine)
        mqtt.onMessage(mqtt.uiTopic, ::onUiCommand)
        mqtt.onMessage(mqtt.personsTopic, ::onPersons)
        mqtt.onMessage(mqtt.routesTopic, routes::accept)
        applyMqttSettings()
        mqtt.publishUi(chosenUi())

        var sinceMqtt = 0L
        var rejects = 0
        while (running) {
            // enforceStopped comes first and outranks everything, including a
            // queued write: nothing is more important than a belt that should
            // not be moving.
            val queued = pendingWrite
            val writes = enforceStopped(lastActualKph) ?: queued ?: decelStep() ?: rampStep()
            // Only consume the queued write if that is what actually went out.
            // A stop command jumping the queue must not silently eat it.
            if (queued != null && writes === queued) pendingWrite = null

            val reply = conn.exchange(FitPro.readWrite(deviceId, READS, writes ?: emptyMap()))
            if (reply == null) { Thread.sleep(POLL_MS); continue }

            val why = FitPro.rejectReason(reply, READS)
            if (why != null) {
                // Log the first few and then every hundredth — a persistent
                // reject reason is a real signal, a lone one is just noise.
                if (rejects < 5 || rejects % 100 == 0) {
                    Log.w(TAG, "dropped frame ($why): ${FitPro.hex(reply)}")
                }
                rejects++
                Thread.sleep(POLL_MS)
                continue
            }

            val v = FitPro.parse(reply, READS).filter { (f, value) ->
                // Reject anything outside what the machine says it can do.
                when (f) {
                    FitPro.Field.ACTUAL_KPH, FitPro.Field.KPH ->
                        value >= 0.0 && value <= maxKph + 0.5
                    FitPro.Field.ACTUAL_INCLINE, FitPro.Field.GRADE ->
                        value >= minGrade - 0.5 && value <= maxGrade + 0.5
                    else -> true
                }
            }
            if (v.isEmpty()) { Thread.sleep(POLL_MS); continue }

            // Follow the board rather than our own idea of the target —
            // physical button presses never reach this app.
            if (writes == null) {
                v[FitPro.Field.KPH]?.let { targetKph = it }
                v[FitPro.Field.GRADE]?.let { targetGrade = it }
            }

            val snap = accumulate(v)
            push(snap)
            coachTick(snap)
            speakSummary()

            sinceMqtt += POLL_MS
            if (sinceMqtt >= MQTT_EVERY_MS) {
                sinceMqtt = 0
                mqtt.publishState(snap)
                // Only while there is a workout to attribute. Publishing on the
                // welcome screen would zero whoever walked last.
                if (Session.isMoving(session) || session == Session.SUMMARY) {
                    cfg.person(walker)?.takeIf { it.publish }
                        ?.let { mqtt.publishPerson(it, snap) }
                }
            }
            Thread.sleep(POLL_MS)
        }
    }

    /**
     * One tick of the resume ramp, or null if there's nothing to wind up.
     *
     * The first step goes straight to the machine's minimum — below 1.6 km/h
     * this treadmill has no "slow", only stopped — and from there it climbs at
     * a walking-pace-per-second until it reaches where you left off.
     */
    private fun rampStep(): Map<FitPro.Field, Double>? {
        val goal = rampTo
        if (goal <= 0.0 || !Session.isMoving(session) || dmk) return null

        val step = RAMP_KPH_PER_SEC * POLL_MS / 1000.0
        val done: Boolean
        if (targetKph < goal) {
            // Below the machine's minimum there is no "slow", only stopped, so
            // the first step off zero jumps straight to it.
            targetKph = (if (targetKph < minKph) minKph else targetKph + step).coerceAtMost(goal)
            done = targetKph >= goal
        } else {
            // Ramping down — cool-down easing back from whatever pace was set.
            targetKph = (targetKph - step).coerceAtLeast(goal)
            done = targetKph <= goal
        }
        if (done) {
            rampTo = 0.0
            rampReason = ""
            Log.i(TAG, "ramp complete at ${"%.1f".format(targetKph)} km/h")
        }
        return mapOf(FitPro.Field.KPH to targetKph)
    }

    /** Fold one board reading into the session and return what to display. */
    private fun accumulate(v: Map<FitPro.Field, Double>): Snapshot {
        advancePhase()
        // Mode 8 is WorkoutMode.Dmk — the dead man's key has been pulled.
        val boardMode = v[FitPro.Field.WORKOUT_MODE]?.toInt()
        // The board reports 8 only in bursts, dropping back to Pause(3) while the
        // key may still be out — so treat it as an edge and latch it. The banner
        // then stays up until acknowledged, and re-arms instantly if 8 recurs.
        val keyOut = boardMode == 8
        if (keyOut) dmkLatched = true
        if (boardMode != null && boardMode != lastBoardMode) {
            Log.i(TAG, "board WorkoutMode -> $boardMode (${FitPro.Mode.name(boardMode)})")
            lastBoardMode = boardMode
        }
        if (keyOut && !dmk) Log.w(TAG, "safety key removed")
        dmk = dmkLatched
        if (keyOut && Session.isMoving(session)) {
            accumulatedMs += SystemClock.elapsedRealtime() - activeSince
            session = Session.PAUSED
            phaseEndsAt = 0L
            targetKph = 0.0
            // Deliberately *not* remembered for the resume ramp. Someone pulled
            // the safety key; whatever happens next should start from a
            // standstill and be asked for explicitly.
            pausedKph = 0.0
            rampTo = 0.0
            rampReason = ""
        }

        val rawDistance = v[FitPro.Field.DISTANCE] ?: 0.0
        val rawCalories = v[FitPro.Field.CALORIES] ?: 0.0
        if (armBaseline) {
            baseDistance = rawDistance
            baseCalories = rawCalories
            armBaseline = false
            Log.i(TAG, "session baseline: d=$baseDistance c=$baseCalories")
        }

        val actual = v[FitPro.Field.ACTUAL_KPH] ?: 0.0
        lastActualKph = actual
        if (actual < STOPPED_KPH && stopNags > 0) {
            Log.i(TAG, "belt confirmed stopped after $stopNags nag(s)")
            stopNags = 0
        }
        val incline = v[FitPro.Field.ACTUAL_INCLINE] ?: 0.0

        // ActualKph reads 0.00 on this board at every speed — see beltSpeed().
        if (Session.isMoving(session) && targetKph > 0.0 && actual <= 0.0) {
            missingSpeedPolls++
            if (missingSpeedPolls > 25 && !speedProbed) { speedProbed = true; probeSpeed() }
        } else if (actual > 0.0) {
            missingSpeedPolls = 0
        }

        val derived = beltSpeed(rawDistance)
        var speed = when {
            actual > 0.0 -> actual          // if the board ever starts answering
            !Session.isMoving(session) -> 0.0
            derived != null -> derived      // measured from the belt itself
            else -> targetKph               // first few seconds, before a trend
        }

        // Settle the readout. Differentiating a whole-metre odometer gives a
        // number that twitches — 4.8, 5.0, 5.1, 4.9 — which is distracting to
        // read and, at a glance mid-run, worse than useless. Once the belt has
        // plainly arrived at the commanded pace, show the commanded pace. The
        // derived figure still drives everything else, and still shows during
        // ramps, where the movement is real and worth watching.
        if (rampTo == 0.0 && !stopping && targetKph > 0.0 &&
            Math.abs(speed - targetKph) <= SETTLE_BAND_KPH) {
            speed = targetKph
        }

        if (Session.isMoving(session)) {
            sessionDistance = (rawDistance - baseDistance).coerceAtLeast(0.0)
            sessionCalories = (rawCalories - baseCalories).coerceAtLeast(0.0)
            if (speed > maxSpeed) maxSpeed = speed
            if (incline > maxIncline) maxIncline = incline
            speedSum += speed
            inclineSum += incline
            samples++
        }

        val elapsedNow = elapsedSec()
        if (Session.isMoving(session)) {
            // A route is ground, not a timetable — see routeTick.
            if (route != null) routeTick(sessionDistance) else planTick(elapsedNow)
        }
        val step = currentStep()

        return Snapshot(
            speed = speed,
            incline = incline,
            targetSpeed = targetKph,
            targetIncline = targetGrade,
            distance = sessionDistance,
            elapsed = elapsedSec(),
            calories = sessionCalories,
            pulse = pulseNow(v[FitPro.Field.PULSE]?.toInt() ?: 0),
            fan = fanState,
            session = session,
            workout = workout,
            dmk = dmk,
            ramping = if (rampTo > 0.0) rampReason else "",
            phaseLeft = phaseLeftSec(),
            boardMode = boardMode ?: -1,
            avgSpeed = if (samples > 0) speedSum / samples else 0.0,
            maxSpeed = maxSpeed,
            avgIncline = if (samples > 0) inclineSum / samples else 0.0,
            maxIncline = maxIncline,
            who = walker,
            plan = planName,
            segment = if (step != null) stepIndex + 1 else 0,
            segments = planSteps.size,
            segmentLabel = step?.label ?: "",
            // Against plan time, not session time — on a circuit those differ
            // by however many laps have gone by.
            // Seconds on a template, **metres** on a route — the steps of a
            // route carry distance in the same fields, which is what let the
            // HUD render a 275 m opening stretch as "4:35 left". The UI is
            // told which it is rather than being left to guess.
            segmentLeft = when {
                step == null -> 0.0
                route != null -> (step.endSec - sessionDistance).coerceAtLeast(0.0)
                else -> (step.endSec - planElapsed).coerceAtLeast(0.0)
            },
            segmentLeftIsDistance = route != null,
            // On a circuit the "next" segment after the last one is the first
            // one again, because the ground comes round rather than running out.
            nextLabel = nextStep(stepIndex)?.label ?: "",
            nextIncline = nextStep(stepIndex)?.incline ?: 0.0,
            planTotalSec = planSteps.lastOrNull()?.endSec ?: 0.0,
            summaryLine = summaryLine,
            planElapsed = planElapsed,
            planLap = planLap,
            planLoops = planLoops,
            // No opinion until he has settled on a pace to be relative to.
            suggestPace = if (step != null && baselineKph > 0.0) {
                (baselineKph + step.paceDelta).coerceIn(minKph, maxKph)
            } else 0.0,
            inclineAuto = inclineAuto,
        )
    }

    /** Everything the HUD needs to draw the path ahead. Sent once per plan. */
    private fun planJson(): String {
        val arr = org.json.JSONArray()
        for (s in planSteps) {
            arr.put(org.json.JSONObject()
                .put("start", Math.round(s.startSec))
                .put("end", Math.round(s.endSec))
                .put("incline", s.incline)
                .put("label", s.label))
        }
        return org.json.JSONObject()
            .put("name", planName)
            .put("loops", planLoops)
            .put("steps", arr)
            .toString()
    }

    /**
     * Belt speed in km/h, measured by differentiating the odometer.
     *
     * `ActualKph` (field 16) reads 0.00 on this board at every speed. It is not
     * a framing bug — `ActualIncline` decodes correctly from the two bytes
     * immediately after it, a bare single-field read of it returns the expected
     * 7-byte frame, and devices 2 and 65 answer the same way. The board simply
     * never fills it in.
     *
     * `Distance` *is* live and talexful though, so the belt tells us its speed
     * whether or not the firmware will say so. Distance is whole metres, which
     * is coarse at walking pace, so measure across a window rather than between
     * consecutive polls: at 5 km/h a 4-second window covers ~5.5 m, good for
     * about ±0.3 km/h, and it tightens as the pace rises.
     *
     * Returns null until there is enough of a trend to be honest about.
     */
    private fun beltSpeed(rawDistance: Double): Double? {
        val now = SystemClock.elapsedRealtime()
        if (!Session.isMoving(session)) { distanceTrail.clear(); smoothedKph = 0.0; return null }

        distanceTrail.addLast(now to rawDistance)
        while (distanceTrail.size > 1 && now - distanceTrail.first().first > WINDOW_MS) {
            distanceTrail.removeFirst()
        }

        val (t0, d0) = distanceTrail.first()
        val spanMs = now - t0
        if (spanMs < MIN_SPAN_MS) return null

        val kph = (rawDistance - d0) / (spanMs / 1000.0) * 3.6
        if (kph < 0 || kph > maxKph + 2.0) return smoothedKph.takeIf { it > 0.0 }

        // Light smoothing: the metre quantisation makes the raw figure twitch.
        smoothedKph = if (smoothedKph == 0.0) kph else smoothedKph * 0.75 + kph * 0.25
        return smoothedKph
    }

    /**
     * One-shot, read-only hunt for a live belt speed.
     *
     * Device 4 answers for everything else, but reports ActualKph as zero. The
     * device table has metrics as addressable devices in their own right
     * (SPEED = 65), so the reading may simply live somewhere else. Rpm comes
     * along because a motor tachometer would do just as well as a speedometer.
     */
    private fun probeSpeed() {
        Log.i(TAG, "--- speed probe: ActualKph reads 0 while the belt is moving ---")
        for (dev in listOf(FitPro.Dev.TREADMILL, FitPro.Dev.MAIN, FitPro.Dev.SPEED)) {
            val reply = conn.exchange(FitPro.readWrite(dev, SPEED_PROBE))
            if (reply == null) { Log.i(TAG, "  dev $dev: no reply"); continue }
            val why = FitPro.rejectReason(reply, SPEED_PROBE)
            if (why != null) { Log.i(TAG, "  dev $dev: rejected ($why) ${FitPro.hex(reply)}"); continue }
            val p = FitPro.parse(reply, SPEED_PROBE)
            Log.i(TAG, "  dev $dev: rpm=${p[FitPro.Field.RPM]} " +
                    "actualKph=${p[FitPro.Field.ACTUAL_KPH]}  raw=${FitPro.hex(reply)}")
            Thread.sleep(100)
        }
        Log.i(TAG, "--- end speed probe (target was ${"%.1f".format(targetKph)} km/h) ---")
    }

    private fun pushPlan() {
        val json = planJson()
        runOnUiThread { web.evaluateJavascript("window.plan($json)", null) }
    }

    /** Hand a state snapshot to the page. */
    private fun push(s: Snapshot) {
        val json = s.toJson()
        runOnUiThread { web.evaluateJavascript("window.render($json)", null) }
    }

    private fun resolveDeviceId(): Boolean {
        val probe = listOf(FitPro.Field.ACTUAL_KPH)
        for (candidate in listOf(FitPro.Dev.TREADMILL, FitPro.Dev.MAIN)) {
            val reply = conn.exchange(FitPro.readWrite(candidate, probe))
            if (reply != null && FitPro.rejectReason(reply, probe) == null) {
                deviceId = candidate
                return true
            }
            Thread.sleep(200)
        }
        return false
    }

    private fun readLimits() {
        val reply = conn.exchange(FitPro.readWrite(deviceId, LIMITS)) ?: return
        val v = FitPro.parse(reply, LIMITS)
        v[FitPro.Field.MAX_KPH]?.let { if (it > 0) maxKph = it }
        v[FitPro.Field.MIN_KPH]?.let { if (it > 0) minKph = it }
        v[FitPro.Field.MAX_GRADE]?.let { if (it != 0.0) maxGrade = it }
        v[FitPro.Field.MIN_GRADE]?.let { minGrade = it }
        Log.i(TAG, "limits: $minKph..$maxKph km/h, $minGrade..$maxGrade %")
    }

    override fun onDestroy() {
        running = false
        voice.close()
        mqtt.close()
        conn.close()
        try { unregisterReceiver(permissionReceiver) } catch (_: IllegalArgumentException) {}
        super.onDestroy()
    }
}
