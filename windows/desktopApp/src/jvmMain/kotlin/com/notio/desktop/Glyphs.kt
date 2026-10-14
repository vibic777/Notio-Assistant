package com.notio.desktop

/**
 * The small pictures the app writes as characters: the alarm clock of the reminders, the bell, the pin (D132). They are emoji,
 * and an emoji is drawn only where the system has a font with it. Windows always has one; a Linux without an emoji font (a
 * bare installation, WSL) drew an empty box in their place — the first Linux build showed the reminders button as a box.
 * There each has a plain sign that every font of a desktop carries. What the person typed is never changed: this is about
 * the app's own signs only.
 */
object Glyphs {
    /** Can the system draw the emoji the app uses? Asked once; on Windows the answer is known. */
    val emojiShown: Boolean by lazy {
        if (System.getProperty("os.name").orEmpty().startsWith("Windows")) return@lazy true
        runCatching {
            val probe = "⏰🔔📌" // the alarm clock, the bell, the pin
            java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().allFonts.any { it.canDisplayUpTo(probe) == -1 }
        }.getOrDefault(false)
    }

    /** Looked up before the window is drawn, off the thread of the window: the list of fonts of a Linux takes a moment. */
    fun warmUp() {
        Thread({ emojiShown }, "notio-glyphs").apply { isDaemon = true; start() }
    }

    private fun pick(emoji: String, plain: String) = if (emojiShown) emoji else plain

    val alarm: String get() = pick("⏰", "◷")
    val bell: String get() = pick("🔔", "◷")
    val pin: String get() = pick("📌", "⚑")
    val trash: String get() = pick("🗑", "✕")
    val comment: String get() = pick("💬", "✎")
    val money: String get() = pick("💰", "¤")
    val task: String get() = pick("📋", "☑")
    val link: String get() = pick("🔗", "URL")
}
