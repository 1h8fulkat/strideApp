package dev.stride.hud

import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject

/**
 * Decides *when* the coach should speak. Home Assistant decides what it says.
 *
 * The split is deliberate. This class sees the session at 5 Hz and knows
 * precisely when a kilometre was crossed or a pace started sagging; HA sees a
 * 1 Hz retained sensor and a session counter that resets every workout, so
 * asking it to detect moments would be asking it to guess. Language is the part
 * that needs a model, and it is the only part that goes over the network.
 *
 * Consequences worth keeping:
 *
 * * **Rate limiting lives here**, not in HA, so a slow or unreachable model can
 *   never cause a pile-up of stale lines.
 * * **Every moment has a canned line** ([fallback]). With HA down, the coach
 *   still marks your kilometres — silently, since nothing on this console can
 *   synthesise speech, but the ribbon still appears.
 * * **Nothing here touches the belt.** A moment is an observation. The user
 *   changes the pace, always.
 */
class Coach {

    companion object {
        /** Never two lines closer together than this, whatever fires. */
        const val MIN_GAP_MS = 90_000L

        /** A segment change may interrupt almost anything — see allowed(). */
        const val SEGMENT_GAP_MS = 15_000L

        /** Say something if nothing else has come up for this long. */
        const val CHECKIN_MS = 5 * 60_000L

        /** How long a sagging pace must persist before it is worth mentioning. */
        const val DROP_HOLD_MS = 45_000L
        const val DROP_RATIO = 0.82

        /** How long a pace must hold to count as having found a rhythm. */
        const val STEADY_HOLD_MS = 3 * 60_000L
        const val STEADY_BAND_KPH = 0.35

        /** Per-kind cooldowns, so the reactive lines cannot become a drumbeat. */
        const val DROP_GAP_MS = 5 * 60_000L
        const val STEADY_GAP_MS = 8 * 60_000L

        /** How long to wait for HA before showing the canned line instead. */
        const val FALLBACK_MS = 8_000L

        /** First mark at 500 m — a short walk deserves one — then every km. */
        const val FIRST_MARK_M = 500.0
        const val MARK_STEP_M = 1000.0
    }

    /** A moment worth a sentence. [kind] picks the prompt, [detail] describes it. */
    data class Moment(val kind: String, val detail: String)

    // Poll-thread state.
    private var lastSession = Session.WELCOME
    private var lastSpokeAt = 0L
    private var nextMark = FIRST_MARK_M
    private var pausedAt = 0L
    private val kindLastAt = HashMap<String, Long>()

    private var lastSegment = 0
    private var dropSince = 0L
    private var steadySince = 0L
    private var steadyRef = 0.0

    /** Set when a moment goes out, cleared when a line comes back. */
    @Volatile private var awaitingSince = 0L
    @Volatile private var awaitingKind = ""

    /** The last few lines actually shown, so the model can avoid repeating itself. */
    private val said = ArrayDeque<String>()

    /** A fresh workout forgets everything — marks, cooldowns, and what was said. */
    private fun reset() {
        lastSpokeAt = 0L
        nextMark = FIRST_MARK_M
        pausedAt = 0L
        kindLastAt.clear()
        lastSegment = 0
        dropSince = 0L
        steadySince = 0L
        steadyRef = 0.0
        awaitingSince = 0L
        awaitingKind = ""
        said.clear()
    }

    /**
     * Dismissed by hand. Forget what was in flight so the canned fallback does
     * not arrive moments later as a second, unwanted line — being dismissed
     * means the moment is over, whatever HA was about to say about it.
     */
    @Synchronized fun hush() {
        awaitingSince = 0L
        awaitingKind = ""
    }

    /** Remember a line that was actually delivered. Called from the MQTT thread. */
    @Synchronized fun heard(line: String) {
        awaitingSince = 0L
        awaitingKind = ""
        said.addLast(line)
        while (said.size > 3) said.removeFirst()
    }

    /**
     * HA has had its chance and said nothing — the canned line for the moment
     * still in flight, or null if there is nothing waiting.
     */
    @Synchronized fun timedOut(): String? {
        if (awaitingSince == 0L) return null
        if (SystemClock.elapsedRealtime() - awaitingSince < FALLBACK_MS) return null
        val line = fallback(awaitingKind)
        awaitingSince = 0L
        awaitingKind = ""
        said.addLast(line)
        while (said.size > 3) said.removeFirst()
        return line
    }

    /**
     * Look at one frame and decide whether to speak.
     *
     * Ordered by priority: a phase change or a kilometre always beats a
     * reactive observation, which always beats a check-in. Only one moment
     * leaves per frame.
     */
    fun observe(s: Snapshot): Moment? {
        val now = SystemClock.elapsedRealtime()
        val was = lastSession
        lastSession = s.session

        if (s.session == Session.WELCOME) {
            if (was != Session.WELCOME) reset()
            return null
        }
        // A new workout starts at WARMUP straight from WELCOME or SUMMARY.
        if (s.session == Session.WARMUP && (was == Session.WELCOME || was == Session.SUMMARY)) {
            reset()
        }
        if (was == Session.ACTIVE && s.session == Session.PAUSED) pausedAt = now

        // The safety key being out is not a coaching moment. Say nothing.
        if (s.dmk) return null

        val moment = detect(s, was, now) ?: return null
        if (!allowed(moment.kind, now)) return null

        lastSpokeAt = now
        kindLastAt[moment.kind] = now
        awaitingSince = now
        awaitingKind = moment.kind
        return moment
    }

