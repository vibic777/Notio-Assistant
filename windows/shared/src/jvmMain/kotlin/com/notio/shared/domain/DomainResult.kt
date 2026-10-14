package com.notio.shared.domain

/** Business-rule failures. UI maps them to localised messages; no text lives in the domain. */
enum class DomainError {
    BLANK_NAME,
    DUPLICATE_FOLDER_NAME,
    INVALID_COLOR,
    INVALID_STYLE,
    FOLDER_NOT_FOUND,
    NOT_IN_TRASH,
    FOLDER_NOT_EMPTY,
    LAST_FOLDER_CANNOT_BE_DELETED,
    TARGET_FOLDER_REQUIRED,
    BLANK_TITLE,
    NOTE_NOT_FOUND,
    NOT_A_BUDGET_NOTE,
    FEATURED_NOTE_NOT_IN_FOLDER,
    BUDGET_NODE_NOT_FOUND,
    BUDGET_PARENT_IS_LEAF,
    BUDGET_NODE_NOT_LEAF,
    BUDGET_ROOT_IMMUTABLE,
    BUDGET_INVALID_MOVE,
    INVALID_QUANTITY,
    INVALID_AMOUNT,
    CLONE_FAILED,
    INVALID_UNIT_NAME,
    DUPLICATE_UNIT_NAME,
    UNIT_NOT_FOUND,
    UNIT_BUILT_IN,
    UNIT_IN_USE,
    INVALID_CURRENCY_NAME,
    DUPLICATE_CURRENCY_NAME,
    CURRENCY_NOT_FOUND,
    CURRENCY_BUILT_IN,
    CURRENCY_IN_USE,
    INVALID_TAG_NAME,
    DUPLICATE_TAG_NAME,
    TAG_NOT_FOUND,
    REMINDER_NOT_FOUND,
    REMINDER_IN_PAST,
    REMINDER_INVALID_REPEAT,
    REMINDER_INVALID_RECURRENCE,
    REMINDER_INVALID_SOUND,
}

sealed interface DomainResult<out T> {
    data class Success<out T>(val value: T) : DomainResult<T>
    data class Failure(val error: DomainError) : DomainResult<Nothing>
}

fun <T> T.asSuccess(): DomainResult<T> = DomainResult.Success(this)

fun DomainError.asFailure(): DomainResult<Nothing> = DomainResult.Failure(this)

fun <T> DomainResult<T>.getOrNull(): T? = (this as? DomainResult.Success)?.value
