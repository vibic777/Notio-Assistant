package com.notebudget.app.feature.media

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import com.notebudget.app.domain.model.GeoPoint
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Where the phone is now (D148), for the "place" of a photo or a recording. No Google services: the system location manager,
 * a fresh fix (up to 4 seconds) or else the last known one. `null` without the permission or when there is no position at all.
 */
object LocationProvider {
    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    suspend fun current(context: Context): GeoPoint? {
        if (!hasPermission(context)) return null
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .filter { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            for (provider in providers.filter { it != LocationManager.PASSIVE_PROVIDER }) {
                val fresh = withTimeoutOrNull(4_000) {
                    suspendCancellableCoroutine<Location?> { continuation ->
                        val signal = CancellationSignal()
                        continuation.invokeOnCancellation { signal.cancel() }
                        runCatching {
                            manager.getCurrentLocation(provider, signal, ContextCompat.getMainExecutor(context)) { continuation.resume(it) }
                        }.onFailure { if (continuation.isActive) continuation.resume(null) }
                    }
                }
                if (fresh != null) return GeoPoint(fresh.latitude, fresh.longitude)
            }
        }
        val last = providers.mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
        return last?.let { GeoPoint(it.latitude, it.longitude) }
    }
}
