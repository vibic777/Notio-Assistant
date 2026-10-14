package com.notio.desktop

import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.w3c.dom.Node

/**
 * The texts of the Android app on the desktop (AGENT.md D17): its `strings.xml` files, copied into `resources/strings/values-xx/`,
 * read here with the Android rules — `\'`, `\"`, `\n`, `\t`, `\@`, `\?` unescaped, surrounding quotes dropped, `%1$s` / `%2$d`
 * filled by [String.format], plurals chosen by the CLDR rules of the language. A text missing in a language comes from the
 * default (English) file, as on Android. So every screen ported from the phone shows the same words in the same ten languages.
 */
class AndroidRes private constructor(val language: String) {
    private val strings = HashMap<String, String>()
    private val plurals = HashMap<String, Map<String, String>>()

    init {
        load("values")
        if (language != "en") load("values-$language")
        // The texts of the desktop's own functions (DESKTOP-ONLY, D45): the same ten languages, not overwritten by syncAndroidStrings.
        load("values", "desktop_strings.xml")
        if (language != "en") load("values-$language", "desktop_strings.xml")
    }

    private fun load(folder: String, file: String = "strings.xml") {
        val stream = AndroidRes::class.java.classLoader.getResourceAsStream("strings/$folder/$file") ?: return
        val doc = stream.use { DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(it) }
        val root = doc.documentElement
        for (i in 0 until root.childNodes.length) {
            val node = root.childNodes.item(i) as? Element ?: continue
            val name = node.getAttribute("name")
            when (node.tagName) {
                "string" -> strings[name] = unescape(textOf(node))
                "plurals" -> {
                    val items = HashMap<String, String>()
                    for (j in 0 until node.childNodes.length) {
                        val item = node.childNodes.item(j) as? Element ?: continue
                        items[item.getAttribute("quantity")] = unescape(textOf(item))
                    }
                    plurals[name] = items
                }
            }
        }
    }

    /** The text of an element, inner markup (`<b>`, `<xliff:g>`) kept as its text. */
    private fun textOf(node: Node): String = buildString {
        for (i in 0 until node.childNodes.length) {
            val c = node.childNodes.item(i)
            if (c.nodeType == Node.TEXT_NODE || c.nodeType == Node.CDATA_SECTION_NODE) append(c.nodeValue) else append(textOf(c))
        }
    }

    fun has(name: String) = name in strings

    // ---- which key a text on the screen is (Android D307: a button takes its icon from the key of its words) ----

    private val labelKeys = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** The texts of this language back to their keys; of two keys with the same words either will do. */
    private val keysByText: Map<String, String> by lazy { strings.entries.associate { (key, text) -> text to key } }

    /** [text] was made from [key] (the desktop's own texts: from their English words). */
    fun rememberLabel(text: String, key: String) { labelKeys[text] = key }

    /** The key of [text], when it is a text of the app as it stands (no values put into it). */
    fun keyOfText(text: String): String? = labelKeys[text] ?: keysByText[text]

    /** The key of an English text of the app, to find the same text in this language (the desktop's own texts, row 19). */
    fun keyOfEnglish(text: String): String? = englishIndex[text]

    /** A text of the desktop only, in this language (`desktop_phrases.json`, keyed by its English words); `null` when it has none. */
    fun phrase(en: String): String? = phrases[en]

    private val phrases: Map<String, String> by lazy {
        if (language == "en") return@lazy emptyMap()
        val stream = AndroidRes::class.java.classLoader.getResourceAsStream("strings/values-$language/desktop_phrases.json") ?: return@lazy emptyMap()
        runCatching {
            val json = stream.use { it.readBytes().toString(Charsets.UTF_8) }
            (kotlinx.serialization.json.Json.parseToJsonElement(json) as kotlinx.serialization.json.JsonObject).mapValues { (_, v) -> (v as kotlinx.serialization.json.JsonPrimitive).content }
        }.getOrDefault(emptyMap())
    }

    /** `R.string.[name]`, filled with [args] like `getString(id, args)`; the name itself when it does not exist (seen at once in tests). */
    fun str(name: String, vararg args: Any?): String {
        val raw = strings[name] ?: return name
        return if (args.isEmpty()) raw else runCatching { String.format(locale, raw, *args) }.getOrDefault(raw)
    }

    /** `R.plurals.[name]` for [count], filled with [args] (usually the count itself), like `getQuantityString`. */
    fun plural(name: String, count: Int, vararg args: Any?): String {
        val items = plurals[name] ?: return name
        val raw = items[category(count)] ?: items["other"] ?: items.values.first()
        return runCatching { String.format(locale, raw, *(if (args.isEmpty()) arrayOf<Any?>(count) else args)) }.getOrDefault(raw)
    }

    /** The language of the texts; with the computer's own region when it speaks the same language (the first day of the week, D236). */
    val locale: Locale = Locale.getDefault().let { sys -> if (sys.language == language && sys.country.isNotEmpty()) Locale.of(language, sys.country) else Locale.forLanguageTag(language) }

    /** The CLDR plural category of [n] for the languages of the app. */
    private fun category(n: Int): String = when (language) {
        "ru", "be", "uk" -> {
            val m10 = n % 10
            val m100 = n % 100
            when {
                m10 == 1 && m100 != 11 -> "one"
                m10 in 2..4 && m100 !in 12..14 -> "few"
                else -> "many"
            }
        }
        "ja", "ko", "zh" -> "other"
        "fr" -> if (n == 0 || n == 1) "one" else "other"
        else -> if (n == 1) "one" else "other"
    }

    companion object {
        /** The ten languages of the app (Android D128, D132). */
        val LANGUAGES = listOf("en", "ru", "be", "de", "es", "fr", "it", "ja", "ko", "zh")

        private val cache = HashMap<String, AndroidRes>()

        /** Every English text of the app and its key; the first key wins when two share a text. */
        private val englishIndex: Map<String, String> by lazy {
            val en = forLanguage("en")
            HashMap<String, String>().also { index -> en.strings.entries.sortedBy { it.key }.forEach { (k, v) -> index.putIfAbsent(v, k) } }
        }

        /** The texts in [tag] (a backup's `languageTag` or the system's), falling back to English for a language the app has not. */
        fun forLanguage(tag: String?): AndroidRes {
            val lang = (tag ?: Locale.getDefault().toLanguageTag()).substringBefore('-').substringBefore('_').lowercase().ifBlank { "en" }
            val chosen = if (lang in LANGUAGES) lang else "en"
            return synchronized(cache) { cache.getOrPut(chosen) { AndroidRes(chosen) } }
        }

        /** Android's escapes in a `<string>`: `\'` `\"` `\n` `\t` `\\` `\@` `\?` and surrounding double quotes. */
        fun unescape(raw: String): String {
            var s = raw
            // Unquoted text: runs of white space (line breaks of the XML file) are one space, as Android reads it.
            if (s.length >= 2 && s.startsWith("\"") && s.endsWith("\"")) s = s.substring(1, s.length - 1)
            else s = s.replace(Regex("\\s+"), " ").trim()
            val out = StringBuilder(s.length)
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '\\' && i + 1 < s.length) {
                    when (val n = s[i + 1]) {
                        'n' -> out.append('\n')
                        't' -> out.append('\t')
                        'u' -> if (i + 5 < s.length) { out.append(s.substring(i + 2, i + 6).toInt(16).toChar()); i += 4 } else out.append(n)
                        else -> out.append(n)
                    }
                    i += 2
                } else {
                    out.append(c)
                    i++
                }
            }
            return out.toString()
        }
    }
}
