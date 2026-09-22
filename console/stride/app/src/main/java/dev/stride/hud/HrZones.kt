package dev.stride.hud

import java.util.Calendar

/**
 * Heart-rate zones for *this* walker, and the arithmetic behind them.
 *
 * Everywhere in the console that wants to know "which zone is 154 bpm" asks
 * here. Before this file there were two answers: [Coach]'s `zoneOf`, which
 * divides by a maximum and has no floor, and `MainActivity.zoneIndex`, which
 * has one. Both took a share rather than a pulse, both hardcoded the same five
 * boundaries, and neither knew what a resting heart rate was. A control loop
 * that drives the belt towards a zone cannot be the third copy.
 *
 * ## Two models, and why the console has both
 *
 * **Tanaka** — `208 − 0.7 × age` — is the maximum. It replaced `220 − age`,
 * which is the number everyone knows and which nobody has ever been able to
 * source: Tanaka et al. (2001) went looking for the study behind it, could not
 * find one, and ran the meta-analysis instead. The two differ by
 * `0.3 × age − 12`, so they agree at exactly 40 and diverge either side —
 * `220 − age` runs *high* for the young and *low* for the old. At 20 it reads
 * 200 where Tanaka reads 194; at 60 it reads 160 where Tanaka reads 166. Six
 * beats is most of a zone. The spread between two
 * people of the same age is ±10-12 bpm either way, which is the part worth
 * remembering — see [ZONE_NAMES] for what the console is allowed to say out
 * loud about that.
 *
 * **Karvonen** works on *reserve* rather than on maximum: a zone floor is
 * `resting + share × (max − resting)`. It needs a resting heart rate and is
 * the better model when there is one, because it accounts for the bottom of
 * the range as well as the top. Two people with the same maximum and resting
 * rates forty beats apart are not doing the same work at 140 bpm, and plain
 * percent-of-max says they are.
 *
 * So: Karvonen when an RHR is known, Tanaka's maximum on plain percentages
 * when it is not, and nothing at all when there is no age. That last case is
 * the default and stays supported — see [Settings.Person.birthday].
 *
 * ## What this file will not do
 *
 * Invent a number. Every function here returns 0 or an empty list rather than
 * a plausible-looking default when the inputs are missing, and the caller is
 * expected to fall back to something that needs nobody's age. A console that
 * dresses a guess as physiology while driving a belt is a worse thing than a
 * console that says it does not know.
 */
object HrZones {

    /**
     * The share of reserve (or of maximum) each zone starts at.
     *
     * Index is the zone: `FLOORS[1]` is the bottom of zone 1, `FLOORS[5]` the
     * bottom of zone 5. Index 0 is 0.0 because zone 0 — everything below the
     * bottom of zone 1 — starts at the floor of the range, wherever that is.
     *
     * The textbook 50/60/70/80/90 split. It is the same one [Coach] has always
     * used and the same one the summary has always drawn, so a zone does not
     * change meaning depending on which part of the console is speaking.
     */
    val FLOORS = doubleArrayOf(0.00, 0.50, 0.60, 0.70, 0.80, 0.90)

    /** The highest zone index. Zone 5 has no ceiling but [maxPulse]. */
    const val TOP = 5

