// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.swipe

import java.text.Normalizer

/**
 *  Positions of the letter keys of a keyboard, and the mapping from characters to keys.
 *  Characters that have no key of their own (ą, é, ...) map to the key of their base letter,
 *  because that is the key the finger passes when swiping the word.
 */
class SwipeLayout(codePoints: IntArray, centersX: FloatArray, centersY: FloatArray, val keyWidth: Float) {
    val keyCount: Int
    val x: FloatArray
    val y: FloatArray
    private val keyCodes: IntArray
    private val keyOfLowChar = ShortArray(TABLE_SIZE) { NO_KEY.toShort() }
    private val keyOfOtherChar = HashMap<Char, Int>()

    init {
        val indices = codePoints.indices.filter { Character.isLetter(codePoints[it]) && codePoints[it] <= 0xFFFF }
        keyCount = indices.size
        keyCodes = IntArray(keyCount) { Character.toLowerCase(codePoints[indices[it]]) }
        x = FloatArray(keyCount) { centersX[indices[it]] }
        y = FloatArray(keyCount) { centersY[indices[it]] }
        val exact = HashMap<Char, Int>()
        for (i in 0 until keyCount) exact.putIfAbsent(keyCodes[i].toChar(), i)
        for (c in 0 until TABLE_SIZE) {
            keyOfLowChar[c] = findKey(c.toChar(), exact).toShort()
        }
        for ((char, key) in exact) if (char.code >= TABLE_SIZE) keyOfOtherChar[char] = key
    }

    private fun findKey(char: Char, exact: Map<Char, Int>): Int {
        if (!Character.isLetter(char)) return NO_KEY
        val lower = Character.toLowerCase(char)
        exact[lower]?.let { return it }
        val base = SPECIAL_BASE_LETTERS[lower]
            ?: Normalizer.normalize(lower.toString(), Normalizer.Form.NFD).firstOrNull()
            ?: return NO_KEY
        return exact[base] ?: NO_KEY
    }

    /** index of the key the finger passes for [char], or [NO_KEY] */
    fun keyOf(char: Char): Int {
        if (char.code < TABLE_SIZE) return keyOfLowChar[char.code].toInt()
        return keyOfOtherChar[char] ?: keyOfOtherChar[Character.toLowerCase(char)] ?: NO_KEY
    }

    /** two layouts with the same signature have the same keys at the same positions */
    val signature: Long = run {
        var h = 1125899906842597L
        for (i in 0 until keyCount) {
            h = 31 * h + keyCodes[i]
            h = 31 * h + x[i].toInt()
            h = 31 * h + y[i].toInt()
        }
        31 * h + keyWidth.toInt()
    }

    companion object {
        const val NO_KEY = -1
        private const val TABLE_SIZE = 0x250 // Latin including Extended-B
        // letters that do not decompose into base letter + combining mark
        private val SPECIAL_BASE_LETTERS = mapOf(
            'ł' to 'l', 'ø' to 'o', 'đ' to 'd', 'ß' to 's', 'æ' to 'a', 'œ' to 'o', 'ð' to 'd', 'þ' to 't', 'ı' to 'i'
        )
    }
}
