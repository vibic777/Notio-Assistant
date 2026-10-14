package com.notio.desktop

import androidx.compose.ui.text.input.KeyboardType

import androidx.compose.foundation.text.KeyboardOptions

import com.notio.shared.lock.*

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** The ready timeouts as chips, plus "Other…" for any minutes and seconds (D129). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TimeoutPicker(seconds: Int, onChange: (Int) -> Unit) {
    var custom by remember { mutableStateOf(false) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        LockTimeout.entries.forEach { t ->
            FilterChip(seconds == t.seconds, { onChange(t.seconds) }, { Text(stringResource(timeoutName(t))) })
        }
        val isCustom = !LockTimeouts.isPreset(seconds)
        FilterChip(
            selected = isCustom,
            onClick = { custom = true },
            label = {
                Text(
                    if (isCustom) stringResource("lock_timeout_custom_value", LockTimeouts.minutesOf(seconds), LockTimeouts.secondsOf(seconds))
                    else stringResource("lock_timeout_custom"),
                )
            },
        )
    }
    if (custom) CustomTimeoutDialog(seconds, onSave = { onChange(it); custom = false }, onDismiss = { custom = false })
}

@Composable
private fun CustomTimeoutDialog(current: Int, onSave: (Int) -> Unit, onDismiss: () -> Unit) {
    var minutes by remember { mutableStateOf(LockTimeouts.minutesOf(current).toString()) }
    var secs by remember { mutableStateOf(LockTimeouts.secondsOf(current).toString()) }
    var invalid by remember { mutableStateOf(false) }
    com.notio.desktop.EditorDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource("lock_timeout_custom_title")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = minutes, onValueChange = { minutes = it.filter(Char::isDigit).take(4) }, singleLine = true,
                        label = { Text(stringResource("lock_minutes")) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = secs, onValueChange = { secs = it.filter(Char::isDigit).take(2) }, singleLine = true,
                        label = { Text(stringResource("lock_seconds")) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                }
                if (invalid) Text(stringResource("lock_timeout_invalid"), color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val total = LockTimeouts.fromMinutesAndSeconds(minutes.toIntOrNull() ?: 0, secs.toIntOrNull() ?: 0)
                if (total == null) invalid = true else onSave(total)
            }) { Text(stringResource("action_save")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource("action_cancel")) } },
    )
}

/**
 * Settings → App lock → Recovery (D129): the three ways back in - a security question, a code sent to the person's own
 * mailbox through THEIR mail server, and an authenticator app. Changing them asks for the current secret first, so someone
 * holding an unlocked phone cannot quietly add a way in for themselves.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RecoverySettings(config: AppLockConfig, lock: AppLockUseCases, onChanged: () -> Unit) {
    val scope = rememberCoroutineScope()
    var verified by remember { mutableStateOf(false) }
    var asking by remember { mutableStateOf(false) }
    var editQuestion by remember { mutableStateOf(false) }
    var editMail by remember { mutableStateOf(false) }
    var editTotp by remember { mutableStateOf(false) }
    var smtp by remember { mutableStateOf<SmtpSettings?>(null) }
    LaunchedEffect(config.emailRecovery) { smtp = lock.smtpSettings() }

    Text(stringResource("lock_recovery_section"), style = MaterialTheme.typography.titleMedium)
    Text(stringResource("lock_recovery_hint"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(
        stringResource(if (config.question != null) "lock_recovery_on" else "lock_recovery_off"),
        style = MaterialTheme.typography.bodyMedium,
    )
    Text(
        if (config.emailRecovery) stringResource("lock_email_status_on", maskAddress(smtp?.to.orEmpty())) else stringResource("lock_email_status_off"),
        style = MaterialTheme.typography.bodyMedium,
    )
    Text(
        stringResource(if (config.totpBox != null) "lock_totp_status_on" else "lock_totp_status_off"),
        style = MaterialTheme.typography.bodyMedium,
    )
    if (!verified) {
        OutlinedButton(onClick = { asking = true }, modifier = Modifier.fillMaxWidth()) { Text(stringResource("lock_recovery_change")) }
    } else {
        OutlinedButton(onClick = { editQuestion = true }, modifier = Modifier.fillMaxWidth()) { Text(stringResource("lock_question_change")) }
        OutlinedButton(onClick = { editMail = true }, modifier = Modifier.fillMaxWidth()) { Text(stringResource("lock_email_setup")) }
        OutlinedButton(onClick = { editTotp = true }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(if (config.totpBox != null) "lock_totp_manage" else "lock_totp_setup"))
        }
    }

    if (asking) VerifyDialog(config, lock, onVerified = { verified = true; asking = false }, onDismiss = { asking = false })
    if (editQuestion) QuestionDialog(lock, onDone = { editQuestion = false; onChanged() }, onDismiss = { editQuestion = false })
    if (editMail) SmtpDialog(lock, smtp, onDone = { editMail = false; onChanged() }, onDismiss = { editMail = false })
    if (editTotp) TotpDialog(config, lock, onDone = { editTotp = false; onChanged() }, onDismiss = { editTotp = false })
}

/** Asks for the current secret; used before recovery options are changed. */
@Composable
private fun VerifyDialog(config: AppLockConfig, lock: AppLockUseCases, onVerified: () -> Unit, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    val wrong = stringResource("lock_wrong")
    val blocked = stringResource("lock_blocked_short")
    val lockedOut = stringResource("lock_locked_out")
    val wiped = stringResource("lock_wiped")
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource("lock_recovery_change")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource("lock_current"))
                SecretForm(config.method, creating = false, submitLabel = stringResource("lock_next"), message = message, pinLength = config.pinLength, onSubmit = { s, l ->
                    scope.launch {
                        when (lock.verify(s, l)) {
                            UnlockResult.Success -> onVerified()
                            is UnlockResult.Blocked -> message = blocked
                            is UnlockResult.Wrong -> message = wrong
                            UnlockResult.LockedOut -> message = lockedOut
                            UnlockResult.Wiped -> message = wiped
                        }
                    }
                })
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource("action_cancel")) } },
    )
}

