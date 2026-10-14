package com.notebudget.app.core.security

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal

/**
 * Fingerprint / face through the system's own prompt (§6.1). The phone keeps the biometric data; the app only hears
 * "yes" or "no" and stores nothing. It is only ever an addition to the password or pattern, which stay the fallback.
 */
object BiometricAuth {

    fun isAvailable(context: Context): Boolean = runCatching {
        when {
            Build.VERSION.SDK_INT >= 30 ->
                context.getSystemService(BiometricManager::class.java)
                    .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) == BiometricManager.BIOMETRIC_SUCCESS
            Build.VERSION.SDK_INT == 29 ->
                @Suppress("DEPRECATION") (context.getSystemService(BiometricManager::class.java).canAuthenticate() == BiometricManager.BIOMETRIC_SUCCESS)
            else -> context.packageManager.hasSystemFeature(PackageManager.FEATURE_FINGERPRINT)
        }
    }.getOrDefault(false)

    /** Shows the prompt; [onSuccess] runs when the person is recognised. Any other end just returns to the lock screen. */
    fun prompt(activity: Activity, title: String, cancel: String, onSuccess: () -> Unit) {
        runCatching {
            val executor = activity.mainExecutor
            val builder = BiometricPrompt.Builder(activity).setTitle(title)
                .setNegativeButton(cancel, executor) { _, _ -> }
            if (Build.VERSION.SDK_INT >= 30) builder.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK)
            builder.build().authenticate(
                CancellationSignal(),
                executor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) = onSuccess()
                },
            )
        }
    }
}
