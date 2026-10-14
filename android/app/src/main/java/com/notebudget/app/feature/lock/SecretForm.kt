package com.notebudget.app.feature.lock

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import com.notebudget.app.core.ui.tip.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.notebudget.app.core.ui.tip.stringResource
import androidx.compose.ui.unit.dp
import com.notebudget.app.R
import com.notebudget.app.domain.model.LockMethod
import com.notebudget.app.domain.model.PatternGrid
import com.notebudget.app.domain.usecase.AppLockUseCases

/**
 * Asks for a secret of the given [method]: a password, a pattern, a PIN, or a login and a password. With [creating]
 * the secret is typed (or drawn) twice; without it once (to unlock or to confirm a change). Calls [onSubmit] with the
 * secret text (a pattern as `0,1,2,5`) and the login. [message] is shown under the fields (a wrong try, an error).
 * [pinLength] is the stored PIN's length (user, 2026-09-22): known when unlocking or changing an existing PIN, so the
 * number pad can submit itself the moment that many digits are typed — `null` while choosing a brand new one, since
 * its length is not decided yet.
 */
@Composable
fun SecretForm(
    method: LockMethod,
    creating: Boolean,
    submitLabel: String,
    message: String?,
    onSubmit: (secret: String, login: String?) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    pinLength: Int? = null,
) {
    var login by remember(method) { mutableStateOf("") }
    var password by remember(method) { mutableStateOf("") }
    var confirm by remember(method) { mutableStateOf("") }
    var firstPattern by remember(method) { mutableStateOf<List<Int>?>(null) }
    var firstPin by remember(method) { mutableStateOf<String?>(null) }
    var local by remember(method) { mutableStateOf<Int?>(null) }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (method == LockMethod.PATTERN_3X3) {
            Text(
                stringResource(if (creating && firstPattern != null) R.string.lock_pattern_again else if (creating) R.string.lock_pattern_draw else R.string.lock_pattern_unlock),
                style = MaterialTheme.typography.bodyMedium,
            )
            PatternPad(
                enabled = enabled,
                onComplete = { dots ->
                    when {
                        dots.size < 4 -> { local = R.string.lock_pattern_short; firstPattern = null }
                        !creating -> { local = null; onSubmit(PatternGrid.encode(dots), null) }
                        firstPattern == null -> { local = null; firstPattern = dots }
                        firstPattern == dots -> { local = null; onSubmit(PatternGrid.encode(dots), null) }
                        else -> { local = R.string.lock_pattern_differs; firstPattern = null }
                    }
                },
            )
        } else if (method == LockMethod.PIN) {
            if (!creating) {
                // Unlocking, or verifying the current PIN before a change: the length is already fixed, so the pad
                // submits itself — no separate button, exactly like a phone's own lock screen.
                Text(stringResource(R.string.lock_pin_enter), style = MaterialTheme.typography.bodyMedium)
                PinPad(
                    value = password,
                    onValueChange = { password = it },
                    enabled = enabled,
                    maxLength = pinLength ?: AppLockUseCases.MAX_PIN,
                    expectedLength = pinLength,
                    onComplete = { onSubmit(password, null); password = "" },
                )
            } else if (firstPin == null) {
                // First entry: the length is being chosen right now, so there is nothing to auto-complete at — Enter
                // on the pad itself confirms "this is my code" (was a separate button, easy to miss; found on a real
                // device test, 2026-09-22: the person kept typing past 8 digits with no way to submit what they had).
                Text(stringResource(R.string.lock_pin_choose), style = MaterialTheme.typography.bodyMedium)
                PinPad(
                    value = password,
                    onValueChange = { password = it; local = null },
                    enabled = enabled,
                    maxLength = AppLockUseCases.MAX_PIN,
                    onComplete = {
                        if (password.length in AppLockUseCases.MIN_PIN..AppLockUseCases.MAX_PIN) {
                            local = null
                            firstPin = password
                            password = ""
                        } else {
                            local = R.string.lock_error_length
                        }
                    },
                )
            } else {
                // Confirm entry: the length is now fixed (whatever was just chosen), so this pad submits itself too.
                Text(stringResource(R.string.lock_pin_confirm), style = MaterialTheme.typography.bodyMedium)
                PinPad(
                    value = confirm,
                    onValueChange = { confirm = it },
                    enabled = enabled,
                    maxLength = firstPin!!.length,
                    expectedLength = firstPin!!.length,
                    onComplete = {
                        if (confirm == firstPin) {
                            local = null
                            onSubmit(confirm, null)
                        } else {
                            local = R.string.lock_password_differs
                            firstPin = null
                            password = ""
                        }
                        confirm = ""
                    },
                )
            }
        } else {
            if (method == LockMethod.LOGIN_PASSWORD) {
                OutlinedTextField(
                    value = login, onValueChange = { login = it.take(AppLockUseCases.MAX_LOGIN) }, singleLine = true, enabled = enabled,
                    label = { Text(stringResource(R.string.lock_login)) }, modifier = Modifier.fillMaxWidth(),
                )
            }
            com.notebudget.app.core.ui.PasswordTextField(
                value = password, onValueChange = { password = it.take(AppLockUseCases.MAX_SECRET) }, enabled = enabled,
                label = { Text(stringResource(R.string.lock_password)) }, modifier = Modifier.fillMaxWidth(),
            )
            if (creating) {
                com.notebudget.app.core.ui.PasswordTextField(
                    value = confirm, onValueChange = { confirm = it.take(AppLockUseCases.MAX_SECRET) }, enabled = enabled,
                    label = { Text(stringResource(R.string.lock_password_again)) }, modifier = Modifier.fillMaxWidth(),
                    // A mismatch is shown at once, right under the field, and the button waits: it was easy to miss before.
                    isError = confirm.isNotEmpty() && confirm != password,
                    supportingText = if (confirm.isNotEmpty() && confirm != password) {
                        { Text(stringResource(R.string.lock_password_differs)) }
                    } else null,
                )
            }
            Button(
                enabled = enabled && password.isNotEmpty() && (!creating || confirm == password),
                onClick = {
                    if (creating && password != confirm) local = R.string.lock_password_differs else { local = null; onSubmit(password, login.takeIf { method == LockMethod.LOGIN_PASSWORD }) }
                },
            ) { Text(submitLabel) }
        }
        (local?.let { stringResource(it) } ?: message)?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
