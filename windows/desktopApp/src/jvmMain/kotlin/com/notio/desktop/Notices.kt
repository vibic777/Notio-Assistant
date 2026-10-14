package com.notio.desktop

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.notio.common.NoticeTime
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The test tag of the message at the bottom of the window. */
const val NOTICE_TAG = "notice"

/**
 * A message at the bottom of the window (Android D360): it stays for the time of the settings ([NoticeTime.seconds], 10 by default) and
 * goes by itself, a newer message takes its place at once, and with the time set to 0 nothing is shown. The time is the app's own:
 * Material's duration grew with the system's accessibility time, and the message hung.
 */
suspend fun SnackbarHostState.showNotice(message: String, actionLabel: String? = null): SnackbarResult {
    val seconds = NoticeTime.seconds
    if (seconds <= 0) return SnackbarResult.Dismissed
    currentSnackbarData?.dismiss()
    return coroutineScope {
        val timer = launch {
            delay(seconds * 1000L)
            currentSnackbarData?.takeIf { it.visuals.message == message }?.dismiss()
        }
        try {
            showSnackbar(message, actionLabel, duration = SnackbarDuration.Indefinite)
        } finally {
            timer.cancel()
        }
    }
}

/** Where the messages of [showNotice] are drawn: each can be swiped away, to either side (Android D360). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoticeHost(state: SnackbarHostState, modifier: Modifier = Modifier) {
    SnackbarHost(state, modifier) { data ->
        val swipe = rememberSwipeToDismissBoxState()
        LaunchedEffect(swipe.currentValue) { if (swipe.currentValue != SwipeToDismissBoxValue.Settled) data.dismiss() }
        SwipeToDismissBox(state = swipe, backgroundContent = {}, modifier = Modifier.testTag(NOTICE_TAG)) { Snackbar(data) }
    }
}
