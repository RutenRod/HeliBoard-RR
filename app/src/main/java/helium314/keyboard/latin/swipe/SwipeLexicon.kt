// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.swipe

/**
 *  Word list for swipe typing, stored in a few large arrays instead of one String per word,
 *  because main dictionaries can contain several hundred thousand words.
 *  [frequency] uses the dictionary scale, 0..255 where higher means more common.
 */
class SwipeLexicon private constructor(
    private val chars: CharArray,
    private val offsets: IntArray, // offsets[i] until offsets[i + 1] are the chars of word i
    private val frequencies: ByteArray
) {
    val size: Int get() = frequencies.size

    fun wordStart(index: Int): Int = offsets[index]
    fun wordEnd(index: Int): Int = offsets[index + 1]
    fun charAt(position: Int): Char = chars[position]
    fun word(index: Int): String = String(chars, offsets[index], offsets[index + 1] - offsets[index])
    fun frequency(index: Int): Int = frequencies[index].toInt() and 0xFF

    class Builder(expectedWords: Int = 1024) {
        private var chars = CharArray(maxOf(expectedWords, 1) * 8)
        private var charCount = 0
        private var offsets = IntArray(maxOf(expectedWords, 1) + 1)
        private var frequencies = ByteArray(maxOf(expectedWords, 1))
        private var wordCount = 0

        fun add(word: String, frequency: Int) {
            if (word.isEmpty() || word.length > MAX_WORD_LENGTH) return
            if (charCount + word.length > chars.size)
                chars = chars.copyOf(maxOf(chars.size * 2, charCount + word.length))
            if (wordCount + 1 >= offsets.size)
                offsets = offsets.copyOf(offsets.size * 2)
            if (wordCount >= frequencies.size)
                frequencies = frequencies.copyOf(frequencies.size * 2)
            word.toCharArray(chars, charCount, 0, word.length)
            charCount += word.length
            frequencies[wordCount] = frequency.coerceIn(0, 255).toByte()
            wordCount++
            offsets[wordCount] = charCount
        }

        fun build() = SwipeLexicon(chars.copyOf(charCount), offsets.copyOf(wordCount + 1), frequencies.copyOf(wordCount))
    }

    companion object {
        const val MAX_WORD_LENGTH = 48
        val EMPTY = Builder(1).build()
    }
}
