package dev.stride.hud

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * The zone arithmetic, pinned.
 *
 * Two jobs. The first is the ordinary one — Tanaka and Karvonen produce the
 * numbers they are supposed to, and the edges behave. The second is that this
 * file is one half of a pair: the same handful of ages and resting rates are
 * asserted against the JavaScript copy in `tools/uitest/zones.js`, because the
 * page cannot call into Kotlin for a zone colour on every frame of a line
 * graph and therefore has its own implementation. Two implementations that
 * drift apart would show a belt speeding up to reach a zone the summary says
 * it was already in.
 *
 * **If you change a boundary, change it in three places.** Here, in
 * [HrZones], and in `zoneFloorsFor` in `stride-core.js`. The uitest suite will
 * tell you which one you forgot.
 */
class HrZonesTest {

    /** A fixed "now", so a test does not change its answer on somebody's
     *  birthday. 15 June 2026, midday UTC. */
    private fun at(y: Int, m: Int, d: Int): Long =
        Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear(); set(y, m - 1, d, 12, 0, 0)
        }.timeInMillis

    private val NOW = at(2026, 6, 15)

    // --- Tanaka ---------------------------------------------------------------

    @Test fun `tanaka is 208 minus 0-7 age, rounded`() {
        assertEquals(199, HrZones.maxPulse(13))
        assertEquals(191, HrZones.maxPulse(25))   // 190.5 rounds up
        assertEquals(180, HrZones.maxPulse(40))
        assertEquals(166, HrZones.maxPulse(60))
        assertEquals(138, HrZones.maxPulse(100))
    }

    /**
     * The whole reason the formula changed. The two differ by `0.3 × age - 12`,
     * so they agree at exactly 40 and diverge either side: `220 - age` runs
     * *high* for the young and *low* for the old, and the gap is most of a
     * zone at both ends.
     */
    @Test fun `tanaka disagrees with 220 minus age at the ends and meets it at 40`() {
        assertEquals(0, HrZones.maxPulse(40) - (220 - 40))
        // 194 against 200 — the old formula flatters a twenty-year-old.
        assertEquals(-6, HrZones.maxPulse(20) - (220 - 20))
        // 166 against 160 — and short-changes a sixty-year-old by as much.
        assertEquals(6, HrZones.maxPulse(60) - (220 - 60))
        assertEquals(9, HrZones.maxPulse(70) - (220 - 70))
    }

    @Test fun `an age the formula does not cover is no maximum at all`() {
        assertEquals(0, HrZones.maxPulse(0))
        assertEquals(0, HrZones.maxPulse(12))
        assertEquals(0, HrZones.maxPulse(101))
        assertEquals(0, HrZones.maxPulse(-5))
    }

    // --- floors ---------------------------------------------------------------

    /**
     * Percent-of-max, which is what a walker who gave no resting rate gets.
     * Max at 40 is 180, so the floors are a flat 0/50/60/70/80/90 percent.
     */
    @Test fun `no resting rate gives percent-of-max floors`() {
        assertArrayEquals(
            intArrayOf(0, 90, 108, 126, 144, 162),
            HrZones.floors(40, 0),
        )
    }

    /**
     * Karvonen, on the worked example in the [HrZones.floors] doc. Max 180,
     * resting 55, reserve 125: zone 2 starts at 55 + 0.6 × 125 = 130.
     *
     * Note how far this is from the percent-of-max answer above — zone 2
     * starts at 108 there and 130 here. That gap is the entire argument for
     * collecting a resting heart rate.
     */
    @Test fun `a resting rate switches the floors to karvonen`() {
        assertArrayEquals(
            intArrayOf(55, 118, 130, 143, 155, 168),
            HrZones.floors(40, 55),
        )
    }

    /** An implausible resting rate is disbelieved rather than used: a bad one
     *  moves every boundary at once and does it silently. */
    @Test fun `an implausible resting rate falls back to percent-of-max`() {
        val percent = HrZones.floors(40, 0)
        assertArrayEquals(percent, HrZones.floors(40, 29))
        assertArrayEquals(percent, HrZones.floors(40, 101))
        assertArrayEquals(percent, HrZones.floors(40, 0))
    }

    @Test fun `no age means no zones at all, not default ones`() {
        assertEquals(0, HrZones.floors(0, 55).size)
        assertEquals(0, HrZones.floors(12, 55).size)
    }

    /**
     * Index 0 is where the walker's range starts, not a boundary, and
     * [HrZones.zoneOf] must not treat it as one. A strap reading 40 on
     * somebody whose resting rate is 55 is a low reading, not an
     * unclassifiable one — zone 0 is the answer, and -1 stays reserved for no
     * reading at all.
     *
     * This matters for the graph: zone 0 is drawn as everything under the
     * bottom of zone 1, so anything that fell out of the bottom of the range
     * has to still come back as zone 0 and be painted grey.
     */
    @Test fun `a pulse under the resting rate is still zone 0, not unknown`() {
        val f = HrZones.floors(40, 55)     // 55, 118, 130, 143, 155, 168
        assertEquals(0, HrZones.zoneOf(40, f))
        assertEquals(0, HrZones.zoneOf(54, f))
        assertEquals(0, HrZones.zoneOf(117, f))
        assertEquals(-1, HrZones.zoneOf(0, f))
    }

    // --- a maximum the walker measured ----------------------------------------

    /**
     * The owner's case, and the reason the override exists. Their watch has
     * measured 175 from a year of real workouts; Tanaka says 178 at 43. Under
     * Karvonen every boundary is a share of `max − resting`, so three beats on
     * the maximum is not three beats on one number, it moves the whole ladder.
     */
    @Test fun `an override replaces tanaka and moves the whole ladder`() {
        assertEquals(178, HrZones.maxPulse(43))
        assertEquals(175, HrZones.maxPulse(43, 175))
        assertArrayEquals(
            intArrayOf(60, 119, 131, 143, 154, 166),
            HrZones.floors(43, 60),
        )
        assertArrayEquals(
            intArrayOf(60, 118, 129, 141, 152, 164),
            HrZones.floors(43, 60, 175),
        )
    }

    /** An implausible maximum is disbelieved, not obeyed — the likeliest one
     *  is a resting rate typed into the wrong box, and it would move every
     *  boundary at once and do it silently. */
    @Test fun `an implausible maximum falls back to the formula`() {
        assertEquals(178, HrZones.maxPulse(43, 0))
        assertEquals(178, HrZones.maxPulse(43, 60))    // a resting rate
        assertEquals(178, HrZones.maxPulse(43, 119))   // just under the range
        assertEquals(178, HrZones.maxPulse(43, 221))
        assertArrayEquals(HrZones.floors(43, 60), HrZones.floors(43, 60, 60))
    }

    /**
     * An override works with no age at all, and that is not a hole in "no age
     * means no zones".
     *
     * That rule is there so the console never invents a number. A maximum
     * somebody measured is the opposite of an invented one, so a walker who
     * knows their ceiling and will not give a birthday gets zones — which is
     * the right answer rather than a loophole.
     */
    @Test fun `a measured maximum gives zones without a birthday`() {
        assertEquals(0, HrZones.maxPulse(0))
        assertEquals(190, HrZones.maxPulse(0, 190))
        val f = HrZones.floors(0, 55, 190)
        assertEquals(6, f.size)
        // Karvonen on a reserve of 135: zone 1 at 55 + 0.5 x 135.
        assertArrayEquals(intArrayOf(55, 123, 136, 150, 163, 177), f)
    }

    /** The top of zone 5 is the maximum in force, not the formula's. */
    @Test fun `the top of zone five follows the override`() {
        assertEquals(166..178, HrZones.band(5, HrZones.floors(43, 60), 43))
        assertEquals(164..175, HrZones.band(5, HrZones.floors(43, 60, 175), 43, 175))
    }

    /** And so does the VO2 max, which is a ratio of the two figures. */
    @Test fun `vo2 max follows the override too`() {
        assertEquals(15.3 * 178 / 60, HrZones.vo2max(43, 60), 0.001)
        assertEquals(15.3 * 175 / 60, HrZones.vo2max(43, 60, 175), 0.001)
    }

    // --- zoneOf ---------------------------------------------------------------

    @Test fun `a pulse lands in the zone its floor says`() {
        val f = HrZones.floors(40, 0)   // 0, 90, 108, 126, 144, 162
        assertEquals(0, HrZones.zoneOf(89, f))
        assertEquals(1, HrZones.zoneOf(90, f))     // exactly on a floor is in
        assertEquals(1, HrZones.zoneOf(107, f))
        assertEquals(2, HrZones.zoneOf(108, f))
        assertEquals(4, HrZones.zoneOf(154, f))
        assertEquals(5, HrZones.zoneOf(162, f))
        assertEquals(5, HrZones.zoneOf(200, f))    // above max is still zone 5
    }

    /**
     * The distinction the whole feature rests on. Zero is "no reading", never
     * "no pulse" — the board's grip field sits at zero all walk on a machine
     * with no grips wired in — and it must not come back as zone 0, which is a
     * real answer meaning "below the bottom of zone 1".
     *
     * If this ever returns 0, a strap that drops out paints the live graph
     * grey and the belt's targeting loop reads "you have stopped working" and
     * speeds up.
     */
    @Test fun `no reading is minus one, not zone zero`() {
        val f = HrZones.floors(40, 0)
        assertEquals(-1, HrZones.zoneOf(0, f))
        assertEquals(-1, HrZones.zoneOf(-1, f))
        assertEquals(0, HrZones.zoneOf(1, f))
    }

    @Test fun `no floors means the question cannot be answered`() {
        assertEquals(-1, HrZones.zoneOf(140, IntArray(0)))
    }

    // --- bands ----------------------------------------------------------------

    @Test fun `a band runs from its floor to one below the next`() {
        val f = HrZones.floors(40, 0)
        assertEquals(108..125, HrZones.band(2, f, 40))
        assertEquals(144..161, HrZones.band(4, f, 40))
    }

    /** Zone 5 tops out at the formula maximum, which people exceed routinely.
     *  A reading above it is a reading, not an error. */
    @Test fun `zone five tops out at the formula maximum`() {
        val f = HrZones.floors(40, 0)
        assertEquals(162..180, HrZones.band(5, f, 40))
        assertEquals(5, HrZones.zoneOf(195, f))
    }

    @Test fun `a band nobody asked for is null rather than invented`() {
        assertNull(HrZones.band(6, HrZones.floors(40, 0), 40))
        assertNull(HrZones.band(-1, HrZones.floors(40, 0), 40))
        assertNull(HrZones.band(2, IntArray(0), 40))
    }

    // --- birthdays ------------------------------------------------------------

    @Test fun `age counts whole years from an iso birthday`() {
        assertEquals(40, HrZones.ageOn("1986-06-15", NOW))   // birthday today
        assertEquals(39, HrZones.ageOn("1986-06-16", NOW))   // tomorrow
        assertEquals(40, HrZones.ageOn("1986-06-14", NOW))   // yesterday
        assertEquals(40, HrZones.ageOn("1986-01-01", NOW))
        assertEquals(39, HrZones.ageOn("1986-12-31", NOW))
    }

    /**
     * The bug the birthday was introduced to fix, stated as a test: the same
     * stored value gives a different age in a later year, without anybody
     * having to go and change it.
     */
    @Test fun `the same birthday ages on its own`() {
        assertEquals(40, HrZones.ageOn("1986-06-15", NOW))
        assertEquals(43, HrZones.ageOn("1986-06-15", at(2029, 6, 15)))
        assertEquals(42, HrZones.ageOn("1986-06-15", at(2029, 6, 14)))
    }

    /** Lenient parsing is how "2001-13-45" becomes a date in 2002 and nobody
     *  finds out for years. */
    @Test fun `a date that does not exist is not a birthday`() {
        assertEquals(0, HrZones.ageOn("1986-02-30", NOW))
        assertEquals(0, HrZones.ageOn("1986-13-01", NOW))
        assertEquals(0, HrZones.ageOn("1986-00-10", NOW))
        assertEquals(0, HrZones.ageOn("1986-06-00", NOW))
        assertEquals(0, HrZones.ageOn("", NOW))
        assertEquals(0, HrZones.ageOn("15/06/1986", NOW))
        assertEquals(0, HrZones.ageOn("1986-6-15", NOW))
        assertEquals(0, HrZones.ageOn("not a date", NOW))
    }

    @Test fun `29 february is a birthday in a leap year and not otherwise`() {
        assertEquals(38, HrZones.ageOn("1988-02-29", NOW))
        assertEquals(0, HrZones.ageOn("1987-02-29", NOW))
        assertEquals(0, HrZones.ageOn("1900-02-29", NOW))   // not a leap year
        assertEquals(26, HrZones.ageOn("2000-02-29", NOW))  // but this one is
    }

    // --- the migration --------------------------------------------------------

    /**
     * The people who set a plain age before birthdays existed are converted
     * rather than cleared, and the round trip has to land on the age they set
     * — otherwise the migration silently ages or de-ages a household.
     */
    @Test fun `a migrated age round-trips to the same age`() {
        for (age in HrZones.AGE_RANGE) {
            for (now in listOf(at(2026, 3, 1), at(2026, 6, 15), at(2026, 11, 20))) {
                val b = HrZones.birthdayForAge(age, now)
                assertEquals("age $age at $now", age, HrZones.ageOn(b, now))
            }
        }
    }

    /** Mid-year, so the error is at most six months either way rather than a
     *  whole year in one direction. */
    @Test fun `a migrated birthday lands on the first of july`() {
        assertTrue(HrZones.birthdayForAge(40, NOW).endsWith("-07-01"))
    }

    // --- vo2 max --------------------------------------------------------------

    @Test fun `vo2 max is the max over resting ratio`() {
        // 15.3 * 180 / 55
        assertEquals(50.07, HrZones.vo2max(40, 55), 0.01)
        assertEquals(36.72, HrZones.vo2max(40, 75), 0.01)
    }

    /** It needs both numbers. Without a resting rate there is no ratio, and
     *  the summary has to say so rather than print half of one. */
    @Test fun `vo2 max needs both numbers`() {
        assertEquals(0.0, HrZones.vo2max(40, 0), 0.0)
        assertEquals(0.0, HrZones.vo2max(0, 55), 0.0)
        assertEquals(0.0, HrZones.vo2max(40, 20), 0.0)
    }

    // --- the palette ----------------------------------------------------------

    /** Six colours and six names, one per zone including zone 0. An index
     *  mismatch here paints the wrong zone the wrong colour everywhere. */
    @Test fun `there is a name and a colour for every zone`() {
        assertEquals(HrZones.TOP + 1, HrZones.ZONE_NAMES.size)
        assertEquals(HrZones.TOP + 1, HrZones.ZONE_COLOURS.size)
        assertEquals(HrZones.TOP + 1, HrZones.FLOORS.size)
        HrZones.ZONE_COLOURS.forEach {
            assertTrue("not a hex colour: $it", Regex("^#[0-9a-f]{6}$").matches(it))
        }
    }
}
