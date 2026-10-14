package com.notio.desktop

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.notio.common.ButtonIcons

/*
 * A line of a menu keeps its words and gets the icon of their meaning beside them (D93, Android D314). DropdownMenu and
 * DropdownMenuItem of this package stand in for the Material ones in every file: the Text of a line tells the line its words, as
 * it tells a button (D85), the resources tell their key and `ButtonIcons` names the icon. A menu whose lines would all get one
 * and the same icon is a list of values (the ways to sort, the themes), not of actions: it stays without icons. In a menu with
 * icons a line that has none keeps the place of one, so the words stand in one column.
 */

/** What the lines of one menu have found out about themselves: the line and the name of its icon. */
class MenuIcons {
    val names = mutableStateMapOf<Any, String>()

    val shown: Boolean get() = shows(names.values)

    companion object {
        /** Icons are shown when there is one at all, and not when two or more lines would all show the same one. */
        fun shows(names: Collection<String>): Boolean = names.isNotEmpty() && !(names.size >= 2 && names.toSet().size == 1)
    }
}

/** How the tests find the icon of a line and the empty place of one. */
const val MENU_ICON = "menu-icon"
const val MENU_GAP = "menu-gap"

private val LocalMenuIcons = compositionLocalOf<MenuIcons?> { null }

@Composable
fun DropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val icons = remember { MenuIcons() }
    androidx.compose.material3.DropdownMenu(expanded = expanded, onDismissRequest = onDismissRequest, modifier = modifier) {
        CompositionLocalProvider(LocalMenuIcons provides icons) { content() }
    }
}

@Composable
fun DropdownMenuItem(
    text: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
) {
    val menu = LocalMenuIcons.current
    val label = remember { ButtonLabel() }
    val shown = label.shown.value
    SideEffect { if (label.shown.value != label.lastText) label.shown.value = label.lastText }
    val res = LocalRes.current
    val key = remember(shown, res) { shown?.let(res::keyOfText) }
    val name = remember(key) { key?.let(ButtonIcons::nameFor) }
    val icon = remember(name) { AppIcons.byName(name) }
    // The menu learns what this line shows: its own icon counts as one no other line has.
    val token = remember { Any() }
    if (menu != null) {
        DisposableEffect(menu, name, icon != null, leadingIcon != null) {
            when {
                leadingIcon != null -> menu.names[token] = "own " + System.identityHashCode(token)
                icon != null && name != null -> menu.names[token] = name
                else -> menu.names.remove(token)
            }
            onDispose { menu.names.remove(token) }
        }
    }
    val danger = key != null && ButtonIcons.isDanger(key)
    val beside: (@Composable () -> Unit)? = when {
        leadingIcon != null -> leadingIcon
        menu?.shown == true -> {
            {
                if (icon != null) androidx.compose.material3.Icon(icon, contentDescription = null, modifier = Modifier.testTag(MENU_ICON), tint = if (danger && enabled) MaterialTheme.colorScheme.error else LocalContentColor.current)
                else Spacer(Modifier.size(24.dp).testTag(MENU_GAP))
            }
        }
        else -> null
    }
    androidx.compose.material3.DropdownMenuItem(
        text = { CompositionLocalProvider(LocalButtonLabel provides label) { text() } },
        onClick = onClick, modifier = modifier, leadingIcon = beside, trailingIcon = trailingIcon, enabled = enabled,
    )
}