    /**
     * Zone names, 0-5.
     *
     * The set every consumer wrist device prints, which is the point: somebody
     * who has been told by a watch that they were in "Aerobic" should not have
     * to work out that this console calls the same band "Moderate". These
     * replaced the "Very light / Light / Moderate / Hard" wording the summary
     * used to draw, which described effort and nothing else.
     *
     * **The trade, so it is on the record.** "Weight control" and "Aerobic"
     * name what a zone is *for* rather than how hard it feels, and that is a
     * bigger claim than a treadmill holding a formula for your maximum can
     * really support — the fat-oxidation story behind "weight control" is a
     * crossover point that moves with training and with what you had for
     * breakfast, and it is routinely read as "the zone that burns fat", which
     * is not what it means. They are kept anyway because they are the words
     * people arrive already knowing, and an unfamiliar-but-defensible label
     * teaches nobody anything.
     *
     * So the console draws these and says nothing further about them. Nothing
     * here advises a zone, and [Coach] speaks in its own softer register — see
     * [Coach.ZONE_WORDS] — precisely so the spoken line never turns a label
     * into a recommendation.
     *
     * Zone 0 is not a training zone and has no industry name: it is the
     * warm-up, the cool-down, and standing on the side rail. "Resting" is the
     * reference screenshot's own word for below-zone-1. Counted separately so
     * it cannot inflate zone 1.
     *
     * These are still labels, not measurements. The boundary between
     * "Anaerobic" and "Maximum" is a smooth ±10-12 bpm smear on a formula.
     */
    val ZONE_NAMES = arrayOf(
        "Resting", "Low intensity", "Weight control", "Aerobic", "Anaerobic", "Maximum",
    )

    /**
     * The five-zone palette, cool to hot, as `#rrggbb`.
     *
     * Duplicated deliberately in `ZONES` in stride-core.js, which is the copy
     * the interfaces draw from: the page cannot call into Kotlin for a colour
     * on every frame of a line graph. This one exists so anything Kotlin
     * publishes — Home Assistant, a log line — describes the same zone with
     * the same colour, and so there is one place to change if the palette does.
     *
     * Grey for zone 0, then blue, green, yellow, orange, red. Yellow at zone 3
     * is not in every chart but it is in the one this was drawn against, and
     * dropping it puts green next to orange with nothing between them.
     *
     * **Zone 0 and zone 1 were retuned on 21 September 2026**, after the live
     * graph went on the console and the owner compared it against their own
     * watch. Both changes are about the colours being *identifiable*, which is
     * a different requirement from being pleasant:
     *
     * * Zone 1 was `#5aa9c8`, hue 194° — a cyan. The reference is 211°, and so
     *   is every other consumer device: low intensity is *blue*. On this
     *   console the old value had a second problem, which is that
     *   `--accent: #39e0ff` is cyan too, so the one zone colour that had to
     *   look like a category read as a piece of the UI chrome. Now 211°, the
     *   same hue as the watch, at this palette's own saturation rather than
     *   the watch's full one.
     * * Zone 0 was `#3a4348`, which is a grey at 28% value — on a `#050b1f`
     *   panel that is not a grey, it is an absence, and a warm-up drawn in it
     *   looked like a gap in the line rather than like time below zone 1. It
     *   is a mid grey now. That does make the least important zone more
     *   visible than it was, which is the trade: a colour nobody can see is
     *   not a quiet colour, it is a missing one.
     *
     * The other four are deliberately left in the muted register they were
     * drawn in. The reference's are fully saturated — `#ffed0d`, `#e21502` —
     * which is right on a phone's pure black and would be three glowing bars
     * on a navy HUD next to a cyan accent.
     */
    val ZONE_COLOURS = arrayOf(
        "#7e8b95", "#5a9ae0", "#5fc08a", "#e0c264", "#e08a4a", "#d75d5d",
    )

    /**
     * The ages Tanaka was fitted over, near enough.
     *
     * Below 13 and above 100 the formula is extrapolation, and a zone floor
     * built on an extrapolation is a number the console made up. Outside this
     * range [maxPulse] returns 0 and the walker is treated as not having given
     * an age at all, which is a state everything downstream already handles.
     */
    val AGE_RANGE = 13..100

    /**
     * What a resting heart rate is allowed to be before it is disbelieved.
     *
     * The floor is where trained endurance athletes actually sit — Indurain's
     * 28 is the usual anecdote — and the ceiling is high enough to accept an
     * unfit resting rate measured badly. Outside it, the likeliest explanation
     * is a typo or a strap reading a forearm, and Karvonen run on a bad
     * resting rate is worse than Tanaka run on none: it shifts every boundary
     * at once and it does so silently.
     */
    val RHR_RANGE = 30..100

