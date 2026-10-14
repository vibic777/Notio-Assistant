package com.notio.desktop

import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.swipeRight
import com.notio.common.NoticeTime
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** The message at the bottom of the window (D141, Android D360): it goes by its time, by a swipe, for a newer one; 0 shows none. */
@OptIn(ExperimentalTestApi::class)
class NoticeTest {
    @AfterTest
    fun backToDefault() { NoticeTime.seconds = NoticeTime.DEFAULT }

    @Test
    fun theTimeIsKeptBetweenZeroAndSixty() {
        assertEquals(10, NoticeTime.of(null))
        assertEquals(0, NoticeTime.of(-5))
        assertEquals(60, NoticeTime.of(600))
        NoticeTime.seconds = 99
        assertEquals(60, NoticeTime.seconds)
    }

    @Test
    fun aMessageGoesByItsTimeBySwipeAndForANewerOne() = runComposeUiTest {
        val host = SnackbarHostState()
        lateinit var scope: CoroutineScope
        setContent { scope = rememberCoroutineScope(); NoticeHost(host) }
        fun shown(text: String) = onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

        // By its time: 2 seconds of the test's clock, though Material itself would never take it away.
        NoticeTime.seconds = 2
        var first: SnackbarResult? = null
        scope.launch { first = host.showNotice("Emptied") }
        waitUntil { shown("Emptied") }
        mainClock.advanceTimeBy(1_000)
        assertEquals(true, shown("Emptied"))
        mainClock.advanceTimeBy(1_500)
        waitUntil { !shown("Emptied") }
        assertEquals(SnackbarResult.Dismissed, first)

        // By a swipe, and a newer message takes the place of the one shown.
        NoticeTime.seconds = 60
        scope.launch { host.showNotice("One") }
        waitUntil { shown("One") }
        scope.launch { host.showNotice("Two") }
        waitUntil { shown("Two") && !shown("One") }
        onNodeWithTag(NOTICE_TAG).performTouchInput { swipeRight() }
        waitUntil { !shown("Two") }

        // 0: nothing is shown, and the caller is told so at once.
        NoticeTime.seconds = 0
        var none: SnackbarResult? = null
        scope.launch { none = host.showNotice("Never") }
        waitUntil { none == SnackbarResult.Dismissed }
        assertEquals(false, shown("Never"))
    }
}
