package com.notebudget.app.feature.lock

import android.app.Activity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import com.notebudget.app.core.ui.tip.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import com.notebudget.app.core.ui.tip.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.notebudget.app.core.ui.tip.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.notebudget.app.R
import com.notebudget.app.core.security.AppLockGate
import com.notebudget.app.core.security.BiometricAuth
import com.notebudget.app.domain.model.AppLockConfig
import com.notebudget.app.domain.model.LockMethod
import com.notebudget.app.domain.model.LockSetupError
import com.notebudget.app.domain.model.UnlockResult
import com.notebudget.app.domain.usecase.AppLockUseCases
import com.notebudget.app.domain.usecase.LockSetup
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun lockErrorText(error: LockSetupError): String = stringResource(
    when (error) {
        LockSetupError.INVALID_METHOD -> R.string.error_generic
        LockSetupError.SECRET_LENGTH -> R.string.lock_error_length
        LockSetupError.LOGIN_INVALID -> R.string.lock_error_login
        LockSetupError.PATTERN_INVALID -> R.string.lock_pattern_short
        LockSetupError.ANSWER_BLANK -> R.string.lock_error_answer
        LockSetupError.RECOVERY_NOT_SET -> R.string.lock_no_recovery
        LockSetupError.CODE_EXPIRED -> R.string.lock_code_expired
    },
)

/**
 * The screen that covers the app while it is locked (§6.1, D124): the password, the pattern or the login and password, the
 * fingerprint / face when chosen (never alone), a pause after too many wrong tries, and "I forgot it" when a security
 * question was set. Behind it nothing of the app is shown.
 */
@Composable
fun LockScreen(gate: AppLockGate, lock: AppLockUseCases, activity: Activity?) {
    val context = LocalContext.current
    val resources = androidx.compose.ui.platform.LocalResources.current
    val scope = rememberCoroutineScope()
    var config by remember { mutableStateOf<AppLockConfig?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var blockedUntil by remember { mutableLongStateOf(0L) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var forgot by remember { mutableStateOf(false) }
    val wrongText = stringResource(R.string.lock_wrong)
    val lockedOutText = stringResource(R.string.lock_locked_out)
    val wipedText = stringResource(R.string.lock_wiped)

    LaunchedEffect(Unit) { config = lock.config(); blockedUntil = lock.waitingUntil() }
    // The clock of the pause.
    LaunchedEffect(blockedUntil) {
        while (blockedUntil > System.currentTimeMillis()) {
            now = System.currentTimeMillis()
            delay(500)
        }
        now = System.currentTimeMillis()
    }

    val biometricTitle = stringResource(R.string.lock_biometric_title)
    val cancelText = stringResource(R.string.action_cancel)
    val biometric = config?.biometric == true && activity != null && remember { BiometricAuth.isAvailable(context) }
    fun askBiometric() {
        activity?.let { BiometricAuth.prompt(it, biometricTitle, cancelText) { gate.unlocked() } }
    }
    LaunchedEffect(config?.biometric) { if (biometric) askBiometric() }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        val c = config
        if (c != null) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(stringResource(R.string.lock_title), style = MaterialTheme.typography.headlineMedium)
                val waiting = blockedUntil > now
                if (waiting) {
                    Text(stringResource(R.string.lock_blocked, ((blockedUntil - now + 999) / 1000).toInt()), color = MaterialTheme.colorScheme.error)
                }
                SecretForm(
                    method = c.method,
                    creating = false,
                    submitLabel = stringResource(R.string.lock_unlock),
                    message = message,
                    enabled = !waiting,
                    pinLength = c.pinLength,
                    onSubmit = { secret, login ->
                        scope.launch {
                            when (val result = lock.verify(secret, login)) {
                                UnlockResult.Success -> gate.unlocked()
                                is UnlockResult.Wrong -> message = wrongText + " " + resources.getQuantityString(R.plurals.lock_tries_left, result.triesBeforeDelay, result.triesBeforeDelay)
                                is UnlockResult.Blocked -> { blockedUntil = result.untilMillis; now = System.currentTimeMillis(); message = wrongText }
                                UnlockResult.LockedOut -> message = lockedOutText
                                UnlockResult.Wiped -> message = wipedText
                            }
                        }
                    },
                )
                if (biometric) OutlinedButton(onClick = ::askBiometric) { Text(stringResource(R.string.lock_use_biometric)) }
                TextButton(onClick = { forgot = true }) { Text(stringResource(R.string.lock_forgot)) }
            }
            if (forgot) RecoveryDialog(c, lock, onDone = { forgot = false; gate.unlocked() }, onDismiss = { forgot = false })
        }
    }
}
