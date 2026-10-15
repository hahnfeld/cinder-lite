package app.cinder

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.textservice.SentenceSuggestionsInfo
import android.view.textservice.SpellCheckerSession
import android.view.textservice.SuggestionsInfo
import android.view.textservice.TextInfo
import android.view.textservice.TextServicesManager

/**
 * Autocorrect for Claude's prompt. A terminal takes keys raw, so the keyboard can't correct them
 * itself; instead this follows the word being typed and, when a space ends it, asks Android's spell
 * checker (the keyboard's own, on the device) about it. A word the checker calls a typo, with a fix
 * it recommends, is erased and retyped, along with whatever was typed since. Backspace right after a
 * fix puts back what was typed, as keyboards do.
 *
 * Keys are never held back, except Enter, which waits (half a second at most) for the last word's
 * check so the prompt isn't sent before it. Only plain words are checked: letters and apostrophes,
 * maybe a capital first, maybe punctuation after ("teh," is "teh"). Anything else (README.md,
 * getProp, ../src) is left alone, as is the rest of a word after a key that moves or edits the line.
 *
 * Runs on the main thread, where the keys and the checker's answers arrive. [type] writes to the
 * terminal.
 */
class Autocorrect(ctx: Context, private val type: (String) -> Unit) : SpellCheckerSession.SpellCheckerSessionListener {

    /** A word sent to the checker: what was typed after it (already passed on), and whether Enter waits on it. */
    private class Check(val word: String, val tail: String, val since: StringBuilder, var enter: Boolean = false)

    private val services = ctx.getSystemService(TextServicesManager::class.java)
    private var checker = newSession()
    private var asked = 0   // numbers each question, so a late answer is never applied to a later word
    private val word = StringBuilder()
    private val tail = StringBuilder()   // punctuation right after the word
    private var plain = true   // whether the word so far is one worth checking
    private var check: Check? = null
    private val afterEnter = StringBuilder()   // typed while Enter waits
    private var undo: String? = null   // what an immediate backspace types, after a fix
    private val main = Handler(Looper.getMainLooper())
    // Enter mustn't wait on a checker that doesn't answer. One that doesn't may have died (the keyboard
    // app updated, say), and this process lives on: start a new session for the next word.
    private val giveUp = Runnable {
        checker?.close()
        checker = newSession()
        finish(null)
    }

    init {
        ask("hello")   // the checker's first answer is slow; get it out of the way before any typing
    }

    /** A character typed. Returns true when this takes it, to type it itself later (only around Enter). */
    fun typed(c: Int): Boolean {
        if (check?.enter == true) { afterEnter.appendCodePoint(c); return true }
        undo = null
        if (c == '\r'.code || c == '\n'.code) {
            if (worthChecking()) start(StringBuilder()) else reset()
            check?.let { it.enter = true; main.postDelayed(giveUp, 500); return true }
            return false
        }
        // Retyping erases what came after the word character by character: give up past a line's
        // worth, or past an emoji or combining mark that the terminal draws as one but counts as more.
        check?.let {
            it.since.appendCodePoint(c)
            if (it.since.length > 40 || c > 0xFFFF || Character.getType(c) == Character.NON_SPACING_MARK.toInt() || c == 0x200D) check = null
        }
        when {
            c == ' '.code -> if (worthChecking()) start(StringBuilder().append(' ')) else reset()
            c in PUNCTUATION -> if (word.isEmpty()) plain = false else tail.appendCodePoint(c)
            Character.isLetter(c) || c == '\''.code -> {
                if (tail.isNotEmpty() || word.isNotEmpty() && Character.isUpperCase(c)) plain = false
                word.appendCodePoint(c)
            }
            else -> plain = false
        }
        return false
    }

    /** Backspace pressed. Returns true when this takes it (undoing a fix, or behind a waiting Enter). */
    fun backspace(): Boolean {
        if (check?.enter == true) { afterEnter.append(DEL); return true }
        undo?.let { type(it); forget(); return true }
        check = null
        when {
            tail.isNotEmpty() -> tail.setLength(tail.length - 1)
            word.isNotEmpty() -> word.setLength(word.length - 1)
            else -> forget()
        }
        return false
    }

    /** Anything else that moves or edits the line (arrows, tab, esc, ctrl): the word is unknown now. */
    fun forget() {
        if (check?.enter == true) finish(null) else check = null
        undo = null
        reset()
        plain = false
    }

    private fun worthChecking() = plain && word.length >= 2 && checker != null

    private fun start(since: StringBuilder) {
        val c = Check(word.toString(), tail.toString(), since)
        check = c
        reset()
        ask(c.word)
    }

    private fun reset() {
        word.setLength(0)
        tail.setLength(0)
        plain = true
    }

    private fun newSession() = services?.newSpellCheckerSession(null, null, this, true)   // the user's own checker and languages

    private fun ask(text: String) {
        checker?.getSentenceSuggestions(arrayOf(TextInfo(text, 0, ++asked)), 3)
    }

    override fun onGetSentenceSuggestions(results: Array<SentenceSuggestionsInfo>?) {
        val info = results?.firstOrNull()?.takeIf { it.suggestionsCount > 0 }?.getSuggestionsInfoAt(0) ?: return
        if (info.sequence == asked) finish(info)   // otherwise an answer that came too late
    }

    override fun onGetSuggestions(results: Array<SuggestionsInfo>?) {}

    /** Applies the checker's answer to the word, if it has a fix, and sends a waiting Enter. */
    private fun finish(info: SuggestionsInfo?) {
        main.removeCallbacks(giveUp)
        val c = check ?: return
        check = null
        val fix = info?.takeIf { it.suggestionsAttributes and TYPO_WITH_FIX == TYPO_WITH_FIX && it.suggestionsCount > 0 }
            ?.getSuggestionAt(0)?.takeIf { it != c.word }
        if (fix != null) {
            type(DEL.repeat(length(c.word + c.tail + c.since)) + fix + c.tail + c.since)
            // Only when nothing but the space was typed since: put back the word as typed.
            if (!c.enter && c.since.toString() == " ") undo = DEL.repeat(length(fix + c.tail) + 1) + c.word + c.tail
        }
        if (c.enter) {
            type("\r$afterEnter")
            if (afterEnter.isNotEmpty()) plain = false   // typed into the next prompt, untracked
            afterEnter.setLength(0)
        }
    }

    private fun length(s: String) = s.codePointCount(0, s.length)   // the terminal erases characters, not chars

    private companion object {
        const val DEL = "\u007f"   // what the terminal's backspace sends
        val PUNCTUATION = ".,!?;:".map { it.code }.toSet()
        const val TYPO_WITH_FIX = SuggestionsInfo.RESULT_ATTR_LOOKS_LIKE_TYPO or SuggestionsInfo.RESULT_ATTR_HAS_RECOMMENDED_SUGGESTIONS
    }
}
