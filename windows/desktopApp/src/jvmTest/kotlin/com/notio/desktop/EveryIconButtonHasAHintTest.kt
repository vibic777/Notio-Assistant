package com.notio.desktop

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * D115 (Android D334; owner, 2026-10-09: "the reminders icon of the main screen has no hint and so no way into the help; check
 * where else a hint was forgotten on the icons"). An icon button learns its hint from the icon inside it; a button whose
 * content is a glyph or a drawing of its own had none. This reads the sources of both apps and names every icon button that
 * cannot have a hint: no `Icon` with words inside it and no `IconHint`.
 */
class EveryIconButtonHasAHintTest {
    private val call = Regex("""\b(IconButton|FilledTonalIconButton)\s*\(""")

    /** The call from its name to the end of its trailing lambda (or of its arguments). */
    private fun block(text: String, start: Int): String {
        fun close(from: Int, open: Char, shut: Char): Int {
            var depth = 0
            var i = from
            while (i < text.length) {
                if (text[i] == open) depth++ else if (text[i] == shut && --depth == 0) return i
                i++
            }
            return text.length - 1
        }
        val args = close(text.indexOf('(', start), '(', ')')
        var next = args + 1
        while (next < text.length && (text[next] == ' ' || text[next] == '\t')) next++
        return if (next < text.length && text[next] == '{') text.substring(start, close(next, '{', '}') + 1) else text.substring(start, args + 1)
    }

    @Test
    fun noIconButtonOfEitherAppIsWithoutAHint() {
        // The tests run in the module's folder; the sources of both apps are two levels up.
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "version.properties").isFile }
        val sources = listOf(File(root, "android/app/src/main/java"), File(root, "windows/desktopApp/src/jvmMain/kotlin"))
        val without = ArrayList<String>()
        var seen = 0
        for (file in sources.flatMap { it.walkTopDown().filter { f -> f.isFile && f.extension == "kt" }.toList() }) {
            // The buttons themselves are declared there.
            if (file.name == "TipButtons.kt") continue
            val text = file.readText()
            for (match in call.findAll(text)) {
                if (text.substring(0, match.range.first).substringAfterLast('\n').contains("fun ")) continue
                seen++
                val body = block(text, match.range.first)
                val saysItself = "IconHint(" in body
                val icon = Regex("""\bIcon\s*\(""").containsMatchIn(body)
                val words = Regex("""contentDescription\s*=\s*(?!null)""").containsMatchIn(body) ||
                    Regex("""\bIcon\s*\([^()]*,\s*(?!null)[A-Za-z"(]""").containsMatchIn(body)
                if (!saysItself && !(icon && words)) without += file.name + ":" + (text.substring(0, match.range.first).count { it == '\n' } + 1)
            }
        }
        assertTrue(seen > 100, "the sources were not found: $seen icon buttons")
        assertTrue(without.isEmpty(), "icon buttons without a hint: $without")
    }
}
