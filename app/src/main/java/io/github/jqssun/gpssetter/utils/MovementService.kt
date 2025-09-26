package io.github.jqssun.gpssetter.utils

import android.app.Service
import android.content.Intent
import android.location.Location
import android.os.Binder
import android.os.IBinder
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import io.github.jqssun.gpssetter.api.RoutePoint
import kotlinx.coroutines.*

class MovementService : Service() {

    private val binder = LocalBinder()
    private var simulationJob: Job? = null
    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private var routePoints: List<RoutePoint> = emptyList()

    inner class LocalBinder : Binder() {
        fun getService(): MovementService = this@MovementService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val routeJson = intent?.getStringExtra("ROUTE_POINTS")
        if (routeJson != null) {
            val type = object : TypeToken<List<RoutePoint>>() {}.type
            routePoints = Gson().fromJson(routeJson, type)
            startSimulation()
        }
        return START_NOT_STICKY
    }

    private fun startSimulation() {
        simulationJob?.cancel()
        simulationJob = serviceScope.launch {
            if (routePoints.size < 2) return@launch

            val startPoint = routePoints.first()
            PrefManager.update(true, startPoint.lat, startPoint.lon, PrefManager.getAltitude)

            for (i in 0 until routePoints.size - 1) {
                if (!isActive) break

                val start = routePoints[i]
                val end = routePoints[i + 1]

                val startLocation = Location("").apply {
                    latitude = start.lat
                    longitude = start.lon
                }
                val endLocation = Location("").apply {
                    latitude = end.lat
                    longitude = end.lon
                }

                val distance = startLocation.distanceTo(endLocation)
                val bearing = startLocation.bearingTo(endLocation)
                val speed = 10.0f
                val durationSeconds = if (speed > 0) (distance / speed).toLong() else 0

                if (durationSeconds > 0) {
                    for (t in 0..durationSeconds) {
                        if (!isActive) break
                        val fraction = t.toFloat() / durationSeconds
                        val currentLat = start.lat + (end.lat - start.lat) * fraction
                        val currentLon = start.lon + (end.lon - start.lon) * fraction

                        PrefManager.update(
                            start = true,
                            la = currentLat,
                            ln = currentLon,
                            alt = PrefManager.getAltitude,
                            speed = speed,
                            bearing = bearing
                        )
                        delay(1000)
                    }
                }
            }

            val lastPoint = routePoints.last()
            PrefManager.update(false, lastPoint.lat, lastPoint.lon, PrefManager.getAltitude)
            stopSelf()
        }
    }

    override fun onDestroy() {
        simulationJob?.cancel()
        super.onDestroy()
    }
}