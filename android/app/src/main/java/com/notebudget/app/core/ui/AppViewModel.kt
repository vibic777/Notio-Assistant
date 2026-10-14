package com.notebudget.app.core.ui

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.notebudget.app.NoteBudgetApplication
import com.notebudget.app.R
import com.notebudget.app.core.common.AppContainer
import com.notebudget.app.domain.model.DomainError

/** The manual DI container of the running application. */
@Composable
fun rememberAppContainer(): AppContainer =
    (LocalContext.current.applicationContext as NoteBudgetApplication).container

private class LambdaFactory<VM : ViewModel>(private val create: () -> VM) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = create() as T
}

/** Creates (or restores) a ViewModel whose dependencies come from [AppContainer] (D77). */
@Composable
inline fun <reified VM : ViewModel> appViewModel(noinline create: (AppContainer) -> VM): VM {
    val container = rememberAppContainer()
    return viewModel(factory = lambdaFactory { create(container) })
}

@PublishedApi
internal fun <VM : ViewModel> lambdaFactory(create: () -> VM): ViewModelProvider.Factory = LambdaFactory(create)

/** UI text of a business-rule failure. The domain itself contains no user-facing strings. */
@StringRes
fun DomainError.messageRes(): Int = when (this) {
    DomainError.BLANK_NAME -> R.string.error_blank_name
    DomainError.DUPLICATE_FOLDER_NAME -> R.string.error_duplicate_folder_name
    DomainError.BLANK_TITLE -> R.string.error_blank_title
    DomainError.LAST_FOLDER_CANNOT_BE_DELETED -> R.string.error_last_folder
    DomainError.INVALID_UNIT_NAME -> R.string.error_unit_name
    DomainError.DUPLICATE_UNIT_NAME -> R.string.error_unit_duplicate
    DomainError.UNIT_IN_USE -> R.string.error_unit_in_use
    DomainError.UNIT_BUILT_IN -> R.string.error_unit_built_in
    DomainError.CLONE_FAILED -> R.string.error_clone_failed
    DomainError.INVALID_CURRENCY_NAME -> R.string.error_currency_name
    DomainError.DUPLICATE_CURRENCY_NAME -> R.string.error_currency_duplicate
    DomainError.CURRENCY_IN_USE -> R.string.error_currency_in_use
    DomainError.CURRENCY_BUILT_IN -> R.string.error_currency_built_in
    DomainError.INVALID_TAG_NAME -> R.string.error_tag_name
    DomainError.DUPLICATE_TAG_NAME -> R.string.error_tag_duplicate
    DomainError.REMINDER_IN_PAST -> R.string.reminder_error_past
    DomainError.REMINDER_INVALID_REPEAT -> R.string.reminder_error_repeat
    DomainError.REMINDER_INVALID_RECURRENCE -> R.string.reminder_error_recurrence
    DomainError.REMINDER_INVALID_SOUND -> R.string.reminder_error_sound
    else -> R.string.error_generic
}
