// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import android.content.Intent
import android.text.format.DateUtils
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import helium314.keyboard.latin.R
import helium314.keyboard.latin.dictionary.history.LearnedWords
import helium314.keyboard.settings.SearchScreen
import helium314.keyboard.settings.dialogs.ConfirmationDialog
import helium314.keyboard.settings.dialogs.ListPickerDialog
import helium314.keyboard.settings.dialogs.ThreeButtonAlertDialog
import helium314.keyboard.settings.filePicker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/** Shows what the keyboard learned from typing, and allows removing and blocking words. */
@Composable
fun LearnedWordsScreen(onClickBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val locales = remember { getSortedDictionaryLocales().toList() }
    var locale by remember { mutableStateOf(locales.firstOrNull() ?: Locale.getDefault()) }
    var words: List<LearnedWords.Entry>? by remember { mutableStateOf(null) }
    var reloadTrigger by remember { mutableIntStateOf(0) }
    var sortByStrength by remember { mutableStateOf(true) }
    var selectedWord: LearnedWords.Entry? by remember { mutableStateOf(null) }
    var showLocalePicker by remember { mutableStateOf(false) }
    var showBlockedWords by remember { mutableStateOf(false) }
    var showForgetRecentConfirmation by remember { mutableStateOf(false) }
    var showRemoveAllConfirmation by remember { mutableStateOf(false) }
    var blockedWordsChanged by remember { mutableStateOf(false) }

    LaunchedEffect(locale, reloadTrigger) {
        words = withContext(Dispatchers.IO) { LearnedWords.list(ctx, locale) }
    }
    // the keyboard needs to load the changed list of blocked words
    DisposableEffect(Unit) {
        onDispose { if (blockedWordsChanged) LearnedWords.reloadKeyboardDictionaries(ctx.applicationContext) }
    }

    fun toast(text: String) = Toast.makeText(ctx, text, Toast.LENGTH_LONG).show()

    /** dictionary changes are done in background, so wait a little before reading the words again */
    fun reloadDelayed() {
        scope.launch {
            delay(500)
            reloadTrigger++
        }
    }

    val exportPicker = filePicker { uri ->
        scope.launch {
            val count = withContext(Dispatchers.IO) {
                runCatching {
                    ctx.contentResolver.openOutputStream(uri)?.use { LearnedWords.exportWords(ctx, locale, it) }
                }.getOrNull()
            }
            toast(if (count == null) ctx.getString(R.string.learned_words_file_error)
                else ctx.getString(R.string.learned_words_exported, count))
        }
    }
    val importPicker = filePicker { uri ->
        scope.launch {
            val count = withContext(Dispatchers.IO) {
                runCatching {
                    ctx.contentResolver.openInputStream(uri)?.use { LearnedWords.importWords(ctx, locale, it) }
                }.getOrNull()
            }
            toast(if (count == null) ctx.getString(R.string.learned_words_file_error)
                else ctx.getString(R.string.learned_words_imported, count))
            reloadDelayed()
        }
    }

    val menu = listOfNotNull(
        stringResource(if (sortByStrength) R.string.learned_words_sort_alphabetically else R.string.learned_words_sort_by_strength) to { sortByStrength = !sortByStrength },
        if (locales.size > 1) stringResource(R.string.learned_words_change_language) to { showLocalePicker = true } else null,
        stringResource(R.string.learned_words_blocked) to { showBlockedWords = true },
        stringResource(R.string.learned_words_forget_recent) to { showForgetRecentConfirmation = true },
        stringResource(R.string.learned_words_export) to {
            exportPicker.launch(
                Intent(Intent.ACTION_CREATE_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_TITLE, "learned-words-${locale.toLanguageTag()}.txt")
            )
        },
        stringResource(R.string.learned_words_import) to {
            importPicker.launch(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("text/*"))
        },
        stringResource(R.string.learned_words_remove_all) to { showRemoveAllConfirmation = true },
    )

    val currentWords = words
    val sortedWords = remember(currentWords, sortByStrength) {
        if (currentWords == null) emptyList()
        else if (sortByStrength) currentWords.sortedWith(compareByDescending<LearnedWords.Entry> { it.strength }.thenBy { it.word.lowercase() })
        else currentWords.sortedBy { it.word.lowercase() }
    }

    SearchScreen(
        onClickBack = onClickBack,
        title = {
            Column {
                Text(stringResource(R.string.learned_words))
                Text(
                    if (currentWords == null) locale.getLocaleDisplayNameForUserDictSettings(ctx)
                    else stringResource(R.string.learned_words_subtitle, locale.getLocaleDisplayNameForUserDictSettings(ctx), currentWords.size),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        menu = menu,
        filteredItems = { term ->
            if (term.isBlank()) sortedWords
            else sortedWords.filter { it.word.contains(term.trim(), ignoreCase = true) }
        },
        itemContent = { entry ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { selectedWord = entry }
                    .padding(vertical = 10.dp, horizontal = 16.dp)
            ) {
                Text(entry.word, style = MaterialTheme.typography.bodyLarge)
                Text(
                    if (entry.strength < 0) stringResource(R.string.learned_words_strength_new) else entry.strength.toString(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    )

    selectedWord?.let { entry ->
        var details: LearnedWords.Details? by remember(entry) { mutableStateOf(null) }
        LaunchedEffect(entry) {
            details = withContext(Dispatchers.IO) { LearnedWords.details(ctx, locale, entry.word) }
        }
        ThreeButtonAlertDialog(
            onDismissRequest = { selectedWord = null },
            cancelButtonText = stringResource(R.string.dialog_close),
            neutralButtonText = stringResource(R.string.learned_words_block),
            onNeutral = {
                LearnedWords.block(ctx, locale, entry.word)
                blockedWordsChanged = true
                words = words?.filterNot { it.word == entry.word }
                selectedWord = null
            },
            confirmButtonText = stringResource(R.string.delete),
            onConfirmed = {
                LearnedWords.remove(ctx, locale, entry.word)
                words = words?.filterNot { it.word == entry.word }
            },
            title = { Text(entry.word) },
            scrollContent = true,
            content = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        if (entry.strength < 0) stringResource(R.string.learned_words_details_new)
                        else stringResource(R.string.learned_words_details_strength, entry.strength)
                    )
                    val currentDetails = details
                    if (currentDetails != null && currentDetails.lastUsedSeconds > 0)
                        Text(stringResource(
                            R.string.learned_words_details_last_used,
                            DateUtils.getRelativeTimeSpanString(
                                currentDetails.lastUsedSeconds * 1000L, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS
                            ).toString()
                        ))
                    if (currentDetails != null && currentDetails.nextWords.isNotEmpty())
                        Text(stringResource(
                            R.string.learned_words_details_next_words,
                            currentDetails.nextWords.take(12).joinToString(", ") { it.word }
                        ))
                    Text(
                        stringResource(R.string.learned_words_details_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        )
    }

    if (showLocalePicker)
        ListPickerDialog(
            onDismissRequest = { showLocalePicker = false },
            items = locales,
            onItemSelected = {
                words = null
                locale = it
            },
            selectedItem = locale,
            title = { Text(stringResource(R.string.learned_words_change_language)) },
            getItemName = { it.getLocaleDisplayNameForUserDictSettings(LocalContext.current) },
        )

    if (showBlockedWords) {
        var blocked by remember { mutableStateOf(LearnedWords.blockedWords(ctx, locale)) }
        ThreeButtonAlertDialog(
            onDismissRequest = { showBlockedWords = false },
            onConfirmed = { },
            confirmButtonText = null,
            cancelButtonText = stringResource(R.string.dialog_close),
            title = { Text(stringResource(R.string.learned_words_blocked)) },
            content = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(if (blocked.isEmpty()) R.string.learned_words_blocked_empty else R.string.learned_words_blocked_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    LazyColumn(Modifier.heightIn(max = 320.dp)) {
                        items(blocked) { word ->
                            Text(
                                word,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        LearnedWords.unblock(ctx, locale, word)
                                        blockedWordsChanged = true
                                        blocked = blocked - word
                                    }
                                    .padding(vertical = 10.dp)
                            )
                        }
                    }
                }
            }
        )
    }

    if (showForgetRecentConfirmation)
        ConfirmationDialog(
            onDismissRequest = { showForgetRecentConfirmation = false },
            onConfirmed = {
                val count = LearnedWords.forgetRecent()
                toast(ctx.getString(R.string.learned_words_forgot_recent, count))
                reloadDelayed()
            },
            title = { Text(stringResource(R.string.learned_words_forget_recent)) },
            content = { Text(stringResource(R.string.learned_words_forget_recent_message)) },
        )

    if (showRemoveAllConfirmation)
        ConfirmationDialog(
            onDismissRequest = { showRemoveAllConfirmation = false },
            onConfirmed = {
                LearnedWords.removeAll(ctx, locale)
                words = emptyList()
            },
            title = { Text(stringResource(R.string.learned_words_remove_all)) },
            content = { Text(stringResource(R.string.learned_words_remove_all_message, locale.getLocaleDisplayNameForUserDictSettings(ctx))) },
        )
}
