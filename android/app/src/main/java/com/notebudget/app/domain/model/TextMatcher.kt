package com.notebudget.app.domain.model

/** How the words of a search are read (D246): with or without the case of the letters, as a pattern or as a regular expression. */
data class SearchOptions(val ignoreCase: Boolean = true, val regex: Boolean = false)

/**
 * The words of a search made into something that finds them (D246). Without [SearchOptions.regex] the words are plain text in
 * which `*` stands for any characters (also none) and `?` for one character; `*` at the start or the end changes nothing, so
 * `*Tasty*` finds "Tasty" wherever it is. With it the words are a regular expression. [error] is set when that expression
 * cannot be read (or takes too long): the search then finds nothing and says why.
 */
class TextPattern private constructor(val regex: Regex?, val error: PatternError?) {

    /** Where the pattern is in [text], without overlaps and without empty matches; at most [limit] of them. */
    fun ranges(text: String, limit: Int = MAX_MATCHES): List<IntRange> {
        val r = regex ?: return emptyList()
        if (text.isEmpty()) return emptyList()
        return guarded(text) { guardedText ->
            val out = ArrayList<IntRange>()
            var m = r.find(guardedText)
            while (m != null && out.size < limit) {
                if (m.range.last >= m.range.first) out += m.range
                m = m.next()
            }
            out
        } ?: emptyList()
    }

    /** Is the pattern somewhere in [text]? */
    fun foundIn(text: String): Boolean {
        val r = regex ?: return false
        return guarded(text) { guardedText -> r.findAll(guardedText).any { it.range.last >= it.range.first } } ?: false
    }

    companion object {
        const val MAX_MATCHES = 10_000

        /** How long one text may be searched: an expression like `(a+)+b` must not freeze the screen (rule 18). */
        private const val TIME_LIMIT_NANOS = 300_000_000L

        /** The words made into a pattern; `null` when there are no words. */
        fun of(words: String, options: SearchOptions = SearchOptions()): TextPattern? {
            if (words.isBlank()) return null
            val flags = if (options.ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet()
            if (options.regex) {
                // The usual mistakes are found by the app itself, so they can be told in the person's language and place (D247).
                PatternCheck.check(words)?.let { return TextPattern(null, it) }
                return try {
                    TextPattern(Regex(words, flags), null)
                } catch (e: java.util.regex.PatternSyntaxException) {
                    TextPattern(null, PatternError(PatternProblem.OTHER, e.index.takeIf { it in words.indices } ?: -1, null))
                } catch (e: IllegalArgumentException) {
                    TextPattern(null, PatternError(PatternProblem.OTHER, -1, null))
                }
            }
            val trimmed = words.trim('*')
            if (trimmed.isEmpty()) return null
            val source = buildString {
                trimmed.forEach { c ->
                    when (c) {
                        // Within one line: "*" never runs on over the end of a line.
                        '*' -> append("[^\\n]*?")
                        '?' -> append("[^\\n]")
                        else -> append(Regex.escape(c.toString()))
                    }
                }
            }
            return TextPattern(Regex(source, flags), null)
        }

        /** Runs [block] over [text], stopping it when it takes longer than the limit; `null` then. */
        private fun <T> guarded(text: String, block: (CharSequence) -> T): T? = try {
            block(Deadline(text, System.nanoTime() + TIME_LIMIT_NANOS))
        } catch (_: TooSlow) {
            null
        } catch (_: StackOverflowError) {
            null
        }
    }

    private class TooSlow : RuntimeException()

    /** [text] that throws [TooSlow] once the time is over; the regular expression engine reads it character by character. */
    private class Deadline(private val text: String, private val until: Long) : CharSequence {
        private var reads = 0
        override val length: Int get() = text.length
        override fun get(index: Int): Char {
            if (++reads and 0xFFF == 0 && System.nanoTime() > until) throw TooSlow()
            return text[index]
        }
        override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = text.subSequence(startIndex, endIndex)
        override fun toString(): String = text
    }
}
