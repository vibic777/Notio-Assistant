package com.notebudget.app.feature.lock

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import com.notebudget.app.core.ui.tip.DropdownMenu
import com.notebudget.app.core.ui.tip.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import com.notebudget.app.core.ui.tip.Icon
import com.notebudget.app.core.ui.tip.IconButton
import androidx.compose.material3.MaterialTheme
import com.notebudget.app.core.ui.tip.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import com.notebudget.app.core.ui.tip.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.notebudget.app.core.ui.tip.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.notebudget.app.R
import com.notebudget.app.core.security.BiometricAuth
import com.notebudget.app.core.ui.rememberAppContainer
import com.notebudget.app.domain.model.AppLockConfig
import com.notebudget.app.domain.model.LockMethod
import com.notebudget.app.domain.model.LockSetupError
import com.notebudget.app.domain.model.LockTimeout
import com.notebudget.app.domain.model.LockoutMode
import com.notebudget.app.domain.model.UnlockResult
import com.notebudget.app.domain.usecase.AppLockUseCases
import com.notebudget.app.domain.usecase.LockSetup
import kotlinx.coroutines.launch

private fun methodName(method: LockMethod): Int = when (method) {
    LockMethod.PIN -> R.string.lock_method_pin
    LockMethod.PATTERN_3X3 -> R.string.lock_method_pattern
    LockMethod.LOGIN_PASSWORD -> R.string.lock_method_login
    else -> R.string.lock_method_password
}

/** Order the person sees everywhere a method is chosen (user, 2026-09-22): PIN first, then pattern, password, login. */
internal val LOCK_METHODS = listOf(LockMethod.PIN, LockMethod.PATTERN_3X3, LockMethod.PASSWORD, LockMethod.LOGIN_PASSWORD)

internal fun timeoutName(timeout: LockTimeout): Int = when (timeout) {
    LockTimeout.IMMEDIATE -> R.string.lock_timeout_immediate
    LockTimeout.SECONDS_30 -> R.string.lock_timeout_30s
    LockTimeout.MINUTE_1 -> R.string.lock_timeout_1m
    LockTimeout.MINUTES_5 -> R.string.lock_timeout_5m
    LockTimeout.MINUTES_15 -> R.string.lock_timeout_15m
    LockTimeout.NEVER -> R.string.lock_timeout_never
}

internal val QUESTIONS = listOf(R.string.lock_q1, R.string.lock_q2, R.string.lock_q3, R.string.lock_q4, R.string.lock_q5)

