package com.notio.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.notio.shared.lock.MailResult
import com.notio.shared.lock.SmtpSettings
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings -> Data -> Database encryption: the phone's `DatabaseEncryptionOptions` (Android D184; AGENT.md D46). The switch, after the
 * phone's confirmation, plans the change and restarts Notio, which converts the file before it opens it; a failure of the last change is
 * shown once with its detail and Copy error text (D189). The passphrase behind Show with Copy and Send by e-mail; the raw database file
 * exported as it is or imported (checked first, replacing everything after a confirmation).
 */
@Composable
fun DatabaseEncryptionOptions(controller: NotioController) {
    val res = LocalRes.current
    val scope = rememberCoroutineScope()
    val enabled = remember { controller.databaseEncrypted }
    val passphrase = remember { if (enabled) controller.databasePassphrase() else null }
    var confirmToggle by remember { mutableStateOf<Boolean?>(null) }
    var busy by remember { mutableStateOf(false) }
    val failure = remember { controller.lastDatabaseError }

    var importFile by remember { mutableStateOf<File?>(null) }
    var importPassword by remember { mutableStateOf<String?>(null) }
    var importWrong by remember { mutableStateOf(false) }

    var confirmEmail by remember { mutableStateOf<SmtpSettings?>(null) }
    var emailBusy by remember { mutableStateOf(false) }
    var emailResult by remember { mutableStateOf<MailResult?>(null) }

    fun runImport(file: File, password: String?) {
        scope.launch {
            busy = true
            val problem = withContext(Dispatchers.IO) { controller.checkDatabaseImport(file, password) }
            busy = false
            when (problem) {
                null -> { importFile = null; busy = true; controller.importDatabaseFile(password) }
                "password" -> { if (importPassword == null) importPassword = "" else importWrong = true }
                else -> {
                    importFile = null; importPassword = null; importWrong = false
                    com.notio.shared.AppLog.critical("database", "the database file could not be imported: $problem")
                    controller.say(res.str("db_file_import_failed"))
                }
            }
        }
    }

    if (busy) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource("db_encryption_busy_title")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource("db_encryption_busy_message"))
                    androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            },
            confirmButton = {},
        )
    }

    confirmToggle?.let { turnOn ->
        AlertDialog(
            onDismissRequest = { confirmToggle = null },
            title = { Text(stringResource(if (turnOn) "db_encryption_confirm_on_title" else "db_encryption_confirm_off_title")) },
            text = { Text(stringResource(if (turnOn) "db_encryption_confirm_on_message" else "db_encryption_confirm_off_message")) },
            confirmButton = {
                TextButton(onClick = { confirmToggle = null; busy = true; controller.changeDatabaseEncryption(turnOn) }) { Text(stringResource("action_continue")) }
            },
            dismissButton = { TextButton(onClick = { confirmToggle = null }) { Text(stringResource("action_cancel")) } },
        )
    }

    importFile?.let { file ->
        com.notio.desktop.EditorDialog(
            onDismissRequest = { importFile = null; importPassword = null; importWrong = false },
            title = { Text(stringResource("db_file_import_confirm_title")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource("db_file_import_confirm_message"))
                    if (importWrong || importPassword != null) {
                        PasswordTextField(
                            value = importPassword.orEmpty(),
                            onValueChange = { importPassword = it; importWrong = false },
                            label = { Text(stringResource("db_encryption_password_field")) },
                            isError = importWrong,
                            supportingText = if (importWrong) { { Text(stringResource("transfer_error_wrong_password")) } } else null,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { runImport(file, importPassword) }) { Text(stringResource("import_confirm")) } },
            dismissButton = {
                TextButton(onClick = { importFile = null; importPassword = null; importWrong = false }) { Text(stringResource("action_cancel")) }
            },
        )
    }

    Hint("settings_db_encryption_hint")
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource("db_encryption_enable"), Modifier.weight(1f))
        Switch(checked = enabled, enabled = !busy, onCheckedChange = { on -> confirmToggle = on })
    }
    if (enabled && passphrase != null) {
        PasswordTextField(
            value = passphrase,
            onValueChange = {},
            label = { Text(stringResource("db_encryption_password_field")) },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { copyToClipboard(passphrase); controller.say(res.str("db_encryption_copied")) }) {
                Text(stringResource("action_copy"))
            }
            OutlinedButton(enabled = !emailBusy, onClick = {
                scope.launch {
                    val settings = controller.appLock.smtpSettings()
                    if (settings == null || !settings.isComplete) controller.say(res.str("db_encryption_email_not_configured"))
                    else confirmEmail = settings
                }
            }) { Text(stringResource("db_encryption_email_button")) }
        }
        Hint("db_encryption_password_warning")
    }
    // The last planned change did not happen: nothing was changed, and why (the phone's message and the exact step).
    failure?.let { (action, detail) ->
        Text(
            stringResource(
                when (action) {
                    "decrypt" -> "db_encryption_disable_failed"
                    "import" -> "db_file_import_failed"
                    else -> "db_encryption_enable_failed"
                },
            ),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error,
        )
        Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        TextButton(onClick = { copyToClipboard(detail); controller.say(res.str("db_encryption_error_copied")) }) {
            Text(stringResource("db_encryption_copy_error"))
        }
    }
    emailResult?.let {
        Text(
            if (it is MailResult.Sent) stringResource("db_encryption_email_sent") else mailErrorText((it as MailResult.Failed).error, it.detail),
            style = MaterialTheme.typography.bodyMedium,
            color = if (it is MailResult.Sent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        )
    }
    confirmEmail?.let { settings ->
        AlertDialog(
            onDismissRequest = { confirmEmail = null },
            title = { Text(stringResource("db_encryption_email_confirm_title")) },
            text = { Text(stringResource("db_encryption_email_confirm_message", settings.to)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmEmail = null
                    val subject = res.str("db_encryption_mail_subject")
                    val body = res.str("db_encryption_mail_body", passphrase.orEmpty())
                    scope.launch {
                        emailBusy = true
                        emailResult = controller.appLock.sendTestMail(settings, subject, body)
                        emailBusy = false
                    }
                }) { Text(stringResource("action_continue")) }
            },
            dismissButton = { TextButton(onClick = { confirmEmail = null }) { Text(stringResource("action_cancel")) } },
        )
    }

    HorizontalDivider(Modifier.padding(vertical = 8.dp))
    Hint("db_file_transfer_hint")
    OutlinedButton(onClick = {
        chooseFile(res.str("db_file_export"), save = true, name = suggestedDbFileName())?.let { target ->
            scope.launch {
                busy = true
                val ok = withContext(Dispatchers.IO) { controller.exportDatabaseFile(target) }
                busy = false
                controller.say(res.str(if (ok) "db_file_export_done" else "db_file_export_failed"))
            }
        }
    }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(stringResource("db_file_export")) }
    OutlinedButton(onClick = {
        chooseFile(res.str("db_file_import"), save = false)?.let { importFile = it; importPassword = null; importWrong = false }
    }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(stringResource("db_file_import")) }
}

private fun suggestedDbFileName(): String =
    "notio-database-" + java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT).format(java.util.Date()) + ".db"
