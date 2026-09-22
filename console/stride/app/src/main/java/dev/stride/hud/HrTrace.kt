package dev.stride.hud

/**
 * The heart rate and the pace of the walk in progress, kept at a size a graph
 * can actually draw.
 *
 * The poll loop produces a reading five times a second. An hour of that is
 * 18 000 samples, and the strip it is drawn into is 1210 pixels wide — so
 * fifteen of every sixteen points land on a pixel that already has one. The
 * buffer therefore downsamples as it goes rather than keeping everything and
 * thinning at the end: it is bucketed by time, and when it runs out of room it
 * merges every neighbouring pair and doubles the bucket. [CAP] buckets at
 * [BASE_BUCKET_SEC] covers half an hour; the first merge takes it to an hour,
 * the second to two, and the walk never has to say in advance how long it is
 * going to be.
 *
 * **In memory, for this walk only.** The owner chose that: the trace feeds the
 * live graph and the post-workout summary and is then gone. Nothing here is
 * written to [History], which stores aggregates and would need a size budget
 * and a retention rule before it stored a time series for 750 walks.
 *
 * **A bpm of 0 is "no reading", the same as everywhere else in this console.**
 * It is never averaged in — a strap that drops out for thirty seconds must not
 * drag the line down towards zero and paint a rest that did not happen. A
 * bucket with no valid reading in it comes out as 0, which is the signal to
 * the renderer to break the line rather than to draw across the gap. Speed has
 * no such rule: a belt at 0.0 km/h really is stopped.
 *
 * Written to and read from different threads — the poll loop adds, the WebView
 * thread asks for the samples when the page wants to redraw — so every entry
 * point is synchronized. The lock is uncontended in practice and held for
 * microseconds.
 */
class HrTrace(
    private val cap: Int = CAP,
    private val baseBucketSec: Double = BASE_BUCKET_SEC,
) {

    companion object {
        /**
         * How many buckets are kept before they are merged in pairs.
         *
         * Even, because merging halves. 360 of them is more points than the
         * 1210-pixel strip on this console can show as anything but a line,
         * and small enough that the whole trace is a few kilobytes of JSON on
         * the way to the page.
         */
        const val CAP = 360

        /** Five seconds a bucket: 25 raw frames folded into one point, and
         *  half an hour of walk before the first merge. */
        const val BASE_BUCKET_SEC = 5.0
    }

    /** One drawable point: when, the mean pulse over the bucket (0 when the
     *  strap said nothing for the whole of it), and the mean belt speed. */
    class Sample(val at: Double, val bpm: Int, val kph: Double)

    /**
     * A bucket under construction, or a finished one.
     *
     * Sums rather than means, so merging two of them is exact and so a bucket
     * cut short by the end of a walk is not given the same weight as a full
     * one. [hrN] counts only the frames that carried a reading, which is what
     * keeps a dropout out of the average.
     */
    private class Bucket(var from: Double) {
        var n = 0
        var tSum = 0.0
        var kphSum = 0.0
        var hrN = 0
        var hrSum = 0.0

        fun sample() = Sample(
            at = if (n > 0) tSum / n else from,
            bpm = if (hrN > 0) Math.round(hrSum / hrN).toInt() else 0,
            kph = if (n > 0) kphSum / n else 0.0,
        )

        fun absorb(o: Bucket) {
            if (o.from < from) from = o.from
            n += o.n; tSum += o.tSum; kphSum += o.kphSum
            hrN += o.hrN; hrSum += o.hrSum
        }
    }

    private val closed = ArrayList<Bucket>(CAP)
    private var open: Bucket? = null

    /** How much walk each point covers, seconds. Doubles on every merge, and
     *  is sent to the page so a graph can say what it is showing. */
    @Volatile var bucketSec: Double = baseBucketSec
        private set

    /**
     * Fold one poll frame in.
     *
     * [atSec] is elapsed session time, which is what the graph's axis is. A
     * frame older than the bucket being filled is dropped rather than
     * reordering the trace: the only way to get one is a clock that went
     * backwards, and a walk that does that has a bigger problem than a missing
     * fifth of a second.
     */
    @Synchronized fun add(atSec: Double, bpm: Int, kph: Double) {
        val held = open
        if (held != null && atSec < held.from) return
        // One close at most: the replacement bucket is founded on this very
        // frame's time, so the frame is always inside it. closeOpen may widen
        // the buckets on its way out, which is why the base is taken after.
        if (held != null && atSec >= held.from + bucketSec) closeOpen()
        val b = open ?: Bucket(bucketBase(atSec)).also { open = it }
        b.n++
        b.tSum += atSec
        b.kphSum += kph
        if (bpm > 0) { b.hrN++; b.hrSum += bpm.toDouble() }
    }

    /**
     * The trace as points, oldest first, including the bucket still filling.
     *
     * The open bucket is included so the line reaches *now* rather than
     * stopping up to five seconds short of it. It is a mean over however much
     * of the bucket has happened, which is the same thing the finished ones
     * are, so it needs no special treatment when it is closed a moment later.
     */
    @Synchronized fun samples(): List<Sample> {
        val out = ArrayList<Sample>(closed.size + 1)
        for (b in closed) out.add(b.sample())
        open?.takeIf { it.n > 0 }?.let { out.add(it.sample()) }
        return out
    }

    @Synchronized fun isEmpty(): Boolean = closed.isEmpty() && (open?.n ?: 0) == 0

    /** A new walk. Called from resetSession — the trace belongs to one walk
     *  and carrying yesterday's into today's graph would be a lie with a
     *  shape. */
    @Synchronized fun clear() {
        closed.clear()
        open = null
        bucketSec = baseBucketSec
    }

    /** The start of the bucket [t] belongs to, so buckets line up on multiples
     *  of their own width rather than on whenever the first frame landed. */
    private fun bucketBase(t: Double): Double = Math.floor(t / bucketSec) * bucketSec

    private fun closeOpen() {
        val b = open ?: return
        open = null
        if (b.n == 0) return
        closed.add(b)
        if (closed.size >= cap) compact()
    }

    /**
     * Merge every neighbouring pair and double the bucket width.
     *
     * Halving in place rather than dropping the oldest half: this is a graph
     * of the whole walk, and a trace that quietly forgot its first thirty
     * minutes would draw a smooth hour-long effort as a flat half hour.
     */
    private fun compact() {
        var i = 0
        var w = 0
        while (i < closed.size) {
            val a = closed[i]
            if (i + 1 < closed.size) a.absorb(closed[i + 1])
            closed[w] = a
            w++
            i += 2
        }
        while (closed.size > w) closed.removeAt(closed.size - 1)
        bucketSec *= 2.0
    }
}
