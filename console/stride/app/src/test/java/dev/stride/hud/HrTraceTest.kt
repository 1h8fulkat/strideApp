package dev.stride.hud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The walk's heart-rate trace, pinned.
 *
 * Two things are worth a test here and the rest is bookkeeping. The first is
 * that a dropout stays a dropout: a bucket that saw no reading has to come out
 * as 0 and not as an average dragged towards it, or the graph paints a rest
 * the walker did not take and Phase 3's targeting loop reads the same buffer.
 * The second is the downsampling — a walk does not say how long it will be, so
 * the buffer halves itself as it fills, and a halving that quietly dropped the
 * first half of the walk would draw an hour of effort as a flat half hour.
 */
class HrTraceTest {

    /** 5 Hz, the rate MainActivity's poll loop actually produces. The pulse
     *  is last so it can be written as a trailing lambda — it is the thing
     *  every test here varies. */
    private fun walk(t: HrTrace, seconds: Double, kph: (Double) -> Double = { 6.0 },
                     bpm: (Double) -> Int) {
        var at = 0.0
        while (at <= seconds) {
            t.add(at, bpm(at), kph(at))
            at += 0.2
        }
    }

    // --- buckets --------------------------------------------------------------

    @Test fun `a bucket is the mean of the frames in it`() {
        val t = HrTrace()
        // Ten seconds at a steady 120: two closed buckets, both 120.
        walk(t, 9.9) { 120 }
        val s = t.samples()
        assertTrue("expected a point per five seconds, got ${s.size}", s.size == 2)
        assertEquals(120, s[0].bpm)
        assertEquals(120, s[1].bpm)
    }

    @Test fun `the bucket still filling is shown, so the line reaches now`() {
        val t = HrTrace()
        walk(t, 1.0) { 110 }
        val s = t.samples()
        assertEquals(1, s.size)
        assertEquals(110, s[0].bpm)
        assertTrue("the head should sit inside the first bucket", s[0].at < 5.0)
    }

    @Test fun `a point is timed by the frames in it, not by the bucket edge`() {
        val t = HrTrace()
        walk(t, 9.9) { 120 }
        val s = t.samples()
        // Frames at 0.0..4.8 average 2.4; 5.0..9.8 average 7.4.
        assertEquals(2.4, s[0].at, 0.05)
        assertEquals(7.4, s[1].at, 0.05)
    }

    // --- the thing a pulse of 0 means -----------------------------------------

    /**
     * The invariant the whole feature rests on, restated for the buffer. A
     * pulse of 0 is the strap saying nothing — the board's grip field sits at
     * zero for an entire walk on this machine — so it is never a number to
     * average with.
     */
    @Test fun `a dropout is a gap, not an average dragged towards zero`() {
        val t = HrTrace()
        // Five seconds where the first half read 150 and the strap then went.
        var at = 0.0
        while (at < 5.0) { t.add(at, if (at < 2.5) 150 else 0, 6.0); at += 0.2 }
        assertEquals(150, t.samples()[0].bpm)
    }

    @Test fun `a bucket with nothing in it at all comes out as no reading`() {
        val t = HrTrace()
        walk(t, 4.9) { 0 }
        assertEquals(0, t.samples()[0].bpm)
    }

    /** A belt at 0.0 km/h really is stopped, so speed has no such rule. */
    @Test fun `speed averages its zeros`() {
        val t = HrTrace()
        var at = 0.0
        while (at < 5.0) { t.add(at, 120, if (at < 2.5) 8.0 else 0.0); at += 0.2 }
        assertEquals(4.0, t.samples()[0].kph, 0.2)
    }

    // --- downsampling ---------------------------------------------------------

    @Test fun `a long walk stays inside the cap`() {
        val t = HrTrace()
        walk(t, 7200.0) { 130 }                       // two hours
        assertTrue("kept ${t.samples().size} points", t.samples().size <= HrTrace.CAP)
        assertTrue("bucket never widened", t.bucketSec >= 20.0)
    }

    @Test fun `halving keeps the whole walk, not the recent half of it`() {
        val t = HrTrace()
        walk(t, 7200.0) { 130 }
        val s = t.samples()
        assertTrue("the trace starts at ${s.first().at}, not near zero", s.first().at < 60.0)
        assertTrue("the trace ends at ${s.last().at}, short of the walk", s.last().at > 7100.0)
    }

    /**
     * Merging is on sums, so the shape survives it. A ramp from 90 to 170 over
     * an hour must still read as that ramp after two halvings — the samples
     * are fewer and each covers more ground, but no point has moved.
     */
    @Test fun `merged points still trace the walk they came from`() {
        val t = HrTrace()
        walk(t, 3600.0) { at -> Math.round(90.0 + 80.0 * at / 3600.0).toInt() }
        for (s in t.samples()) {
            val want = 90.0 + 80.0 * s.at / 3600.0
            assertEquals("at ${s.at}s", want, s.bpm.toDouble(), 2.0)
        }
    }

    @Test fun `the points stay in order across a merge`() {
        val t = HrTrace()
        walk(t, 4000.0) { 130 }
        val s = t.samples()
        for (i in 1 until s.size) {
            assertTrue("point $i at ${s[i].at} follows ${s[i - 1].at}", s[i].at > s[i - 1].at)
        }
    }

    // --- the edges ------------------------------------------------------------

    @Test fun `clear puts it back to an empty walk at the base bucket`() {
        val t = HrTrace()
        walk(t, 4000.0) { 130 }
        t.clear()
        assertTrue(t.isEmpty())
        assertEquals(0, t.samples().size)
        assertEquals(HrTrace.BASE_BUCKET_SEC, t.bucketSec, 0.0)
    }

    /** Only a clock that went backwards produces one, and the trace would
     *  rather lose the frame than draw a line that doubles back. */
    @Test fun `a frame older than the bucket being filled is dropped`() {
        val t = HrTrace()
        t.add(20.0, 140, 6.0)
        t.add(19.0, 60, 6.0)
        assertEquals(140, t.samples()[0].bpm)
    }

    @Test fun `an untouched trace is empty rather than a point at zero`() {
        assertTrue(HrTrace().isEmpty())
        assertEquals(0, HrTrace().samples().size)
    }
}
