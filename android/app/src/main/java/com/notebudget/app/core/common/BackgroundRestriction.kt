package com.notebudget.app.core.common

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/**
 * Whether Android lets Notio work in the background (D249). On a phone where the battery settings put Notio in "Restricted" (on a
 * Samsung also "Sleeping apps"), a recording stops as soon as the person leaves the app: the screen capture of the user's Samsung
 * ended half a second after it began, whatever the app did.
 */
object BackgroundRestriction {

    fun isRestricted(context: Context): Boolean =
        runCatching { context.getSystemService(ActivityManager::class.java)?.isBackgroundRestricted == true }.getOrDefault(false)

    /** The page of the app in the phone's settings, where Battery -> Unrestricted is chosen. */
    fun openSettings(context: Context) {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    /** A capture that Android ended within this time after its start while the app was restricted is explained by it. */
    const val QUICK_STOP_MILLIS = 5_000L

    /** Is the restriction the likely reason Android ended a recording [sinceStart] ms after it began? */
    fun explainsStop(sinceStart: Long, restricted: Boolean): Boolean = restricted && sinceStart in 0..QUICK_STOP_MILLIS
}
