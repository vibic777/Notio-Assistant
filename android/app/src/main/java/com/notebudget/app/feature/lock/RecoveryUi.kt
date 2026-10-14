package com.notebudget.app.feature.lock

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import com.notebudget.app.core.ui.tip.Button
import androidx.compose.material3.MaterialTheme
import com.notebudget.app.core.ui.tip.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import com.notebudget.app.core.ui.tip.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import com.notebudget.app.core.ui.tip.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.notebudget.app.R
import com.notebudget.app.core.mail.MailError
import com.notebudget.app.domain.model.AppLockConfig
import com.notebudget.app.domain.model.EmailCodeResult
import com.notebudget.app.domain.model.LockMethod
import com.notebudget.app.domain.model.LockSetupError
import com.notebudget.app.domain.model.RecoveryMethod
import com.notebudget.app.domain.model.UnlockResult
import com.notebudget.app.domain.usecase.AppLockUseCases
import com.notebudget.app.domain.usecase.LockSetup
import kotlinx.coroutines.launch

/** `k***@example.com`: enough for the person to recognise the address, not for a bystander to read it. */
fun maskAddress(address: String): String {
    val at = address.indexOf('@')
    if (at <= 0) return address
    return address.first() + "***" + address.substring(at)
}

/** The text of a failed mail try: what went wrong in words a person can act on. */
@Composable
fun mailErrorText(error: MailError, detail: String): String {
    val base = stringResource(
        when (error) {
            MailError.NOT_CONFIGURED -> R.string.mail_error_not_configured
            MailError.CONNECTION -> R.string.mail_error_connection
            MailError.TIMEOUT -> R.string.mail_error_timeout
            MailError.TLS -> R.string.mail_error_tls
            MailError.AUTH -> R.string.mail_error_auth
            MailError.REJECTED -> R.string.mail_error_rejected
            MailError.PROTOCOL -> R.string.mail_error_protocol
        },
    )
    return if (detail.isBlank()) base else "$base ($detail)"
}

/** A QR code drawn with plain rectangles (black modules on white, with the quiet zone the standard asks for). */
@Composable
fun QrCode(text: String, modifier: Modifier = Modifier.size(220.dp)) {
    val matrix = remember(text) {
        runCatching {
            QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 0))
        }.getOrNull()
    }
    Canvas(modifier.background(Color.White)) {
        val m = matrix ?: return@Canvas
        val quiet = 3
        val cells = m.width + quiet * 2
        val cell = size.minDimension / cells
        for (y in 0 until m.height) for (x in 0 until m.width) {
            if (m.get(x, y)) drawRect(Color.Black, Offset((x + quiet) * cell, (y + quiet) * cell), Size(cell + 0.5f, cell + 0.5f))
        }
    }
}

/**
 * "I forgot it" (D129): the ways back in that the person set up are offered - the security question, a code sent to their own
 * mailbox, a code from an authenticator app - and each one ends with choosing a new password.
 */
