package dev.stride.hud

/**
 * The loop that steers belt speed to hold a heart-rate zone.
 *
 * This is the first code in this console that commands the belt without
 * somebody having pressed something. Everything before it — the plan, the
 * coach, the route — either moved the *deck*, which has no interlock and
 * cannot run away underneath anyone, or printed a pace and waited to be
 * obeyed. The rule that made that distinction, "incline is driven, speed is
 * suggested", was withdrawn on 21 September 2026 on the owner's explicit
 * instruction so that this file could exist. See `SAFETY.md`; the reasoning is
 * not in the diff.
 *
 * Because of that, this class is written to be *boring*. It is pure: no
 * Android, no clock of its own, no access to the board. It is handed a time, a
 * pulse, the walker's zone ladder, the current setpoint and the board's own
 * limits, and it answers with one number or with nothing at all. Everything
 * interesting about it is therefore reachable from [ZoneControlTest] through a
 * scripted heart-rate trace, which is the only way to test a control loop
 * without standing on the thing.
 *
 * ## What it does
 *
 * Below the target zone it steps the belt up; above it, down; inside it,
 * nothing. The step is [STEP_KPH] **per zone of distance**, capped at
 * [MAX_STEPS] — coarse while the walker is far from the band, and down to a
 * single step for the last zone, which is the approach that actually lands
 * them in it. The wait between steps is [DWELL_MS], which the owner chose
 * from a 15–30 s range: the point of the wait is that a heart takes most of a
 * minute to answer a change in pace, so a loop that adjusts every second is
 * not controlling anything, it is chasing its own last adjustment.
 *
 * It does **not** aim for the middle of the zone. It stops the moment the zone
 * is right, which means it settles near whichever boundary it arrived from and
 * will step back and forth across that boundary over a long walk. That is
 * accepted rather than fixed: the oscillation is bounded by one step every
 * twenty seconds, and the alternative — targeting a bpm inside the band —
 * makes the console hold a number it cannot defend to a precision the
 * underlying formula does not have. A zone is a band because the physiology is
 * a smear; steering to its centre would be false precision with a motor
 * attached.
 *
 * ## The four things that stop it
 *
 * 1. **No reading.** A pulse of 0 is the strap saying nothing, never a stopped
 *    heart — the board's grip field reads 0 all walk on machines with no grips
 *    wired in. [HrZones.zoneOf] returns -1 for it and this loop holds the
 *    speed exactly where it is until a real reading comes back. It is the one
 *    failure that has an obvious wrong answer: a loop that read 0 bpm as "far
 *    below the target zone" would accelerate a treadmill under somebody whose
 *    strap had just fallen off.
 * 2. **A hand on the speed buttons.** Not this class's job — [MainActivity]
 *    drops `zoneAuto` the way it drops `inclineAuto` when the incline is
 *    touched — but it is why [reset] exists.
 * 3. **A belt that is not running.** If the setpoint is below the board's own
 *    minimum there is no walk to steer, and this returns nothing rather than
 *    stepping up into [minKph]. The loop adjusts a walk in progress; it never
 *    starts one, and it never stops one either. Stopping is the safety key's
 *    job and the safety key is the stop of record.
 * 4. **The board's limits.** Every answer is clamped into `minKph..maxKph` as
 *    reported by the board, and when the clamp means the answer equals the
 *    setpoint the loop returns nothing rather than re-commanding the same
 *    number every twenty seconds. That state is worth showing rather than
 *    hiding — see [atLimit].
 *
 * ## Why it acts on an average and not on the latest frame
 *
 * The poll loop produces a reading five times a second and any one of them can
 * be a transient: a strap settling, an arm swing, a single dropped frame. The
 * owner's instruction was that the loop act on "a settled average rather than
 * a transient", so every sample goes into a [WINDOW_MS] rolling mean and the
 * zone is read off that. The instantaneous pulse is still consulted for one
 * thing only — deciding whether there is a reading at all — because a strap
 * that came off two seconds ago must stop the loop immediately rather than
 * ten seconds later when the window finally empties.
 */
