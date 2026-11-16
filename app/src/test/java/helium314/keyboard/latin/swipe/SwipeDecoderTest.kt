// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.swipe

import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SwipeDecoderTest {
    private val keyWidth = 100f
    private val keyHeight = 140f
    private val layout: SwipeLayout = run {
        val rows = listOf("qwertyuiop" to 0f, "asdfghjkl" to 0.5f, "zxcvbnm" to 1.5f)
        val codes = ArrayList<Int>()
        val xs = ArrayList<Float>()
        val ys = ArrayList<Float>()
        rows.forEachIndexed { rowIndex, (letters, offset) ->
            letters.forEachIndexed { i, letter ->
                codes.add(letter.code)
                xs.add((offset + i + 0.5f) * keyWidth)
                ys.add((rowIndex + 0.5f) * keyHeight)
            }
        }
        SwipeLayout(codes.toIntArray(), xs.toFloatArray(), ys.toFloatArray(), keyWidth)
    }
    private val decoder = SwipeDecoder(layout)

    private fun lexicon(vararg words: Pair<String, Int>): SwipeLexicon {
        val builder = SwipeLexicon.Builder(2) // small to test growing
        words.forEach { builder.add(it.first, it.second) }
        return builder.build()
    }

    /** a swipe that goes exactly through the key centers of [word], moved by [offsetX] */
    private fun swipe(word: String, offsetX: Float = 0f): Pair<IntArray, IntArray> {
        val keys = word.map { layout.keyOf(it) }.filter { it != SwipeLayout.NO_KEY }
        val xs = ArrayList<Int>()
        val ys = ArrayList<Int>()
        for (i in 1 until keys.size) {
            for (step in 0 until 10) {
                val t = step / 10f
                xs.add((layout.x[keys[i - 1]] + (layout.x[keys[i]] - layout.x[keys[i - 1]]) * t + offsetX).roundToInt())
                ys.add((layout.y[keys[i - 1]] + (layout.y[keys[i]] - layout.y[keys[i - 1]]) * t).roundToInt())
            }
        }
        xs.add((layout.x[keys.last()] + offsetX).roundToInt())
        ys.add(layout.y[keys.last()].roundToInt())
        return xs.toIntArray() to ys.toIntArray()
    }

    private fun decode(word: String, lexicon: SwipeLexicon, offsetX: Float = 0f): List<String> {
        val (xs, ys) = swipe(word, offsetX)
        return decoder.decode(xs, ys, xs.size, listOf(decoder.Index(lexicon))).map { it.word }
    }

    @Test fun lexiconKeepsWordsAndFrequencies() {
        val lexicon = lexicon("jeden" to 200, "dwa" to 150, "trzy" to 100, "" to 50)
        assertEquals(3, lexicon.size)
        assertEquals("dwa", lexicon.word(1))
        assertEquals(100, lexicon.frequency(2))
    }

    @Test fun exactSwipeFindsWord() {
        val lexicon = lexicon("kot" to 150, "kat" to 150, "lot" to 150, "pies" to 150, "dom" to 150)
        assertEquals("kot", decode("kot", lexicon).first())
        assertEquals("pies", decode("pies", lexicon).first())
        assertEquals("dom", decode("dom", lexicon).first())
    }

    @Test fun slightlyShiftedSwipeFindsWord() {
        val lexicon = lexicon("kot" to 150, "kat" to 150, "lot" to 150, "pies" to 150, "dom" to 150)
        assertEquals("pies", decode("pies", lexicon, offsetX = 30f).first())
    }

    @Test fun lettersWithDiacriticsUseBaseLetterKey() {
        val lexicon = lexicon("żółw" to 150, "łąka" to 150, "kot" to 150)
        assertEquals("żółw", decode("zolw", lexicon).first())
        assertEquals("łąka", decode("laka", lexicon).first())
        assertEquals(layout.keyOf('l'), layout.keyOf('Ł'))
    }

    @Test fun moreCommonWordWinsForSamePath() {
        val lexicon = lexicon("kąt" to 120, "kat" to 180)
        assertEquals(listOf("kat", "kąt"), decode("kat", lexicon))
        val lexicon2 = lexicon("kąt" to 180, "kat" to 120)
        assertEquals(listOf("kąt", "kat"), decode("kat", lexicon2))
    }

    @Test fun doubleLettersNeedNoExtraMovement() {
        val lexicon = lexicon("lekko" to 150, "leki" to 150)
        assertEquals("lekko", decode("leko", lexicon).first())
    }

    @Test fun wordsOnSingleKeyAndUnrelatedWordsAreNotSuggested() {
        val lexicon = lexicon("a" to 250, "aa" to 250, "zupa" to 150)
        assertTrue(decode("kot", lexicon).isEmpty())
    }

    @Test fun wordFromSecondLexiconIsFound() {
        val (xs, ys) = swipe("heliboard")
        val main = decoder.Index(lexicon("hejnał" to 150, "herold" to 150))
        val learned = decoder.Index(lexicon("heliboard" to 150))
        assertEquals("heliboard", decoder.decode(xs, ys, xs.size, listOf(main, learned)).first().word)
    }

    @Test fun tooShortSwipeGivesNothing() {
        val lexicon = lexicon("kot" to 150)
        assertTrue(decoder.decode(intArrayOf(100, 110), intArrayOf(100, 100), 2, listOf(decoder.Index(lexicon))).isEmpty())
    }
}