@Composable
fun RecoveryDialog(config: AppLockConfig, lock: AppLockUseCases, onDone: () -> Unit, onDismiss: () -> Unit) {
    val methods = remember(config) { lock.recoveryMethods(config) }
    var chosen by remember { mutableStateOf(methods.singleOrNull()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.lock_forgot)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    methods.isEmpty() -> Text(stringResource(R.string.lock_no_recovery))
                    chosen == null -> {
                        Text(stringResource(R.string.lock_recovery_choose))
                        if (RecoveryMethod.QUESTION in methods) OutlinedButton(onClick = { chosen = RecoveryMethod.QUESTION }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.lock_recovery_question_option)) }
                        if (RecoveryMethod.EMAIL in methods) OutlinedButton(onClick = { chosen = RecoveryMethod.EMAIL }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.lock_recovery_email_option)) }
                        if (RecoveryMethod.AUTHENTICATOR in methods) OutlinedButton(onClick = { chosen = RecoveryMethod.AUTHENTICATOR }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.lock_recovery_totp_option)) }
                    }
                    chosen == RecoveryMethod.QUESTION -> QuestionRecovery(config, lock, onDone)
                    chosen == RecoveryMethod.EMAIL -> EmailRecovery(lock, onDone)
                    else -> AuthenticatorRecovery(lock, onDone)
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** Two password fields and the outcome of a recovery try, shared by the three ways. */
@Composable
private fun NewPasswordAndSubmit(
    enabled: Boolean,
    message: String?,
    onSubmit: (password: String, showLocal: (String) -> Unit) -> Unit,
) {
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var local by remember { mutableStateOf<String?>(null) }
    val differs = stringResource(R.string.lock_password_differs)
    Text(stringResource(R.string.lock_new_password_hint), style = MaterialTheme.typography.bodySmall)
    com.notebudget.app.core.ui.PasswordTextField(
        value = password, onValueChange = { password = it.take(AppLockUseCases.MAX_SECRET) }, enabled = enabled,
        label = { Text(stringResource(R.string.lock_password)) },
    )
    com.notebudget.app.core.ui.PasswordTextField(
        value = confirm, onValueChange = { confirm = it.take(AppLockUseCases.MAX_SECRET) }, enabled = enabled,
        label = { Text(stringResource(R.string.lock_password_again)) },
        isError = confirm.isNotEmpty() && confirm != password,
        supportingText = if (confirm.isNotEmpty() && confirm != password) { { Text(differs) } } else null,
    )
    (local ?: message)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    Button(
        enabled = enabled && password.isNotEmpty() && password == confirm,
        onClick = { if (password != confirm) local = differs else { local = null; onSubmit(password) { local = it } } },
    ) { Text(stringResource(R.string.action_save)) }
}

@Composable
private fun QuestionRecovery(config: AppLockConfig, lock: AppLockUseCases, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var answer by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    val wrongAnswer = stringResource(R.string.lock_wrong_answer)
    val blocked = stringResource(R.string.lock_blocked_short)
    val length = stringResource(R.string.lock_error_length)
    val lockedOut = stringResource(R.string.lock_locked_out)
    val wiped = stringResource(R.string.lock_wiped)
    Text(config.question.orEmpty(), style = MaterialTheme.typography.titleMedium)
    OutlinedTextField(value = answer, onValueChange = { answer = it }, singleLine = true, label = { Text(stringResource(R.string.lock_answer)) })
    NewPasswordAndSubmit(enabled = true, message = message) { password, _ ->
        scope.launch {
            val (result, error) = lock.recover(answer, LockSetup(LockMethod.PASSWORD, password))
            when {
                result is UnlockResult.Blocked -> message = blocked
                result is UnlockResult.Wrong -> message = wrongAnswer
                result is UnlockResult.LockedOut -> message = lockedOut
                result is UnlockResult.Wiped -> message = wiped
                error == LockSetupError.SECRET_LENGTH -> message = length
                else -> onDone()
            }
        }
    }
}

