package org.hearth.ember.ui.chat

import android.content.Context
import android.view.textservice.SentenceSuggestionsInfo
import android.view.textservice.SpellCheckerSession
import android.view.textservice.SuggestionsInfo
import android.view.textservice.TextInfo
import android.view.textservice.TextServicesManager
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** A word the system spell checker does not know, where it is in the text, and what it would put instead. */
data class SpellError(val start: Int, val end: Int, val suggestions: List<String>)

/**
 * Spell checking for the message box, through Android's own spell checker (the one the keyboard and the phone's language settings use), so it
 * follows the person's language and dictionary and needs nothing of its own. Compose text fields do not underline mistakes by themselves.
 * Does nothing (and says no mistakes) when the phone has no spell checker switched on.
 */
class SpellChecker(context: Context) {
    private val manager = context.getSystemService(Context.TEXT_SERVICES_MANAGER_SERVICE) as? TextServicesManager
    private var session: SpellCheckerSession? = null
    private var waiting: ((Array<SentenceSuggestionsInfo>?) -> Unit)? = null
    private var waitingWords: ((Array<SuggestionsInfo>?) -> Unit)? = null

    private val listener = object : SpellCheckerSession.SpellCheckerSessionListener {
        override fun onGetSuggestions(results: Array<SuggestionsInfo>?) {
            waitingWords?.invoke(results)
            waitingWords = null
        }
        override fun onGetSentenceSuggestions(results: Array<SentenceSuggestionsInfo>?) {
            waiting?.invoke(results)
            waiting = null
        }
    }

    private fun open(): SpellCheckerSession? {
        session?.let { if (!it.isSessionDisconnected) return it }
        // null locale: the language chosen for spell checking in the phone's settings.
        session = runCatching { manager?.newSpellCheckerSession(null, null, listener, true) }.getOrNull()
        return session
    }

    /** The mistakes in [text], or none. The answer arrives a moment later; asking again cancels the one in flight. */
    suspend fun check(text: String): List<SpellError> {
        if (text.isBlank()) return emptyList()
        val s = open() ?: return emptyList()
        val results = suspendCancellableCoroutine<Array<SentenceSuggestionsInfo>?> { cont ->
            waiting = { cont.resume(it) }
            cont.invokeOnCancellation { waiting = null }
            runCatching { s.getSentenceSuggestions(arrayOf(TextInfo(text)), 5) }.onFailure { waiting = null; if (cont.isActive) cont.resume(null) }
        }
        val fromSentence = results?.let { parse(text, it) }.orEmpty()
        // Sentence answers are the best (they know the context); a spell checker that gives none is asked word by word instead.
        if (results != null && results.any { it.suggestionsCount > 0 }) return fromSentence
        return checkWords(s, text)
    }

    private suspend fun checkWords(s: SpellCheckerSession, text: String): List<SpellError> {
        val words = Regex("[\\p{L}'\u2019-]+").findAll(text).filter { isCheckable(it.value) }.take(60).toList()
        if (words.isEmpty()) return emptyList()
        val answers = suspendCancellableCoroutine<Array<SuggestionsInfo>?> { cont ->
            waitingWords = { cont.resume(it) }
            cont.invokeOnCancellation { waitingWords = null }
            runCatching { s.getSuggestions(words.map { TextInfo(it.value) }.toTypedArray(), 4, false) }.onFailure { waitingWords = null; if (cont.isActive) cont.resume(null) }
        } ?: return emptyList()
        val out = ArrayList<SpellError>()
        for ((i, w) in words.withIndex()) {
            val info = answers.getOrNull(i) ?: continue
            if (!isMistake(info.suggestionsAttributes, info.suggestionsCount)) continue
            out.add(SpellError(w.range.first, w.range.last + 1, (0 until info.suggestionsCount).map { info.getSuggestionAt(it) }.filter { it != w.value }.take(4)))
        }
        return out
    }