    private fun detect(s: Snapshot, was: Int, now: Long): Moment? {
        // --- phase changes: things that just happened, worth marking ---------
        if (was == Session.WARMUP && s.session == Session.ACTIVE) {
            return Moment("warmup_done", "the warm-up is over and the workout proper has started")
        }
        if (was != Session.COOLDOWN && s.session == Session.COOLDOWN) {
            return Moment("cooldown", "he has ended the workout and the belt is easing down")
        }
        if (was == Session.PAUSED && s.session == Session.ACTIVE) {
            val held = (now - pausedAt) / 1000
            // A ten-second breather does not need commentary.
            if (pausedAt > 0L && held >= 30) {
                return Moment("resumed", "he paused for ${held}s and has just started moving again")
            }
        }

        if (s.session != Session.ACTIVE) return null

        // A guided walk changing segment is the strongest moment there is — the
        // ground is about to change under him and he should hear why.
        if (s.segments > 0 && s.segment != lastSegment) {
            val previous = lastSegment
            lastSegment = s.segment
            // Segment one is the opening settle; he does not need telling that
            // the walk he just started has started.
            if (previous != 0) {
                return Moment("segment",
                    "the walk has moved into segment ${s.segment} of ${s.segments}, " +
                    "\"${s.segmentLabel}\", where the incline goes to " +
                    "${"%.1f".format(s.targetIncline)}%" +
                    if (s.suggestPace > 0) " and the suggested pace is " +
                        "${"%.1f".format(s.suggestPace)} km/h" else "")
            }
        }

        // --- distance: the backbone -----------------------------------------
        if (s.distance >= nextMark) {
            val reached = nextMark
            nextMark = if (reached < MARK_STEP_M) MARK_STEP_M else reached + MARK_STEP_M
            val label = if (reached >= 1000) "${(reached / 1000).toInt()} km" else "${reached.toInt()} m"
            return Moment("milestone", "he has just passed $label")
        }

        // --- reactive: only once the belt is genuinely at pace ---------------
        val moving = s.speed > 0.5 && s.ramping.isEmpty()

        if (moving && s.avgSpeed > 1.0) {
            if (s.speed < s.avgSpeed * DROP_RATIO) {
                if (dropSince == 0L) dropSince = now
                if (now - dropSince >= DROP_HOLD_MS) {
                    dropSince = 0L
                    return Moment("pace_drop",
                        "his pace has been sitting below his session average for the last minute")
                }
            } else {
                dropSince = 0L
            }
        } else {
            dropSince = 0L
        }

        if (moving) {
            if (steadySince == 0L || Math.abs(s.speed - steadyRef) > STEADY_BAND_KPH) {
                steadyRef = s.speed
                steadySince = now
            } else if (now - steadySince >= STEADY_HOLD_MS) {
                steadySince = now
                return Moment("steady",
                    "he has held the same pace steadily for the last few minutes")
            }
        } else {
            steadySince = 0L
        }

        // --- nothing has happened for a while --------------------------------
        if (lastSpokeAt > 0L && now - lastSpokeAt >= CHECKIN_MS) {
            return Moment("checkin", "nothing in particular has happened; he is simply still going")
        }
        return null
    }

    /** The floor between lines, plus the per-kind cooldowns. */
    private fun allowed(kind: String, now: Long): Boolean {
        // A segment change gets a much shorter floor than everything else. On
        // the first real guided walk a 500 m marker landed three seconds before
        // "Hill one" began, and the floor suppressed the hill — the coach
        // announced a distance marker and said nothing about the ground about
        // to change underfoot. Those are not the same class of event.
        val floor = if (kind == "segment") SEGMENT_GAP_MS else MIN_GAP_MS
        if (lastSpokeAt > 0L && now - lastSpokeAt < floor) return false
        val gap = when (kind) {
            "pace_drop" -> DROP_GAP_MS
            "steady" -> STEADY_GAP_MS
            else -> 0L
        }
        val last = kindLastAt[kind] ?: return true
        return now - last >= gap
    }

    /**
     * What the console says on its own when Home Assistant does not answer.
     * Flat and factual — the model's job is to be better than these, not the
     * other way round.
     */
    fun fallback(kind: String): String = when (kind) {
        "warmup_done" -> "Warm-up done. Settle into a pace that feels easy."
        "milestone" -> "Another one down. Keep it steady."
        "cooldown" -> "Easing down now. Good work."
        "segment" -> "New stretch coming up."
        "resumed" -> "Back on it."
        "pace_drop" -> "Pace has eased off a little."
        "steady" -> "Nice rhythm — that pace is holding well."
        else -> "Still going. That is the whole job."
    }

    /** The moment, plus everything HA needs to write a sentence about it. */
    @Synchronized fun payload(m: Moment, s: Snapshot): String {
        val history = JSONArray()
        for (line in said) history.put(line)
        return JSONObject()
            .put("kind", m.kind)
            .put("detail", m.detail)
            .put("workout", s.workout)
            .put("elapsed", Math.round(s.elapsed))
            .put("distance", Math.round(s.distance))
            .put("speed", String.format("%.1f", s.speed).toDouble())
            .put("avg_speed", String.format("%.1f", s.avgSpeed).toDouble())
            .put("incline", String.format("%.1f", s.incline).toDouble())
            .put("calories", Math.round(s.calories))
            .put("pulse", s.pulse)
            .put("who", s.who)
            .put("plan", s.plan)
            .put("segment_label", s.segmentLabel)
            .put("segment", s.segment)
            .put("segments", s.segments)
            .put("suggest_pace", s.suggestPace)
            .put("said", history)
            .toString()
    }
}
