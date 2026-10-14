package com.notio.desktop

import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import com.notio.shared.data.AppPaths
import com.notio.shared.domain.ThemeMode
import java.io.File
import java.nio.file.Files
import kotlin.coroutines.resume
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import org.jetbrains.skia.EncodedImageFormat

/**
 * Renders the window off screen with the demo data loaded and saves the screens as PNG files in `build/screenshots`: a smoke test
 * of the whole path import -> database -> UI, and pictures to compare with the phone.
 */
class ScreenshotTest {
    private val none = HomeActions({}, {}, {}, {}, {})

    @Test
    fun theFirstStartAsksForAFolder() = runBlocking {
        val home = Files.createTempDirectory("notio-first").toFile()
        val controller = NotioController(AppPaths(home))
        current = controller
        try {
            waitFor { controller.state.value.loaded }
            shoot(controller, "0-first-start.png")
        } finally {
            controller.close()
            home.deleteRecursively()
        }
    }

    @Test
    fun screensWithDemoData() = runBlocking {
        val home = Files.createTempDirectory("notio-ui").toFile()
        val controller = NotioController(AppPaths(home))
        current = controller
        try {
            withTimeout(30_000) {
                suspendCancellableCoroutine { c -> controller.loadDemo { r -> r.getOrThrow(); c.resume(Unit) } }
            }
            controller.setThemeMode(ThemeMode.LIGHT)
            controller.selectFolder("demo-personal")
            waitFor { controller.state.value.notes.size == 3 }
            shoot(controller, "1-home-light.png")
            controller.setThemeMode(ThemeMode.DARK)
            controller.selectFolder("demo-work")
            waitFor { controller.state.value.notes.size == 2 }
            shoot(controller, "2-home-dark.png")
            controller.setThemeMode(ThemeMode.LIGHT)
            for ((id, name) in listOf("demo-budget" to "3-budget.png", "demo-ideas" to "4-markdown.png", "demo-shopping" to "5-list.png", "demo-drawing" to "6-drawing.png", "demo-word" to "6b-word.png", "demo-schedule" to "6c-schedule.png")) {
                controller.openNote(id)
                waitFor { controller.state.value.detail?.note?.id == id }
                shoot(controller, name)
            }
            controller.openNote("demo-shopping", edit = true)
            waitFor { controller.state.value.detail?.note?.id == "demo-shopping" }
            shoot(controller, "5b-list-edit.png")
            controller.openNote("demo-budget", edit = true)
            waitFor { controller.state.value.detail?.note?.id == "demo-budget" }
            shoot(controller, "3b-budget-edit.png")
            controller.back()
            controller.toggleSearch()
            controller.toggleSearchTag(controller.state.value.allTags.first { it.name == "семья" }.id)
            waitFor { controller.state.value.notes.size == 2 }
            shoot(controller, "2b-search-tag.png")
            controller.toggleSearch()
            // The budget featured on its folder card with its totals, and notes sorted by colour with the group headers (D121, D196).
            controller.setFeatured("demo-money", "demo-budget", 7, com.notio.shared.domain.FeaturedLayout.FULL)
            controller.selectFolder("demo-personal")
            controller.setNoteSort(com.notio.shared.domain.SortOption(com.notio.shared.domain.SortKey.COLOR, false), applyAll = false)
            waitFor { controller.state.value.noteGroups.isNotEmpty() && controller.state.value.folders.any { it.featured?.remainderMinor != null } }
            shoot(controller, "2c-sorted-featured.png")
            controller.beginSelection("demo-personal")
            controller.selection.toggle(controller.state.value.notes.first().id)
            shoot(controller, "2d-select.png")
            controller.selection.end()
            controller.openReminders(null)
            waitFor { controller.state.value.reminderRows.isNotEmpty() }
            shoot(controller, "7c-reminders.png")
            // A reminder ringing (Android D293): the red light, "Ringing now", Stop at the top and on its card; a silent WAV, nothing is heard.
            val silent = File(home, "silent.wav").apply {
                val format = javax.sound.sampled.AudioFormat(8_000f, 16, 1, true, false)
                javax.sound.sampled.AudioSystem.write(
                    javax.sound.sampled.AudioInputStream(java.io.ByteArrayInputStream(ByteArray(16_000)), format, 8_000L),
                    javax.sound.sampled.AudioFileFormat.Type.WAVE, this,
                )
            }
            controller.reminders.useCases.create("demo-ideas", com.notio.shared.domain.ReminderDraft(System.currentTimeMillis() + 300, soundUri = silent.absolutePath, popupEnabled = false))
            if (runCatching { waitFor("ringing") { controller.reminders.sounding.value != null } }.isSuccess) {
                controller.openReminders(null)
                shoot(controller, "7c3-reminder-ringing.png")
                controller.reminders.silence()
            }
            // The calendar (D236): tomorrow's demo reminder, the day chosen and listed under the month.
            controller.openCalendar()
            controller.calendar.select(java.time.LocalDate.now().plusDays(1))
            if (java.time.LocalDate.now().plusDays(1).month != java.time.LocalDate.now().month) controller.calendar.nextMonth()
            controller.calendar.select(java.time.LocalDate.now().plusDays(1))
            waitFor { controller.calendar.days.value.isNotEmpty() }
            shoot(controller, "7i-calendar.png")
            // A reminder switched off (Android D268): paler, "Switched off", its switch; the calendar opened from its notification (D267).
            val first = controller.state.value.reminderRows.first { it.isPending }
            controller.setReminderEnabled(first.reminder.id, false)
            waitFor { controller.state.value.reminderRows.any { it.reminder.id == first.reminder.id && !it.reminder.isEnabled } }
            controller.openReminders(null)
            shoot(controller, "7c2-reminder-off.png")
            controller.setReminderEnabled(first.reminder.id, true)
            waitFor { controller.state.value.reminderRows.any { it.reminder.id == first.reminder.id && it.reminder.isEnabled } }
            controller.openCalendarAt(first.reminder.triggerAt, first.reminder.id)
            waitFor { controller.calendar.days.value.isNotEmpty() }
            shoot(controller, "7i2-calendar-marked.png")
            // The sheet background (Android D265).
            // Android D298: the icons a kind of note and a kind of folder are chosen by.
            shootContent(controller, "16-kind-icons.png") {
                androidx.compose.material3.Surface {
                    androidx.compose.foundation.layout.Row {
                        ALL_KINDS.forEachIndexed { i, k -> IconChoice(AppIcons.forKind(k.id), k.id, i == 1, {}) }
                        IconChoice(AppIcons.TaskAlt, "task", false, {}); IconChoice(AppIcons.Folder, "folder", true, {}); IconAction(AppIcons.NewFolder, "new folder", {})
                    }
                }
            }
            shootContent(controller, "8x-sheet-background.png") { SheetBackgroundDialog(SheetBackground(0x336699, transparent = true, transparency = 40), {}, {}) }
            controller.openSettings()
            shoot(controller, "7-settings.png")
            // The pages of the phone's settings (D29): what they change shows on the cards at once.
            controller.appSettings.update { it.copy(previewLines = 1, stampDisplay = com.notio.shared.domain.StampDisplay(notesCreated = true, notesModified = false, foldersCreated = false, foldersModified = true).encode()) }
            controller.openSettingsSection("appearance")
            shoot(controller, "7d-settings-appearance.png")
            // Android D273: the strength of the card colour, with its sample (the page scrolled to it).
            shootContent(controller, "7d2-card-tint.png") { androidx.compose.runtime.CompositionLocalProvider(LocalSettings provides controller.appSettings) { androidx.compose.foundation.layout.Column { CardTintOptions() } } }
            controller.openSettingsSection("about")
            shoot(controller, "7e-settings-about.png")
            controller.openSettingsSection("data")
            shoot(controller, "7j-settings-data.png")
            controller.openSettingsSection("recordings")
            shoot(controller, "7k-settings-recordings.png")
            controller.openHelp()
            shoot(controller, "7f-help.png")
            assertEquals(null, controller.addUnit("мешок"))
            assertEquals(com.notio.shared.domain.DomainError.DUPLICATE_UNIT_NAME, controller.addUnit("Мешок"))
            controller.openCatalog("units")
            shoot(controller, "7g-units.png")
            controller.openCatalog("tags")
            shoot(controller, "7h-tags.png")
            controller.back()
            assertEquals(Screen.Settings, controller.state.value.screen)
            // Android D280: a kept connection shows the address it is reached at, with Address… to correct it.
            controller.syncStore.add("pair-1", "phone-1", "Pixel", ByteArray(32), 1_000L)
            controller.syncStore.update("pair-1") { it.copy(lastHost = "192.168.1.153", lastPort = 47821) }
            controller.openSync()
            shoot(controller, "7b-sync.png")
            // Android D282: lines of a budget's log with Undo, one undone already, an old one without its item (no Undo).
            val K = com.notio.shared.domain.BudgetLogKind
            val log = listOf(
                com.notio.shared.transfer.BudgetLogRecord("l1", "b", 1_000, K.SUM, "Хлеб", 0, 1_000),
                com.notio.shared.transfer.BudgetLogRecord("l2", "b", 2_000, K.SUM, "Хлеб", 1_000, 51_000, "ADD", nodeId = "n1"),
                com.notio.shared.transfer.BudgetLogRecord("l3", "b", 3_000, K.RENAMED, "Хлеб ржаной", detail = "Хлеб", nodeId = "n1"),
                com.notio.shared.transfer.BudgetLogRecord("l4", "b", 4_000, K.OP_UNDONE, "Хлеб", 51_000, 1_000, "l2\n" + K.SUM, nodeId = "n1"),
                com.notio.shared.transfer.BudgetLogRecord("l5", "b", 5_000, K.ITEM_ADDED, "Молоко", detail = "", nodeId = "n2"),
            )
            shootContent(controller, "3c-budget-log-undo.png") {
                androidx.compose.material3.Surface { BudgetLogView(controller.strings, log, "Бюджет", { com.notio.shared.domain.Money.formatLabelled(it, "₽") }, onBack = {}, onClear = {}) }
            }

            // Editing, through the same calls the menus and dialogs make.
            var created = false
            controller.createFolder("Проекты", "#7B1FA2", "GRADIENT", "🚀", onError = { error("folder: $it") }) { created = true }
            waitFor { created && controller.state.value.folders.any { it.folder.name == "Проекты" } }
            val folderId = controller.state.value.folders.first { it.folder.name == "Проекты" }.folder.id
            var duplicate: com.notio.shared.data.EditError? = null
            controller.createFolder("проекты", "#7B1FA2", "SOFT", null, onError = { duplicate = it }) {}
            waitFor { duplicate != null }
            assertEquals(com.notio.shared.data.EditError.DUPLICATE_FOLDER_NAME, duplicate)
            controller.justCreated.value = null
            controller.createNote(folderId, "План", com.notio.shared.domain.NoteType.TEXT, onError = { error("note: $it") }) {}
            // The note stays in the list, framed (D257); a tap opens it.
            waitFor { controller.justCreated.value != null }
            controller.openNote(controller.justCreated.value!!, edit = true)
            waitFor { (controller.state.value.screen as? Screen.NoteView)?.edit == true && controller.state.value.detail != null }
            val noteId = controller.state.value.detail!!.note.id
            controller.saveNoteText(noteId, "План на неделю", "# Задачи\n- [ ] **сайт**\n- [x] логотип")
            waitFor { controller.state.value.detail?.note?.contentText?.contains("сайт") == true }
            shoot(controller, "8-editor.png")
            controller.back()
            waitFor { controller.state.value.notes.any { it.id == noteId } }
            shoot(controller, "9-new-folder.png")
            controller.trashNote(noteId)
            waitFor { controller.state.value.trash.any { it.id == noteId } }
            controller.restoreNote(noteId)
            waitFor { controller.state.value.notes.any { it.id == noteId } }
            controller.openTrash()
            shoot(controller, "10-trash.png")

            // Single notes (D27): export in every format of the demo notes, import the files back, clone.
            val out = File(home, "exports").apply { mkdirs() }
            for (id in listOf("demo-budget", "demo-ideas", "demo-shopping", "demo-drawing", "demo-word", "demo-schedule")) {
                val note = controller.notesOf(controller.state.value.folders.first { f -> controller.notesOf(f.folder.id).any { it.id == id } }.folder.id).first { it.id == id }
                for (format in controller.exportFormats(note)) {
                    val target = File(out, "$id-${format.key}.${format.extension}")
                    controller.exportNote(id, format, target)
                    waitFor { target.isFile && target.length() > 0 }
                }
            }
            assertTrue(File(out, "demo-drawing-png.png").readBytes()[1] == 'P'.code.toByte())
            controller.selectFolder(folderId)
            for (name in listOf("demo-budget-json.json", "demo-drawing-json.json", "demo-ideas-md.md", "demo-drawing-jpeg.jpg")) {
                val before = controller.notesOf(folderId).size
                controller.importNote(File(out, name))
                waitFor { controller.notesOf(folderId).size == before + 1 }
            }
            val budgetCopy = controller.notesOf(folderId).first { it.type == com.notio.shared.domain.NoteType.BUDGET }
            controller.cloneNote(budgetCopy.id)
            waitFor { controller.notesOf(folderId).any { it.title == budgetCopy.title + controller.res.str("note_clone_suffix", 1) } }
            controller.back()
            waitFor { controller.state.value.screen == Screen.Home }
            waitFor { controller.state.value.notes.size == controller.notesOf(folderId).size }
            shoot(controller, "11-imported.png")
            // Android D272: the copy hides its sums and goals — stars on its card; in the note the eye shows them (D273: the card colour at its default).
            val copyId = controller.notesOf(folderId).first { it.title == budgetCopy.title + controller.res.str("note_clone_suffix", 1) }.id
            controller.openBudget(copyId).let { it.setLayout(it.layout().copy(hideSums = true, hideGoals = true)) }
            controller.refresh()
            waitFor { controller.state.value.notes.any { it.id == copyId && com.notio.shared.domain.BudgetLayout.parse(it.extras).hideSums } }
            waitFor { controller.state.value.budgetRoots.containsKey(copyId) }
            shoot(controller, "11b-budget-hidden-card.png")
            controller.openNote(copyId)
            waitFor { controller.state.value.detail?.note?.id == copyId }
            shoot(controller, "11c-budget-hidden-note.png")
            controller.back()
            waitFor { controller.state.value.screen == Screen.Home }
            // Android D271: a tag made in Settings -> Tags.
            shootContent(controller, "7t-add-tag.png") {
                androidx.compose.material3.AlertDialog(onDismissRequest = {}, title = { androidx.compose.material3.Text(stringResource("tags_add_tag")) }, text = {
                    androidx.compose.material3.OutlinedTextField("работа", {}, singleLine = true, label = { androidx.compose.material3.Text(stringResource("tags_new_hint")) })
                }, confirmButton = { androidx.compose.material3.TextButton({}) { androidx.compose.material3.Text(stringResource("tags_add_confirm")) } })
            }
            // An audio note with two recordings (D112, D35): the files as the phone names them, the list in the note's JSON.
            var audioId: String? = null
            var reopened = false
            controller.justCreated.value = null
            controller.createNote(folderId, "Голосовые", com.notio.shared.domain.NoteType.AUDIO, onError = { error("audio: $it") }) {}
            // The note stays in the list, framed (D257); a tap opens it.
            waitFor { controller.justCreated.value != null }
            controller.openNote(controller.justCreated.value!!, edit = false)
            waitFor { controller.state.value.detail?.note?.type == com.notio.shared.domain.NoteType.AUDIO }
            audioId = controller.state.value.detail!!.note.id
            val clips = listOf(
                com.notio.shared.domain.AudioClip("a1b2c3d4e5f6", "Идея для проекта", "Позвонить в понедельник", createdAt = 1_000, durationMs = 1000),
                com.notio.shared.domain.AudioClip("f6e5d4c3b2a1", "", "", createdAt = 2_000, durationMs = 1000),
            )
            for (c in clips) {
                val f = File(home, "tmp-" + c.id + ".m4a")
                // The encoder is Windows Media Foundation: on Linux the clip is a file of any bytes, the list shows it the same (D129).
                if (com.notio.desktop.media.MF.available) com.notio.desktop.media.AacM4aWriter(f).use { w -> repeat(10) { w.write(ByteArray(8820)) } }
                else f.writeBytes(ByteArray(2048))
                AudioStore(com.notio.shared.data.AttachmentStore(controller.paths.attachments)).write(audioId + "_" + c.id, f.readBytes())
            }
            val doc = com.notio.shared.domain.AudioDocument(clips = clips)
            controller.saveNoteContent(audioId, doc.searchText(), doc.encode())
            // The save is written in the background: the note is opened again until the database has it.
            waitFor("audio extras") {
                val stored = controller.state.value.detail?.note?.takeIf { it.id == audioId && controller.state.value.screen is Screen.NoteView }
                if (stored?.extras?.contains("a1b2c3d4e5f6") == true && reopened) true
                else { controller.back(); controller.openNote(audioId); reopened = true; false }
            }
            shoot(controller, "13-audio.png")
            // A recording deleted goes to the media trash (D156) and comes back into its note at its place.
            val vm = controller.audioViewModel(audioId)
            waitFor("audio vm") { vm.state.value.loaded && vm.state.value.clips.size == 2 }
            vm.delete("a1b2c3d4e5f6")
            waitFor("clip in trash") { controller.state.value.mediaTrash.any { it.media.id == audioId + "_a1b2c3d4e5f6" } }
            vm.clear()
            controller.openTrash()
            shoot(controller, "13b-media-trash.png")
            controller.restoreMedia(audioId + "_a1b2c3d4e5f6")
            waitFor("trash empty") { controller.state.value.mediaTrash.isEmpty() }
            assertEquals(listOf("a1b2c3d4e5f6", "f6e5d4c3b2a1"), com.notio.shared.domain.AudioDocument.parse(controller.notesOf(folderId).first { it.id == audioId }.extras)!!.clips.map { it.id })
            controller.back()

            // A photo note (D147, D36): a picture file taken over as JPEG, and a video note with a made-up clip; then one photo to the media trash and back.
            controller.justCreated.value = null
            controller.createNote(folderId, "Фото с прогулки", com.notio.shared.domain.NoteType("PHOTO"), onError = { error("photo: $it") }) {}
            // The note stays in the list, framed (D257); a tap opens it.
            waitFor { controller.justCreated.value != null }
            controller.openNote(controller.justCreated.value!!, edit = false)
            waitFor { controller.state.value.detail?.note?.type?.id == "PHOTO" }
            val photoId = controller.state.value.detail!!.note.id
            controller.addPhotoFromFile(photoId, File(out, "demo-drawing-png.png"))
            controller.addPhotoFromFile(photoId, File(out, "demo-drawing-jpeg.jpg"))
            waitFor { com.notio.shared.domain.MediaDocument.parse(controller.state.value.detail?.note?.extras).items.size == 2 }
            shoot(controller, "14-photo.png")
            val photoItems = com.notio.shared.domain.MediaDocument.parse(controller.state.value.detail!!.note.extras).items
            controller.deleteMediaItems(photoId, com.notio.shared.domain.MediaKind.PHOTO, setOf(photoItems.first().id))
            waitFor { controller.state.value.mediaTrash.any { it.media.kind == "PHOTO" } }
            controller.restoreMedia(photoId + "_" + photoItems.first().id)
            waitFor { controller.state.value.mediaTrash.none { it.media.kind == "PHOTO" } }
            assertEquals(photoItems.map { it.id }, com.notio.shared.domain.MediaDocument.parse(controller.notesOf(folderId).first { it.id == photoId }.extras).items.map { it.id })
            controller.back()
            controller.justCreated.value = null
            controller.createNote(folderId, "Видео", com.notio.shared.domain.NoteType("VIDEO"), onError = { error("video: $it") }) {}
            // The note stays in the list, framed (D257); a tap opens it.
            waitFor { controller.justCreated.value != null }
            controller.openNote(controller.justCreated.value!!, edit = false)
            waitFor { controller.state.value.detail?.note?.type?.id == "VIDEO" }
            val videoId = controller.state.value.detail!!.note.id
            val clip = controller.newMediaTempFile("mp4")
            // The encoder is Windows Media Foundation: on Linux the clip is a file of any bytes (D129).
            if (com.notio.desktop.media.MF.available) com.notio.desktop.media.H264Mp4Writer(clip, 320, 240, fps = 30).use { w ->
                val px = IntArray(320 * 240) { i -> if (i % 320 < 160) 0xFF2E7D32.toInt() else 0xFFFFC107.toInt() }
                for (f in 0 until 30) w.addFrame(px, 320, f * 1000L / 30)
            } else clip.writeBytes(ByteArray(2048))
            controller.addCapturedMedia(videoId, com.notio.shared.domain.MediaKind.VIDEO, CapturedMedia(clip, 320, 240, 1000, "NONE"))
            waitFor { com.notio.shared.domain.MediaDocument.parse(controller.state.value.detail?.note?.extras).items.size == 1 }
            delay(500)
            shoot(controller, "14b-video.png")
            // The media note exports as the phone's: one file as it is, several in a zip.
            val video = controller.notesOf(folderId).first { it.id == videoId }
            assertEquals(listOf("mp4"), controller.exportFormats(video).map { it.extension })
            // The overlays dialog of photos (D148, D40): a time stamp and a text switched on, its preview drawn by the renderer.
            controller.setOverlaySettings(com.notio.shared.domain.MediaKind.PHOTO, com.notio.shared.domain.OverlaySettings(
                stamp = com.notio.shared.domain.StampLayer(enabled = true, sizePercent = 5),
                text = com.notio.shared.domain.TextLayer(enabled = true, text = "Notio · дача", sizePercent = 6, backgroundArgb = 0x99000000.toInt()),
            ))
            shootContent(controller, "14c-overlays.png") {
                OverlayDialog(com.notio.shared.domain.MediaKind.PHOTO, controller.overlaySettings(com.notio.shared.domain.MediaKind.PHOTO), onSave = {}, onDismiss = {})
            }
            controller.back()

            // Backgrounds (D110): a colour for the app, a picture for the folder strip, the page of Settings.
            controller.setBackground(com.notio.shared.domain.BackgroundSlot.APP, com.notio.shared.domain.BackgroundSpec.Color(0xFFFFF3C4.toInt()))
            controller.setBackgroundImage(com.notio.shared.domain.BackgroundSlot.FOLDERS, File(out, "demo-drawing-png.png"))
            waitFor { controller.state.value.display.backgrounds[com.notio.shared.domain.BackgroundSlot.FOLDERS] is com.notio.shared.domain.BackgroundSpec.Image }
            shoot(controller, "12-backgrounds-home.png")
            controller.openCatalog("backgrounds")
            shoot(controller, "12b-backgrounds-page.png")
            controller.setBackground(com.notio.shared.domain.BackgroundSlot.FOLDERS, com.notio.shared.domain.BackgroundSpec.None)
            assertTrue(!controller.backgroundImages.exists(com.notio.shared.domain.BackgroundSlot.FOLDERS))
            // Share (D216, D38): the clipboard gets the note's text (the person's clipboard is put back afterwards).
            val clipboard = java.awt.Toolkit.getDefaultToolkit().systemClipboard
            val before = runCatching { clipboard.getContents(null) }.getOrNull()
            try {
                controller.shareNote("demo-shopping")
                waitFor { runCatching { clipboard.getData(java.awt.datatransfer.DataFlavor.stringFlavor) as String }.getOrNull()?.startsWith("Покупки") == true }
                controller.shareNote(photoId)
                waitFor { runCatching { (clipboard.getData(java.awt.datatransfer.DataFlavor.javaFileListFlavor) as List<*>).size }.getOrNull() == 2 }
            } finally {
                before?.let { runCatching { clipboard.setContents(it, null) } }
            }

            // The app lock (D124, D37): the lock screen with the PIN pad, then its settings page.
            assertEquals(null, controller.appLock.enable(com.notio.shared.lock.LockSetup(com.notio.shared.domain.LockMethod.PIN, "2468")))
            controller.lockGate.start()
            shoot(controller, "15-lock.png")
            controller.lockGate.unlocked()
            controller.openCatalog("lock")
            shoot(controller, "15b-lock-settings.png")
        } finally {
            controller.close()
            home.deleteRecursively()
        }
    }