/** Settings → App lock (§6.1, D124): turn it on with a password, a pattern or a login, choose the timeout, biometrics and recovery. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LockSettingsScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val container = rememberAppContainer()
    val lock = container.appLock
    val gate = container.lockGate
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var config by remember { mutableStateOf<AppLockConfig?>(null) }
    val biometricAvailable = remember { BiometricAuth.isAvailable(context) }

    suspend fun reload() {
        config = lock.config()
        gate.refresh()
    }
    LaunchedEffect(Unit) { reload() }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.lock_settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        val c = config
        Column(
            Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (c == null) return@Column
            if (!c.enabled) {
                SetupSection(biometricAvailable, onEnable = { setup -> lock.enable(setup).also { if (it == null) reload() } })
            } else {
                Text(stringResource(R.string.lock_status_on, stringResource(methodName(c.method))), style = MaterialTheme.typography.titleMedium)
                if (biometricAvailable) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.lock_biometric_option), Modifier.weight(1f))
                        Switch(checked = c.biometric, onCheckedChange = { on -> scope.launch { lock.updateOptions(on, c.timeoutSeconds); reload() } })
                    }
                }
                Text(stringResource(R.string.lock_timeout_title), style = MaterialTheme.typography.titleSmall)
                TimeoutPicker(c.timeoutSeconds) { seconds -> scope.launch { lock.updateOptions(c.biometric, seconds); reload() } }
                RecoverySettings(c, lock, onChanged = { scope.launch { reload() } })
                LockoutPolicySection(c, lock, onChanged = { scope.launch { reload() } })
                Text(stringResource(R.string.lock_secure_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                var changing by remember { mutableStateOf(false) }
                var turningOff by remember { mutableStateOf(false) }
                OutlinedButton(onClick = { changing = true }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.lock_change)) }
                OutlinedButton(onClick = { turningOff = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.lock_turn_off), color = MaterialTheme.colorScheme.error)
                }
                if (changing) ChangeDialog(c, onDone = { changing = false; scope.launch { reload() } }, onDismiss = { changing = false })
                if (turningOff) TurnOffDialog(c, onDone = { turningOff = false; scope.launch { reload() } }, onDismiss = { turningOff = false })
            }
        }
    }
}

@Composable
private fun SetupSection(biometricAvailable: Boolean, onEnable: suspend (LockSetup) -> LockSetupError?) {
    val scope = rememberCoroutineScope()
    var method by remember { mutableStateOf(LockMethod.PIN) }
    var biometric by remember { mutableStateOf(false) }
    var timeoutSeconds by remember { mutableStateOf(LockTimeout.DEFAULT.seconds) }
    var questionIndex by remember { mutableStateOf(-1) }
    var answer by remember { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<LockSetupError?>(null) }
    val questionText = if (questionIndex >= 0) stringResource(QUESTIONS[questionIndex]) else null

    Text(stringResource(R.string.lock_status_off), style = MaterialTheme.typography.titleMedium)
    Text(stringResource(R.string.lock_intro), style = MaterialTheme.typography.bodyMedium)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        LOCK_METHODS.forEach { m ->
            FilterChip(method == m, { method = m }, { Text(stringResource(methodName(m))) }, modifier = Modifier.fillMaxWidth())
        }
    }
    if (biometricAvailable) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.lock_biometric_option), Modifier.weight(1f))
            Switch(checked = biometric, onCheckedChange = { biometric = it })
        }
    }
    Text(stringResource(R.string.lock_timeout_title), style = MaterialTheme.typography.titleSmall)
    TimeoutPicker(timeoutSeconds) { timeoutSeconds = it }
    Text(stringResource(R.string.lock_question_title), style = MaterialTheme.typography.titleSmall)
    Column {
        OutlinedButton(onClick = { menu = true }) { Text(questionText ?: stringResource(R.string.lock_question_none), maxLines = 1) }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.lock_question_none)) }, onClick = { questionIndex = -1; menu = false })
            QUESTIONS.forEachIndexed { i, q -> DropdownMenuItem(text = { Text(stringResource(q)) }, onClick = { questionIndex = i; menu = false }) }
        }
    }
    if (questionText != null) {
        OutlinedTextField(value = answer, onValueChange = { answer = it }, singleLine = true, label = { Text(stringResource(R.string.lock_answer)) }, modifier = Modifier.fillMaxWidth())
    } else {
        Text(stringResource(R.string.lock_warning_no_recovery), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
    SecretForm(
        method = method,
        creating = true,
        submitLabel = stringResource(R.string.lock_turn_on),
        message = error?.let { lockErrorText(it) },
        onSubmit = { secret, login ->
            scope.launch {
                error = onEnable(LockSetup(method, secret, login, biometric && biometricAvailable, timeoutSeconds, questionText, answer))
            }
        },
    )
}

@Composable
private fun ChangeDialog(config: AppLockConfig, onDone: () -> Unit, onDismiss: () -> Unit) {
    val lock = rememberAppContainer().appLock
    val scope = rememberCoroutineScope()
    var oldSecret by remember { mutableStateOf<String?>(null) }
    var oldLogin by remember { mutableStateOf<String?>(null) }
    var newMethod by remember { mutableStateOf(config.method) }
    var message by remember { mutableStateOf<String?>(null) }
    val wrong = stringResource(R.string.lock_wrong)
    val blocked = stringResource(R.string.lock_blocked_short)
    val lockedOut = stringResource(R.string.lock_locked_out)
    val wiped = stringResource(R.string.lock_wiped)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.lock_change)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (oldSecret == null) {
                    Text(stringResource(R.string.lock_current))
                    SecretForm(config.method, creating = false, submitLabel = stringResource(R.string.lock_next), message = message, pinLength = config.pinLength, onSubmit = { s, l ->
                        scope.launch {
                            when (lock.verify(s, l)) {
                                UnlockResult.Success -> { oldSecret = s; oldLogin = l; message = null }
                                is UnlockResult.Blocked -> message = blocked
                                is UnlockResult.Wrong -> message = wrong
                                UnlockResult.LockedOut -> message = lockedOut
                                UnlockResult.Wiped -> message = wiped
                            }
                        }
                    })
                } else {
                    Text(stringResource(R.string.lock_new))
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        LOCK_METHODS.forEach { m ->
                            FilterChip(newMethod == m, { newMethod = m }, { Text(stringResource(methodName(m))) }, modifier = Modifier.fillMaxWidth())
                        }
                    }
                    SecretForm(newMethod, creating = true, submitLabel = stringResource(R.string.action_save), message = message, onSubmit = { s, l ->
                        scope.launch {
                            val (_, error) = lock.changeSecret(oldSecret.orEmpty(), oldLogin, LockSetup(newMethod, s, l))
                            if (error == null) onDone() else message = "!" + error.name
                        }
                    })
                    message?.takeIf { it.startsWith("!") }?.let { key ->
                        lockErrorText(LockSetupError.valueOf(key.drop(1)))
                    }?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun TurnOffDialog(config: AppLockConfig, onDone: () -> Unit, onDismiss: () -> Unit) {
    val lock = rememberAppContainer().appLock
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    val wrong = stringResource(R.string.lock_wrong)
    val blocked = stringResource(R.string.lock_blocked_short)
    val lockedOut = stringResource(R.string.lock_locked_out)
    val wiped = stringResource(R.string.lock_wiped)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.lock_turn_off)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.lock_current))
                SecretForm(config.method, creating = false, submitLabel = stringResource(R.string.lock_turn_off), message = message, pinLength = config.pinLength, onSubmit = { s, l ->
                    scope.launch {
                        when (lock.disable(s, l)) {
                            UnlockResult.Success -> onDone()
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
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/**
 * The wrong-try policy (D-lockout, user, 2026-09-22): free tries and the wait that follows are now numbers the person
 * sets (were fixed at 5 / 30 s), and past a total limit (off by default) either direct guessing is refused until a
 * recovery method is used, or — only if the person explicitly opts in behind [WipeConfirmDialog] — the app's own data
 * is erased. One "Save" commits all three numbers and the mode together.
 */
