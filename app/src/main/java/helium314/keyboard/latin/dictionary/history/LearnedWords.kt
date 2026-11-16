// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.dictionary.history

import android.content.Context
import android.content.Intent
import helium314.keyboard.dictionarypack.DictionaryPackConstants
import helium314.keyboard.latin.NgramContext
import helium314.keyboard.latin.dictionary.Dictionary
import helium314.keyboard.latin.dictionary.ExpandableBinaryDictionary
import helium314.keyboard.latin.utils.Log
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.lang.ref.WeakReference
import java.util.Locale

/**
 *  Access to what the keyboard has learned from typing, for showing and managing it in the settings:
 *  the learned words of a language, words blocked from being suggested and learned,
 *  and forgetting what was learned in the last few minutes.
 *  Everything stays on the device.
 */
object LearnedWords {
    private const val TAG = "LearnedWords"

    class Entry(val word: String, val strength: Int)

    /** [lastUsedSeconds] is seconds since epoch, 0 if unknown. [nextWords] are words written after this word. */
    class Details(val word: String, val strength: Int, val lastUsedSeconds: Int, val nextWords: List<Entry>)

    // ------------------ learned words ------------------

    /** All words in the learned words dictionary. [Entry.strength] is -1 for words that are remembered, but not suggested (yet). */
    fun list(context: Context, locale: Locale): List<Entry> {
        val result = ArrayList<Entry>()
        try {
            UserHistoryDictionary.getDictionary(context, locale).forEachWord { word, strength -> result.add(Entry(word, strength)) }
        } catch (e: Throwable) {
            Log.w(TAG, "could not list learned words for $locale", e)
        }
        return result
    }

    fun details(context: Context, locale: Locale, word: String): Details? {
        val property = try {
            UserHistoryDictionary.getDictionary(context, locale).getWordProperty(word, false)
        } catch (e: Throwable) {
            Log.w(TAG, "could not get details for learned word", e)
            null
        } ?: return null
        val nextWords = property.mNgrams.orEmpty()
            .map { Entry(it.mTargetWord.mWord, it.mTargetWord.probability) }
            .filter { it.word.isNotEmpty() }
            .sortedByDescending { it.strength }
        return Details(word, property.mProbabilityInfo.mProbability, property.mProbabilityInfo.mTimestamp, nextWords)
    }

    fun remove(context: Context, locale: Locale, word: String) {
        UserHistoryDictionary.getDictionary(context, locale).removeUnigramEntryDynamically(word)
    }

    fun removeAll(context: Context, locale: Locale) {
        UserHistoryDictionary.getDictionary(context, locale).clear()
    }

    /** Writes one line per word: the word, a tab and its strength. */
    fun exportWords(context: Context, locale: Locale, output: OutputStream): Int {
        val words = list(context, locale).sortedByDescending { it.strength }
        output.bufferedWriter(Charsets.UTF_8).use { writer ->
            words.forEach { writer.append(it.word).append('\t').append(it.strength.toString()).append('\n') }
        }
        return words.size
    }

    /** Reads words from lines, only the text before the first tab or space is used. @return the number of added words */
    fun importWords(context: Context, locale: Locale, input: InputStream): Int {
        val dictionary = UserHistoryDictionary.getDictionary(context, locale)
        val blocked = blockedWords(context, locale).toHashSet()
        val timestamp = (System.currentTimeMillis() / 1000).toInt()
        var count = 0
        input.bufferedReader(Charsets.UTF_8).useLines { lines ->
            for (line in lines) {
                val word = line.trim().takeWhile { !it.isWhitespace() }
                if (word.isEmpty() || word.length > 48 || word in blocked) continue
                // a word needs to be seen twice to be suggested
                repeat(2) { dictionary.updateEntriesForWord(NgramContext.EMPTY_PREV_WORDS_INFO, word, true, 1, timestamp) }
                count++
            }
        }
        return count
    }

    // ------------------ blocked words ------------------
    // same file as the blacklist used by DictionaryFacilitatorImpl, the keyboard reads it when dictionaries are loaded

    private fun blockedWordsFile(context: Context, locale: Locale) =
        File(context.filesDir, "blacklists" + File.separator + locale.toLanguageTag() + ".txt")

