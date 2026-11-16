// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.swipe

import kotlin.math.ln
import kotlin.math.sqrt

/**
 *  Finds the words that best match a swipe path.
 *
 *  Every word has an ideal path: straight lines between the centers of its keys. The swipe and the
 *  ideal paths are resampled to the same number of evenly spaced points, and the average distance
 *  between corresponding points says how well the shapes match. That is combined with how common
 *  the word is. Only words that start near the first point and end near the last point of the swipe
 *  are considered.
 */
class SwipeDecoder(val layout: SwipeLayout) {

    class Candidate(val word: String, val score: Float, val distance: Float, val frequency: Int)

    /** Words of one lexicon arranged by first and last key of their ideal path on [layout] */
    inner class Index(val lexicon: SwipeLexicon) {
        private val bucketStarts: IntArray
        private val bucketWords: IntArray
        internal val idealLength: FloatArray

        init {
            val keys = layout.keyCount
            val pairOfWord = IntArray(lexicon.size)
            idealLength = FloatArray(lexicon.size)
            val counts = IntArray(keys * keys + 1)
            for (w in 0 until lexicon.size) {
                var first = SwipeLayout.NO_KEY
                var previous = SwipeLayout.NO_KEY
                var length = 0f
                var distinctKeys = 0
                for (p in lexicon.wordStart(w) until lexicon.wordEnd(w)) {
                    val key = layout.keyOf(lexicon.charAt(p))
                    if (key == SwipeLayout.NO_KEY || key == previous) continue
                    if (first == SwipeLayout.NO_KEY) first = key
                    else length += distance(layout.x[previous], layout.y[previous], layout.x[key], layout.y[key])
                    previous = key
                    distinctKeys++
                }
                if (distinctKeys < 2) {
                    pairOfWord[w] = -1 // words on a single key can't be swiped
                    continue
                }
                pairOfWord[w] = first * keys + previous
                idealLength[w] = length
                counts[pairOfWord[w] + 1]++
            }
            for (i in 1 until counts.size) counts[i] += counts[i - 1]
            bucketStarts = counts.copyOf()
            bucketWords = IntArray(counts.last())
            val next = counts.copyOf()
            for (w in 0 until lexicon.size) {
                val pair = pairOfWord[w]
                if (pair >= 0) bucketWords[next[pair]++] = w
            }
        }

        internal fun bucketStart(firstKey: Int, lastKey: Int) = bucketStarts[firstKey * layout.keyCount + lastKey]
        internal fun bucketEnd(firstKey: Int, lastKey: Int) = bucketStarts[firstKey * layout.keyCount + lastKey + 1]
        internal fun bucketWord(position: Int) = bucketWords[position]
    }

    // scratch arrays, a decoder must only be used from one thread at a time
    private val swipeX = FloatArray(SAMPLES)
    private val swipeY = FloatArray(SAMPLES)
    private val idealX = FloatArray(SAMPLES)
    private val idealY = FloatArray(SAMPLES)
    private val keysX = FloatArray(SwipeLexicon.MAX_WORD_LENGTH)
    private val keysY = FloatArray(SwipeLexicon.MAX_WORD_LENGTH)
    private val startKeys = IntArray(MAX_END_KEYS)
    private val endKeys = IntArray(MAX_END_KEYS)