@Composable
private fun LockoutPolicySection(config: AppLockConfig, lock: AppLockUseCases, onChanged: () -> Unit) {
    val scope = rememberCoroutineScope()
    var freeTries by remember(config) { mutableStateOf(config.freeTries.toString()) }
    var baseWait by remember(config) { mutableStateOf(config.baseWaitSeconds.toString()) }
    var maxAttempts by remember(config) { mutableStateOf(config.maxAttempts.toString()) }
    var mode by remember(config) { mutableStateOf(config.lockoutMode) }
    var confirmWipe by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }

    Text(stringResource(R.string.lock_attempts_title), style = MaterialTheme.typography.titleSmall)
    Text(stringResource(R.string.lock_attempts_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            freeTries, { freeTries = it.filter(Char::isDigit).take(2); saved = false }, singleLine = true, modifier = Modifier.weight(1f),
            label = { Text(stringResource(R.string.lock_free_tries)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
        OutlinedTextField(
            baseWait, { baseWait = it.filter(Char::isDigit).take(4); saved = false }, singleLine = true, modifier = Modifier.weight(1f),
            label = { Text(stringResource(R.string.lock_base_wait)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
    }
    OutlinedTextField(
        maxAttempts, { maxAttempts = it.filter(Char::isDigit).take(3); saved = false }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        label = { Text(stringResource(R.string.lock_max_attempts)) }, supportingText = { Text(stringResource(R.string.lock_max_attempts_hint)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
    if ((maxAttempts.toIntOrNull() ?: 0) > 0) {
        Text(stringResource(R.string.lock_lockout_mode_title), style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(mode == LockoutMode.RECOVERY_ONLY, { mode = LockoutMode.RECOVERY_ONLY; saved = false }, { Text(stringResource(R.string.lock_lockout_recovery)) })
            FilterChip(mode == LockoutMode.WIPE, { confirmWipe = true }, { Text(stringResource(R.string.lock_lockout_wipe)) })
        }
        if (mode == LockoutMode.WIPE) {
            Text(stringResource(R.string.lock_lockout_wipe_active_warning), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
    OutlinedButton(
        onClick = {
            scope.launch {
                lock.updateLockoutPolicy(
                    freeTries.toIntOrNull() ?: config.freeTries,
                    baseWait.toIntOrNull() ?: config.baseWaitSeconds,
                    maxAttempts.toIntOrNull() ?: 0,
                    mode,
                )
                saved = true
                onChanged()
            }
        },
        modifier = Modifier.fillMaxWidth(),
    ) { Text(stringResource(R.string.action_save)) }
    if (saved) Text(stringResource(R.string.lock_attempts_saved), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)

    if (confirmWipe) {
        WipeConfirmDialog(
            onConfirm = { mode = LockoutMode.WIPE; saved = false; confirmWipe = false },
            onDismiss = { confirmWipe = false },
        )
    }
}

/** Dangerous-action confirmation (§1.3): says exactly what happens, that it is irreversible, and needs a typed phrase, not just a tap. */
@Composable
private fun WipeConfirmDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    var typed by remember { mutableStateOf("") }
    val phrase = stringResource(R.string.lock_wipe_confirm_phrase)
    com.notebudget.app.core.ui.EditorDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.lock_wipe_confirm_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.lock_wipe_confirm_message), color = MaterialTheme.colorScheme.error)
                OutlinedTextField(
                    value = typed, onValueChange = { typed = it }, singleLine = true,
                    label = { Text(stringResource(R.string.lock_wipe_confirm_field, phrase)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = typed.trim().equals(phrase, ignoreCase = true)) {
                Text(stringResource(R.string.lock_wipe_confirm_button), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