@Composable
private fun QuestionDialog(lock: AppLockUseCases, onDone: () -> Unit, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var index by remember { mutableStateOf(-1) }
    var answer by remember { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<LockSetupError?>(null) }
    val question = if (index >= 0) stringResource(QUESTIONS[index]) else null
    com.notio.desktop.EditorDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource("lock_question_title")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Column {
                    OutlinedButton(onClick = { menu = true }) { Text(question ?: stringResource("lock_question_none"), maxLines = 1) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text(stringResource("lock_question_none")) }, onClick = { index = -1; menu = false })
                        QUESTIONS.forEachIndexed { i, q -> DropdownMenuItem(text = { Text(stringResource(q)) }, onClick = { index = i; menu = false }) }
                    }
                }
                if (question != null) OutlinedTextField(value = answer, onValueChange = { answer = it }, singleLine = true, label = { Text(stringResource("lock_answer")) })
                error?.let { Text(lockErrorText(it), color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                scope.launch {
                    error = lock.setQuestion(question, answer)
                    if (error == null) onDone()
                }
            }) { Text(stringResource("action_save")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource("action_cancel")) } },
    )
}

/** Everything a letter needs: server, port, protection, login, password, the sender and the address the code goes to. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SmtpDialog(lock: AppLockUseCases, saved: SmtpSettings?, onDone: () -> Unit, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var host by remember { mutableStateOf(saved?.host.orEmpty()) }
    var port by remember { mutableStateOf((saved?.port ?: SmtpSettings.defaultPort(SmtpSecurity.STARTTLS)).toString()) }
    var security by remember { mutableStateOf(saved?.security ?: SmtpSecurity.STARTTLS) }
    var user by remember { mutableStateOf(saved?.username.orEmpty()) }
    var password by remember { mutableStateOf(saved?.password.orEmpty()) }
    var from by remember { mutableStateOf(saved?.from.orEmpty()) }
    var to by remember { mutableStateOf(saved?.to.orEmpty()) }
    var message by remember { mutableStateOf<String?>(null) }
    var failure by remember { mutableStateOf<MailResult.Failed?>(null) }
    var busy by remember { mutableStateOf(false) }
    // Saving mail without encryption (usually port 25) is asked once more, with the warning (O2).
    var confirmPlain by remember { mutableStateOf(false) }
    val invalidText = stringResource("smtp_invalid")
    val testOk = stringResource("smtp_test_ok")
    val testSubject = stringResource("smtp_test_subject")
    val testBody = stringResource("smtp_test_body")

    fun current() = SmtpSettings(host.trim(), port.toIntOrNull() ?: 0, security, user.trim(), password, from.trim(), to.trim())

    com.notio.desktop.EditorDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource("lock_email_setup")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource("smtp_intro"), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(host, { host = it }, singleLine = true, label = { Text(stringResource("smtp_host")) }, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                Text(stringResource("smtp_security"), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(SmtpSecurity.NONE to "smtp_sec_none", SmtpSecurity.STARTTLS to "smtp_sec_starttls", SmtpSecurity.SSL_TLS to "smtp_sec_ssl").forEach { (s, label) ->
                        FilterChip(security == s, {
                            // A port that is still the usual one of the old protection follows the new choice.
                            if (port.toIntOrNull() == SmtpSettings.defaultPort(security)) port = SmtpSettings.defaultPort(s).toString()
                            security = s
                        }, { Text(stringResource(label)) })
                    }
                }
                if (security == SmtpSecurity.NONE) Text(stringResource("smtp_none_warning"), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(port, { port = it.filter(Char::isDigit).take(5) }, singleLine = true, label = { Text(stringResource("smtp_port")) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                OutlinedTextField(user, { user = it }, singleLine = true, label = { Text(stringResource("smtp_user")) }, modifier = Modifier.fillMaxWidth())
                PasswordTextField(password, { password = it }, label = { Text(stringResource("smtp_password")) }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(from, { from = it }, singleLine = true, label = { Text(stringResource("smtp_from")) }, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
                OutlinedTextField(to, { to = it }, singleLine = true, label = { Text(stringResource("smtp_to")) }, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
                message?.let { Text(it, color = if (failure == null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error) }
                failure?.let { Text(mailErrorText(it.error, it.detail), color = MaterialTheme.colorScheme.error) }
                OutlinedButton(enabled = !busy, onClick = {
                    val settings = current()
                    if (!settings.isComplete) { message = invalidText; failure = null; return@OutlinedButton }
                    busy = true; message = null; failure = null
                    scope.launch {
                        when (val r = lock.sendTestMail(settings, testSubject, testBody)) {
                            MailResult.Sent -> message = testOk
                            is MailResult.Failed -> failure = r
                        }
                        busy = false
                    }
                }) { Text(stringResource("smtp_test")) }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                val settings = current()
                when {
                    !settings.isComplete -> { message = invalidText; failure = null }
                    settings.security == SmtpSecurity.NONE -> confirmPlain = true
                    else -> scope.launch { lock.saveSmtp(settings); onDone() }
                }
            }) { Text(stringResource("action_save")) }
        },
        dismissButton = {
            Row {
                if (saved != null) TextButton(onClick = { scope.launch { lock.saveSmtp(null); onDone() } }) { Text(stringResource("smtp_remove"), color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onDismiss) { Text(stringResource("action_cancel")) }
            }
        },
    )
    if (confirmPlain) {
        AlertDialog(
            onDismissRequest = { confirmPlain = false },
            title = { Text(stringResource("smtp_none_confirm_title")) },
            text = { Text(stringResource("smtp_none_warning"), color = MaterialTheme.colorScheme.error) },
            confirmButton = {
                TextButton(onClick = { confirmPlain = false; scope.launch { lock.saveSmtp(current()); onDone() } }) {
                    Text(stringResource("smtp_none_confirm"), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmPlain = false }) { Text(stringResource("action_cancel")) } },
        )
    }
}

/** Adds the app to an authenticator (QR code or key), proves it with a code, or removes it. */
@Composable
private fun TotpDialog(config: AppLockConfig, lock: AppLockUseCases, onDone: () -> Unit, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val issuer = stringResource("app_name")
    val account = stringResource("lock_totp_account")
    val setup: TotpSetup = remember { lock.startTotp(issuer, account) }
    var code by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    val already = config.totpBox != null
    com.notio.desktop.EditorDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource("lock_totp_title")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                if (already) Text(stringResource("lock_totp_replace_hint"))
                Text(stringResource("lock_totp_scan"))
                QrCode(setup.uri, Modifier.size(220.dp))
                SelectionContainer { Text(setup.secret.chunked(4).joinToString(" "), style = MaterialTheme.typography.titleMedium) }
                OutlinedTextField(
                    value = code, onValueChange = { code = it.filter(Char::isDigit).take(6); wrong = false }, singleLine = true,
                    label = { Text(stringResource("lock_totp_code")) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                )
                if (wrong) Text(stringResource("lock_code_wrong"), color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            Button(enabled = code.length == 6, onClick = {
                scope.launch { if (lock.confirmTotp(setup.secret, code)) onDone() else wrong = true }
            }) { Text(stringResource("action_save")) }
        },
        dismissButton = {
            Row {
                if (already) TextButton(onClick = { scope.launch { lock.removeTotp(); onDone() } }) { Text(stringResource("lock_totp_remove"), color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onDismiss) { Text(stringResource("action_cancel")) }
            }
        },
    )
}
