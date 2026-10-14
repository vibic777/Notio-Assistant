package com.notio.desktop

import androidx.compose.runtime.staticCompositionLocalOf
import com.notio.shared.domain.CustomUnit

/** The units the person added (kept in the backup's settings, as on the phone) and the adoption of a list's unknown ones. */
class UnitsSource(val custom: List<CustomUnit>, val adopt: (names: Map<String, String>, reserved: Collection<String>) -> Unit)

val LocalUnits = staticCompositionLocalOf { UnitsSource(emptyList()) { _, _ -> } }
