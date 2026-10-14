package com.notebudget.app.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.notebudget.app.R
import com.notebudget.app.domain.model.NoteType

/**
 * The kinds of note whose content can be hidden until the eye is pressed (D363): a note, an MD note, a list, a drawing and a
 * blueprint. Not photo, video, screen recording or audio; a budget hides its amounts in its own way (D272).
 */
val HIDEABLE_KINDS = setOf(NoteType.TEXT, NoteType.WORD, NoteType.LIST, NoteType.DRAWING, NoteType.BLUEPRINT)

/** The section of the help that tells about hiding the content. */
const val HIDE_HELP = "privacy"

/** How the tests find the eye, the switch of the option and the cover. */
const val HIDE_EYE = "hide_eye"
const val HIDE_OPTION = "hide_option"
const val HIDDEN_CONTENT = "hidden_content"

/**
 * The hiding of the content of the open note (D363). [hidden] is the option of the note, set in Edit; [shown] - the eye was
 * pressed, for as long as the note is open. A cover against a glance, not a lock: the app lock keeps a stranger out.
 */
class NoteHide(val hidden: Boolean, val shown: Boolean, val setHidden: (Boolean) -> Unit, val setShown: (Boolean) -> Unit) {
    /** The content is covered now. */
    val masked: Boolean get() = hidden && !shown
}

/** Provided by the screen of a note of one of the [HIDEABLE_KINDS]; `null` elsewhere. */
val LocalNoteHide = compositionLocalOf<NoteHide?> { null }

/**
 * The eye in the row of the View / Edit tabs. In Edit it is the option itself: the crossed eye, tinted while the note hides its
 * content. In View, when the note hides it, the eye shows the content and hides it again.
 */
@Composable
fun HideEye(editing: Boolean) {
    val hide = LocalNoteHide.current ?: return
    if (editing) {
        com.notebudget.app.core.ui.tip.IconButton(onClick = { hide.setHidden(!hide.hidden) }, modifier = Modifier.size(40.dp).testTag(HIDE_OPTION), helpTopic = HIDE_HELP) {
            com.notebudget.app.core.ui.tip.Icon(
                AppIcons.VisibilityOff,
                contentDescription = stringResource(if (hide.hidden) R.string.note_hide_off else R.string.note_hide_on),
                tint = if (hide.hidden) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    } else if (hide.hidden) {
        com.notebudget.app.core.ui.tip.IconButton(onClick = { hide.setShown(!hide.shown) }, modifier = Modifier.size(40.dp).testTag(HIDE_EYE), helpTopic = HIDE_HELP) {
            com.notebudget.app.core.ui.tip.Icon(
                if (hide.shown) AppIcons.VisibilityOff else AppIcons.Visibility,
                contentDescription = stringResource(if (hide.shown) R.string.note_hide_again else R.string.note_hide_show),
            )
        }
    }
}

/** What the View of a note shows instead of its hidden content: the words and the button that shows it. */
@Composable
fun HiddenContent(modifier: Modifier = Modifier) {
    val hide = LocalNoteHide.current
    Column(
        modifier.padding(24.dp).testTag(HIDDEN_CONTENT),
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        com.notebudget.app.core.ui.tip.Icon(AppIcons.VisibilityOff, contentDescription = null, modifier = Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(stringResource(R.string.note_hidden_text), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(stringResource(R.string.note_hidden_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        if (hide != null) com.notebudget.app.core.ui.tip.FilledTonalIconButton(onClick = { hide.setShown(true) }) {
            com.notebudget.app.core.ui.tip.Icon(AppIcons.Visibility, contentDescription = stringResource(R.string.note_hide_show))
        }
    }
}

/** The line a card shows instead of the preview of a hidden note. */
@Composable
fun HiddenLine() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        com.notebudget.app.core.ui.tip.Icon(AppIcons.VisibilityOff, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(stringResource(R.string.note_hidden_text), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
