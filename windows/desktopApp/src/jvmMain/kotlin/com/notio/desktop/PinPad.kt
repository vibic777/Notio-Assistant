package com.notio.desktop

import com.notio.shared.lock.*

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * A number-pad PIN entry (user, 2026-09-22, revised same day after a real device test). A dot per digit typed, then
 * a 3x4 grid of digit buttons, backspace, and — always, in every case, not only when the length is known — an
 * **Enter** button: the first version only auto-submitted once [expectedLength] digits were typed and had no manual
 * way to submit otherwise, which stranded the person during PIN setup (choosing a new PIN has no fixed length yet,
 * so it never auto-submitted, and they had no way to confirm what they had already typed once they noticed). Now:
 * when [expectedLength] is known, the pad still auto-submits at that length (convenience), but Enter is always there
 * too as the one dependable way to submit, at any length, in any context — including "this is deliberately not the
 * PIN, show me the wrong-code message" per the person's own request. [onComplete] is called either way; it is up to
 * the caller to decide what an early or mismatched length means (typically: verify anyway, let a real mismatch fail
 * normally instead of the UI silently refusing to submit).
 */
@Composable
fun PinPad(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    maxLength: Int = 8,
    expectedLength: Int? = null,
    enabled: Boolean = true,
    onComplete: () -> Unit = {},
) {
    LaunchedEffect(value, expectedLength) {
        if (expectedLength != null && value.length == expectedLength) onComplete()
    }

    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        val dotSlots = (expectedLength ?: value.length).coerceAtLeast(value.length)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            repeat(dotSlots) { i ->
                val filled = i < value.length
                Box(
                    Modifier
                        .size(14.dp)
                        .let { if (filled) it.background(MaterialTheme.colorScheme.primary, CircleShape) else it.border(1.dp, MaterialTheme.colorScheme.outline, CircleShape) },
                )
            }
        }
        val rows = listOf(
            listOf("1", "2", "3"),
            listOf("4", "5", "6"),
            listOf("7", "8", "9"),
            listOf(ENTER, "0", BACKSPACE),
        )
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                row.forEach { digit ->
                    when (digit) {
                        ENTER -> OutlinedButton(
                            onClick = onComplete,
                            enabled = enabled && value.isNotEmpty(),
                            modifier = Modifier.size(64.dp),
                            shape = CircleShape,
                        ) { Text(ENTER) }
                        BACKSPACE -> OutlinedButton(
                            onClick = { if (value.isNotEmpty()) onValueChange(value.dropLast(1)) },
                            enabled = enabled && value.isNotEmpty(),
                            modifier = Modifier.size(64.dp),
                            shape = CircleShape,
                        ) { Text(BACKSPACE) }
                        else -> OutlinedButton(
                            onClick = { if (value.length < maxLength) onValueChange(value + digit) },
                            enabled = enabled && value.length < maxLength,
                            modifier = Modifier.size(64.dp),
                            shape = CircleShape,
                        ) { Text(digit, style = MaterialTheme.typography.headlineSmall) }
                    }
                }
            }
        }
    }
}

private const val BACKSPACE = "⌫"
private const val ENTER = "⏎"