    private lateinit var current: NotioController

    private suspend fun waitFor(label: String = "", condition: () -> Boolean) = try {
        withTimeout(20_000) { while (!condition()) delay(20) }
    } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
        val st = current.state.value
        error("timed out ($label); screen=${st.screen} notes=${st.notes.map { it.title }} groups=${st.noteGroups} sort=${st.noteSort} featured=${st.folders.map { it.featured }}")
    }

    /** A single composable (a dialog) in the window's theme and texts. */
    private suspend fun shootContent(controller: NotioController, name: String, content: @androidx.compose.runtime.Composable () -> Unit) {
        delay(100)
        val scene = ImageComposeScene(1100, 800, Density(1f)) {
            com.notio.desktop.theme.NotioTheme(controller.state.value.theme) {
                androidx.compose.runtime.CompositionLocalProvider(LocalRes provides controller.res, LocalController provides controller) { content() }
            }
        }
        scene.render(0)
        delay(50)
        scene.render(500_000_000)
        val image = scene.render(1_000_000_000)
        val out = File("build/screenshots/$name").apply { parentFile.mkdirs() }
        out.writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
        scene.close()
    }

    private suspend fun shoot(controller: NotioController, name: String) {
        delay(100)
        val scene = ImageComposeScene(1100, 800, Density(1f)) { NotioApp(controller, WindowChrome.none(), none, SnackbarHostState()) }
        scene.render(0)
        // The settings pages collect their flows in effects: a frame between lets the first values arrive.
        delay(50)
        scene.render(500_000_000)
        delay(50)
        scene.render(1_000_000_000)
        // Rows that came with a load after the first frames fade in (animateItem): a few more frames let them finish.
        delay(100)
        scene.render(1_500_000_000)
        scene.render(2_000_000_000)
        val image = scene.render(3_000_000_000)
        val out = File("build/screenshots/$name").apply { parentFile.mkdirs() }
        out.writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
        scene.close()
        assertTrue(out.length() > 0)
    }
}