    /**
     *  @param xs,ys the swipe path in the same coordinates as the [layout]
     *  @return the best candidates, best first. A word may occur more than once if it is in several [indexes].
     */
    fun decode(xs: IntArray, ys: IntArray, pointCount: Int, indexes: List<Index>, maxResults: Int = 20): List<Candidate> {
        if (pointCount < 2 || layout.keyCount < 2) return emptyList()
        val keyWidth = layout.keyWidth
        val px = FloatArray(pointCount) { xs[it].toFloat() }
        val py = FloatArray(pointCount) { ys[it].toFloat() }
        val swipeLength = resample(px, py, pointCount, swipeX, swipeY)
        if (swipeLength < keyWidth * MIN_SWIPE_LENGTH) return emptyList()

        val startKeyCount = nearKeys(px[0], py[0], startKeys)
        val endKeyCount = nearKeys(px[pointCount - 1], py[pointCount - 1], endKeys)
        val minIdealLength = swipeLength * 0.6f - keyWidth
        val maxIdealLength = swipeLength * 1.7f + keyWidth

        // the best candidates so far, sorted by score descending
        val best = ArrayList<Candidate>(maxResults + 1)
        var worstScore = Float.NEGATIVE_INFINITY

        for (index in indexes) {
            val lexicon = index.lexicon
            for (s in 0 until startKeyCount) for (e in 0 until endKeyCount) {
                val firstKey = startKeys[s]
                val lastKey = endKeys[e]
                for (position in index.bucketStart(firstKey, lastKey) until index.bucketEnd(firstKey, lastKey)) {
                    val w = index.bucketWord(position)
                    val idealLength = index.idealLength[w]
                    if (idealLength < minIdealLength || idealLength > maxIdealLength) continue
                    val frequency = lexicon.frequency(w)
                    val languageScore = (frequency - 255) * FREQUENCY_LOG_STEP * LANGUAGE_WEIGHT
                    // largest average distance that still gets this word into the results
                    var maxDistance = MAX_DISTANCE
                    if (best.size == maxResults) {
                        val room = (languageScore - worstScore) / SHAPE_WEIGHT
                        if (room <= 0f) continue
                        maxDistance = minOf(maxDistance, sqrt(room))
                    }
                    // ideal path through the key centers
                    var keyCount = 0
                    var previous = SwipeLayout.NO_KEY
                    for (p in lexicon.wordStart(w) until lexicon.wordEnd(w)) {
                        val key = layout.keyOf(lexicon.charAt(p))
                        if (key == SwipeLayout.NO_KEY || key == previous) continue
                        keysX[keyCount] = layout.x[key]
                        keysY[keyCount] = layout.y[key]
                        keyCount++
                        previous = key
                    }
                    resample(keysX, keysY, keyCount, idealX, idealY)
                    val maxSum = maxDistance * keyWidth * SAMPLES
                    var sum = 0f
                    var i = 0
                    while (i < SAMPLES && sum <= maxSum) {
                        sum += distance(swipeX[i], swipeY[i], idealX[i], idealY[i])
                        i++
                    }
                    if (sum > maxSum) continue
                    val averageDistance = sum / SAMPLES / keyWidth
                    val score = languageScore - SHAPE_WEIGHT * averageDistance * averageDistance
                    var insertAt = best.size
                    while (insertAt > 0 && best[insertAt - 1].score < score) insertAt--
                    best.add(insertAt, Candidate(lexicon.word(w), score, averageDistance, frequency))
                    if (best.size > maxResults) best.removeAt(best.lastIndex)
                    if (best.size == maxResults) worstScore = best[best.lastIndex].score
                }
            }
        }
        return best
    }

    /** fills [out] with the keys close enough to the point to be the intended first or last key of a swipe */
    private fun nearKeys(pointX: Float, pointY: Float, out: IntArray): Int {
        val radius = layout.keyWidth * END_KEY_RADIUS
        var count = 0
        var nearest = 0
        var nearestDistance = Float.MAX_VALUE
        for (key in 0 until layout.keyCount) {
            val d = distance(pointX, pointY, layout.x[key], layout.y[key])
            if (d < nearestDistance) {
                nearestDistance = d
                nearest = key
            }
            if (d <= radius && count < out.size) out[count++] = key
        }
        if (count == 0) out[count++] = nearest // swipe started outside the letter keys
        return count
    }

    /**
     *  Writes [SAMPLES] points evenly spaced along the polyline into [outX] and [outY].
     *  @return the length of the polyline
     */
    private fun resample(px: FloatArray, py: FloatArray, count: Int, outX: FloatArray, outY: FloatArray): Float {
        var total = 0f
        for (i in 1 until count) total += distance(px[i - 1], py[i - 1], px[i], py[i])
        if (total <= 0f) {
            outX.fill(px[0])
            outY.fill(py[0])
            return 0f
        }
        val step = total / (SAMPLES - 1)
        var segment = 1
        var segmentStartLength = 0f
        var segmentLength = distance(px[0], py[0], px[1], py[1])
        for (i in 0 until SAMPLES) {
            val target = step * i
            while (segment < count - 1 && segmentStartLength + segmentLength < target) {
                segmentStartLength += segmentLength
                segment++
                segmentLength = distance(px[segment - 1], py[segment - 1], px[segment], py[segment])
            }
            val t = if (segmentLength <= 0f) 0f else ((target - segmentStartLength) / segmentLength).coerceIn(0f, 1f)
            outX[i] = px[segment - 1] + (px[segment] - px[segment - 1]) * t
            outY[i] = py[segment - 1] + (py[segment] - py[segment - 1]) * t
        }
        return total
    }

    companion object {
        private const val SAMPLES = 40
        private const val MAX_END_KEYS = 12
        /** swipes shorter than this many key widths are ignored */
        private const val MIN_SWIPE_LENGTH = 0.5f
        /** first and last key of a word must be within this many key widths of the swipe's ends */
        private const val END_KEY_RADIUS = 1.3f
        /** words whose ideal path is on average further away than this many key widths are never suggested */
        private const val MAX_DISTANCE = 1.2f
        /** natural log of the probability ratio between two adjacent dictionary frequency values */
        @JvmField val FREQUENCY_LOG_STEP = ln(1.15f)
        private const val LANGUAGE_WEIGHT = 1f
        private const val SHAPE_WEIGHT = 25f

        private fun distance(x1: Float, y1: Float, x2: Float, y2: Float): Float {
            val dx = x1 - x2
            val dy = y1 - y2
            return sqrt(dx * dx + dy * dy)
        }
    }
}
