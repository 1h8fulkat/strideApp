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

        /**
         * How far ahead of a segment change to speak.
         *
         * The coach used to fire *on* the change, which put the line behind the
         * event: Home Assistant takes about three and a half seconds to answer
         * — measured at 3.75 s on a real walk — and then the sentence has to be
         * spoken. "Descent coming up" arrived while already descending.
         *
         * Fifteen seconds puts the words in front of the ground. It is also
         * long enough that the line can say what is about to happen rather than
         * narrating what just did.
         */
        const val LOOKAHEAD_S = 15.0

        /**
         * The same look-ahead, in metres, for a route.
         *
         * A route is driven by distance, so `segmentLeft` counts down in metres
         * and comparing it to a number of seconds is a unit error that happens
         * to be nearly right at walking pace. Twenty-five metres is about
         * eighteen seconds at 5 km/h — one grid step of the converted profile,
         * which is the natural unit for "the stretch you are about to enter".
         */
        const val LOOKAHEAD_M = 25.0

        /**
         * How much the ground must change before it is worth warning about.
         *
         * Nothing for the four hand-authored templates: their segments are far
         * apart by design and each is a phase of the walk. Routes are why this
         * exists — a converted walk has a segment every 25 m of quantisation,
         * so announcing each one narrates the rounding rather than the terrain.
         * The first route anybody walked had thirty-five, and the coach talked
         * through all of it.
         */
        const val SEGMENT_MIN_CHANGE = 2.0

        /**
         * Below this, a walk gets no closing line.
         *
         * A rule that came from the belt: stopping a route four minutes in
         * summary that had nothing to sum up. A walk that short is an
         * interruption, not a session, and a coach reflecting on it sounds like
         * it was not paying attention.
         */
        const val SUMMARY_MIN_MS = 4 * 60_000L + 30_000L

        /**
         * How far through a plan to ask for the closing line.
         *
         * Not at the end. Home Assistant takes a few seconds to answer and the
         * summary appears the instant the belt stops, so asking then would put
         * a blank space where the sentence goes and fill it after somebody has
         * already turned away. At 98% everything worth saying is known — the
         * distance, the pace held, the climb — and the answer is waiting before
         * the screen needs it.
         */
        const val SUMMARY_AT = 0.98

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

    /** A line the console wrote itself, and which moment it belongs to. The
     *  kind matters on the way out: "summary" is held for the summary screen
     *  rather than shown over the walk, exactly as it is when HA answers. */
    data class Local(val kind: String, val text: String)

    // Poll-thread state.
    private var lastSession = Session.WELCOME
    private var lastSpokeAt = 0L
    private var nextMark = FIRST_MARK_M
    private var pausedAt = 0L
    private val kindLastAt = HashMap<String, Long>()

    private var lastSegment = 0
    /** Which segment we have already warned *from*, so it happens once. */
    private var warnedFor = 0
    /** The incline the coach last spoke about, so it can tell a hill from a
     *  rounding step on a route. See SEGMENT_MIN_CHANGE. */
    private var spokenIncline = 0.0

    /** The briefing is offered once per walk, at the top. */
    private var openingSent = false

    /** The closing line is asked for once per walk. */
    private var summarySent = false
    private var dropSince = 0L
    private var steadySince = 0L
    private var steadyRef = 0.0

    /** Set when a moment goes out, cleared when a line comes back. */
    @Volatile private var awaitingSince = 0L
    @Volatile private var awaitingKind = ""

    /**
     * The line to show if that answer never arrives, built at the moment the
     * moment happens rather than when the wait runs out — which is the only
     * point where the snapshot is in hand. See [canned].
     */
    @Volatile private var awaitingCanned = ""

    /** How many of each kind have fired this walk, so a canned line that has
     *  to be used twice is not the same sentence twice. */
    private val kindCount = HashMap<String, Int>()

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
        spokenIncline = 0.0
        awaitingSince = 0L
        awaitingKind = ""
        awaitingCanned = ""
        kindCount.clear()
        openingSent = false
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
        awaitingCanned = ""
    }

    /**
     * Remember a line that was actually delivered. Called from the MQTT thread.
     *
     * @return how long Home Assistant took to answer, in ms, or -1 if nothing
     *         was waiting on it. Logged by the caller: the fallback firing is
     *         the only symptom this has, and "it seemed laggy" is not something
     *         you can act on. A number is.
     */
    @Synchronized fun heard(line: String): Long {
        val took = if (awaitingSince == 0L) -1L
                   else SystemClock.elapsedRealtime() - awaitingSince
        awaitingSince = 0L
        awaitingKind = ""
        awaitingCanned = ""
        said.addLast(line)
        while (said.size > 3) said.removeFirst()
        return took
    }

    /**
     * HA has had its chance and said nothing — the canned line for the moment
     * still in flight, or null if there is nothing waiting.
     */
    @Synchronized fun timedOut(): Local? {
        if (awaitingSince == 0L) return null
        if (SystemClock.elapsedRealtime() - awaitingSince < FALLBACK_MS) return null
        return giveUp()
    }

    /**
     * Stop waiting now and take the canned line.
     *
     * Separate from [timedOut] because there are two ways to end up without an
     * answer and only one of them is a wait. If the broker is down, or the
     * walker is not set up for coaching, then nothing was ever asked and the
     * eight seconds are spent staring at a ribbon that was never going to
     * arrive. Say the local line straight away instead.
     *
     * Returns null when there is nothing in flight.
     */
    @Synchronized fun giveUp(): Local? {
        if (awaitingSince == 0L) return null
        val local = Local(awaitingKind, awaitingCanned.ifEmpty { fallback(awaitingKind) })
        awaitingSince = 0L
        awaitingKind = ""
        awaitingCanned = ""
        said.addLast(local.text)
        while (said.size > 3) said.removeFirst()
        return local
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
        kindCount[moment.kind] = (kindCount[moment.kind] ?: 0) + 1
        // Built here, while the frame that caused the moment is still in hand.
        awaitingCanned = canned(moment.kind, s)
        return moment
    }

    private fun detect(s: Snapshot, was: Int, now: Long): Moment? {
        // --- the briefing -----------------------------------------------------
        //
        // Said once, at the top, before anything has happened. A walk used to
        // begin in silence and the first word came only when the warm-up ended,
        // by which point you are already walking and the shape of the thing is
        // a surprise you discover a hill at a time.
        //
        // Not a summary of numbers — those are on the screen. It is the one
        // moment where the coach knows something you do not: what is coming.
        if (!openingSent && s.session != Session.WELCOME && s.plan.isNotEmpty()) {
            openingSent = true
            val shape = StringBuilder("this walk is \"${s.plan}\"")
            if (s.planClimbM > 0) {
                // A route: real distance and real ascent, measured outdoors.
                shape.append(", ${"%.1f".format(s.planTotalSec / 1000)} km with " +
                             "${"%.0f".format(s.planClimbM)} metres of climbing in it")
            } else if (s.planTotalSec > 0) {
                shape.append(", ${(s.planTotalSec / 60).toInt()} minutes")
            }
            if (s.planPeakIncline >= 4) {
                shape.append(", steepest around ${"%.0f".format(s.planPeakIncline)}%")
            }
            return Moment("opening",
                "$shape. He is at the very start and the belt is still easing up. " +
                "Set him up for what is coming, and tell him to settle in and let " +
                "the muscles warm — do not read the numbers back as a list")
        }

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

        // The closing line, asked for early so it is there when the summary is.
        if (!summarySent && s.segments > 0 && s.planTotalSec > 0 &&
            s.elapsed * 1000 >= SUMMARY_MIN_MS &&
            s.planElapsed >= s.planTotalSec * SUMMARY_AT) {
            summarySent = true
            return Moment("summary",
                "the walk is about to finish. This is the closing line, shown on " +
                "the summary screen next to the numbers — so do not read the " +
                "numbers back")
        }

        // A guided walk changing segment is the strongest moment there is — the
        // ground is about to change under him and he should hear why. Said
        // *before* it happens, not after: see LOOKAHEAD_S.
        if (s.segments > 0 && s.segment != lastSegment) {
            lastSegment = s.segment
            warnedFor = 0            // the warning belongs to the segment ahead
        }

        // Metres on a route, seconds on a template — segmentLeft is whichever
        // the walk is driven by, so the threshold has to match it.
        val lookahead = if (s.segmentLeftIsDistance) LOOKAHEAD_M else LOOKAHEAD_S

        // On a route, only speak when the ground has actually moved. Every
        // guarantee below this is about terrain; a one-percent tick between two
        // 25 m grid steps is the profile rounding, not a hill.
        val worthSaying = !s.segmentLeftIsDistance ||
            Math.abs(s.nextIncline - spokenIncline) >= SEGMENT_MIN_CHANGE

        if (s.segments > 0 && s.nextLabel.isNotEmpty() && worthSaying &&
            s.segmentLeft in 0.0..lookahead && warnedFor != s.segment) {
            warnedFor = s.segment
            spokenIncline = s.nextIncline
            val climbing = s.nextIncline > s.targetIncline + 0.4
            val dropping = s.nextIncline < s.targetIncline - 0.4
            val shape = when {
                climbing -> "the ground rises to ${"%.1f".format(s.nextIncline)}%"
                dropping -> "the ground drops to ${"%.1f".format(s.nextIncline)}%"
                else -> "it stays about level at ${"%.1f".format(s.nextIncline)}%"
            }
            // A route's "label" is its own name repeated once per segment, so
            // naming it thirty-five times says nothing. The ground is the news.
            val where = if (s.segmentLeftIsDistance)
                "in about ${s.segmentLeft.toInt()} metres"
            else
                "in about ${s.segmentLeft.toInt()} seconds"
            val what = if (s.segmentLeftIsDistance) "the walk reaches a point where"
                       else "the walk moves into \"${s.nextLabel}\", segment " +
                            "${s.segment + 1} of ${s.segments}, where"
            return Moment("segment",
                "$where $what $shape. Tell him what is coming, not what he is " +
                "doing now")
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
     *
     * The generic form, used when there is no frame to hand. [canned] is the
     * one that actually gets shown, and it says more.
     */
    fun fallback(kind: String): String = when (kind) {
        "warmup_done" -> "Warm-up done. Settle into a pace that feels easy."
        "milestone" -> "Another one down. Keep it steady."
        "cooldown" -> "Easing down now. Good work."
        "opening" -> "Right — let's get into it. Settle in and warm those legs."
        "segment" -> "New stretch coming up."
        "resumed" -> "Back on it."
        "pace_drop" -> "Pace has eased off a little."
        "steady" -> "Nice rhythm — that pace is holding well."
        "summary" -> "That is another one done."
        else -> "Still going. That is the whole job."
    }

    /**
     * The local line for a moment, built from the frame that caused it.
     *
     * Three "New stretch coming up" in one walk is what prompted this. Every
     * one of them was correct and none of them was worth reading, and worse,
     * they were *identical* — the same seven words, so the third one read as a
     * console that had stopped paying attention rather than one whose coach was
     * briefly unreachable.
     *
     * The console is not short of things to say here. It knows the gradient it
     * is about to drive the deck to, how far off that is, how far he has come
     * and how long he has been going. It cannot phrase any of it as well as the
     * model can, which is the whole reason the model is asked first — but a
     * flat sentence with a real number in it beats a warm one with nothing.
     *
     * [n] rotates the wording for the kinds that can fire repeatedly, so a walk
     * with a poor connection does not become a loop.
     */
    private fun canned(kind: String, s: Snapshot): String {
        val n = kindCount[kind] ?: 1
        // 6.0 reads as "6", 6.5 stays "6.5". A hill is not a measurement.
        fun pct(v: Double) = "%.1f".format(v).removeSuffix(".0")
        return when (kind) {
            "segment" -> {
                val where = if (s.segmentLeftIsDistance)
                    "In about ${s.segmentLeft.toInt()} metres"
                else
                    "In about ${s.segmentLeft.toInt()} seconds"
                when {
                    s.nextIncline > s.targetIncline + 0.4 ->
                        "$where the ground rises to ${pct(s.nextIncline)}%. " +
                        "Shorten the stride and stay tall."
                    s.nextIncline < s.targetIncline - 0.4 ->
                        "$where it drops to ${pct(s.nextIncline)}%. " +
                        "Let the legs turn over — don't chase it."
                    else ->
                        "$where it settles at about ${pct(s.nextIncline)}%. " +
                        "Hold what you have."
                }
            }
            "milestone" -> {
                val far = if (s.distance >= 1000) "%.1f km".format(s.distance / 1000)
                          else "${s.distance.toInt()} m"
                if (n % 2 == 1) "$far, ${Math.round(s.elapsed / 60)} minutes. Keep it steady."
                else "$far down. That pace is doing the work."
            }
            "steady" -> "${"%.1f".format(s.speed)} km/h, held for minutes. " +
                        "That is the rhythm — stay in it."
            "pace_drop" -> "Sitting a little under your average of " +
                           "${"%.1f".format(s.avgSpeed)}. No need to chase it."
            "checkin" -> {
                val mins = Math.round(s.elapsed / 60)
                if (n % 2 == 1) "$mins minutes in, ${s.distance.toInt()} metres done."
                else "Still going at $mins minutes. That is the whole job."
            }
            "warmup_done" ->
                if (s.segments > 0) "Warm-up done — ${s.segments} stretches ahead. " +
                                    "Settle into a pace that feels easy."
                else fallback(kind)
            "cooldown" -> "Easing down from ${"%.1f".format(s.avgSpeed)} average. Good work."
            "summary" -> "${"%.2f".format(s.distance / 1000)} km, " +
                         "${Math.round(s.elapsed / 60)} minutes. That is another one done."
            else -> fallback(kind)
        }
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
