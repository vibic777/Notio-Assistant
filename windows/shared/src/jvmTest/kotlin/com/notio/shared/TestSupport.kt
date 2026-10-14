package com.notio.shared

import com.notio.shared.domain.IdGenerator

/** Ids `id-1`, `id-2` ... as in the Android tests (`Fakes.kt`). */
class SequentialIds : IdGenerator {
    private var next = 1
    override fun newId(): String = "id-${next++}"
}