class ZoneControl(
    private val stepKph: Double = STEP_KPH,
    private val dwellMs: Long = DWELL_MS,
    private val windowMs: Long = WINDOW_MS,
) {

    companion object {
        /**
         * How much the belt moves in one adjustment, km/h.
         *
         * 0.2 km/h is about 0.12 mph — under the belt it is a change you
         * notice without it being a change you brace for. At one step per
         * [DWELL_MS] the loop can move the belt by at most 0.6 km/h in a
         * minute, which is the figure worth holding in your head: this is
         * roughly a third as fast as a person pressing SPEED + at a
         * comfortable rate, and that is the intent.
         */
        const val STEP_KPH = 0.2

        /**
         * How long to leave a change alone before making another, ms.
         *
         * The owner specified 15–30 s and this is the middle of it. The number
         * is about the *heart*, not about the motor: heart rate lags a change
         * in pace by something like 30–60 s before it settles, so a shorter
         * dwell means the loop is reading the pulse that belonged to the
         * previous speed and stepping again on the strength of it. That is how
         * a gentle controller turns into a treadmill that winds itself up.
         *
         * It is deliberately longer than the response is fast. Overshooting a
         * heart-rate zone costs a walker more than reaching it slowly does.
         */
        const val DWELL_MS = 20_000L

        /** How much recent pulse the rolling mean covers, ms. Two dwells would
         *  smear across a change the loop itself made; a second or two would
         *  not be an average at all. Ten seconds is 50 frames at the poll
         *  loop's 5 Hz. */
        const val WINDOW_MS = 10_000L

        /**
         * Valid readings needed in the window before the mean is trusted.
         *
         * Two seconds' worth at 5 Hz. It matters on exactly one path: the
         * strap has been dead, the dwell has run out underneath the dropout,
         * and a single reconnect frame arrives. Without this the loop would
         * act on a sample size of one at the first opportunity, which is the
         * transient case the averaging exists to prevent.
         */
        const val MIN_SAMPLES = 10

        /**
         * The most steps one adjustment may take, however far off the zone is.
         *
         * The adjustment is **proportional to the distance**: one step per
         * zone between the walker and their target, capped here. Three zones
         * out or more is 0.6 km/h at a time, two zones is 0.4, and the last
         * zone — the approach that actually lands you in the band — is one
         * step, exactly as gentle as it has always been.
         *
         * **This replaced a flat one-step-up rule after the walk of 23
         * September 2026.** The original asymmetry was one step up however
         * far below, up to two down, on the reasoning that there is never a
         * cause to hurry a heart rate upwards. That was recorded at the time
         * as an unasked-for default and flagged for reversal, and the
         * treadmill reversed it: every gate passed but the owner reported the
         * ramp felt slow in *both* directions. A climb from zone 0 to zone 2
         * was twelve 0.2 km/h steps and about four minutes of creeping, which
         * is not gentleness, it is a console that appears not to be working.
         *
         * Proportional rather than simply larger, and that is the point of
         * the shape. Making every step bigger would have bought the same
         * speed by spending it where it costs most — on the final approach,
         * where a big step is what sails you past the band before your pulse
         * has answered. Coarse far away and fine close in gets the walker to
         * the zone quickly and then stops hard, which is the behaviour the
         * dwell was protecting in the first place.
         *
         * **The worst case is worth knowing and is stated plainly in
         * `SAFETY.md`.** A pulse that never answers — a strap on somebody
         * else, a reading stuck low — leaves the loop permanently two or
         * three zones short and therefore permanently at its coarsest: up to
         * 1.8 km/h a minute, about 6 km/h over five unanswered minutes. It is
         * bounded, it is visible on the gauge, one press of SPEED ends it,
         * and the safety key is still the stop of record.
         */
        const val MAX_STEPS = 3

        /** Ring capacity. Comfortably over [WINDOW_MS] at the poll loop's
         *  5 Hz, with room for a faster loop without the window silently
         *  shortening to whatever fits. */
        private const val RING = 256
    }

    /**
     * One adjustment: the speed to command, and a short phrase for the log.
     *
     * [why] is written for `logcat` rather than for the screen. What the
     * walker sees is composed on the page out of the target zone and the
     * auto/manual flag — see `zoneAutoNote` in `stride-core.js` — because the
     * page has to say it in five interfaces and in the walker's own units.
     */
    class Decision(val kph: Double, val why: String)

    private val atMs = LongArray(RING)
    private val bpmAt = IntArray(RING)
    private var head = 0
    private var count = 0

    private var lastMoveAt = 0L

    /**
     * Whether [lastMoveAt] means anything yet.
     *
     * The dwell is armed by the first reading rather than by the first
     * adjustment, so that a controller nobody remembered to [reset] still
     * waits a full [dwellMs] before it touches the belt. Without this the
     * "first move is a dwell away" promise would be a property of the calling
     * code rather than of this class, and it is far too load-bearing to leave
     * somewhere it can be forgotten.
     */
    private var armed = false

    /**
     * True when the loop wanted to move and the board's own range would not
     * let it — the belt is at [maxKph] with the pulse still under the target
     * zone, or at [minKph] with it still over.
     *
     * Worth surfacing rather than swallowing. A walker who has asked for zone
     * 4 and is watching a belt sit at the same speed deserves to be told the
     * machine has run out of room, not left to conclude the feature is broken.
     * Read by [MainActivity] into the frame and drawn by the HUD.
     */
    var atLimit: Boolean = false
        private set

    /**
     * Forget the recent past and start the dwell again.
     *
     * Called when the walk resets, when the loop is switched on, and when the
     * walker hands the belt back after taking it by hand. All three want the
     * same thing: the next adjustment is a full [dwellMs] away, not
     * immediately. Switching the loop on does not jerk the belt, and neither
     * does pressing RESUME — whatever the pulse is doing at that instant, the
     * walker gets twenty seconds of the speed they left it at first.
     *
     * The sample window is *not* cleared, because the readings in it are
     * still true: the walker's heart did not restart when they touched a
     * button. Only the dwell moves.
     */
    fun reset(nowMs: Long) {
        lastMoveAt = nowMs
        armed = true
        atLimit = false
    }

    /** Throw away the window as well. For a new walk, where the previous
     *  walker's pulse is not evidence about this one. */
    fun clear() {
        head = 0
        count = 0
        lastMoveAt = 0L
        armed = false
        atLimit = false
    }

    /**
     * Feed the loop a reading, whether or not it is currently driving.
     *
     * Sampled even while the walker has the belt by hand, so that RESUME comes
     * back to a warm window rather than to two seconds of silence. Zero is
     * recorded rather than skipped — it ages out of the window like any other
     * frame and the mean simply ignores it, which keeps this method free of
     * any opinion about what a dropout means.
     */
    fun sample(nowMs: Long, pulse: Int) {
        // The first reading of a walk starts the dwell — see [armed].
        if (!armed) { lastMoveAt = nowMs; armed = true }
        atMs[head] = nowMs
        bpmAt[head] = pulse
        head = (head + 1) % RING
        if (count < RING) count++
    }

    /**
     * The mean of the valid readings inside the window, or 0 when there are
     * too few to mean anything.
     *
     * Rounded to a whole bpm because that is the resolution everything else in
     * this console speaks in, and because [HrZones.zoneOf] takes an Int.
     */
    fun settledPulse(nowMs: Long): Int {
        var sum = 0L
        var n = 0
        val cutoff = nowMs - windowMs
        for (i in 0 until count) {
            // Walk back from the newest, so an old ring entry that predates
            // the window ends the scan rather than being skipped over.
            val idx = ((head - 1 - i) % RING + RING) % RING
            if (atMs[idx] < cutoff) break
            if (bpmAt[idx] > 0) { sum += bpmAt[idx]; n++ }
        }
        if (n < MIN_SAMPLES) return 0
        return Math.round(sum.toDouble() / n).toInt()
    }

    /**
     * The next speed to command, or null for "leave it alone".
     *
     * Null is by far the common answer — the loop is inside the zone, or
     * inside its dwell, or has no reading — and the caller is expected to do
     * nothing at all with it rather than re-commanding the current setpoint.
     * Re-sending the setpoint every frame would keep the board's write queue
     * permanently busy and would stamp on the manual press this loop is
     * supposed to yield to.
     *
     * @param pulse        the newest reading, used only to tell a live strap
     *                     from a dead one. The zone is decided on the mean —
     *                     see [settledPulse].
     * @param floors       the walker's ladder from [HrZones.floors], built on
     *                     whatever maximum Settings holds for them: their own
     *                     measured override when they gave one, Tanaka's
     *                     estimate when they did not. This loop has no opinion
     *                     about which, and must not acquire one — the walker's
     *                     settings are the single answer to "what is their
     *                     maximum" and the belt follows it.
     * @param targetZone   1..[HrZones.TOP]. Anything else is "off".
     * @param setpointKph  what the console last asked the board for, not what
     *                     the board reports it is doing. Stepping from the
     *                     measured speed would compound the board's own ramp
     *                     lag into the step size.
     */
    fun decide(
        nowMs: Long,
        pulse: Int,
        floors: IntArray,
        targetZone: Int,
        setpointKph: Double,
        minKph: Double,
        maxKph: Double,
    ): Decision? {
        atLimit = false
        if (targetZone < 1 || targetZone > HrZones.TOP) return null

        // No walk to steer. The loop adjusts a belt that is already running;
        // it does not start one, so a setpoint under the board's minimum is
        // left alone rather than stepped up into it.
        if (setpointKph < minKph) return null

        // -1 is "cannot say", and it is the same function the time-in-zone
        // accounting reads — the two must not disagree about what an unknown
        // zone is. Hold, and do not touch the dwell: the belt has not changed,
        // so whenever the strap comes back the reading it gives has been
        // settling under this speed the whole time.
        if (HrZones.zoneOf(pulse, floors) < 0) return null

        val settled = settledPulse(nowMs)
        val zone = HrZones.zoneOf(settled, floors)
        if (zone < 0) return null
        if (zone == targetZone) return null

        // Inside the dwell. Decided after the zone check rather than before it
        // so that atLimit and the hold cases are evaluated on every frame and
        // the HUD does not have to wait out a dwell to learn the truth.
        if (armed && nowMs - lastMoveAt < dwellMs) return null

        // One step per zone of distance, capped, and the same both ways —
        // see MAX_STEPS for why this is proportional rather than flat, and
        // for what the treadmill said about the flat version.
        val below = zone < targetZone
        val steps = Math.min(Math.abs(zone - targetZone), MAX_STEPS)
        val delta = if (below) stepKph * steps else -stepKph * steps

        val next = (setpointKph + delta).coerceAtMost(maxKph).coerceAtLeast(minKph)
        if (Math.abs(next - setpointKph) < 0.001) {
            // The board's range is in the way. Say so rather than silently
            // doing nothing for the rest of the walk.
            atLimit = true
            return null
        }

        lastMoveAt = nowMs
        armed = true
        val dir = if (below) "under" else "over"
        return Decision(
            next,
            "zone $zone is $dir target $targetZone ($settled bpm settled)",
        )
    }
}
