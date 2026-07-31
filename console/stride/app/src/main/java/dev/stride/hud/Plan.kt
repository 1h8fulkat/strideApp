package dev.stride.hud

/**
 * A guided walk: an ordered list of segments the console executes.
 *
 * The source of a plan is deliberately irrelevant to the thing running it.
 * Today these are four hand-written templates; later they could come from a
 * model, or from a real route exported out of Strava. All three produce the
 * same shape, which is why this file has no idea which it is holding.
 *
 * Two rules the plan cannot break, both enforced in `MainActivity`, not here:
 *
 * * **Incline is driven, speed is suggested.** The deck has no interlock and
 *   cannot run away underneath anyone, so the plan moves it. The belt can, so
 *   the plan only ever proposes a pace and the person on the treadmill decides.
 * * **Everything is clamped against what the board says it can do.** A plan is
 *   a proposal. The machine's own reported limits win.
 *
 * Pace is expressed as a *delta*, not an absolute. The baseline is whatever
 * pace Sam settles at during the opening segment, so a plan adapts to the day
 * and to whatever fitness he has at the time rather than assuming a number that
 * was true when the template was written.
 */
object Plan {

    /**
     * One block of a walk.
     *
     * @param share    fraction of the total walk this segment occupies
     * @param incline  target grade, %, before clamping to the board's range
     * @param paceDelta km/h to suggest relative to the settled baseline pace
     */
    data class Segment(
        val share: Double,
        val incline: Double,
        val paceDelta: Double,
        val label: String,
        val note: String,
    )

    data class Template(
        val id: String,
        val name: String,
        val blurb: String,
        val segments: List<Segment>,
    )

    /** A segment resolved against a real duration. */
    data class Step(
        val startSec: Double,
        val endSec: Double,
        val incline: Double,
        val paceDelta: Double,
        val label: String,
        val note: String,
    ) {
        val seconds: Double get() = endSec - startSec
    }

    /**
     * Four shapes to walk, deliberately different enough from each other to be
     * worth comparing.
     *
     * Written after the first full guided walk, which found the originals too
     * timid: nothing went past 6% on a machine that does −3 to +12, and every
     * hill was the same shape as every other hill. Real ground is not linear.
     * These use uneven segment lengths, uneven heights, short sharp spikes and
     * the occasional genuine descent.
     *
     * A short spike is its own segment on purpose. The coach speaks on segment
     * changes, and "ten percent for forty seconds, stay tall" is exactly the
     * kind of thing worth being told.
     *
     * Every one still opens with a flat settle and closes with a flat ease-down.
     */
    val TEMPLATES = listOf(
        Template(
            "steady", "Steady", "One long hill, honestly earned",
            listOf(
                Segment(0.14, 0.0, 0.0, "Settle in", "Find a pace you could hold all day."),
                Segment(0.12, 2.0, 0.0, "Gentle rise", "The ground starts to tilt."),
                Segment(0.22, 3.5, 0.2, "The long middle", "Settle in. This one lasts."),
                Segment(0.10, 5.0, -0.2, "A little steeper", "Slightly more than you had."),
                Segment(0.16, 3.0, 0.2, "Back to steady", "Easier again — use it."),
                Segment(0.12, 1.5, 0.2, "Easing back", "Almost level."),
                Segment(0.14, 0.0, -0.8, "Cool down", "Flat. Ease off whenever you like."),
            ),
        ),
        Template(
            "rolling", "Rolling hills", "Uneven ground, no two the same",
            listOf(
                Segment(0.11, 0.0, 0.0, "Settle in", "Find your pace before the ground moves."),
                Segment(0.09, 4.0, 0.0, "First rise", "A gentle one to open with."),
                Segment(0.07, -1.0, 0.4, "Down the far side", "Genuinely downhill. Let it run."),
                Segment(0.12, 6.5, -0.2, "The long one", "Longer and steeper than the first."),
                Segment(0.06, 1.0, 0.4, "Short recovery", "Not long. Use it well."),
                Segment(0.05, 9.5, -0.4, "The wall", "Short and steep. Shorten your stride."),
                Segment(0.09, 2.0, 0.3, "Over the top", "That was the worst of it."),
                Segment(0.08, 5.5, -0.1, "One more", "Not as bad as the last one."),
                Segment(0.07, -1.5, 0.4, "Long descent", "Downhill. Let the legs come back."),
                Segment(0.12, 3.0, 0.0, "The last drag", "Nothing dramatic. Just keep going."),
                Segment(0.14, 0.0, -0.8, "Cool down", "Flat. Wind it down."),
            ),
        ),
        Template(
            "pyramid", "Pyramid", "Up in steps, down in steps",
            listOf(
                Segment(0.12, 0.0, 0.0, "Settle in", "Flat to start."),
                Segment(0.09, 2.5, 0.0, "First step", "The ground begins to tilt."),
                Segment(0.09, 5.0, 0.0, "Second step", "Still climbing."),
                Segment(0.09, 7.5, -0.2, "Third step", "Getting honest now."),
                Segment(0.06, 10.0, -0.5, "The peak", "Ten percent. Short steps, stay tall."),
                Segment(0.04, 11.5, -0.6, "One more", "Forty seconds. That is all."),
                Segment(0.09, 7.0, 0.2, "Over the top", "Downhill from here, all the way."),
                Segment(0.09, 4.0, 0.3, "Coming down", "Easier every minute."),
                Segment(0.09, 1.5, 0.3, "Nearly level", "Almost back."),
                Segment(0.12, 0.0, -0.8, "Cool down", "Flat. Well climbed."),
            ),
        ),
        Template(
            "climb", "The long climb", "One ascent, with a sting near the top",
            listOf(
                Segment(0.14, 0.0, 0.0, "Settle in", "Flat. Get comfortable — you will want it."),
                Segment(0.10, 3.0, 0.0, "The approach", "The climb starts here, gently."),
                Segment(0.13, 5.5, -0.2, "Into it", "Settle into the work."),
                Segment(0.08, 4.5, 0.1, "A brief flattening", "A false summit. Take the break."),
                Segment(0.13, 7.5, -0.3, "The steep part", "This is the real climb."),
                Segment(0.05, 11.0, -0.6, "The sting", "The steepest it gets. Under a minute."),
                Segment(0.09, 6.0, 0.2, "Over the crest", "Done. The hard part is behind you."),
                Segment(0.10, 2.5, 0.4, "The long descent", "All downhill now."),
                Segment(0.18, 0.0, -0.6, "Cool down", "Flat. That was the whole point."),
            ),
        ),
    )

    fun byId(id: String): Template? = TEMPLATES.firstOrNull { it.id == id }

    /**
     * Resolve a template against a chosen duration.
     *
     * Shares are normalised rather than trusted to sum to 1, so a template can
     * be edited without arithmetic; and the last step is stretched to land
     * exactly on the requested duration, because a walk advertised as thirty
     * minutes should end at thirty minutes.
     */
    fun resolve(template: Template, minutes: Int, minGrade: Double, maxGrade: Double): List<Step> {
        val total = minutes * 60.0
        val sum = template.segments.sumOf { it.share }
        val steps = ArrayList<Step>(template.segments.size)
        var at = 0.0
        for ((i, seg) in template.segments.withIndex()) {
            val length = total * (seg.share / sum)
            val end = if (i == template.segments.lastIndex) total else at + length
            steps += Step(
                startSec = at,
                endSec = end,
                incline = seg.incline.coerceIn(minGrade, maxGrade),
                paceDelta = seg.paceDelta,
                label = seg.label,
                note = seg.note,
            )
            at = end
        }
        return steps
    }
}
