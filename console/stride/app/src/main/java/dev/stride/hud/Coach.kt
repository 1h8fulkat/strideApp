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

        /**
         * Effort, measured against the walk rather than against a chart.
         *
         * The console does not know his age, his maximum, or his zones, and
         * inventing them from a birthday would be worse than not having them —
         * a number dressed up as physiology. What it does know is what his
         * heart has been doing *for the last few minutes*, so that is the
         * reference: twelve beats clear of the session's own average, held,
         * means the work has genuinely got harder, whoever he is.
         *
         * Deliberately not a warning. A pulse that climbs on a hill is the
         * system working, not a fault, and the coach's job here is to notice
         * effort and say something useful about breathing and rhythm — never
         * to diagnose, alarm, or tell anyone to stop. See KINDS in
         * stride_coach_live.py, which says so to the model too.
         */
        const val HR_RISE_BPM = 12
        const val HR_SETTLE_BPM = 5
        const val HR_HOLD_MS = 45_000L
        const val HR_GAP_MS = 6 * 60_000L

        /**
         * How long before a session average is worth comparing against.
         *
         * Early on the average is two minutes of warm-up, so everything looks
         * like a climb against it. It has to have seen the walk before it can
         * describe a departure from it.
         */
        const val HR_SETTLED_AFTER_S = 240.0

        /**
         * Heart-rate zones, as a share of maximum.
         *
         * The five everybody uses, and they only exist when the walker has
         * given an age — see Settings.Person.age. Everything below is a
         * fraction of `220 - age`, which is a population average with about
         * ±10-12 bpm of spread between two people of the same age. That is
         * wide enough that the coach must never read a zone number out as if
         * it were measured: it says "easy aerobic" and "working hard", not
         * "zone 2" and "78% of max". The boundaries are for deciding *whether
         * to speak*, not for reciting.
         */
        const val Z1_TOP = 0.60   // recovery, barely working
        const val Z2_TOP = 0.70   // easy aerobic — where a walk wants to live
        const val Z3_TOP = 0.80   // steady
        const val Z4_TOP = 0.90   // threshold; above this is Z5

        /**
         * The band a walk is aiming for, and how long it has to sit outside it
         * before that is worth a word.
         *
         * Z2 and Z3 — easy aerobic through steady. Below Z2 the walk is not
         * really doing anything, above Z3 it has stopped being a walk. Both
         * edges are held for a good while before the coach says so, because a
         * heart rate crosses a line constantly and a coach that reacts to every
         * crossing is a nag.
         */
        const val ZONE_FLOOR = Z1_TOP
        const val ZONE_CEIL = Z3_TOP
        const val ZONE_HOLD_MS = 60_000L
        const val ZONE_GAP_MS = 5 * 60_000L

        /**
         * How much pace to suggest when the heart rate wants moving.
         *
         * Deliberately a nudge and not a calculation. Turning "your heart rate
         * is 8 bpm low" into a speed needs a model of *this* walker's response
         * on *this* gradient, and the console has no such thing — inventing one
         * would be exactly the number-dressed-up-as-physiology this file has
         * argued against from the start. Half a km/h is a change a walker can
         * feel and undo, and the coach can suggest it again in five minutes if
         * it was not enough.
         */
        const val ZONE_NUDGE_KPH = 0.5

        /**
         * Zone names, in the coach's own register rather than the textbook's.
         *
         * Index is the zone, 1-5. These are what gets spoken; the numbers stay
         * behind the curtain — see [Z1_TOP].
         */
        val ZONE_WORDS = arrayOf(
            "", "barely ticking over", "easy aerobic", "steady",
            "working hard", "flat out",
        )

        /** How long to wait for HA before showing the canned line instead. */
        const val FALLBACK_MS = 8_000L

        /**
         * First mark at 500 m — a short walk deserves one — then every km.
         *
         * On an imperial console the same idea in the walker's own round
         * numbers: half a mile, then every mile. Scaling the metric marks
         * instead would have the coach announcing 0.62 and 1.24 miles, which
         * are not milestones, they are conversions.
         */
        const val FIRST_MARK_M = 500.0
        const val MARK_STEP_M = 1000.0
        const val FIRST_MARK_MI = 804.672      // half a mile
        const val MARK_STEP_MI = 1609.344      // a mile
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

    /**
     * Which zone a share of maximum falls in, 1-5.
     *
     * Indexes [ZONE_WORDS], and the word is the part anyone hears — see
     * [Z1_TOP] for why the number stays out of the coach's mouth.
     */
    private fun zoneOf(share: Double): Int = when {
        share < Z1_TOP -> 1
        share < Z2_TOP -> 2
        share < Z3_TOP -> 3
        share < Z4_TOP -> 4
        else -> 5
    }

    /** The closing line is asked for once per walk. */
    private var summarySent = false
    private var dropSince = 0L
    private var steadySince = 0L
    private var steadyRef = 0.0

    /** Effort state — see HR_RISE_BPM. [hrElevated] survives a strap dropping
     *  out, because a lost signal is not the same as a heart rate coming down. */
    private var hrHighSince = 0L
    private var hrBackSince = 0L
    private var hrElevated = false

    /** Zone state — how long the heart rate has been under or over the band
     *  the walk is aiming for. Only ever set when an age is known. */
    private var zoneLowSince = 0L
    private var zoneHighSince = 0L

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
        hrHighSince = 0L
        hrBackSince = 0L
        hrElevated = false
        zoneLowSince = 0L
        zoneHighSince = 0L
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
            val shape = StringBuilder("this workout is \"${s.plan}\"")
            if (s.planClimbM > 0) {
                // A route: real distance and real ascent, measured outdoors.
                shape.append(", ${s.far(s.planTotalSec)} with " +
                             "${s.up(s.planClimbM)} of climbing in it")
            } else if (s.planTotalSec > 0) {
                shape.append(", ${(s.planTotalSec / 60).toInt()} minutes")
            }
            if (s.planPeakIncline >= 4) {
                shape.append(", steepest around ${"%.0f".format(s.planPeakIncline)}%")
            }
            return Moment("opening",
                "$shape. He is at the very start, just up to the warm-up pace. " +
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
                "the workout is about to finish. This is the closing line, shown on " +
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
                "in about ${s.ahead(s.segmentLeft)}"
            else
                "in about ${s.segmentLeft.toInt()} seconds"
            val what = if (s.segmentLeftIsDistance) "the workout reaches a point where"
                       else "the workout moves into \"${s.nextLabel}\", segment " +
                            "${s.segment + 1} of ${s.segments}, where"
            return Moment("segment",
                "$where $what $shape. Tell him what is coming, not what he is " +
                "doing now")
        }

        // --- distance: the backbone -----------------------------------------
        val firstMark = if (s.mi()) FIRST_MARK_MI else FIRST_MARK_M
        val markStep  = if (s.mi()) MARK_STEP_MI else MARK_STEP_M
        /* `nextMark` is armed at the metric first mark before any state has
           arrived, and the unit setting is only knowable from a frame — so the
           ladder is pulled onto the right rungs here, before it is compared
           against. Without this an imperial walk announced "half a mile" at
           500 m, which is 0.31. */
        if (nextMark < firstMark) nextMark = firstMark

        if (s.distance >= nextMark) {
            val reached = nextMark
            nextMark = if (reached < markStep) markStep else reached + markStep
            val label = s.mark(reached)
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

        // --- zones: effort against the walker, not against the walk ----------
        //
        // The session-average test below is good at "harder than you have been"
        // and blind to "you have been taking it easy for twenty minutes" — a
        // walk that never troubles the heart is perfectly steady, and reads as
        // fine. An age turns the pulse into a fraction of a maximum, which is
        // the only way the console can tell those two apart.
        //
        // Both edges are worth saying and they are not symmetrical. Under the
        // floor is an invitation and comes with a pace to try. Over the ceiling
        // is about form first — stride, breathing — and pace second, because
        // "slow down" is the advice a walker has already thought of.
        if (moving && s.pulse > 0 && s.hrMax > 0 && s.elapsed >= HR_SETTLED_AFTER_S) {
            val share = s.pulse.toDouble() / s.hrMax
            val zone = zoneOf(share)

            if (share < ZONE_FLOOR) {
                zoneHighSince = 0L
                if (zoneLowSince == 0L) zoneLowSince = now
                if (now - zoneLowSince >= ZONE_HOLD_MS) {
                    zoneLowSince = 0L
                    val suggest = s.speed + ZONE_NUDGE_KPH
                    return Moment("zone_low",
                        "his heart rate has been sitting at ${s.pulse} bpm for the last " +
                        "minute, which is ${ZONE_WORDS[zone]} for him and below the easy " +
                        "aerobic range this workout wants. He is at " +
                        "${s.pace(s.speed)}; a little more pace would bring it " +
                        "up. Invite him to try ${s.pace(suggest)} if he has it " +
                        "in him, and make clear it is an offer, not an instruction")
                }
            } else if (share > ZONE_CEIL) {
                zoneLowSince = 0L
                if (zoneHighSince == 0L) zoneHighSince = now
                if (now - zoneHighSince >= ZONE_HOLD_MS) {
                    zoneHighSince = 0L
                    val suggest = (s.speed - ZONE_NUDGE_KPH).coerceAtLeast(0.0)
                    return Moment("zone_high",
                        "his heart rate has been at ${s.pulse} bpm for the last minute, " +
                        "which is ${ZONE_WORDS[zone]} for him and above where this workout is " +
                        "meant to sit. Tell him to lengthen his stride and breathe longer " +
                        "first, and to ease back to about " +
                        "${s.pace(suggest)} from " +
                        "${s.paceNum(s.speed)} if it does not settle. Calm, not alarmed " +
                        "— a high heart rate on a hill is the body working, not a fault")
                }
            } else {
                zoneLowSince = 0L
                zoneHighSince = 0L
            }
        } else {
            zoneLowSince = 0L
            zoneHighSince = 0L
        }

        // --- effort: the one thing the belt cannot see -----------------------
        //
        // Speed and incline say what the treadmill is doing. Only the strap
        // says what it is costing, and the gap between those two is the most
        // useful thing the coach has ever been handed: the same hill on a
        // tired day is a different walk, and until now nothing on this console
        // could tell the difference.
        //
        // This runs whether or not an age is known, and is the *whole* of the
        // heart-rate coaching when it is not: a walker who never gives one is
        // still told when the work has got harder, measured against their own
        // walk. Nobody has to tell a treadmill their age to be coached by it.
        if (moving && s.pulse > 0 && s.avgPulse > 0 && s.elapsed >= HR_SETTLED_AFTER_S) {
            val above = s.pulse - s.avgPulse

            if (!hrElevated) {
                if (above >= HR_RISE_BPM) {
                    if (hrHighSince == 0L) hrHighSince = now
                    if (now - hrHighSince >= HR_HOLD_MS) {
                        hrHighSince = 0L
                        hrElevated = true
                        hrBackSince = 0L
                        return Moment("hr_climb",
                            "his heart rate has been sitting around ${s.pulse} bpm for the " +
                            "last minute, about $above above his session average of " +
                            "${s.avgPulse} — he is working harder than he has been")
                    }
                } else {
                    hrHighSince = 0L
                }
            } else {
                if (above <= HR_SETTLE_BPM) {
                    if (hrBackSince == 0L) hrBackSince = now
                    if (now - hrBackSince >= HR_HOLD_MS) {
                        hrBackSince = 0L
                        hrElevated = false
                        return Moment("hr_settled",
                            "his heart rate has come back to ${s.pulse} bpm, level with his " +
                            "session average of ${s.avgPulse} again, after a spell of working " +
                            "harder — he has recovered without stopping")
                    }
                } else {
                    hrBackSince = 0L
                }
            }
        } else {
            // No reading, or too early to have an average worth comparing to.
            // The timers stop; whether he is elevated is not something a lost
            // signal gets to answer.
            hrHighSince = 0L
            hrBackSince = 0L
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
            "hr_climb", "hr_settled" -> HR_GAP_MS
            "zone_low", "zone_high" -> ZONE_GAP_MS
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
        "hr_climb" -> "Effort is up. Breathe into it and keep the rhythm."
        "hr_settled" -> "That has come back down. Nicely recovered."
        "zone_low" -> "Heart rate is easing off. A touch more pace if you have it."
        "zone_high" -> "Long stride, long breaths. Let that come down a little."
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
    /* ---- units --------------------------------------------------------
     *
     * The coach talks in numbers, and every one of them used to be metric
     * whatever the console was set to — spoken aloud as well as written to the
     * ribbon, so an imperial walker was told "5.0 km/h" by a display reading
     * 3.1 mph. These are also what goes into the prompt Home Assistant writes
     * from, so the language model is given the walker's own units and does not
     * have to be told twice.
     *
     * `Snapshot.units` is the setting; "mi" is the only imperial value. */
    private fun Snapshot.mi() = units == "mi"

    /** A pace, with its unit. */
    private fun Snapshot.pace(kph: Double): String =
        if (mi()) "%.1f mph".format(kph * 0.621371) else "%.1f km/h".format(kph)

    /** A pace with no unit, for a sentence that supplies its own. */
    private fun Snapshot.paceNum(kph: Double): String =
        if (mi()) "%.1f".format(kph * 0.621371) else "%.1f".format(kph)

    /** A distance covered: the big unit once there is enough of it. */
    private fun Snapshot.far(metres: Double): String = when {
        mi() -> {
            val miles = metres / 1609.344
            if (miles >= 0.1) "%.2f miles".format(miles)
            else "${Math.round(metres * 3.280840)} feet"
        }
        metres >= 1000 -> "%.1f km".format(metres / 1000)
        else -> "${metres.toInt()} metres"
    }

    /** A distance still ahead — short, so the small unit does the work. */
    private fun Snapshot.ahead(metres: Double): String =
        if (mi()) "${Math.round(metres * 3.280840)} feet" else "${metres.toInt()} metres"

    /** Ascent. */
    private fun Snapshot.up(metres: Double): String =
        if (mi()) "${Math.round(metres * 3.280840)} feet" else "${metres.toInt()} metres"

    /**
     * A round distance marker the walk has just passed.
     *
     * Said as a phrase rather than a measurement — "half a mile", "3 miles" —
     * because these are the marks [FIRST_MARK_M] and [markStep] put there on
     * purpose, and a coach reading "0.5 miles" aloud sounds like a readout.
     */
    private fun Snapshot.mark(metres: Double): String {
        if (!mi()) {
            return if (metres >= 1000) "${(metres / 1000).toInt()} km" else "${metres.toInt()} m"
        }
        val miles = metres / 1609.344
        if (miles < 0.9) return "half a mile"
        val n = "%.1f".format(miles).removeSuffix(".0")
        return if (n == "1") "1 mile" else "$n miles"
    }

    private fun canned(kind: String, s: Snapshot): String {
        val n = kindCount[kind] ?: 1
        // 6.0 reads as "6", 6.5 stays "6.5". A hill is not a measurement.
        fun pct(v: Double) = "%.1f".format(v).removeSuffix(".0")
        return when (kind) {
            "segment" -> {
                val where = if (s.segmentLeftIsDistance)
                    "In about ${s.ahead(s.segmentLeft)}"
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
                val far = s.far(s.distance)
                if (n % 2 == 1) "$far, ${Math.round(s.elapsed / 60)} minutes. Keep it steady."
                else "$far down. That pace is doing the work."
            }
            "steady" -> "${s.pace(s.speed)}, held for minutes. " +
                        "That is the rhythm — stay in it."
            "pace_drop" -> "Sitting a little under your average of " +
                           "${s.paceNum(s.avgSpeed)}. No need to chase it."
            "hr_climb" -> if (n % 2 == 1)
                "${s.pulse} bpm, up from ${s.avgPulse} today. Breathe steady — " +
                "you are doing the work now."
            else
                "Heart rate is up around ${s.pulse}. Long breaths, same rhythm."
            "hr_settled" -> "Back to ${s.pulse} bpm. That recovered well while still moving."
            // The canned pair carry the suggested pace too. These are what gets
            // spoken when Home Assistant is unreachable, and a nudge without a
            // number is the half of the advice that is no use.
            "zone_low" -> "${s.pulse} bpm — easy going. Try " +
                "${s.paceNum(s.speed + ZONE_NUDGE_KPH)} if you have it in you."
            "zone_high" -> "${s.pulse} bpm. Lengthen the stride, breathe long — " +
                "and ease to ${s.paceNum((s.speed - ZONE_NUDGE_KPH).coerceAtLeast(0.0))} " +
                "if it stays up."
            "checkin" -> {
                val mins = Math.round(s.elapsed / 60)
                if (n % 2 == 1) "$mins minutes in, ${s.far(s.distance)} done."
                else "Still going at $mins minutes. That is the whole job."
            }
            "warmup_done" ->
                if (s.segments > 0) "Warm-up done — ${s.segments} stretches ahead. " +
                                    "Settle into a pace that feels easy."
                else fallback(kind)
            "cooldown" -> "Easing down from ${s.paceNum(s.avgSpeed)} average. Good work."
            "summary" -> "${s.far(s.distance)}, " +
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
            .put("avg_pulse", s.avgPulse)
            .put("max_pulse", s.maxPulse)
            // The walker's own maximum, not this walk's — 0 when no age has
            // been given, which is HA's signal that zones are not available and
            // it must not talk about them. See Settings.Person.age.
            .put("hr_max", s.hrMax)
            .put("hr_zone", if (s.hrMax > 0 && s.pulse > 0)
                zoneOf(s.pulse.toDouble() / s.hrMax) else 0)
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
