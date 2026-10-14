package com.notebudget.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.notebudget.app.core.ui.tip.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.navigation.compose.rememberNavController
import com.notebudget.app.core.ui.navigation.NoteBudgetNavHost
import com.notebudget.app.core.ui.rememberAppContainer
import com.notebudget.app.domain.model.Folder
import com.notebudget.app.feature.onboarding.OnboardingScreen

/**
 * Root: waits for the first folder list, then shows onboarding while no folder exists (D03,
 * INV-2 needs at least one) and the main navigation afterwards. Creating the first folder
 * makes the list non-empty, which switches the screen without any explicit navigation.
 */
@Composable
fun NoteBudgetApp() {
    val container = rememberAppContainer()
    val folders by produceState<List<Folder>?>(initialValue = null, container) {
        container.folderRepository.observeAll().collect { value = it }
    }

    when {
        folders == null -> {
            val loading = stringResource(R.string.loading)
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.semantics { contentDescription = loading })
            }
        }

        folders.isNullOrEmpty() -> OnboardingScreen()

        else -> {
            val navController = rememberNavController()
            NoteBudgetNavHost(navController = navController)
            // A tap on a reminder notification opens that note (D126); a note that is gone (deleted, in the trash) is skipped.
            val request by container.openNote.noteId.collectAsState()
            LaunchedEffect(request) {
                val id = request ?: return@LaunchedEffect
                if (container.noteRepository.getById(id) != null) {
                    // Navigation must be driven from the main thread, whichever thread the database answered on.
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
                        navController.navigate(com.notebudget.app.core.ui.navigation.NoteEditor(id)) { launchSingleTop = true }
                    }
                }
                container.openNote.consumed()
            }
            // A tap on a reminder notification opens the calendar on that day with the reminder marked (D267).
            val calendarRequest by container.openNote.calendar.collectAsState()
            LaunchedEffect(calendarRequest) {
                val target = calendarRequest ?: return@LaunchedEffect
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
                    navController.navigate(com.notebudget.app.core.ui.navigation.Calendar(target.at, target.reminderId))
                }
                container.openNote.calendarShown()
            }
        }
    }
}
