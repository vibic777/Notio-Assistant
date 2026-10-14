package com.notebudget.app.core.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.notebudget.app.feature.help.HelpScreen
import com.notebudget.app.feature.home.HomeScreen
import com.notebudget.app.feature.settings.SettingsScreen
import com.notebudget.app.feature.backgrounds.BackgroundsScreen
import com.notebudget.app.feature.lock.LockSettingsScreen
import com.notebudget.app.feature.reminders.RemindersScreen
import com.notebudget.app.feature.tags.TagsScreen
import com.notebudget.app.feature.trash.TrashScreen
import com.notebudget.app.feature.units.UnitsScreen
import com.notebudget.app.feature.currencies.CurrenciesScreen
import com.notebudget.app.feature.notes.NoteEditorScreen

@Composable
fun NoteBudgetNavHost(
    navController: NavHostController,
    modifier: Modifier = Modifier,
) {
    // "Always ready for a sync" (D323): a question of another device comes while any screen is open — the Sync page, where it is
    // answered, opens.
    val syncAlways by com.notebudget.app.data.sync.SyncAlways.enabled.collectAsState()
    if (syncAlways) {
        val container = com.notebudget.app.core.ui.rememberAppContainer()
        val prompt by container.syncEngine.prompt.collectAsState()
        androidx.compose.runtime.LaunchedEffect(prompt) {
            if (prompt != null && navController.currentBackStackEntry?.destination?.route?.contains("Settings") != true) {
                navController.navigate(Settings(section = "sync", entry = "sync"))
            }
        }
    }
    NavHost(
        navController = navController,
        startDestination = Home,
        modifier = modifier,
    ) {
        composable<Home> { entry ->
            com.notebudget.app.core.ui.tip.HelpTopic("start") {
            HomeScreen(
                onOpenNote = { navController.from(entry) { navigate(NoteEditor(it)) } },
                onOpenSettings = { navController.from(entry) { navigate(Settings()) } },
                onOpenTrash = { navController.from(entry) { navigate(Trash) } },
                onOpenReminders = { navController.from(entry) { navigate(Reminders(it)) } },
                onOpenCalendar = { navController.from(entry) { navigate(Calendar()) } },
                onOpenSync = { navController.from(entry) { navigate(Settings(section = "sync", entry = "sync")) } },
            )
            }
        }
        composable<Calendar> { entry ->
            val route = entry.toRoute<Calendar>()
            com.notebudget.app.core.ui.tip.HelpTopic("reminders") {
            com.notebudget.app.feature.calendar.CalendarScreen(
                focusAt = route.at,
                focusReminderId = route.reminderId,
                onBack = { navController.from(entry) { popBackStack() } },
                onOpenNote = { navController.from(entry) { navigate(NoteEditor(it)) } },
            )
            }
        }
        composable<Settings> { entry ->
            val route = entry.toRoute<Settings>()
            com.notebudget.app.core.ui.tip.HelpTopic("settings") {
            SettingsScreen(
                initialSection = route.section,
                initialEntry = route.entry,
                onBack = { navController.from(entry) { popBackStack() } },
                onOpenHelp = { navController.from(entry) { navigate(Help) } },
                onOpenUnits = { navController.from(entry) { navigate(Units) } },
                onOpenCurrencies = { navController.from(entry) { navigate(Currencies) } },
                onOpenTrash = { navController.from(entry) { navigate(Trash) } },
                onOpenReminders = { navController.from(entry) { navigate(Reminders(null)) } },
                onOpenTags = { navController.from(entry) { navigate(Tags) } },
                onOpenBackgrounds = { navController.from(entry) { navigate(Backgrounds) } },
                onOpenLock = { navController.from(entry) { navigate(AppLock) } },
            )
            }
        }
        composable<Reminders> { entry ->
            val ofNote = entry.toRoute<Reminders>().noteId
            com.notebudget.app.core.ui.tip.HelpTopic("reminders") {
            RemindersScreen(
                noteId = ofNote,
                onBack = { navController.from(entry) { popBackStack() } },
                // Opened from that very note: back to it, not a second copy of it over this page (D312).
                onOpenNote = { id -> navController.from(entry) { if (id == ofNote) popBackStack() else navigate(NoteEditor(id)) } },
            )
            }
        }
        composable<AppLock> { entry -> com.notebudget.app.core.ui.tip.HelpTopic("lock") { LockSettingsScreen(onBack = { navController.from(entry) { popBackStack() } }) } }
        composable<Backgrounds> { entry -> com.notebudget.app.core.ui.tip.HelpTopic("backgrounds") { BackgroundsScreen(onBack = { navController.from(entry) { popBackStack() } }) } }
        composable<Tags> { entry -> TagsScreen(onBack = { navController.from(entry) { popBackStack() } }) }
        composable<Units> { entry -> com.notebudget.app.core.ui.tip.HelpTopic("lists") { UnitsScreen(onBack = { navController.from(entry) { popBackStack() } }) } }
        composable<Currencies> { entry -> com.notebudget.app.core.ui.tip.HelpTopic("budget") { CurrenciesScreen(onBack = { navController.from(entry) { popBackStack() } }) } }
        composable<Trash> { entry ->
            com.notebudget.app.core.ui.tip.HelpTopic("trash") {
            TrashScreen(
                onBack = { navController.from(entry) { popBackStack() } },
                onOpenSettings = { navController.from(entry) { navigate(Settings(section = "trash", entry = "trash")) } },
            )
            }
        }
        composable<Help> { entry -> HelpScreen(onBack = { navController.from(entry) { popBackStack() } }) }
        composable<NoteEditor> { entry ->
            com.notebudget.app.core.ui.tip.HelpTopic("notes") {
            NoteEditorScreen(
                noteId = entry.toRoute<NoteEditor>().noteId,
                onBack = { navController.from(entry) { popBackStack() } },
                onOpenReminders = { navController.from(entry) { navigate(Reminders(it)) } },
            )
            }
        }
    }
}

/**
 * Runs [action] only while [entry] is still the screen on top. A screen that is leaving stays drawn during its exit animation and
 * still takes taps: a second tap on its back arrow used to pop the screen under it too (Home), leaving an empty grey window; a
 * double tap on a note opened it twice. Also covers a back that comes after an asynchronous save, twice.
 */
private inline fun NavHostController.from(entry: NavBackStackEntry, action: NavHostController.() -> Unit) {
    if (currentBackStackEntry?.id == entry.id) action()
}
