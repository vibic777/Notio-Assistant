package com.notio.common

/**
 * The app log as a file to save (Android D303, as in FitEasy): JSON for a program, TXT and LOG for a person and for a log viewer
 * (the same text under two extensions). Shared by the phone and the desktop: both write their log in the same lines,
 * `2026-10-01 10:27:53.245 WARN     window: the window was busy 994 ms`, with the lines of an error indented under its entry.
 */
object AppLogExport {
    enum class Type(val extension: String, val mime: String) {
        JSON("json", "application/json"),
        TXT("txt", "text/plain"),
        LOG("log", "text/plain"),
    }

    /** One entry of the log; [raw] instead of the parts when a line is not in the usual shape. */
    data class Entry(
        val time: String? = null,
        val level: String? = null,
        val tag: String? = null,
        val message: String? = null,
        val details: List<String> = emptyList(),
        val raw: String? = null,
    )

    private val LINE = Regex("^(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3}) (\\S+)\\s+([^:\\s][^:]*): (.*)$")

    fun fileName(appVersion: String, type: Type): String = "Notio-log-$appVersion.${type.extension}"

    /** The entries of the log text, oldest first, as it is written. */
    fun entries(text: String): List<Entry> {
        val out = ArrayList<Entry>()
        for (line in text.lineSequence()) {
            if (line.isBlank()) continue
            if ((line.startsWith(" ") || line.startsWith("\t")) && out.isNotEmpty()) {
                val last = out.removeAt(out.size - 1)
                out += last.copy(details = last.details + line.trim())
                continue
            }
            val m = LINE.matchEntire(line)
            out += if (m != null) Entry(m.groupValues[1], m.groupValues[2], m.groupValues[3], m.groupValues[4]) else Entry(raw = line)
        }
        return out
    }

    /** The bytes of the file (UTF-8). [generatedAt] is the moment of the export as text. */
    fun export(text: String, type: Type, appVersion: String, platform: String, generatedAt: String): ByteArray {
        if (type != Type.JSON) return text.toByteArray(Charsets.UTF_8)
        val sb = StringBuilder(text.length + 256)
        sb.append("{\n  \"format\": \"notio-log\",\n  \"version\": 1,\n  \"app\": \"Notio by vibic\",\n")
        sb.append("  \"appVersion\": ").append(quote(appVersion)).append(",\n")
        sb.append("  \"platform\": ").append(quote(platform)).append(",\n")
        sb.append("  \"generatedAt\": ").append(quote(generatedAt)).append(",\n  \"entries\": [")
        entries(text).forEachIndexed { i, e ->
            sb.append(if (i == 0) "\n    {" else ",\n    {")
            val fields = ArrayList<String>()
            e.time?.let { fields += "\"time\": " + quote(it) }
            e.level?.let { fields += "\"level\": " + quote(it) }
            e.tag?.let { fields += "\"tag\": " + quote(it) }
            e.message?.let { fields += "\"message\": " + quote(it) }
            if (e.details.isNotEmpty()) fields += "\"details\": [" + e.details.joinToString(", ") { quote(it) } + "]"
            e.raw?.let { fields += "\"raw\": " + quote(it) }
            sb.append(fields.joinToString(", ")).append('}')
        }
        sb.append("\n  ]\n}\n")
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    private fun quote(s: String): String {
        val sb = StringBuilder(s.length + 2).append('"')
        for (c in s) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c < ' ' -> sb.append("\\u").append(c.code.toString(16).padStart(4, '0'))
                else -> sb.append(c)
            }
        }
        return sb.append('"').toString()
    }
}
