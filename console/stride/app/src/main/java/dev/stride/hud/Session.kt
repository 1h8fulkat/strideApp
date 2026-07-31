package dev.stride.hud

/**
 * Where the *app* thinks we are in a workout.
 *
 * Deliberately not the board's `WorkoutMode`. The board's state machine is
 * one-way — it will go Idle → Running but refuses Running → Idle — so a workout
 * that has plainly finished still reports `running` for hours afterwards. Home
 * Assistant then believes the treadmill has been in use since breakfast, and the
 * elapsed counter never returns to zero.
 *
 * The user pressing START and END is the fact we can actually trust, so that is
 * what drives the HUD, the timer, and everything published to HA. The board's
 * mode is kept only for the two things it alone knows: the belt interlock and
 * the safety key.
 */
object Session {
    const val WELCOME = 0    // "what are we doing today?"
    const val WARMUP = 1     // easing in at walking pace, skippable
    const val ACTIVE = 2     // the workout proper
    const val PAUSED = 3     // belt stopped, timer held
    const val COOLDOWN = 4   // easing back down, skippable
    const val SUMMARY = 5    // post-workout recap

    fun name(v: Int) = when (v) {
        WELCOME -> "welcome"; WARMUP -> "warmup"; ACTIVE -> "running"
        PAUSED -> "paused"; COOLDOWN -> "cooldown"; SUMMARY -> "summary"
        else -> "unknown"
    }

    /** States in which the belt is live and the clock is running. */
    fun isMoving(v: Int) = v == WARMUP || v == ACTIVE || v == COOLDOWN
}

/** One frame of everything both the HUD and Home Assistant need. */
data class Snapshot(
    val speed: Double,
    val incline: Double,
    val targetSpeed: Double,
    val targetIncline: Double,
    val distance: Double,
    val elapsed: Double,
    val calories: Double,
    val pulse: Int,
    val fan: Int,
    val session: Int,
    val workout: String,
    val dmk: Boolean,
    /** "warmup", "resuming", "cooldown", or empty when no ramp is in flight. */
    val ramping: String,
    /** Seconds left in a timed phase (warm-up / cool-down); 0 otherwise. */
    val phaseLeft: Double,
    /** The board's own WorkoutMode, carried purely for diagnosis. 8 = safety key out. */
    val boardMode: Int,
    val avgSpeed: Double,
    val maxSpeed: Double,
    val avgIncline: Double,
    val maxIncline: Double,
    /** Who tapped their name on the welcome screen. */
    val who: String = "",
    /** Guided walk, or empty on a casual one. */
    val plan: String = "",
    val segment: Int = 0,
    val segments: Int = 0,
    val segmentLabel: String = "",
    val segmentLeft: Double = 0.0,
    /** The segment after this one, so the coach can warn before it arrives. */
    val nextLabel: String = "",
    val nextIncline: Double = 0.0,
    /** Plan time wrapped into the current lap. Equals elapsed unless looping. */
    val planElapsed: Double = 0.0,
    /** Which lap of an open-ended circuit; 1 for an ordinary walk. */
    val planLap: Int = 1,
    /** True when the route is a circuit with no end but STOP. */
    val planLoops: Boolean = false,
    /** Pace the plan is suggesting, km/h. 0 when it has no opinion. */
    val suggestPace: Double = 0.0,
    /** False once a manual incline press has taken the deck back for this segment. */
    val inclineAuto: Boolean = false,
) {
    val mode: String get() = Session.name(session)

    fun toJson(): String = buildString {
        append("{")
        append("\"speed\":${"%.1f".format(speed)},")
        append("\"incline\":${"%.1f".format(incline)},")
        append("\"targetSpeed\":${"%.1f".format(targetSpeed)},")
        append("\"targetIncline\":${"%.1f".format(targetIncline)},")
        append("\"distance\":${"%.0f".format(distance)},")
        append("\"elapsed\":${"%.0f".format(elapsed)},")
        append("\"calories\":${"%.0f".format(calories)},")
        append("\"pulse\":$pulse,")
        append("\"fan\":$fan,")
        append("\"workout\":\"$workout\",")
        append("\"dmk\":$dmk,")
        append("\"ramping\":\"$ramping\",")
        append("\"phaseLeft\":${"%.0f".format(phaseLeft)},")
        append("\"boardMode\":$boardMode,")
        append("\"avgSpeed\":${"%.1f".format(avgSpeed)},")
        append("\"maxSpeed\":${"%.1f".format(maxSpeed)},")
        append("\"avgIncline\":${"%.1f".format(avgIncline)},")
        append("\"maxIncline\":${"%.1f".format(maxIncline)},")
        append("\"who\":\"$who\",")
        append("\"plan\":\"$plan\",")
        append("\"segment\":$segment,")
        append("\"segments\":$segments,")
        append("\"segmentLabel\":\"$segmentLabel\",")
        append("\"segmentLeft\":${"%.0f".format(segmentLeft)},")
        append("\"nextLabel\":\"$nextLabel\",")
        append("\"nextIncline\":${"%.1f".format(nextIncline)},")
        append("\"planElapsed\":${"%.0f".format(planElapsed)},")
        append("\"planLap\":$planLap,")
        append("\"planLoops\":$planLoops,")
        append("\"suggestPace\":${"%.1f".format(suggestPace)},")
        append("\"inclineAuto\":$inclineAuto,")
        append("\"mode\":\"$mode\"")
        append("}")
    }
}
