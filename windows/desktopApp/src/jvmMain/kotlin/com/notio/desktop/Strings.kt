package com.notio.desktop

import com.notio.shared.domain.NoteType
import com.notio.shared.transfer.TransferError
import java.util.Locale

/**
 * The texts of the desktop app (Android rule 8: every user-facing string is localizable), in the ten languages of the app
 * (row 19 of the plan): Russian and English as written here; the other languages take the phone's own text when the English words
 * are the phone's (`strings.xml`), else the desktop's `desktop_phrases.json`; a text in neither stays English.
 */
class Strings(private val res: AndroidRes) {
    private val ru = res.language == "ru"

    private fun t(ru: String, en: String): String {
        val key = res.keyOfEnglish(en)
        val text = when {
            this.ru -> ru
            res.language == "en" -> en
            key != null -> res.str(key)
            else -> res.phrase(en) ?: en
        }
        // A button with this text finds its icon by the key (Android D307); the desktop's own texts by their English words.
        res.rememberLabel(text, key ?: en.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_'))
        return text
    }

    /** A text with values: the phone's [key] in the other languages, [fallback] (Russian / English as written here) in these two. */
    private fun k(key: String, vararg args: Any?, fallback: () -> String): String {
        val text = if (ru || res.language == "en" || !res.has(key)) fallback() else res.str(key, *args)
        if (args.isEmpty()) res.rememberLabel(text, key)
        return text
    }

    /** The same for a plural [key]. */
    private fun kp(key: String, count: Int, vararg args: Any?, fallback: () -> String): String =
        if (ru || res.language == "en") fallback() else res.plural(key, count, *args)

    // ---- the texts of the Android app's screens (res/values*/strings.xml), same wording ----
    val appName = "Notio by vibic"
    val newNote = t("Новая заметка", "New note")
    val newFolder = t("Новая папка", "New folder")
    val emptyNotesHint = t("Нажмите «Новая заметка», чтобы добавить.", "Tap “New note” to add one.")
    val settings = t("Настройки", "Settings")
    val reminders = t("Напоминания", "Reminders")
    val more = t("Ещё", "More")
    val back = t("Назад", "Back")
    val view = t("Просмотр", "View")
    val edit = t("Правка", "Edit")
    val zoomNormal = t("Обычный", "Normal")
    val noteMissing = t("Этой заметки больше нет", "This note no longer exists")
    val trashHint = t(
        "Удалённые заметки и папки ждут здесь, пока вы не восстановите их или не удалите навсегда.",
        "Deleted notes and folders wait here until you restore them or delete them for good.",
    )
    val theme = t("Тема", "Theme")
    val colorTheme = t("Цветовая тема", "Colour theme")
    val fontSize = t("Размер шрифта", "Font size")
    val appearance = t("Оформление", "Appearance")
    val backups = t("Бэкапы", "Backups")
    val comingSoon = t("На ПК это появится в следующих версиях", "This comes to the PC in a later version")

    // ---- editing (Android texts) ----
    val folderName = t("Название папки", "Folder name")
    val folderIcon = t("Значок или эмодзи (необязательно)", "Icon or emoji (optional)")
    val folderIconHint = t("Win + . открывает эмодзи. Лучше всего один эмодзи; можно и короткое слово. Пусто: показывается первая буква названия.", "Win + . opens the emoji panel. One emoji is best; a short word works too. Empty: the first letter of the name.")
    val color = t("Цвет", "Colour")
    val style = t("Стиль", "Style")
    val preview = t("Предпросмотр", "Preview")
    val titleHint = t("Заголовок", "Title")
    val pin = t("Закрепить", "Pin")
    val unpin = t("Открепить", "Unpin")
    val boldTitle = t("Жирный заголовок", "Bold title")
    val normalTitle = t("Обычный заголовок", "Normal title")
    val commentAction = t("Комментарий…", "Comment…")
    val moveAction = t("Переместить в папку…", "Move to folder…")
    val delete = t("Удалить", "Delete")
    val undo = t("Отменить", "Undo")
    val editAction = t("Изменить", "Edit")
    val save = t("Сохранить", "Save")
    val create = t("Создать", "Create")
    val movedToTrash = t("Перемещено в корзину. Восстановить можно по значку корзины вверху.", "Moved to the trash. Restore it from the trash icon at the top.")
    val restore = t("Восстановить", "Restore")
    val deleteForever = t("Удалить навсегда", "Delete for good")
    val emptyTrashAction = t("Очистить корзину", "Empty the trash")
    val emptyTrashTitle = t("Очистить корзину?", "Empty the trash?")
    val trashFolderLabel = t("Папка", "Folder")
    val pickFolderTitle = t("В какую папку восстановить?", "Restore into which folder?")
    val pickFolderHint = t("Папка, в которой была заметка, находится в корзине. Выберите папку для неё.", "The note's folder is in the trash. Choose a folder for it.")
    val purgeNoteTitle = t("Удалить эту заметку навсегда?", "Delete this note for good?")
    fun purgeNoteBody(title: String) = k("trash_purge_note_body", title) { t("«$title» будет удалена навсегда вместе с напоминаниями и файлами. Это нельзя отменить.", "“$title” will be deleted for good with its reminders and files. This cannot be undone.") }
    val purgeFolderTitle = t("Удалить эту папку навсегда?", "Delete this folder for good?")
    fun purgeFolderBody(name: String, notes: Int) = kp("trash_purge_folder_body", notes, name, notes) { t("Папка «$name» и заметки в ней (${notesCount(notes)}) будут удалены навсегда вместе с напоминаниями и файлами. Это нельзя отменить.", "Folder “$name” and its notes (${notesCount(notes)}) will be deleted for good with their reminders and files. This cannot be undone.") }
    fun emptyTrashBody(n: Int) = kp("trash_empty_confirm_body", n, n) { t("Элементов корзины: $n. Они будут удалены навсегда вместе с напоминаниями и файлами. Это нельзя отменить.", "$n items of the trash will be deleted for good with their reminders and files. This cannot be undone.") }
    fun deleteFolderTitle(name: String) = k("delete_folder_title", name) { t("Удалить папку «$name»?", "Delete folder “$name”?") }
    val deleteFolderEmpty = t("Папка пуста и будет перемещена в корзину. Её можно восстановить из корзины (значок корзины вверху главного экрана).", "The folder is empty and goes to the trash. It can be restored from the trash (the trash icon at the top).")
    val deleteFolderNotes = t("Выберите, что сделать с заметками этой папки. Их можно восстановить из корзины (значок корзины вверху главного экрана).", "Choose what to do with the notes of this folder. They can be restored from the trash (the trash icon at the top).")
    val deleteFolderMove = t("Переместить заметки в другую папку", "Move the notes to another folder")
    val deleteFolderTrash = t("Переместить заметки в корзину вместе с папкой", "Move the notes to the trash with the folder")
    val deleteFolderTarget = t("Переместить заметки в:", "Move the notes to:")
    val deleteNoteTitle = t("Удалить заметку?", "Delete the note?")
    fun deleteNoteBody(title: String) = k("delete_note_message", title) { t("«$title» будет перемещена в корзину. Действие можно отменить сразу после удаления или позже восстановить заметку из корзины.", "“$title” goes to the trash. Undo it right away or restore the note from the trash later.") }
    val moveNoteTitle = t("Переместить заметку в папку", "Move the note to a folder")
    val moveNoteHint = t("Если заметка выбрана главной в своей папке, эта связь будет снята.", "If the note is the featured one of its folder, that link is removed.")
    val noteColorTitle = t("Цвет заметки", "Note colour")
    val noteColorNone = t("Цвет папки", "Folder colour")
    val commentNoteTitle = t("Комментарий к заметке", "Comment on the note")
    val commentFolderTitle = t("Комментарий к папке", "Comment on the folder")
    val commentHint = t("На карточке показываются первые 16 символов первой строки. Пусто — комментарий удаляется.", "The card shows the first 16 characters of the first line. Empty deletes the comment.")
    val newNoteKind = t("Вид заметки", "Kind of note")
    val kindLater = t("на ПК позже", "on the PC later")
    val restored = t("Восстановлено", "Restored")

    fun styleName(id: String) = when (id) {
        "MINIMAL" -> t("Минимализм", "Minimal")
        "GRADIENT" -> t("Градиент", "Gradient")
        "PATTERN" -> t("Узор", "Pattern")
        "OUTLINE" -> t("Контур", "Outline")
        "COMPACT" -> t("Компактный", "Compact")
        else -> t("Мягкий", "Soft")
    }

    fun editError(e: com.notio.shared.data.EditError) = when (e) {
        com.notio.shared.data.EditError.BLANK_NAME, com.notio.shared.data.EditError.BLANK_TITLE -> t("Введите название", "Enter a name")
        com.notio.shared.data.EditError.DUPLICATE_FOLDER_NAME -> t("Папка с таким названием уже есть", "A folder with this name exists")
        com.notio.shared.data.EditError.LAST_FOLDER_CANNOT_BE_DELETED -> t("Последнюю папку удалить нельзя", "The last folder cannot be deleted")
        com.notio.shared.data.EditError.TARGET_FOLDER_REQUIRED -> t("Выберите папку для заметок", "Choose a folder for the notes")
        com.notio.shared.data.EditError.FOLDER_NOT_FOUND -> t("Папка не найдена", "The folder was not found")
        com.notio.shared.data.EditError.NOTE_NOT_FOUND -> t("Этой заметки больше нет", "This note no longer exists")
        else -> t("Что-то пошло не так", "Something went wrong")
    }

    // ---- budget (Android res budget_*) ----
    val budgetTotal = t("Итого", "Total")
    fun budgetGroupTotal(name: String) = k("budget_group_total", name) { t("Итого: $name", "Total: $name") }
    fun budgetSumGoal(sum: String, goal: String) = k("budget_sum_goal", sum, goal) { t("Сумма $sum / цель $goal", "Sum $sum / goal $goal") }
    fun budgetRemainder(v: String) = k("budget_remainder", v) { t("Остаток $v", "Remainder $v") }
    fun budgetEach(sum: String, goal: String) = k("budget_each", sum, goal) { t("Каждая: сумма $sum / цель $goal", "Each: sum $sum / goal $goal") }
    val budgetAddItem = t("Добавить статью", "Add an item")
    val budgetAddGroup = t("Добавить группу", "Add a group")
    val budgetNodeTitle = t("Название", "Name")
    val budgetQuantity = t("Количество", "Quantity")
    val budgetQuantityHint = t("Сколько раз статья повторяется. Сумма и цель выше — за одну.", "How many times the item repeats. The sum and goal above are for one.")
    val budgetInvalidQuantity = t("Введите целое число от 1 до 9999", "Enter a whole number from 1 to 9999")
    val budgetInvalidNumber = t("Введите число, не более двух знаков после запятой", "Enter a number with at most two decimals")
    val budgetDeleteTitle = t("Удалить?", "Delete?")
    fun budgetDeleteMessage(name: String) = k("budget_delete_message", name) { t("«$name» и всё внутри будет удалено.", "“$name” and everything inside it will be deleted.") }
    val budgetEmptyHint = t("Откройте меню ⋮ у «Итого», чтобы добавить первую статью или группу.", "Open the ⋮ menu of “Total” to add the first item or group.")
    val budgetLayoutButton = t("Настройки вида", "Display options")
    val budgetLayoutTitle = t("Как показывать этот бюджет", "How to show this budget")
    val budgetTotalBottom = t("Общий итог внизу", "Overall total at the bottom")
    val budgetGroupTotalsBottom = t("Итоги групп под их статьями", "Group totals below their items")
    val budgetNewestFirst = t("Сначала новые статьи", "Newest items first")
    val budgetShowComments = t("Показывать комментарии (первые 16 символов)", "Show comments (first 16 characters)")
    val budgetGroup = t("Группа", "Group")
    val budgetAdjust = t("Изменить сумму", "Change the sum")
    fun budgetAdjustCurrentBoth(sum: String, goal: String) = k("budget_adjust_current_both", sum, goal) { t("Сейчас: сумма $sum, цель $goal", "Now: sum $sum, goal $goal") }
    val adjustAdd = t("Прибавить", "Add")
    val adjustSubtract = t("Вычесть", "Subtract")
    val adjustSet = t("Ввести", "Enter")
    val adjustAmount = t("Сумма", "Amount")
    val adjustNewValue = t("Новое значение", "New value")
    val adjustInvalid = t("Введите сумму больше нуля, не более двух знаков после запятой", "Enter an amount above zero with at most two decimals")
    fun adjustResult(v: String) = k("budget_adjust_result", v) { t("Станет: $v", "Will be: $v") }
    fun adjustPerPiece(n: Int) = k("budget_adjust_per_piece", n) { t("Статья повторяется $n раз: изменение относится к сумме одной штуки.", "The item repeats $n times: the change is for one piece.") }
    val budgetLog = t("Журнал операций", "Log")
    val budgetLogEmpty = t("Пока ничего не менялось. Здесь будут записываться добавление статей и изменение их значений.", "Nothing has changed yet. Added items and changed values will be written here.")
    val budgetLogSaveTxt = t("Сохранить в файл TXT", "Save as a TXT file")
    val budgetLogClear = t("Очистить журнал", "Clear the log")
    val budgetLogClearTitle = t("Очистить журнал?", "Clear the log?")
    val budgetLogClearMessage = t("Все записи журнала этого бюджета будут удалены. Сам бюджет не изменится. Отменить нельзя.", "Every line of this budget's log will be deleted. The budget itself stays. This cannot be undone.")
    fun budgetLogExportTitle(name: String) = k("budget_log_export_title", name) { t("Журнал операций бюджета «$name»", "Log of the budget “$name”") }
    val budgetLogNotSet = t("не задано", "not set")
    fun budgetCurrencyButton(label: String?) = k("budget_currency_button", label ?: res.str("budget_currency_none")) { t("Валюта: ${label ?: "не выбрана"}", "Currency: ${label ?: "none"}") }
    val budgetCurrencyTitle = t("Валюта этого бюджета", "Currency of this budget")
    val budgetCurrencyHint = t("Одна валюта на весь бюджет: она подписывает все суммы.", "One currency for the whole budget: it labels every sum.")
    val budgetCurrencyNone = t("не выбрана", "none")
    val budgetCollapseAll = t("Свернуть все группы", "Collapse all groups")
    val budgetExpandAll = t("Развернуть все группы", "Expand all groups")
    val undoAction = t("Отменить", "Undo")
    val redoAction = t("Повторить", "Redo")
    val sortTitle = t("Сортировка", "Sort")
    val sortAscending = t("По возрастанию", "Ascending")
    val sortDescending = t("По убыванию", "Descending")

    fun budgetSortKey(k: com.notio.shared.domain.BudgetSortKey) = when (k) {
        com.notio.shared.domain.BudgetSortKey.MANUAL -> t("Мой порядок", "My order")
        com.notio.shared.domain.BudgetSortKey.NAME -> t("Название", "Name")
        com.notio.shared.domain.BudgetSortKey.CREATED -> t("Создано", "Created")
        com.notio.shared.domain.BudgetSortKey.CHANGED -> t("Изменено", "Changed")
        com.notio.shared.domain.BudgetSortKey.SUM -> t("Сумма", "Sum")
        com.notio.shared.domain.BudgetSortKey.GOAL -> t("Цель", "Goal")
        com.notio.shared.domain.BudgetSortKey.REMAINDER -> t("Остаток", "Remainder")
        com.notio.shared.domain.BudgetSortKey.PROGRESS -> t("Выполнение (доля цели)", "Progress (share of the goal)")
    }

    /** A line of the budget log in words (Android BudgetLogText). */
    fun budgetLogLine(e: com.notio.shared.transfer.BudgetLogRecord, money: (Long) -> String, goalMoney: (Long) -> String = money): String {
        fun v(x: Long?) = when {
            x == null -> budgetLogNotSet
            e.kind == com.notio.shared.domain.BudgetLogKind.QUANTITY -> x.toString()
            e.kind == com.notio.shared.domain.BudgetLogKind.GOAL -> goalMoney(x)
            else -> money(x)
        }
        fun g(name: String) = name.ifEmpty { budgetTotal }
        val K = com.notio.shared.domain.BudgetLogKind
        return when (e.kind) {
            K.ITEM_ADDED -> k("budget_log_item_added", e.title, g(e.detail.orEmpty())) { t("Добавлена статья «${e.title}» в «${g(e.detail.orEmpty())}»", "Item “${e.title}” added to “${g(e.detail.orEmpty())}”") }
            K.GROUP_ADDED -> k("budget_log_group_added", e.title, g(e.detail.orEmpty())) { t("Добавлена группа «${e.title}» в «${g(e.detail.orEmpty())}»", "Group “${e.title}” added to “${g(e.detail.orEmpty())}”") }
            K.SUM -> when (e.detail) {
                "SET" -> k("budget_log_sum_set", e.title, v(e.oldValue), v(e.newValue)) { t("«${e.title}»: введена сумма ${v(e.newValue)} (было ${v(e.oldValue)})", "“${e.title}”: sum entered ${v(e.newValue)} (was ${v(e.oldValue)})") }
                "ADD", "SUBTRACT" -> {
                    val delta = (e.newValue ?: 0) - (e.oldValue ?: 0)
                    val d = money(delta).let { if (it == com.notio.shared.domain.BudgetPrivacy.MASK || delta < 0) it else "+$it" }
                    k("budget_log_sum_delta", e.title, v(e.oldValue), v(e.newValue), d) { t("«${e.title}»: сумма ${v(e.oldValue)} → ${v(e.newValue)} ($d)", "“${e.title}”: sum ${v(e.oldValue)} → ${v(e.newValue)} ($d)") }
                }
                else -> k("budget_log_sum", e.title, v(e.oldValue), v(e.newValue)) { t("«${e.title}»: сумма ${v(e.oldValue)} → ${v(e.newValue)}", "“${e.title}”: sum ${v(e.oldValue)} → ${v(e.newValue)}") }
            }
            K.GOAL -> k("budget_log_goal", e.title, v(e.oldValue), v(e.newValue)) { t("«${e.title}»: цель ${v(e.oldValue)} → ${v(e.newValue)}", "“${e.title}”: goal ${v(e.oldValue)} → ${v(e.newValue)}") }
            K.QUANTITY -> k("budget_log_quantity", e.title, v(e.oldValue), v(e.newValue)) { t("«${e.title}»: количество ${v(e.oldValue)} → ${v(e.newValue)}", "“${e.title}”: quantity ${v(e.oldValue)} → ${v(e.newValue)}") }
            K.RENAMED -> k("budget_log_renamed", e.title, e.detail.orEmpty()) { t("Переименовано: «${e.detail.orEmpty()}» → «${e.title}»", "Renamed: “${e.detail.orEmpty()}” → “${e.title}”") }
            K.MOVED -> {
                val parts = e.detail.orEmpty().split('\n')
                k("budget_log_moved", e.title, g(parts.getOrNull(0).orEmpty()), g(parts.getOrNull(1).orEmpty())) { t("«${e.title}» перенесена из «${g(parts.getOrNull(0).orEmpty())}» в «${g(parts.getOrNull(1).orEmpty())}»", "“${e.title}” moved from “${g(parts.getOrNull(0).orEmpty())}” to “${g(parts.getOrNull(1).orEmpty())}”") }
            }
            K.DELETED -> if (e.detail == K.TREE) k("budget_log_deleted_tree", e.title) { t("Удалена группа «${e.title}» вместе со всем внутри", "Group “${e.title}” deleted with everything inside") }
            else k("budget_log_deleted", e.title) { t("Удалено: «${e.title}»", "Deleted: “${e.title}”") }
            K.ITEM_MERGED -> k("budget_log_item_merged", e.title, e.detail.orEmpty()) { t("Статья «${e.title}» слита со статьёй «${e.detail.orEmpty()}»", "Item “${e.title}” merged into “${e.detail.orEmpty()}”") }
            K.UNDONE -> t("Последнее изменение отменено", "The last change was undone")
            K.REDONE -> t("Отменённое изменение повторено", "The undone change was redone")
            else -> e.title
        }
    }

    fun domainError(e: com.notio.shared.domain.DomainError) = when (e) {
        com.notio.shared.domain.DomainError.BLANK_TITLE, com.notio.shared.domain.DomainError.BLANK_NAME -> t("Введите название", "Enter a name")
        com.notio.shared.domain.DomainError.INVALID_QUANTITY -> budgetInvalidQuantity
        com.notio.shared.domain.DomainError.INVALID_AMOUNT -> budgetInvalidNumber
        com.notio.shared.domain.DomainError.BUDGET_PARENT_IS_LEAF -> t("В статью нельзя добавить другую статью", "An item cannot hold other items")
        com.notio.shared.domain.DomainError.BUDGET_INVALID_MOVE -> t("Так переместить нельзя", "It cannot be moved there")
        else -> t("Что-то пошло не так", "Something went wrong")
    }

    // ---- formatted note (Android md_*, rich_*) ----
    val previewEmpty = t("Пока нечего показывать.", "Nothing to show yet.")
    val textHint = t("Начните писать…", "Start writing…")
    val mdBold = t("Жирный", "Bold")
    val mdItalic = t("Курсив", "Italic")
    val mdStrike = t("Зачёркнутый", "Strikethrough")
    val mdBullets = t("Маркированный список", "Bulleted list")
    val mdNumbered = t("Нумерованный список", "Numbered list")
    val mdChecklist = t("Чек-лист", "Checklist")
    val richUnderline = t("Подчёркнутый", "Underline")
    val richSuperscript = t("Верхний индекс", "Superscript")
    val richSubscript = t("Нижний индекс", "Subscript")
    val richSize = t("Размер текста", "Text size")
    val richTextColor = t("Цвет текста", "Text colour")
    val richBackground = t("Цвет фона", "Background colour")
    val richColorAuto = t("Автоматически", "Automatic")
    val richIndentLess = t("Уменьшить отступ", "Decrease indent")
    val richIndentMore = t("Увеличить отступ", "Increase indent")
    val richAlignLeft = t("По левому краю", "Align left")
    val richAlignCenter = t("По центру", "Centre")
    val richAlignRight = t("По правому краю", "Align right")
    val richClear = t("Убрать оформление", "Clear formatting")

    val trayStillRunning = t("Notio работает в трее, чтобы звенели напоминания. Выход — правой кнопкой по значку.", "Notio keeps running in the tray so reminders ring. Right-click the icon to exit.")
    val keepInTray = t("Работать в трее, пока есть напоминания", "Keep running in the tray while reminders are set")
    val keepInTrayHint = t("Закрытое окно уходит в трей, и напоминания звенят вовремя. Выключено — закрытие окна завершает Notio.", "The closed window goes to the tray and reminders ring on time. Off: closing the window ends Notio.")

    fun notesCount(n: Int): String = if (ru) {
        val m10 = n % 10
        val m100 = n % 100
        when {
            m10 == 1 && m100 != 11 -> "$n заметка"
            m10 in 2..4 && m100 !in 12..14 -> "$n заметки"
            else -> "$n заметок"
        }
    } else if (res.language != "en") res.plural("notes_count", n, n) else if (n == 1) "1 note" else "$n notes"

    fun themeMode(m: com.notio.shared.domain.ThemeMode) = when (m) {
        com.notio.shared.domain.ThemeMode.SYSTEM -> t("Как в системе", "System")
        com.notio.shared.domain.ThemeMode.LIGHT -> t("Светлая", "Light")
        com.notio.shared.domain.ThemeMode.DARK -> t("Тёмная", "Dark")
    }

    fun colorThemeName(c: com.notio.shared.domain.ColorTheme) = when (c) {
        com.notio.shared.domain.ColorTheme.GREEN -> t("Зелёная", "Green")
        com.notio.shared.domain.ColorTheme.PINK -> t("Розовая", "Pink")
        com.notio.shared.domain.ColorTheme.ORANGE -> t("Оранжевая", "Orange")
        com.notio.shared.domain.ColorTheme.RED -> t("Красная", "Red")
        com.notio.shared.domain.ColorTheme.LIGHT_BLUE -> t("Голубая", "Light blue")
        com.notio.shared.domain.ColorTheme.BLUE -> t("Синяя", "Blue")
        com.notio.shared.domain.ColorTheme.LIME -> t("Салатовая", "Lime")
        com.notio.shared.domain.ColorTheme.BLACK -> t("Чёрная", "Black")
        com.notio.shared.domain.ColorTheme.GRAY -> t("Серая", "Grey")
        com.notio.shared.domain.ColorTheme.WHITE -> t("Белая", "White")
        com.notio.shared.domain.ColorTheme.YELLOW -> t("Жёлтая", "Yellow")
        com.notio.shared.domain.ColorTheme.LILAC -> t("Сиреневая", "Lilac")
        com.notio.shared.domain.ColorTheme.MAGENTA -> t("Пурпурная", "Magenta")
        com.notio.shared.domain.ColorTheme.CUSTOM -> t("Свой цвет", "Your colour")
    }

    fun fontSizeName(f: com.notio.shared.domain.FontSize) = when (f) {
        com.notio.shared.domain.FontSize.SMALL -> t("Мелкий", "Small")
        com.notio.shared.domain.FontSize.NORMAL -> t("Обычный", "Normal")
        com.notio.shared.domain.FontSize.MEDIUM -> t("Средний", "Medium")
        com.notio.shared.domain.FontSize.LARGE -> t("Большой", "Large")
        com.notio.shared.domain.FontSize.EXTRA_LARGE -> t("Крупный", "Extra large")
    }

    val file = t("Файл", "File")
    val importBackup = t("Импорт бэкапа…", "Import backup…")
    val exportBackup = t("Экспорт бэкапа…", "Export backup…")
    val openDataFolder = t("Открыть папку данных", "Open data folder")
    val exit = t("Выход", "Exit")
    val help = t("Справка", "Help")
    val about = t("О программе", "About")
    val developer = t("Разработчик: vibic", "Developer: vibic")
    val aboutText = t(
        "Версия для Windows. Сейчас: папки и заметки (Markdown и списки редактируются), корзина, темы, импорт и экспорт бэкапов в формате Android-приложения.",
        "Windows version. So far: folders and notes (Markdown and lists can be edited), the trash, themes, import and export of backups in the Android app's format.",
    )

    val folders = t("Папки", "Folders")
    val allNotes = t("Все заметки", "All notes")
    val trash = t("Корзина", "Trash")
    val search = t("Поиск", "Search")
    val sharedToClipboard = t("Скопировано: вставьте в письмо, мессенджер или документ (Ctrl+V).", "Copied: paste it into a mail, a messenger or a document (Ctrl+V).")
    val noFolders = t("Папок пока нет. Создайте папку или импортируйте бэкап с телефона (меню ⋮).", "No folders yet. Create a folder or import a backup from the phone (menu ⋮).")
    val noNotes = t("В этой папке пока нет заметок", "No notes in this folder yet")
    val nothingFound = t("Ничего не найдено", "Nothing found")
    val emptyTrash = t("Корзина пуста", "The trash is empty")
    val chooseNote = t("Выберите заметку", "Choose a note")
    val created = t("Создана", "Created")
    val changed = t("Изменена", "Changed")
    val tags = t("Теги", "Tags")
    val comment = t("Комментарий", "Comment")
    val reminder = t("Напоминание", "Reminder")
    val inTrashSince = t("В корзине с", "In the trash since")
    val pinned = t("Закреплена", "Pinned")
    val empty = t("Пусто", "Empty")
    val sum = t("Сумма", "Sum")
    val goal = t("Цель", "Goal")
    val remainder = t("Остаток", "Remainder")
    val files = t("Файлы", "Files")
    val open = t("Открыть", "Open")
    val noFiles = t("Файлов нет", "No files")
    val notYet = t("Этот вид заметки на ПК пока показывается только текстом.", "This kind of note is shown as text only on the PC so far.")

    val importTitle = t("Импорт бэкапа", "Import backup")
    val mode = t("Что сделать с данными на этом ПК", "What to do with the data on this PC")
    val modeMerge = t("Объединить — новые изменения побеждают", "Merge — newer changes win")
    val modeNewIds = t("Добавить как копии", "Add as copies")
    val modeReplace = t("Заменить всё — данные на ПК будут удалены", "Replace everything — the data on this PC is deleted")
    val replaceWarning = t(
        "Все папки, заметки, бюджеты, напоминания и файлы на этом ПК будут удалены и заменены содержимым бэкапа. Отменить это нельзя.",
        "Every folder, note, budget, reminder and file on this PC will be deleted and replaced by the backup. This cannot be undone.",
    )
    val replaceConfirm = t("Удалить и заменить", "Delete and replace")
    val password = t("Пароль бэкапа", "Backup password")
    val passwordHint = t("Файл защищён паролем", "The file is protected by a password")
    val exportPassword = t("Пароль (необязательно — пусто = без шифрования)", "Password (optional — empty = not encrypted)")
    val show = t("Показать", "Show")
    val hide = t("Скрыть", "Hide")
    val ok = t("ОК", "OK")
    val cancel = t("Отмена", "Cancel")
    val export = t("Экспорт", "Export")
    val importAction = t("Импорт", "Import")
    val working = t("Подождите…", "Please wait…")
    val done = t("Готово", "Done")
    val error = t("Ошибка", "Error")

    fun importDone(folders: Int, notes: Int, updated: Int, skipped: Int, files: Int, filesSkipped: Int) = t(
        "Папок добавлено: $folders\nЗаметок добавлено: $notes, обновлено: $updated, пропущено: $skipped\nФайлов восстановлено: $files" +
            if (filesSkipped > 0) ", не удалось: $filesSkipped" else "",
        "Folders added: $folders\nNotes added: $notes, updated: $updated, skipped: $skipped\nFiles restored: $files" +
            if (filesSkipped > 0) ", failed: $filesSkipped" else "",
    )

    fun exportDone(path: String, files: Int, skipped: Int) = t(
        "Сохранено: $path\nФайлов в бэкапе: $files" + if (skipped > 0) ", не вошло (слишком большие): $skipped" else "",
        "Saved: $path\nFiles in the backup: $files" + if (skipped > 0) ", left out (too big): $skipped" else "",
    )

    fun transferError(e: TransferError) = when (e) {
        TransferError.NOT_A_BACKUP -> t("Это не бэкап Notio.", "This is not a Notio backup.")
        TransferError.UNSUPPORTED_VERSION -> t("Бэкап сделан более новой версией Notio. Обновите приложение.", "The backup was made by a newer Notio. Update the app.")
        TransferError.CORRUPT -> t("Файл повреждён.", "The file is damaged.")
        TransferError.NO_FOLDERS -> t("В бэкапе нет ни одной папки — замена отменена.", "The backup has no folder — the replace was cancelled.")
        TransferError.TOO_LARGE -> t("Бэкап слишком велик для памяти этого компьютера. Ничего не изменено.", "The backup is too large to open with this computer's memory. Nothing was changed.")
        TransferError.UNRECOGNIZED_FILE -> t("Неизвестный тип файла.", "Unknown kind of file.")
        TransferError.FOLDER_NOT_FOUND -> t("Папка не найдена.", "The folder was not found.")
        TransferError.PASSWORD_REQUIRED -> t("Нужен пароль.", "A password is needed.")
        TransferError.WRONG_PASSWORD -> t("Неверный пароль.", "Wrong password.")
    }

    fun kind(type: NoteType): String = when (type.id) {
        "TEXT" -> t("Заметка MD", "Markdown")
        "WORD" -> t("Заметка", "Note")
        "LIST" -> t("Список", "List")
        "BUDGET" -> t("Бюджет", "Budget")
        "DRAWING" -> t("Рисунок", "Drawing")
        "BLUEPRINT" -> t("Чертёж", "Blueprint")
        "AUDIO" -> t("Аудио", "Audio")
        "PHOTO" -> t("Фото", "Photo")
        "VIDEO" -> t("Видео с камеры", "Camera video")
        "SCREEN" -> t("Запись экрана", "Screen recording")
        "SCHEDULE" -> t("Расписание", "Schedule")
        "TASK" -> t("Задача", "Task")
        else -> type.id
    }

    companion object {
        fun forSystem(): Strings = Strings(AndroidRes.forLanguage(null))
    }
}