    /**
     * The age the console proceeds with when nobody will say.
     *
     * Only ever reached after the walker has been asked and has declined or
     * ignored the prompt — see the quick-play flow in MainActivity. It is the
     * population middle, it is the age at which Tanaka and `220 − age` happen
     * to agree, and it exists so that a refusal to answer never blocks a walk.
     *
     * Zones built on it are labelled as an assumption wherever they are shown,
     * because they are one.
     */
    const val ASSUMED_AGE = 40

    /**
     * Maximum heart rate by Tanaka, or 0 when the age is not one this means
     * anything for.
     *
     * Rounded rather than truncated: at 43 the formula gives 177.9, and 177 is
     * the wrong side of a whole beat for no reason.
     */
    fun maxPulse(age: Int): Int =
        if (age in AGE_RANGE) Math.round(208.0 - 0.7 * age).toInt() else 0

    /**
     * The bpm each zone starts at, indexed 0-5, or empty when there is no
     * usable maximum.
     *
     * Karvonen when [restingHr] is a believable resting rate, percent-of-max
     * when it is not — and the two disagree by enough to matter. A 40-year-old
     * with a resting rate of 55: zone 2 starts at 108 on percent-of-max and at
     * 127 by Karvonen. The Karvonen figure is the one that corresponds to the
     * effort "zone 2" is supposed to name.
     *
     * Index 0 is the bottom of the range — the resting rate under Karvonen, or
     * zero under percent-of-max — so that `floors[z]` is always "the lowest
     * pulse that counts as zone z" and [zoneOf] can be a plain scan.
     */
    fun floors(age: Int, restingHr: Int): IntArray {
        val max = maxPulse(age)
        if (max <= 0) return IntArray(0)
        val karvonen = restingHr in RHR_RANGE && restingHr < max
        val base = if (karvonen) restingHr.toDouble() else 0.0
        val span = max - base
        return IntArray(TOP + 1) { z -> Math.round(base + FLOORS[z] * span).toInt() }
    }

    /**
     * Which zone a pulse falls in, 0-5, or -1 when the question cannot be
     * answered.
     *
     * -1 rather than 0 for "unknown" on purpose. Zone 0 is a real answer that
     * means "below the bottom of zone 1", and a graph that paints an unknown
     * pulse grey because it read zero is the same mistake as a heart animation
     * that keeps beating after the strap comes off. A caller that cannot tell
     * the two apart will eventually show one as the other.
     *
     * A pulse of 0 is "no reading", never "no pulse" — the board's grip field
     * sits at zero all walk on machines with no grips wired in. It returns -1
     * here, and the zone-targeting loop reads that as a disconnect and holds
     * the belt where it is.
     */
    fun zoneOf(pulse: Int, floors: IntArray): Int {
        if (pulse <= 0 || floors.size <= TOP) return -1
        var z = 0
        for (i in 1..TOP) if (pulse >= floors[i]) z = i
        return z
    }

    /**
     * The bpm band a zone occupies, as `from..to`, or null when unknown.
     *
     * Zone 5's top is [maxPulse], which is the one boundary that is a promise
     * rather than a threshold — people exceed their formula maximum routinely,
     * and a reading above it is a reading, not an error. Callers that draw a
     * band should treat the top of zone 5 as open.
     */
    fun band(zone: Int, floors: IntArray, age: Int): IntRange? {
        if (zone < 0 || zone > TOP || floors.size <= TOP) return null
        val top = if (zone == TOP) maxPulse(age) else floors[zone + 1] - 1
        return floors[zone]..top
    }

