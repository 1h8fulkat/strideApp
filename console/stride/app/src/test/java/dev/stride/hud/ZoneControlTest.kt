package dev.stride.hud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The loop that drives the belt, driven through scripted walks.
 *
 * This is the test that matters most on this branch, because it is the only
 * place the belt-commanding logic can be wrung out without somebody standing
 * on a moving treadmill. The treadmill gate is still mandatory — see the phase
 * 3 section of `claudeDesign/HEART_RATE_ZONES.md` — but the gate confirms a
 * feel, and these confirm the arithmetic that produces it.
 *
 * Three groups, in descending order of how much they would cost to get wrong:
 * the holds (a dropped strap, a stopped belt, the board's limits), the ramp
 * (one small step, then a real wait), and the override handshake.
 */
class ZoneControlTest {

    /** A 43-year-old resting at 60, with Tanaka's 178: the walker the plan
     *  document works its examples through. Floors land at 0/119/131/143/154/166. */
    private val floors = HrZones.floors(43, 60)

    /** One thing the loop did: when, and what it asked the belt for. */
    private class Move(val at: Double, val kph: Double)

    /** Drive a walk through the loop at the poll loop's real 5 Hz, applying
     *  every adjustment to the setpoint the way MainActivity does.
     *
     *  Returns every adjustment with the second it happened on, so a test can
     *  assert on the shape of a ramp — and on when it stopped — rather than on
     *  one frame of it. */
    private fun walk(
        c: ZoneControl,
        seconds: Double,
        targetZone: Int,
        startKph: Double = 5.0,
        minKph: Double = 0.8,
        maxKph: Double = 19.31,
        bpm: (Double) -> Int,
    ): MutableList<Move> {
        val moves = mutableListOf<Move>()
        var kph = startKph
        var at = 0.0
        while (at <= seconds) {
            val now = Math.round(at * 1000.0)
            val pulse = bpm(at)
            c.sample(now, pulse)
            val d = c.decide(now, pulse, floors, targetZone, kph, minKph, maxKph)
            if (d != null) { kph = d.kph; moves.add(Move(at, kph)) }
            at += 0.2
        }
        return moves
    }

    // --- the holds ------------------------------------------------------------

    @Test fun `a strap that drops out holds the belt where it is`() {
        val c = ZoneControl()
        // A minute in zone 1 with a target of 3 — the loop is climbing, so it
        // is in exactly the state where a bug would keep it climbing — and
        // then the strap comes off for two minutes. This is the treadmill
        // gate's own test: removing the strap mid-walk must hold the belt,
        // not accelerate it.
        val moves = walk(c, 180.0, targetZone = 3) { t -> if (t < 60.0) 120 else 0 }
        assertTrue("never started climbing, so the test proves nothing", moves.isNotEmpty())
        assertTrue(
            "the loop went on adjusting after the strap died, last at ${moves.last().at}s",
            moves.last().at < 60.0,
        )
    }

    @Test fun `zero bpm never reads as far below the zone`() {
        val c = ZoneControl()
        // The whole walk with no reading at all. A loop that took 0 bpm for a
        // very low heart rate would wind the belt up the entire time.
        val moves = walk(c, 300.0, targetZone = 5) { 0 }
        assertTrue("accelerated on a dead strap: $moves", moves.isEmpty())
    }

    @Test fun `a belt below the board minimum is never stepped up into it`() {
        val c = ZoneControl()
        // Setpoint 0.0 with a board minimum of 0.8: the walk is not running.
        // Starting it is not this loop's job.
        val moves = walk(c, 120.0, targetZone = 4, startKph = 0.0) { 100 }
        assertTrue("started a stopped belt: $moves", moves.isEmpty())
    }

    @Test fun `the loop is off unless a real zone is targeted`() {
        val c = ZoneControl()
        for (z in intArrayOf(0, -1, 6, 99)) {
            val moves = walk(ZoneControl(), 120.0, targetZone = z) { 100 }
            assertTrue("target $z drove the belt: $moves", moves.isEmpty())
        }
        assertFalse(c.atLimit)
    }

    @Test fun `inside the target zone it does nothing at all`() {
        val c = ZoneControl()
        // 148 bpm is zone 3 for this walker (143..153).
        val moves = walk(c, 600.0, targetZone = 3) { 148 }
        assertTrue("adjusted a walk already in the zone: $moves", moves.isEmpty())
    }

    @Test fun `the board ceiling stops the ramp and says so`() {
        val c = ZoneControl()
        // Target zone 5 with a pulse that will not rise: the loop climbs to
        // the board's maximum and then has to stop.
        val moves = walk(c, 1800.0, targetZone = 5, startKph = 19.0, maxKph = 19.31) { 100 }
        assertTrue("never reached the ceiling", moves.isNotEmpty())
        assertEquals(19.31, moves.last().kph, 0.001)
        assertTrue("at the ceiling and not saying so", c.atLimit)
    }

    @Test fun `the board floor stops the climb down and says so`() {
        val c = ZoneControl()
        val moves = walk(c, 1800.0, targetZone = 1, startKph = 1.0, minKph = 0.8) { 175 }
        assertEquals(0.8, moves.last().kph, 0.001)
        assertTrue("at the floor and not saying so", c.atLimit)
    }

    @Test fun `atLimit clears once the belt has room again`() {
        val c = ZoneControl()
        walk(c, 600.0, targetZone = 5, startKph = 19.31, maxKph = 19.31) { 100 }
        assertTrue(c.atLimit)
        // Same controller, a lower setpoint: there is room, so the complaint
        // goes away rather than sticking for the rest of the walk.
        walk(c, 60.0, targetZone = 5, startKph = 10.0) { 100 }
        assertFalse("atLimit stuck after the belt had room", c.atLimit)
    }

    // --- the ramp -------------------------------------------------------------

    @Test fun `one adjustment per dwell, whatever its size`() {
        val c = ZoneControl()
        // 137 is zone 2 on this ladder, one under a target of 3, so this is
        // the gentlest case: one step of 0.2. The part being pinned is the
        // count — proportional stepping changes how big an adjustment is and
        // must not change how often one happens.
        val moves = walk(c, 30.0, targetZone = 3, startKph = 5.0) { 137 }
        assertEquals("expected exactly one adjustment in 30 s, got $moves", 1, moves.size)
        assertEquals(5.2, moves[0].kph, 0.001)
    }

    @Test fun `the dwell is real — no second step inside twenty seconds`() {
        val c = ZoneControl()
        // 19 seconds is inside the dwell however long the walk is warmed up.
        val moves = walk(c, 19.0, targetZone = 3) { 120 }
        assertTrue("adjusted more than once inside the dwell: $moves", moves.size <= 1)
    }

    @Test fun `a five minute climb with no answer stays bounded`() {
        val c = ZoneControl()
        // Target zone 3 with a heart that never answers — a strap on somebody
        // else, or a reading stuck low. The worst case for a runaway, because
        // nothing the loop does makes it stop wanting more, and it therefore
        // never gets close enough to the zone for the step to shrink.
        //
        // 120 bpm is zone 1 on this ladder, two zones under the target, so it
        // sits at 0.4 km/h a step for the whole five minutes. The number below
        // is the one quoted in SAFETY.md: know it, and change it there too if
        // this ever moves.
        val moves = walk(c, 300.0, targetZone = 3, startKph = 5.0) { 120 }
        val gained = moves.last().kph - 5.0
        assertTrue(
            "gained ${"%.2f".format(gained)} km/h in five unanswered minutes",
            gained <= 6.1,
        )
        // Two zones out the whole way, so every step is the same 0.4 and every
        // one is upward. An uneven step here would mean the distance was being
        // recomputed off something other than the settled zone.
        var prev = 5.0
        for (m in moves) {
            assertEquals("uneven step", 0.4, m.kph - prev, 0.001)
            prev = m.kph
        }
    }

    @Test fun `the step shrinks as the walker approaches the zone`() {
        val c = ZoneControl()
        // A heart that does answer. It starts at 100 — zone 0, three under a
        // target of 3 — and climbs through the zones a minute at a time. The
        // adjustments have to get *smaller* as the gap closes: 0.6 while three
        // zones out, 0.4 at two, 0.2 for the last one. That taper is the whole
        // reason this is proportional rather than just bigger, because the
        // final approach is where an over-large step sails past the band.
        val sizes = mutableListOf<Double>()
        var prev = 5.0
        val moves = walk(c, 240.0, targetZone = 3, startKph = 5.0) { t ->
            when {
                t < 60.0 -> 100    // zone 0, three out
                t < 120.0 -> 125   // zone 1, two out
                t < 180.0 -> 137   // zone 2, one out
                else -> 148        // zone 3, arrived
            }
        }
        for (m in moves) { sizes.add(Math.round((m.kph - prev) * 100.0) / 100.0); prev = m.kph }
        assertTrue("no adjustments at all", sizes.isNotEmpty())
        assertEquals("first step should be the coarsest", 0.6, sizes.first(), 0.001)
        assertEquals("last step should be the gentlest", 0.2, sizes.last(), 0.001)
        // Never coarser than it was a step ago: the taper is monotone.
        for (i in 1 until sizes.size) {
            assertTrue(
                "step ${i + 1} of ${sizes.size} grew: ${sizes.joinToString(", ")}",
                sizes[i] <= sizes[i - 1] + 0.001,
            )
        }
    }

    @Test fun `distance sets the step, and the cap holds it both ways`() {
        // Five zones under a target of 5, and four zones over a target of 1.
        // Both are past MAX_STEPS, so both come out at the cap — and they come
        // out at the *same* cap. The flat one-step-up rule this replaced was
        // reversed by the walk of 23 September 2026; see MAX_STEPS.
        val up = ZoneControl()
        val upMoves = walk(up, 60.0, targetZone = 5, startKph = 8.0) { 100 }   // zone 0
        val down = ZoneControl()
        val downMoves = walk(down, 60.0, targetZone = 1, startKph = 8.0) { 175 } // zone 5

        assertEquals("up should be capped at 3 steps", 0.6, upMoves[0].kph - 8.0, 0.001)
        assertEquals("down should be capped at 3 steps", 0.6, 8.0 - downMoves[0].kph, 0.001)
        assertTrue("came down by going up", downMoves[0].kph < 8.0)
    }

    @Test fun `two zones out is two steps`() {
        val c = ZoneControl()
        // 120 is zone 1; target 3. Two zones, so 0.4 and not 0.2 or 0.6.
        val moves = walk(c, 30.0, targetZone = 3, startKph = 6.0) { 120 }
        assertEquals(1, moves.size)
        assertEquals(6.4, moves[0].kph, 0.001)
    }

    @Test fun `one zone over is still a single step`() {
        val c = ZoneControl()
        // 148 is zone 3; target 2. One zone over, so one step, not two.
        val moves = walk(c, 30.0, targetZone = 2, startKph = 6.0) { 148 }
        assertEquals(1, moves.size)
        assertEquals(5.8, moves[0].kph, 0.001)
    }

    @Test fun `it settles when the heart finally answers`() {
        val c = ZoneControl()
        // A heart that responds to pace: starts in zone 1 and climbs into
        // zone 3 over two minutes, then stays. The loop should push early,
        // stop once the zone is right, and leave it alone after that.
        val moves = walk(c, 600.0, targetZone = 3) { t ->
            when {
                t < 60.0 -> 120
                t < 120.0 -> 120 + ((t - 60.0) / 60.0 * 25).toInt()
                else -> 146
            }
        }
        assertTrue("never pushed at all", moves.isNotEmpty())
        // Nothing for the last six minutes of a ten minute walk: a walker
        // sitting in their zone is one the loop has finished with, and a loop
        // that keeps nudging a settled walk is the failure this asserts on.
        assertTrue(
            "still adjusting a settled walk at ${moves.last().at}s",
            moves.last().at < 180.0,
        )
    }

    // --- the settled average --------------------------------------------------

    @Test fun `a one frame spike does not move the belt`() {
        val c = ZoneControl()
        // A walk sitting comfortably in zone 3 with a single 200 bpm frame in
        // it — an arm swing against the strap. The mean barely notices, and
        // the belt must not.
        val moves = walk(c, 300.0, targetZone = 3) { t -> if (Math.abs(t - 100.0) < 0.1) 200 else 148 }
        assertTrue("a single bad frame moved the belt: $moves", moves.isEmpty())
    }

    @Test fun `the mean ignores dropped frames rather than averaging them in`() {
        val c = ZoneControl()
        // Alternating 148 and 0 at 5 Hz. The mean of the valid half is 148;
        // a mean that counted the zeroes would read 74 and the loop would
        // conclude the walker had nearly stopped.
        var n = 0
        walk(c, 60.0, targetZone = 3) { _ -> n++; if (n % 2 == 0) 148 else 0 }
        assertEquals(148, c.settledPulse(60_000L))
    }

    @Test fun `too few readings is not an average`() {
        val c = ZoneControl()
        c.sample(1000L, 150)
        c.sample(1200L, 150)
        assertEquals("two frames should not constitute a settled pulse", 0, c.settledPulse(1400L))
    }

    @Test fun `readings age out of the window`() {
        val c = ZoneControl()
        var at = 0L
        while (at <= 10_000L) { c.sample(at, 100); at += 200L }
        assertEquals(100, c.settledPulse(10_000L))
        // Thirty seconds later, with nothing since, the window is empty and
        // there is no settled pulse to act on.
        assertEquals(0, c.settledPulse(40_000L))
    }

    // --- the override handshake -----------------------------------------------

    @Test fun `resume gives the walker a full dwell before anything moves`() {
        val c = ZoneControl()
        // Warm the window up, then hand the belt back at t=60s.
        walk(c, 60.0, targetZone = 3) { 120 }
        c.reset(60_000L)
        // Nineteen seconds of the same low pulse: the loop must sit on its
        // hands. Pressing RESUME should not be felt through the belt.
        var at = 60_200L
        var moved = false
        while (at < 79_000L) {
            c.sample(at, 120)
            if (c.decide(at, 120, floors, 3, 5.0, 0.8, 19.31) != null) moved = true
            at += 200L
        }
        assertFalse("RESUME moved the belt inside the first dwell", moved)
        // And then it does act, once the dwell has actually passed.
        c.sample(81_000L, 120)
        assertTrue(
            "still frozen a dwell after RESUME",
            c.decide(81_000L, 120, floors, 3, 5.0, 0.8, 19.31) != null,
        )
    }

    @Test fun `reset keeps the window, because the heart did not restart`() {
        val c = ZoneControl()
        walk(c, 60.0, targetZone = 3) { 148 }
        c.reset(60_000L)
        assertEquals("reset threw away readings that were still true", 148, c.settledPulse(60_000L))
    }

    @Test fun `clear throws the window away, because a new walk is a new walker`() {
        val c = ZoneControl()
        walk(c, 60.0, targetZone = 3) { 148 }
        c.clear()
        assertEquals(0, c.settledPulse(60_000L))
    }

    // --- the ladder it is handed ---------------------------------------------

    @Test fun `it follows whatever maximum settings holds, override or formula`() {
        // The same walk, the same pulse, two ladders: Tanaka's 178 and a
        // measured 175. The loop has no opinion about which is right — the
        // walker's settings are the answer and the belt follows them — but the
        // two ladders do put 141 bpm in different zones, and the loop must act
        // on the one it was handed.
        val tanaka = HrZones.floors(43, 60)          // z3 starts at 143
        val measured = HrZones.floors(43, 60, 175)   // z3 starts at 141

        assertEquals(143, tanaka[3])
        assertEquals(141, measured[3])

        // 141 bpm, target zone 3. On Tanaka's ladder that is zone 2 and the
        // belt should speed up; on the measured one it is already zone 3 and
        // the belt should be left alone.
        val a = ZoneControl()
        var at = 0L
        while (at <= 30_000L) { a.sample(at, 141); at += 200L }
        assertTrue(
            "should have pushed towards zone 3 on Tanaka's ladder",
            a.decide(30_000L, 141, tanaka, 3, 5.0, 0.8, 19.31) != null,
        )

        val b = ZoneControl()
        at = 0L
        while (at <= 30_000L) { b.sample(at, 141); at += 200L }
        assertNull(
            "already in zone 3 on the measured ladder — nothing to do",
            b.decide(30_000L, 141, measured, 3, 5.0, 0.8, 19.31),
        )
    }

    @Test fun `a walker with no zones is never steered`() {
        val c = ZoneControl()
        // No age and no override: floors() returns empty and zoneOf returns
        // -1 for everything. The console does not invent a ladder and the belt
        // does not move.
        val none = HrZones.floors(0, 0)
        assertEquals(0, none.size)
        var at = 0L
        var moved = false
        while (at <= 120_000L) {
            c.sample(at, 140)
            if (c.decide(at, 140, none, 3, 5.0, 0.8, 19.31) != null) moved = true
            at += 200L
        }
        assertFalse("steered a walker who has no zones", moved)
    }
}
