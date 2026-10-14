package com.notio.shared.domain

/** What is wrong in a regular expression, in words a person understands (D247); the texts are in every language of the app. */
enum class PatternProblem {
    /** `*`, `+`, `?` or `{2}` with nothing before it: `*Tasty*`, `(+a)`, `a|*b`. */
    NOTHING_TO_REPEAT,

    /** A `(` that is never closed. */
    UNCLOSED_GROUP,

    /** A `)` without its `(`. */
    UNMATCHED_CLOSE,

    /** A `[` that is never closed. */
    UNCLOSED_CLASS,

    /** A `{` that is not a count like `{2}` or `{1,3}`. */
    BAD_BRACE,

    /** A `\` at the very end, with nothing after it. */
    TRAILING_BACKSLASH,

    /** A range in `[ ]` that goes backwards, like `[z-a]`. */
    BAD_RANGE,

    /** Anything else the engine of the phone refuses. */
    OTHER,
}

/** A problem of an expression: what, where (the index of the character, from 0; -1 when not known) and which sign it is. */
data class PatternError(val problem: PatternProblem, val index: Int, val sign: String?)

/**
 * Finds the usual mistakes in a regular expression before the engine sees it (D247). The engine of Android (ICU) and the one of
 * the JVM word their errors differently and only in English, so the app reads the expression itself and says what and where.
 * Pure, so it is tested.
 */
object PatternCheck {

    fun check(expr: String): PatternError? {
        val groups = ArrayDeque<Int>()
        // Can a quantifier stand here, i.e. is there something before it to repeat?
        var canRepeat = false
        var i = 0
        while (i < expr.length) {
            val c = expr[i]
            when (c) {
                '\\' -> {
                    if (i == expr.length - 1) return PatternError(PatternProblem.TRAILING_BACKSLASH, i, "\\")
                    i += 2
                    canRepeat = true
                    continue
                }
                '(' -> {
                    groups.addLast(i)
                    // "(?:", "(?=", "(?!", "(?<=", "(?<!", "(?i)" and the like: the "?" belongs to the group.
                    if (i + 1 < expr.length && expr[i + 1] == '?') i++
                    canRepeat = false
                }
                ')' -> {
                    if (groups.isEmpty()) return PatternError(PatternProblem.UNMATCHED_CLOSE, i, ")")
                    groups.removeLast()
                    canRepeat = true
                }
                '|' -> canRepeat = false
                '[' -> {
                    val end = classEnd(expr, i) ?: return PatternError(PatternProblem.UNCLOSED_CLASS, i, "[")
                    badRange(expr, i, end)?.let { return it }
                    i = end + 1
                    canRepeat = true
                    continue
                }
                '*', '+', '?' -> {
                    if (!canRepeat) return PatternError(PatternProblem.NOTHING_TO_REPEAT, i, c.toString())
                    // "*?", "*+", "+?" … : lazy and possessive forms are one quantifier.
                    if (i + 1 < expr.length && (expr[i + 1] == '?' || expr[i + 1] == '+')) i++
                    canRepeat = false
                }
                '{' -> {
                    val close = expr.indexOf('}', i)
                    val body = if (close > i) expr.substring(i + 1, close) else null
                    if (body == null || !COUNT.matches(body)) return PatternError(PatternProblem.BAD_BRACE, i, "{")
                    // A count with nothing before it ("{2}a") is read differently by the engines: they decide.
                    i = close + 1
                    if (i < expr.length && (expr[i] == '?' || expr[i] == '+')) i++
                    canRepeat = false
                    continue
                }
                '^', '$' -> canRepeat = false
                else -> canRepeat = true
            }
            i++
        }
        if (groups.isNotEmpty()) return PatternError(PatternProblem.UNCLOSED_GROUP, groups.last(), "(")
        return null
    }

    private val COUNT = Regex("\\d+(,\\d*)?")

    /** The index of the `]` that closes the class opened at [start], or `null`. A `]` right after `[` or `[^` is a letter. */
    private fun classEnd(expr: String, start: Int): Int? {
        var i = start + 1
        if (i < expr.length && expr[i] == '^') i++
        if (i < expr.length && expr[i] == ']') i++
        while (i < expr.length) {
            when (expr[i]) {
                '\\' -> i++
                ']' -> return i
                '[' -> { val inner = classEnd(expr, i) ?: return null; i = inner }
            }
            i++
        }
        return null
    }

    /** A range like `z-a` inside the class from [start] to [end]. */
    private fun badRange(expr: String, start: Int, end: Int): PatternError? {
        var i = start + 1
        if (i < end && expr[i] == '^') i++
        while (i < end) {
            if (expr[i] == '\\') { i += 2; continue }
            if (i + 2 < end && expr[i + 1] == '-' && expr[i + 2] != '\\' && expr[i + 2] != '[') {
                if (expr[i] > expr[i + 2]) return PatternError(PatternProblem.BAD_RANGE, i, expr.substring(i, i + 3))
                i += 3
                continue
            }
            i++
        }
        return null
    }
}