    /**
     * Age in whole years on [nowMs], from an ISO `yyyy-MM-dd` birthday, or 0
     * when the string is not one.
     *
     * 0 is "has not said", which is the default and a supported state — see
     * [Settings.Person.birthday]. Parsed by hand rather than with
     * `SimpleDateFormat`, which is lenient by default and reads "2001-13-45" as
     * a date in 2002 without complaining.
     *
     * A birthday rather than a number because a number is wrong from the next
     * birthday onwards and nothing ever tells the console. A walker who set
     * their age to 39 three years ago has been given a maximum two beats high
     * ever since, and the console had no way to know.
     */
    fun ageOn(birthday: String, nowMs: Long = System.currentTimeMillis()): Int {
        val p = parseIso(birthday) ?: return 0
        val (by, bm, bd) = p
        val cal = Calendar.getInstance().apply { timeInMillis = nowMs }
        val y = cal.get(Calendar.YEAR)
        val m = cal.get(Calendar.MONTH) + 1
        val d = cal.get(Calendar.DAY_OF_MONTH)
        var age = y - by
        // Not had it yet this year.
        if (m < bm || (m == bm && d < bd)) age--
        return if (age in 0..120) age else 0
    }

    /**
     * `yyyy-MM-dd` to a `(year, month, day)` triple, or null.
     *
     * Rejects a date that does not exist rather than rolling it forward. The
     * console's own date picker cannot produce one, but a birthday also
     * survives a settings export and an edit in a text editor, and a 31st of
     * February silently becoming the 3rd of March is the kind of thing that is
     * noticed years later or never.
     */
    private fun parseIso(s: String): Triple<Int, Int, Int>? {
        if (s.length != 10 || s[4] != '-' || s[7] != '-') return null
        val y = s.substring(0, 4).toIntOrNull() ?: return null
        val m = s.substring(5, 7).toIntOrNull() ?: return null
        val d = s.substring(8, 10).toIntOrNull() ?: return null
        if (m !in 1..12 || d < 1) return null
        return if (d <= daysIn(y, m)) Triple(y, m, d) else null
    }

    /** Days in a month, Gregorian leap rule. */
    fun daysIn(year: Int, month: Int): Int = when (month) {
        1, 3, 5, 7, 8, 10, 12 -> 31
        4, 6, 9, 11 -> 30
        2 -> if (year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)) 29 else 28
        else -> 0
    }

    /**
     * An ISO birthday for somebody who is [age] now, as the midpoint of the
     * year that makes them that age.
     *
     * Only used to migrate the people who set a plain age before birthdays
     * existed. 1 July rather than 1 January: a January date makes everyone a
     * year older the moment the migration lands for half the calendar, and the
     * midpoint is wrong by at most six months in either direction instead of
     * by a year in one.
     *
     * The alternative was to drop the stored ages, which would have silently
     * taken zones away from every walker who had one.
     */
    fun birthdayForAge(age: Int, nowMs: Long = System.currentTimeMillis()): String {
        val cal = Calendar.getInstance().apply { timeInMillis = nowMs }
        val year = cal.get(Calendar.YEAR) - age
        // If their 1 July has not arrived yet this year they are still one
        // younger than the subtraction says, so step the year back.
        val beforeJuly = cal.get(Calendar.MONTH) + 1 < 7
        return "%04d-07-01".format(if (beforeJuly) year - 1 else year)
    }

    /**
     * Estimated VO2 max in ml/kg/min, or 0.0 when it cannot be estimated.
     *
     * The Uth-Sørensen-Overgaard-Pedersen ratio: `15.3 × max / resting`. It
     * needs both numbers, which is why the summary only offers this to walkers
     * who gave a resting rate — Karvonen's requirement buys this for free.
     *
     * **It is an estimate of an estimate.** The maximum is a formula, the
     * ratio is a regression over 46 men, and the honest error bar is several
     * points. What it is good for is the derivative: the same person measured
     * the same way month after month, watching the number move. It is not
     * comparable against somebody else's, and it is not a lab test.
     *
     * Gated on exertion by the caller, not here — see the summary screen. A
     * ratio of maxima says nothing about a walk that never left zone 1, and
     * printing a fitness score after a stroll invites the reading that the
     * stroll produced it.
     */
    fun vo2max(age: Int, restingHr: Int): Double {
        val max = maxPulse(age)
        if (max <= 0 || restingHr !in RHR_RANGE) return 0.0
        return 15.3 * max / restingHr
    }
}