@Composable
private fun EmailRecovery(lock: AppLockUseCases, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var address by remember { mutableStateOf("") }
    var sent by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var mailFailure by remember { mutableStateOf<EmailCodeResult.Failed?>(null) }
    val subject = stringResource(R.string.smtp_code_subject)
    val bodyTemplate = stringResource(R.string.smtp_code_body)
    // The last resort (D350): the letter also carries the passwords kept on this phone.
    val container = com.notebudget.app.core.ui.rememberAppContainer()
    val secretsNote = stringResource(R.string.lock_email_secrets_note)
    val databaseLine = stringResource(R.string.lock_email_secrets_database)
    val backupLine = stringResource(R.string.lock_email_secrets_backup)
    val wrongCode = stringResource(R.string.lock_code_wrong)
    val expired = stringResource(R.string.lock_code_expired)
    val blocked = stringResource(R.string.lock_blocked_short)
    val length = stringResource(R.string.lock_error_length)
    val lockedOut = stringResource(R.string.lock_locked_out)
    val wiped = stringResource(R.string.lock_wiped)
    val notConfigured = stringResource(R.string.mail_error_not_configured)
    val waitTemplate = stringResource(R.string.lock_email_wait)

    LaunchedEffect(Unit) { address = lock.smtpSettings()?.to.orEmpty() }
    Text(stringResource(R.string.lock_email_intro, maskAddress(address)))
    Text(stringResource(R.string.lock_email_secrets_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Button(enabled = !sending, onClick = {
        sending = true
        message = null
        mailFailure = null
        scope.launch {
            // Read when the letter is asked for, not before: a password that cannot be read leaves the letter of the code as it was.
            val databasePassword = runCatching { container.databaseEncryption.read().passphrase }.getOrNull()
            val backupPassword = runCatching { container.backupService.savedPassword() }.getOrNull()
            when (val result = lock.sendRecoveryCode(subject) { c, minutes ->
                com.notio.common.RecoveryMail.body(
                    bodyTemplate.format(c, minutes), databasePassword, backupPassword, secretsNote,
                    databaseLine = { databaseLine.format(it) }, backupLine = { backupLine.format(it) },
                )
            }) {
                EmailCodeResult.Sent -> sent = true
                EmailCodeResult.NotConfigured -> message = notConfigured
                is EmailCodeResult.TooSoon -> { message = waitTemplate.format(((result.waitMillis + 999) / 1000).toInt()); sent = true }
                is EmailCodeResult.Failed -> mailFailure = result
            }
            sending = false
        }
    }) { Text(stringResource(if (sent) R.string.lock_email_send_again else R.string.lock_email_send)) }
    mailFailure?.let { Text(mailErrorText(it.error, it.detail), color = MaterialTheme.colorScheme.error) }
    if (sent) {
        OutlinedTextField(
            value = code, onValueChange = { code = it.filter { c -> c.isDigit() }.take(6) }, singleLine = true,
            label = { Text(stringResource(R.string.lock_email_code)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        )
        NewPasswordAndSubmit(enabled = code.length == 6, message = message) { password, _ ->
            scope.launch {
                val (result, error) = lock.recoverWithEmail(code, LockSetup(LockMethod.PASSWORD, password))
                when {
                    result is UnlockResult.Blocked -> message = blocked
                    result is UnlockResult.Wrong && error == LockSetupError.CODE_EXPIRED -> message = expired
                    result is UnlockResult.Wrong -> message = wrongCode
                    result is UnlockResult.LockedOut -> message = lockedOut
                    result is UnlockResult.Wiped -> message = wiped
                    error == LockSetupError.SECRET_LENGTH -> message = length
                    else -> onDone()
                }
            }
        }
    } else {
        message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun AuthenticatorRecovery(lock: AppLockUseCases, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    val wrongCode = stringResource(R.string.lock_code_wrong)
    val blocked = stringResource(R.string.lock_blocked_short)
    val length = stringResource(R.string.lock_error_length)
    val lockedOut = stringResource(R.string.lock_locked_out)
    val wiped = stringResource(R.string.lock_wiped)
    Text(stringResource(R.string.lock_totp_recover_intro))
    OutlinedTextField(
        value = code, onValueChange = { code = it.filter { c -> c.isDigit() }.take(6) }, singleLine = true,
        label = { Text(stringResource(R.string.lock_totp_code)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
    )
    NewPasswordAndSubmit(enabled = code.length == 6, message = message) { password, _ ->
        scope.launch {
            val (result, error) = lock.recoverWithTotp(code, LockSetup(LockMethod.PASSWORD, password))
            when {
                result is UnlockResult.Blocked -> message = blocked
                result is UnlockResult.Wrong -> message = wrongCode
                result is UnlockResult.LockedOut -> message = lockedOut
                result is UnlockResult.Wiped -> message = wiped
                error == LockSetupError.SECRET_LENGTH -> message = length
                else -> onDone()
            }
        }
    }
}
