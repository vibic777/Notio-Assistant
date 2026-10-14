package com.notio.desktop

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import com.notio.common.ButtonIcons

/*
 * Every button with words becomes an icon with the words as its hint (Android D307). TextButton, OutlinedButton, Button,
 * FilledTonalButton and Text of this package stand in for the Material ones in every file of the app: a Text inside a button tells
 * the button its words, the resources tell which key those words are (`action_cancel`; for the desktop's own texts their English
 * words), `ButtonIcons` of the shared code names the icon for the key, and the button draws the icon. Its words stay inside it,
 * unseen, for a screen reader and the tests, and come up as the hint under the mouse or a held finger. Words no icon says (a name,
 * "JSON", a sentence) leave the button as it was.
 */

/** What the text inside a button says. */
class ButtonLabel {
    // Noted while the content is composed; the button takes it up after (see WordsOrIcon).
    @JvmField var lastText: String? = null
    val shown = mutableStateOf<String?>(null)
}

internal val LocalButtonLabel = compositionLocalOf<ButtonLabel?> { null }

@Composable
fun Text(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontStyle: FontStyle? = null,
    fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    textDecoration: TextDecoration? = null,
    textAlign: TextAlign? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
    onTextLayout: ((TextLayoutResult) -> Unit)? = null,
    style: TextStyle = LocalTextStyle.current,
) {
    LocalButtonLabel.current?.lastText = text
    androidx.compose.material3.Text(
        text = text, modifier = modifier, color = color, fontSize = fontSize, fontStyle = fontStyle, fontWeight = fontWeight, fontFamily = fontFamily,
        letterSpacing = letterSpacing, textDecoration = textDecoration, textAlign = textAlign, lineHeight = lineHeight, overflow = overflow,
        softWrap = softWrap, maxLines = maxLines, minLines = minLines, onTextLayout = onTextLayout, style = style,
    )
}

@Composable
fun Text(
    text: AnnotatedString,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontStyle: FontStyle? = null,
    fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    textDecoration: TextDecoration? = null,
    textAlign: TextAlign? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
    inlineContent: Map<String, InlineTextContent> = mapOf(),
    onTextLayout: (TextLayoutResult) -> Unit = {},
    style: TextStyle = LocalTextStyle.current,
) {
    androidx.compose.material3.Text(
        text = text, modifier = modifier, color = color, fontSize = fontSize, fontStyle = fontStyle, fontWeight = fontWeight, fontFamily = fontFamily,
        letterSpacing = letterSpacing, textDecoration = textDecoration, textAlign = textAlign, lineHeight = lineHeight, overflow = overflow,
        softWrap = softWrap, maxLines = maxLines, minLines = minLines, inlineContent = inlineContent, onTextLayout = onTextLayout, style = style,
    )
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
    SideEffect {
        if (label.shown.value != label.lastText) label.shown.value = label.lastText
        if (!settled.value) settled.value = true
    }
    val res = LocalRes.current
    val key = remember(shown, res) { shown?.let(res::keyOfText) }
    val icon = remember(key) { key?.let { AppIcons.byName(ButtonIcons.nameFor(it)) } }
    val danger = key != null && ButtonIcons.isDanger(key)
    // One and the same tree for words and for an icon — only what is seen differs: the content is never thrown away and made
    // again when the button learns its icon.
    HoverTip(if (icon != null) shown else null, if (settled.value) modifier else modifier.alpha(0f), topic = com.notio.common.HelpMap.topicForKey(key)) {
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
    SideEffect { if (label.shown.value != label.lastText) label.shown.value = label.lastText }
    val res = LocalRes.current
    val key = remember(shown, res) { shown?.let(res::keyOfText) }
    val icon = remember(key) { key?.let { AppIcons.byName(ButtonIcons.nameFor(it)) } } ?: AppIcons.Add
    HoverTip(shown, modifier, topic = com.notio.common.HelpMap.topicForKey(key)) {
        androidx.compose.material3.FloatingActionButton(onClick = onClick) {
            if (own != null) own() else androidx.compose.material3.Icon(icon, contentDescription = null)
            Row(Modifier.requiredSize(0.dp).alpha(0f)) { CompositionLocalProvider(LocalButtonLabel provides label) { words() } }
        }
    }
}