    /** Is a spell checker switched on in Android's settings? (Without one there is nothing to check with.) */
    fun available(): Boolean = open() != null

    fun close() { runCatching { session?.close() }; session = null }

    companion object {
        /** Words that are not words to a dictionary: addresses, numbers, handles, tags and the like are never marked. */
        fun isCheckable(word: String): Boolean =
            word.length > 1 && word.all { it.isLetter() || it == '\'' || it == '’' || it == '-' } && word.any { it.isLetter() }

        /**
         * Not in the dictionary, and either marked as a typo or with suggestions to offer (what Android's own text boxes use; Google's keyboard
         * marks a typo by offering suggestions rather than by the typo flag).
         */
        fun isMistake(attributes: Int, suggestionCount: Int): Boolean =
            attributes and SuggestionsInfo.RESULT_ATTR_IN_THE_DICTIONARY == 0 &&
                (attributes and SuggestionsInfo.RESULT_ATTR_LOOKS_LIKE_TYPO != 0 || suggestionCount > 0)

        fun parse(text: String, results: Array<SentenceSuggestionsInfo>): List<SpellError> {
            val out = ArrayList<SpellError>()
            for (sentence in results) {
                for (i in 0 until sentence.suggestionsCount) {
                    val info = sentence.getSuggestionsInfoAt(i)
                    if (!isMistake(info.suggestionsAttributes, info.suggestionsCount)) continue
                    val start = sentence.getOffsetAt(i)
                    val end = start + sentence.getLengthAt(i)
                    if (start < 0 || end > text.length || end <= start) continue
                    val word = text.substring(start, end)
                    if (!isCheckable(word)) continue
                    // A word that is part of an address or a handle ("@maya", "a.b/c") is not a mistake.
                    if (start > 0 && text[start - 1] in "@#/:._") continue
                    if (end < text.length && text[end] in "@/:_") continue
                    val suggestions = (0 until info.suggestionsCount).map { info.getSuggestionAt(it) }.filter { it.isNotBlank() && it != word }.take(4)
                    out.add(SpellError(start, end, suggestions))
                }
            }
            return out
        }

        /** The mistake the cursor is in or touching, for the suggestions above the box. */
        fun at(errors: List<SpellError>, cursor: Int): SpellError? = errors.firstOrNull { cursor in it.start..it.end }

        /** [text] with the mistake replaced, and where the cursor goes (just after the new word). */
        fun apply(text: String, error: SpellError, replacement: String): Pair<String, Int> =
            text.replaceRange(error.start, error.end, replacement) to error.start + replacement.length
    }
}

/** The wavy red line under each mistake, drawn under the text of the box (one piece for each line a word sits on). */
fun DrawScope.drawSpellErrors(layout: TextLayoutResult?, errors: List<SpellError>, color: Color) {
    if (layout == null || errors.isEmpty()) return
    val textLength = layout.layoutInput.text.length
    val amplitude = 1.5.dp.toPx()
    val step = 3.dp.toPx()
    for (e in errors) {
        if (e.end > textLength) continue
        var from = e.start
        while (from < e.end) {
            val line = layout.getLineForOffset(from)
            val lineEnd = minOf(e.end, layout.getLineEnd(line, visibleEnd = true))
            val to: Int = if (lineEnd > from) lineEnd else e.end
            val left = layout.getBoundingBox(from).left
            val right = layout.getBoundingBox(maxOf(from, to - 1)).right
            val y = layout.getLineBottom(line) - 1.dp.toPx()
            val path = Path().apply {
                moveTo(left, y)
                var x = left
                var up = true
                while (x < right) {
                    x = kotlin.math.min(x + step, right)
                    lineTo(x, y + if (up) -amplitude else amplitude)
                    up = !up
                }
            }
            drawPath(path, color, style = Stroke(width = 1.2.dp.toPx()))
            from = to
        }
    }
}
