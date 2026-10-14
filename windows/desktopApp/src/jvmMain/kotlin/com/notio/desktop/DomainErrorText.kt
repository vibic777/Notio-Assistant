package com.notio.desktop

import com.notio.shared.domain.DomainError

/** The Android text of a refused edit (core/ui/AppViewModel.kt), by its resource name. */
fun DomainError.messageRes(): String = when (this) {
    DomainError.BLANK_NAME -> "error_blank_name"
    DomainError.DUPLICATE_FOLDER_NAME -> "error_duplicate_folder_name"
    DomainError.BLANK_TITLE -> "error_blank_title"
    DomainError.LAST_FOLDER_CANNOT_BE_DELETED -> "error_last_folder"
    DomainError.INVALID_UNIT_NAME -> "error_unit_name"
    DomainError.DUPLICATE_UNIT_NAME -> "error_unit_duplicate"
    DomainError.UNIT_IN_USE -> "error_unit_in_use"
    DomainError.UNIT_BUILT_IN -> "error_unit_built_in"
    DomainError.CLONE_FAILED -> "error_clone_failed"
    DomainError.INVALID_CURRENCY_NAME -> "error_currency_name"
    DomainError.DUPLICATE_CURRENCY_NAME -> "error_currency_duplicate"
    DomainError.CURRENCY_IN_USE -> "error_currency_in_use"
    DomainError.CURRENCY_BUILT_IN -> "error_currency_built_in"
    DomainError.INVALID_TAG_NAME -> "error_tag_name"
    DomainError.DUPLICATE_TAG_NAME -> "error_tag_duplicate"
    DomainError.REMINDER_IN_PAST -> "reminder_error_past"
    DomainError.REMINDER_INVALID_REPEAT -> "reminder_error_repeat"
    DomainError.REMINDER_INVALID_RECURRENCE -> "reminder_error_recurrence"
    DomainError.REMINDER_INVALID_SOUND -> "reminder_error_sound"
    else -> "error_generic"
}
