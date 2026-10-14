package com.notio.shared

import kotlin.test.Test
import kotlin.test.assertEquals

class AppInfoTest {
    @Test
    fun syncProtocolMatchesAndroid() {
        assertEquals(1, AppInfo.SYNC_PROTOCOL_VERSION)
    }
}