    fun blockedWords(context: Context, locale: Locale): List<String> {
        val file = blockedWordsFile(context, locale)
        if (!file.isFile) return emptyList()
        return try {
            file.readLines().filter { it.isNotBlank() }.distinct()
        } catch (e: Exception) {
            Log.w(TAG, "could not read blocked words", e)
            emptyList()
        }
    }

    /** The word is forgotten, not suggested, and not learned again. Call [reloadKeyboardDictionaries] when done with changes. */
    fun block(context: Context, locale: Locale, word: String) {
        remove(context, locale, word)
        val words = blockedWords(context, locale)
        if (word in words) return
        writeBlockedWords(context, locale, words + word)
    }

    fun unblock(context: Context, locale: Locale, word: String) {
        writeBlockedWords(context, locale, blockedWords(context, locale) - word)
    }

    private fun writeBlockedWords(context: Context, locale: Locale, words: List<String>) {
        try {
            val file = blockedWordsFile(context, locale)
            file.parentFile?.mkdirs()
            file.writeText(if (words.isEmpty()) "" else words.joinToString("\n", postfix = "\n"))
        } catch (e: Exception) {
            Log.w(TAG, "could not write blocked words", e)
        }
    }

    /** Makes the keyboard load the dictionaries again, necessary for changed blocked words to be used. */
    fun reloadKeyboardDictionaries(context: Context) {
        context.sendBroadcast(Intent(DictionaryPackConstants.NEW_DICTIONARY_INTENT_ACTION))
    }

    // ------------------ forgetting recently learned things ------------------

    private class Learned(
        val dictionary: WeakReference<ExpandableBinaryDictionary>, val ngramContext: NgramContext, val word: String,
        val isNewWord: Boolean, val isNewSequence: Boolean, val timestampSeconds: Int
    )

    // what was learned recently, only in memory
    private val recentlyLearned = ArrayDeque<Learned>()
    private const val REMEMBER_LEARNING_SECONDS = 15 * 60
    private const val MAX_REMEMBERED = 3000

    // words written before this time are not learned, relevant because learning a word is delayed
    @Volatile private var forgetBeforeSeconds = 0

    /**
     *  To be called right before a word is added to the learned words.
     *  @return false if the word must not be added because the user asked to forget it
     */
    @JvmStatic
    fun onBeforeLearning(dictionary: ExpandableBinaryDictionary, ngramContext: NgramContext, word: String, timestampSeconds: Int): Boolean {
        if (timestampSeconds <= forgetBeforeSeconds) return false
        try {
            val isNewWord = !dictionary.isInDictionary(word)
            val isNewSequence = ngramContext.isValid
                    && dictionary.getNgramProbability(ngramContext, word) == Dictionary.NOT_A_PROBABILITY
            if (!isNewWord && !isNewSequence) return true // only gets stronger, nothing that could be forgotten later
            synchronized(recentlyLearned) {
                recentlyLearned.addLast(Learned(WeakReference(dictionary), ngramContext, word, isNewWord, isNewSequence, timestampSeconds))
                while (recentlyLearned.size > MAX_REMEMBERED
                    || (recentlyLearned.isNotEmpty() && recentlyLearned.first().timestampSeconds < timestampSeconds - REMEMBER_LEARNING_SECONDS))
                    recentlyLearned.removeFirst()
            }
        } catch (e: Throwable) {
            Log.w(TAG, "could not remember what is learned", e)
        }
        return true
    }

    /**
     *  Removes words and word sequences that were first learned in the last [seconds], in all languages.
     *  Words that were known before only got a little stronger, this is not undone.
     *  @return the number of forgotten words and word sequences
     */
    fun forgetRecent(seconds: Int = 300): Int {
        val now = (System.currentTimeMillis() / 1000).toInt()
        forgetBeforeSeconds = now
        val toForget = synchronized(recentlyLearned) {
            val recent = recentlyLearned.filter { it.timestampSeconds >= now - seconds }
            recentlyLearned.removeAll(recent.toSet())
            recent
        }
        for (learned in toForget) {
            val dictionary = learned.dictionary.get() ?: continue
            if (learned.isNewSequence) dictionary.removeNgramEntryDynamically(learned.ngramContext, learned.word)
            if (learned.isNewWord) dictionary.removeUnigramEntryDynamically(learned.word)
        }
        return toForget.size
    }
}
