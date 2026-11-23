package uk.co.agentengines.circlealert

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

internal class OneShotLocation(private val context: Context) {
    private val manager = context.getSystemService(LocationManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var listener: LocationListener? = null
    private var timeout: Runnable? = null

    private fun allowed(permission: String) = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    fun isAllowed() = allowed(Manifest.permission.ACCESS_FINE_LOCATION) || allowed(Manifest.permission.ACCESS_COARSE_LOCATION)

    @SuppressLint("MissingPermission")
    fun capture(callback: (Fix) -> Unit) {
        cancel()
        if (!isAllowed()) { callback(Fix.Missing(permissionDenied)); return }
        if (manager == null) {
            callback(Fix.Missing("unavailable"))
            return
        }
        val started = SystemClock.elapsedRealtimeNanos()
        val providers = manager.getProviders(true).filter {
            (it == LocationManager.GPS_PROVIDER && allowed(Manifest.permission.ACCESS_FINE_LOCATION)) ||
                it == LocationManager.NETWORK_PROVIDER
        }
        val unavailable = Fix.Missing("unavailable")
        if (providers.isEmpty()) { callback(unavailable); return }
        val observer = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (listener !== this || location.elapsedRealtimeNanos < started) return
                cancel()
                callback(Fix.Position(location.latitude, location.longitude, location.accuracy, location.time))
            }
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
            @Deprecated("Required on Android 8 and 9")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        }
        listener = observer
        var registered = false
        for (provider in providers) {
            try {
                manager.requestLocationUpdates(provider, 0L, 0f, observer, Looper.getMainLooper())
                registered = true
            } catch (_: SecurityException) {
                // Approximate-only permission can exclude a provider.
            } catch (_: IllegalArgumentException) {
                // The provider can disappear between discovery and registration.
            }
        }
        if (!registered) {
            cancel()
            callback(if (isAllowed()) unavailable else Fix.Missing(permissionDenied))
            return
        }
        timeout = Runnable {
            if (listener === observer) {
                cancel()
                callback(Fix.Missing("timeout"))
            }
        }.also { main.postDelayed(it, 10_000) }
    }

    fun cancel() {
        timeout?.let(main::removeCallbacks)
        timeout = null
        listener?.let { try { manager?.removeUpdates(it) } catch (_: SecurityException) {} }
        listener = null
    }
}
