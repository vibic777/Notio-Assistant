package com.notebudget.app.core.ui.tip

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.notebudget.app.core.ui.AppIcons
import com.notio.common.ButtonIcons

/*
 * Every button with words becomes an icon with the words as its hint (D307). The screens import TextButton, OutlinedButton,
 * Button, FilledTonalButton and stringResource from HERE: the text a button asks for inside itself tells the button its key
 * (`action_cancel` …), `ButtonIcons` of the shared code names the icon for that key, and the button draws the icon; its words
 * stay inside it, unseen, so TalkBack and the tests still read them, and come up as the hint on a long press. A key no icon
 * says (or words that are not a resource: a name, "JSON") leaves the button as it was, with its words.
 */

/** What the text inside a button was made from: the resource and the words. */
class ButtonLabel {
    // Compared without reading state, so asking for a text never makes its own scope run again.
    @JvmField var lastId = 0
    @JvmField var lastText: String? = null
    val shown = mutableStateOf<Pair<Int, String>?>(null)
}

internal val LocalButtonLabel = compositionLocalOf<ButtonLabel?> { null }

private fun tellLabel(holder: ButtonLabel?, id: Int, text: String) {
    if (holder == null) return
    // Only noted here: the button takes it up after its content is composed (a state written now, in the middle of the
    // composition that made it, did not make the button look again).
    holder.lastId = id
    holder.lastText = text
}

@Composable
@ReadOnlyComposable
fun stringResource(@StringRes id: Int): String {
    val text = androidx.compose.ui.res.stringResource(id)
    tellLabel(LocalButtonLabel.current, id, text)
    return text
}

@Composable
@ReadOnlyComposable
fun stringResource(@StringRes id: Int, vararg formatArgs: Any): String {
    val text = androidx.compose.ui.res.stringResource(id, *formatArgs)
    tellLabel(LocalButtonLabel.current, id, text)
    return text
}

private enum class ButtonKind { TEXT, OUTLINED, FILLED, TONAL }

@Composable
private fun MaterialButton(
    kind: ButtonKind, onClick: () -> Unit, modifier: Modifier, enabled: Boolean, colors: ButtonColors?, padding: PaddingValues?,
    body: @Composable RowScope.() -> Unit,
) {
    when (kind) {
        ButtonKind.TEXT -> androidx.compose.material3.TextButton(
            onClick = onClick, modifier = modifier, enabled = enabled, colors = colors ?: ButtonDefaults.textButtonColors(),
            contentPadding = padding ?: ButtonDefaults.TextButtonContentPadding, content = body,
        )
        ButtonKind.OUTLINED -> androidx.compose.material3.OutlinedButton(
            onClick = onClick, modifier = modifier, enabled = enabled, colors = colors ?: ButtonDefaults.outlinedButtonColors(),
            contentPadding = padding ?: ButtonDefaults.ContentPadding, content = body,
        )
        ButtonKind.FILLED -> androidx.compose.material3.Button(
            onClick = onClick, modifier = modifier, enabled = enabled, colors = colors ?: ButtonDefaults.buttonColors(),
            contentPadding = padding ?: ButtonDefaults.ContentPadding, content = body,
        )
        ButtonKind.TONAL -> androidx.compose.material3.FilledTonalButton(
            onClick = onClick, modifier = modifier, enabled = enabled, colors = colors ?: ButtonDefaults.filledTonalButtonColors(),
            contentPadding = padding ?: ButtonDefaults.ContentPadding, content = body,
        )
    }
}

