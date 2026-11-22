// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.swipe

import android.content.Context
import android.os.SystemClock
import helium314.keyboard.keyboard.Keyboard
import helium314.keyboard.latin.dictionary.Dictionary
import helium314.keyboard.latin.utils.Log
import java.io.IOException
import java.lang.ref.WeakReference
import java.util.Locale
import java.util.zip.GZIPInputStream
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 *  Built-in swipe typing for one language: keeps the word lists taken from the dictionaries
 *  and decodes swipes against the current keyboard layout.
 *  This is used when no gesture typing library is loaded, and works completely on-device.
 */
class SwipeTyping(private val context: Context?) {
    @Volatile private var mainLexicon = SwipeLexicon.EMPTY
    // words from dictionaries that change while typing: personal dictionary, learned words, contacts
    @Volatile private var dynamicLexicon = SwipeLexicon.EMPTY
    @Volatile private var dynamicLexiconTime = 0L
    private val isLoadingDynamicLexicon = AtomicBoolean(false)

    // decoding state, only accessed in synchronized decode
    private var lastKeyboard = WeakReference<Keyboard>(null)
    private var decoder: SwipeDecoder? = null
    private var mainIndex: SwipeDecoder.Index? = null
    private var dynamicIndex: SwipeDecoder.Index? = null

    /** Reads all words of the main dictionary, takes a second or two for large dictionaries. Must not be called on the main thread. */
    fun loadMainDictionary(dictionary: Dictionary?) {
        if (dictionary == null) {
            mainLexicon = SwipeLexicon.EMPTY
            return
        }
        synchronized(mainLexiconCache) { mainLexiconCache[dictionary] }?.let {
            mainLexicon = it
            return
        }
        val start = SystemClock.elapsedRealtime()
        val lexicon = readFrequencyAsset(dictionary.mLocale) ?: readWords(listOf(dictionary), 200_000)
        Log.i(TAG, "loaded ${lexicon.size} words for swipe typing in ${SystemClock.elapsedRealtime() - start} ms")
        if (lexicon.size > 0) // empty when the dictionary is not readable yet, don't remember that
            synchronized(mainLexiconCache) { mainLexiconCache[dictionary] = lexicon }
        mainLexicon = lexicon
    }

    val hasMainWords: Boolean get() = mainLexicon.size > 0

    fun needsDynamicWordsRefresh(): Boolean =
        !isLoadingDynamicLexicon.get() && SystemClock.elapsedRealtime() - dynamicLexiconTime > DYNAMIC_REFRESH_INTERVAL_MS

    /** Re-reads the words of the changing dictionaries. Must not be called on the main thread. */
    fun refreshDynamicWords(dictionaries: List<Dictionary>) {
        if (!isLoadingDynamicLexicon.compareAndSet(false, true)) return
        try {
            dynamicLexicon = readWords(dictionaries, 2_000)
            dynamicLexiconTime = SystemClock.elapsedRealtime()
        } finally {
            isLoadingDynamicLexicon.set(false)
        }
    }

    /**
     *  Words of the bundled dictionary with frequencies corrected for swipe typing, see rr/make-swipe-frequencies.py.
     *  @return null if there is no such file for the locale
     */
    private fun readFrequencyAsset(locale: Locale?): SwipeLexicon? {
        val assets = context?.assets ?: return null
        if (locale == null) return null
        val available = try { assets.list("swipe").orEmpty().toSet() } catch (_: IOException) { return null }
        val name = listOf(locale.toLanguageTag(), locale.language).map { "$it.tsv.gz" }.firstOrNull { it in available }
            ?: return null
        return try {
            val builder = SwipeLexicon.Builder(200_000)
            GZIPInputStream(assets.open("swipe/$name")).bufferedReader(Charsets.UTF_8).useLines { lines ->
                for (line in lines) {
                    val tab = line.indexOf('\t')
                    if (tab <= 0) continue
                    builder.add(line.substring(0, tab), line.substring(tab + 1).toIntOrNull() ?: continue)
                }
            }
            builder.build()
        } catch (e: Exception) {
            Log.w(TAG, "could not read swipe frequencies $name", e)
            null
        }
    }

    private fun readWords(dictionaries: List<Dictionary>, expectedWords: Int): SwipeLexicon {
        val builder = SwipeLexicon.Builder(expectedWords)
        for (dictionary in dictionaries) {
            try {
                dictionary.forEachWord { word, frequency -> if (frequency > 0) builder.add(word, frequency) }
            } catch (e: Throwable) {
                Log.w(TAG, "could not read words from ${dictionary.mDictType} dictionary", e)
            }
        }
        return builder.build()
    }

    /** @return words matching the swipe, best first, may contain a word more than once */
    @Synchronized
    fun decode(keyboard: Keyboard, xs: IntArray, ys: IntArray, pointCount: Int): List<SwipeDecoder.Candidate> {
        if (pointCount < 2) return emptyList()
        var currentDecoder = decoder
        if (currentDecoder == null || lastKeyboard.get() !== keyboard) {
            val layout = createLayout(keyboard)
            if (currentDecoder == null || currentDecoder.layout.signature != layout.signature) {
                currentDecoder = SwipeDecoder(layout)
                decoder = currentDecoder
                mainIndex = null
                dynamicIndex = null
            }
            lastKeyboard = WeakReference(keyboard)
        }
        val main = mainLexicon
        val dynamic = dynamicLexicon
        var currentMainIndex = mainIndex
        if (currentMainIndex == null || currentMainIndex.lexicon !== main) {
            currentMainIndex = currentDecoder.Index(main)
            mainIndex = currentMainIndex
        }
        var currentDynamicIndex = dynamicIndex
        if (currentDynamicIndex == null || currentDynamicIndex.lexicon !== dynamic) {
            currentDynamicIndex = currentDecoder.Index(dynamic)
            dynamicIndex = currentDynamicIndex
        }
        return currentDecoder.decode(xs, ys, pointCount, listOf(currentMainIndex, currentDynamicIndex), MAX_CANDIDATES)
    }

    private fun createLayout(keyboard: Keyboard): SwipeLayout {
        val keys = keyboard.sortedKeys.filter { it.code > 0 && Character.isLetter(it.code) }
        return SwipeLayout(
            IntArray(keys.size) { keys[it].code },
            FloatArray(keys.size) { keys[it].x + keys[it].width / 2f },
            FloatArray(keys.size) { keys[it].y + keys[it].height / 2f },
            keyboard.mMostCommonKeyWidth.toFloat().coerceAtLeast(1f)
        )
    }

    companion object {
        private const val TAG = "SwipeTyping"
        private const val MAX_CANDIDATES = 24
        private const val DYNAMIC_REFRESH_INTERVAL_MS = 30_000L
        // dictionaries are re-used when settings change, no need to read their words again
        private val mainLexiconCache = WeakHashMap<Dictionary, SwipeLexicon>()

        /** converts a decoder score (0 is the best possible, more negative is worse) to a suggestion score */
        @JvmStatic
        fun toSuggestionScore(score: Float): Int = (2_000_000 + score * 20_000).toInt().coerceAtLeast(1)
    }
}
