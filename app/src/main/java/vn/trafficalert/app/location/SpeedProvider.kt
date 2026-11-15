package vn.trafficalert.app.location

import android.annotation.SuppressLint
import android.content.Context
import android.os.Looper
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

/** Đo tốc độ xe từ GPS, mỗi giây một lần. */
class SpeedProvider(context: Context, private val onSpeedKmh: (Float) -> Unit) {

    private val client = LocationServices.getFusedLocationProviderClient(context)
    private var started = false

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val loc = result.lastLocation ?: return
            if (loc.hasSpeed()) onSpeedKmh(loc.speed * 3.6f)
        }
    }

    /** Chỉ gọi sau khi đã có quyền vị trí. */
    @SuppressLint("MissingPermission")
    fun start() {
        if (started) return
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
            .setMinUpdateIntervalMillis(500L)
            .build()
        client.requestLocationUpdates(request, callback, Looper.getMainLooper())
        started = true
    }

    fun stop() {
        if (!started) return
        client.removeLocationUpdates(callback)
        started = false
    }
}
