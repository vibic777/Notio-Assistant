package com.notebudget.app.domain.repository

import com.notebudget.app.domain.model.CustomUnit
import kotlinx.coroutines.flow.Flow

/** The units the user added (D92). The built-in units are not stored: they are part of the app. */
interface UnitRepository {
    fun observeCustom(): Flow<List<CustomUnit>>
    suspend fun getCustom(): List<CustomUnit>
    suspend fun setCustom(units: List<CustomUnit>)
}
