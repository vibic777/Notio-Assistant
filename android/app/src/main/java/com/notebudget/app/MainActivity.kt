package com.notebudget.app

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Color
import android.os.Bundle
import android.os.LocaleList
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import android.view.WindowManager
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import com.notebudget.app.core.ui.has
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.notebudget.app.core.datastore.SettingsDataStore
import com.notebudget.app.core.ui.theme.NoteBudgetTheme
import com.notebudget.app.domain.model.ThemeMode
import java.util.Locale
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

class MainActivity : ComponentActivity() {

    /**
     * Applies the language chosen in the app settings before any UI exists. `null` means "follow
     * the system". Read synchronously (one small file) so the first frame is already localised.
     */
    override fun attachBaseContext(newBase: Context) {
        val tag = runCatching { runBlocking { SettingsDataStore(newBase).languageTag.first() } }.getOrNull()
        super.attachBaseContext(localized(newBase, tag))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val settings = (application as NoteBudgetApplication).container.settings
        // The look of the first frame is read before it (no flash of the wrong theme), in one wait instead of three (D200).
        val (initialTheme, initialColorTheme, initialFontSize) = runBlocking {
            Triple(settings.themeMode.first(), settings.colorTheme.first(), settings.fontSize.first())
        }
        val initialCustomColor = runBlocking { settings.customThemeColor.first() }
        val gate = (application as NoteBudgetApplication).container.lockGate
        // Opened by the person: every alarm is set again (D341). A phone that force-stopped the app took them all away without a word,
        // and nothing else would bring them back before the next restart of the phone.
        if (savedInstanceState == null) {
            val container = (application as NoteBudgetApplication).container
            container.appScope.launch(kotlinx.coroutines.Dispatchers.Default) {
                runCatching { com.notebudget.app.core.alarm.rearmAlarms(container) }
                    .onFailure { com.notebudget.app.core.common.AppLog.e("reminder", "the alarms could not be set again at the start", it) }
                runCatching { container.refreshReminderStatus() }
            }
        }
        // Opened from a reminder's notification: its melody ends, as in onNewIntent (D296).
        if (intent?.action == com.notebudget.app.core.notification.OpenNoteRequest.ACTION_OPEN_CALENDAR ||
            intent?.action == com.notebudget.app.core.notification.OpenNoteRequest.ACTION_OPEN_NOTE
        ) {
            com.notebudget.app.core.notification.ReminderRingService.stop(this)
            answeredRing(intent)
        }
        (application as NoteBudgetApplication).container.openNote.take(intent)
        // The lock is read before the first frame, so the notes are never visible for a moment when it is on.
        runBlocking { gate.start() }
        lifecycleScope.launch {
            // While the lock is on the window is hidden from screenshots and from the recent-apps picture.
            gate.enabled.collect { on ->
                if (on) window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
        setContent {
            val mode by settings.themeMode.collectAsState(initial = initialTheme)
            val colorTheme by settings.colorTheme.collectAsState(initial = initialColorTheme)
            val customColor by settings.customThemeColor.collectAsState(initial = initialCustomColor)
            val fontSize by settings.fontSize.collectAsState(initial = initialFontSize)
            val container = (application as NoteBudgetApplication).container
            val specs by settings.backgrounds.collectAsState(initial = emptyMap())
            val dateTimeFormats by settings.dateTimeFormats.collectAsState(initial = com.notebudget.app.domain.model.DateTimeFormats())
            val previewLines by settings.previewLines.collectAsState(initial = 2)
            // How long a message at the bottom stays (D360): read by whoever shows one.
            androidx.compose.runtime.LaunchedEffect(Unit) { settings.noticeSeconds.collect { com.notio.common.NoticeTime.seconds = it } }
            val mediaPreview by settings.mediaPreview.collectAsState(initial = com.notebudget.app.domain.model.MediaPreviewMode.FIRST)
            val drawingPreview by settings.drawingPreview.collectAsState(initial = true)
            val cardTint by settings.cardTint.collectAsState(initial = com.notebudget.app.core.datastore.CardTint.DEFAULT)
            val commentDisplay by settings.commentDisplay.collectAsState(initial = com.notebudget.app.domain.model.CommentDisplay())
            val stampDisplay by settings.stampDisplay.collectAsState(initial = com.notebudget.app.domain.model.StampDisplay())
            val backgrounds = androidx.compose.runtime.remember(specs) { com.notebudget.app.core.ui.effectiveBackgrounds(specs, container.backgroundImages) }
            val dark = when (mode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            // Status / navigation bar icons must stay readable when the theme differs from the system's.
            SideEffect {
                enableEdgeToEdge(
                    statusBarStyle = if (dark) SystemBarStyle.dark(Color.TRANSPARENT)
                    else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
                    navigationBarStyle = if (dark) SystemBarStyle.dark(Color.TRANSPARENT)
                    else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
                )
            }
            NoteBudgetTheme(
                darkTheme = dark, colorTheme = colorTheme, fontSize = fontSize, customColor = customColor,
                transparentPage = backgrounds.has(com.notebudget.app.domain.model.BackgroundSlot.APP),
            ) {
                androidx.compose.runtime.CompositionLocalProvider(
                    com.notebudget.app.core.ui.LocalBackgrounds provides backgrounds,
                    com.notebudget.app.core.ui.LocalDateTimeFormats provides dateTimeFormats,
                    com.notebudget.app.core.ui.LocalStampDisplay provides stampDisplay,
                    com.notebudget.app.core.ui.LocalCommentDisplay provides commentDisplay,
                    com.notebudget.app.core.ui.LocalPreviewLines provides previewLines,
                    com.notebudget.app.core.ui.LocalMediaPreview provides mediaPreview,
                    com.notebudget.app.core.ui.LocalDrawingPreview provides drawingPreview,
                    com.notebudget.app.core.ui.LocalCardTint provides cardTint / 100f,
                ) {
                    // Enter of the on-screen keyboard as text, so no word is typed twice on the next line (every field).
                    com.notebudget.app.core.ui.ImeEnterFix {
                    androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize()) {
                        com.notebudget.app.core.ui.BackgroundLayer(
                            com.notebudget.app.domain.model.BackgroundSlot.APP,
                            androidx.compose.ui.Modifier.matchParentSize(),
                        )
                        val locked by gate.locked.collectAsState()
                        // Nothing of the app is composed while it is locked.
                        if (locked) {
                            com.notebudget.app.feature.lock.LockScreen(gate, container.appLock, this@MainActivity)
                        } else {
                            NoteBudgetApp()
                            com.notebudget.app.core.ui.LastErrorDialog()
                            // A pressed hint opens the help over whatever is shown (D332).
                            com.notebudget.app.feature.help.HelpOverlay()
                        }
                    }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        (application as NoteBudgetApplication).container.openNote.take(intent)
        // Opening the note from the reminder also stops its melody.
        com.notebudget.app.core.notification.ReminderRingService.stop(this)
        answeredRing(intent)
    }

    /** Opened from the notification of a reminder: it was answered, its next ring is a first ring again (D351). */
    private fun answeredRing(intent: android.content.Intent?) {
        intent?.getStringExtra(com.notebudget.app.core.notification.OpenNoteRequest.EXTRA_REMINDER_ID)
            ?.let { com.notebudget.app.core.alarm.RingSeries.answered(this, it) }
    }

    /**
     * Every window of the phone that the app opens itself goes through here (D346): the file picker of a backup or an export, a
     * chosen picture or melody, "share", a page of the phone's settings. The lock is told, so the way back does not ask for it.
     */
    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun startActivityForResult(intent: android.content.Intent, requestCode: Int, options: Bundle?) {
        runCatching { (application as NoteBudgetApplication).container.lockGate.onOwnWindow() }
        super.startActivityForResult(intent, requestCode, options)
    }

    override fun onStop() {
        super.onStop()
        // A turn of the screen is not "leaving the app".
        if (!isChangingConfigurations) (application as NoteBudgetApplication).container.lockGate.onLeft()
        // "Always ready for a sync" (D342): the service keeps the port while the app is in the background. Without it (the switch
        // is off, or Android did not let the service start) nothing listens off the screen, as before (D323).
        if (!isChangingConfigurations) {
            com.notebudget.app.data.sync.SyncListenService.appOnScreen = false
            runCatching {
                val engine = (application as NoteBudgetApplication).container.syncEngine
                // Off the screen the port and the announcement stay; looking for other devices is for the pages of the app (D345).
                if (com.notebudget.app.data.sync.SyncListenService.keeps) engine.lookForDevices(false) else engine.stopListening()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        (application as NoteBudgetApplication).container.lockGate.onReturned()
        com.notebudget.app.data.sync.SyncListenService.appOnScreen = true
        // "Always ready for a sync" (D323, D342): the phone listens all the time, on the screen and off it; the service that keeps it
        // so in the background is started from here, where Android always allows it.
        if (com.notebudget.app.data.sync.SyncAlways.isOn(this)) {
            com.notebudget.app.data.sync.SyncListenService.follow(this)
            runCatching { (application as NoteBudgetApplication).container.syncEngine.run { lookForDevices(true); startListening() } }
        }
    }

    private fun localized(base: Context, tag: String?): Context {
        val locale = if (tag == null) {
            // Back to "system": undo an earlier Locale.setDefault of this process.
            Resources.getSystem().configuration.locales[0]
        } else {
            Locale.forLanguageTag(tag)
        }
        Locale.setDefault(locale)
        if (tag == null) return base
        val config = Configuration(base.resources.configuration).apply {
            setLocale(locale)
            setLocales(LocaleList(locale))
        }
        return base.createConfigurationContext(config)
    }
}
