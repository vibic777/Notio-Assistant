package com.notebudget.app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

/** What is wrong in a regular expression, and where (D247). */
class PatternCheckTest {

    private fun problem(expr: String): Pair<PatternProblem, Int>? = PatternCheck.check(expr)?.let { it.problem to it.index }

    /** The check must not invent mistakes: every expression it refuses is refused by the engine too. */
    private fun engineRefuses(expr: String) {
        try {
            Regex(expr)
            fail("the engine reads \"$expr\"")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun `the usual mistakes are found with their place`() {
        val cases = mapOf(
            "*Вкусная*" to (PatternProblem.NOTHING_TO_REPEAT to 0),
            "a|+b" to (PatternProblem.NOTHING_TO_REPEAT to 2),
            "(?b)" to null, // "(?" opens a group with flags: not this check's business, the engine decides
            "(a" to (PatternProblem.UNCLOSED_GROUP to 0),
            "a(b(c)" to (PatternProblem.UNCLOSED_GROUP to 1),
            "a)" to (PatternProblem.UNMATCHED_CLOSE to 1),
            "[abc" to (PatternProblem.UNCLOSED_CLASS to 0),
            "a{x}" to (PatternProblem.BAD_BRACE to 1),
            "tea\\" to (PatternProblem.TRAILING_BACKSLASH to 3),
            "[z-a]" to (PatternProblem.BAD_RANGE to 1),
            "[я-а]" to (PatternProblem.BAD_RANGE to 1),
        )
        for ((expr, expected) in cases) {
            if (expected == null) continue
            assertEquals(expr, expected, problem(expr))
            engineRefuses(expr)
        }
    }

    @Test
    fun `correct expressions pass`() {
        listOf(
            "^Buy", "\\d+ kg", "tea|coffee", "Tast(y|e)", "a{2}", "a{1,3}?", "a*?", "a++", "[]a]", "[^]a]", "[a-z0-9_]",
            "\\*Tasty\\*", "(?:ab)+", "(?i)tea", "x$", "[\\]]", "a.*b", "\\(", "",
        ).forEach { expr ->
            assertNull(expr, PatternCheck.check(expr))
            Regex(expr)
        }
    }

    @Test
    fun `a search with a mistake carries it, a correct one does not`() {
        val wrong = TextPattern.of("*Вкусная*", SearchOptions(regex = true))!!
        assertEquals(PatternError(PatternProblem.NOTHING_TO_REPEAT, 0, "*"), wrong.error)
        assertNull(TextPattern.of("Вкусн(ая|ый)", SearchOptions(regex = true))!!.error)
        // Without Regexp nothing is a mistake: the stars are wildcards.
        assertNull(TextPattern.of("*Вкусная*")!!.error)
    }
}