@Composable
private fun WordsOrIcon(
    kind: ButtonKind,
    onClick: () -> Unit,
    modifier: Modifier,
    enabled: Boolean,
    colors: ButtonColors?,
    contentPadding: PaddingValues?,
    content: @Composable RowScope.() -> Unit,
) {
    val label = remember { ButtonLabel() }
    val shown = label.shown.value
    // The content has been composed once and has said what it holds: until then the button is laid out but not seen, so the
    // words never flash before their icon.
    val settled = remember { mutableStateOf(false) }
    androidx.compose.runtime.SideEffect {
        val now = label.lastText?.let { label.lastId to it }
        if (label.shown.value != now) label.shown.value = now
        if (!settled.value) settled.value = true
    }
    val resources = LocalContext.current.resources
    val key = remember(shown?.first) { shown?.let { runCatching { resources.getResourceEntryName(it.first) }.getOrNull() } }
    val icon = remember(key) { key?.let { AppIcons.byName(ButtonIcons.nameFor(it)) } }

    // One and the same tree for words and for an icon — only what is seen differs: the content is never thrown away and made
    // again when the button learns its icon (a click that began on the first frame would go nowhere).
    val danger = key != null && ButtonIcons.isDanger(key)
    HintBox(if (icon != null) shown?.second else null, if (settled.value) modifier else modifier.alpha(0f), topic = com.notio.common.HelpMap.topicForKey(key)) {
        MaterialButton(kind, onClick, Modifier, enabled, colors, if (icon != null) PaddingValues(8.dp) else contentPadding) {
            if (icon != null) {
                androidx.compose.material3.Icon(
                    icon, contentDescription = null,
                    tint = if (danger && enabled && kind != ButtonKind.FILLED) MaterialTheme.colorScheme.error else LocalContentColor.current,
                )
            }
            // With an icon the words stay in the button for a screen reader and the tests, without taking room.
            Row(if (icon != null) Modifier.requiredSize(0.dp).alpha(0f) else Modifier) {
                CompositionLocalProvider(LocalButtonLabel provides label) { content() }
            }
        }
    }
}

@Composable
fun TextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: ButtonColors? = null,
    contentPadding: PaddingValues? = null,
    content: @Composable RowScope.() -> Unit,
) = WordsOrIcon(ButtonKind.TEXT, onClick, modifier, enabled, colors, contentPadding, content)

@Composable
fun OutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: ButtonColors? = null,
    contentPadding: PaddingValues? = null,
    content: @Composable RowScope.() -> Unit,
) = WordsOrIcon(ButtonKind.OUTLINED, onClick, modifier, enabled, colors, contentPadding, content)

@Composable
fun Button(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: ButtonColors? = null,
    contentPadding: PaddingValues? = null,
    content: @Composable RowScope.() -> Unit,
) = WordsOrIcon(ButtonKind.FILLED, onClick, modifier, enabled, colors, contentPadding, content)

@Composable
fun FilledTonalButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: ButtonColors? = null,
    contentPadding: PaddingValues? = null,
    content: @Composable RowScope.() -> Unit,
) = WordsOrIcon(ButtonKind.TONAL, onClick, modifier, enabled, colors, contentPadding, content)

/**
 * The round button of a screen's main action with words beside its icon ("Add reminder"): an icon alone now (D307), its words
 * the hint. The words stay inside for a screen reader.
 */
@Composable
fun ExtendedFloatingActionButton(onClick: () -> Unit, modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) =
    IconFabOf(onClick, modifier, null, content)

@Composable
fun ExtendedFloatingActionButton(text: @Composable () -> Unit, icon: @Composable () -> Unit, onClick: () -> Unit, modifier: Modifier = Modifier) =
    IconFabOf(onClick, modifier, icon) { text() }

@Composable
private fun IconFabOf(onClick: () -> Unit, modifier: Modifier, own: (@Composable () -> Unit)?, words: @Composable RowScope.() -> Unit) {
    val label = remember { ButtonLabel() }
    val shown = label.shown.value
    androidx.compose.runtime.SideEffect {
        val now = label.lastText?.let { label.lastId to it }
        if (label.shown.value != now) label.shown.value = now
    }
    val resources = LocalContext.current.resources
    val key = remember(shown?.first) { shown?.let { runCatching { resources.getResourceEntryName(it.first) }.getOrNull() } }
    val icon = remember(key) { key?.let { AppIcons.byName(ButtonIcons.nameFor(it)) } } ?: AppIcons.Add
    HintBox(shown?.second, modifier, topic = com.notio.common.HelpMap.topicForKey(key)) {
        androidx.compose.material3.FloatingActionButton(onClick = onClick) {
            if (own != null) own() else androidx.compose.material3.Icon(icon, contentDescription = null)
            Row(Modifier.requiredSize(0.dp).alpha(0f)) { CompositionLocalProvider(LocalButtonLabel provides label) { words() } }
        }
    }
}
