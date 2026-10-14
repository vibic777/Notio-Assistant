package com.notio.desktop

import com.notio.shared.AppInfo

/**
 * The name of the app as the system shows it (D131, Android D355): the title of the window, the tray, a notification. It follows
 * the language of the SYSTEM, not the one chosen inside Notio — Russian has a name of its own, every other language the English one.
 */
object SystemName {
    const val RUSSIAN = "Нотио от Вибик"

    /** `user.language` is the language of the user of the system as the JVM found it at its start; Notio does not change it. */
    fun forLanguage(language: String?): String = if (language.orEmpty().lowercase().startsWith("ru")) RUSSIAN else AppInfo.NAME

    val app: String by lazy { forLanguage(System.getProperty("user.language")) }
}
