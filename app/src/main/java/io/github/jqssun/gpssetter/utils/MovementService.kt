package io.github.jqssun.gpssetter.utils

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import io.github.jqssun.gpssetter.R
import io.github.jqssun.gpssetter.api.RoutePoint
import io.github.jqssun.gpssetter.ui.MapActivity
import kotlinx.coroutines.*
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class MovementService : Service() {

    private val binder = LocalBinder()
    private var simulationJob: Job? = null
    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private var routePoints: List<RoutePoint> = emptyList()
    private var currentPointIndex = 0
    private var isPaused = false
    private var totalDistance = 0.0
    private var traveledDistance = 0.0

    // Настройки симуляции
    private val updateIntervalMs = 500L // Обновление каждые 500мс
    private val baseSpeedKmh = 40.0 // Базовая скорость 40 км/ч
    private val maxSpeedKmh = 80.0 // Максимальная скорость 80 км/ч
    private val accelerationMps2 = 2.0 // Ускорение 2 м/с²
    private val decelerationMps2 = 3.0 // Замедление 3 м/с²

    inner class LocalBinder : Binder() {
        fun getService(): MovementService = this@MovementService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val routeJson = intent?.getStringExtra("ROUTE_POINTS")
        val action = intent?.action

        when (action) {
            "PAUSE" -> pauseSimulation()
            "RESUME" -> resumeSimulation()
            "STOP" -> stopSimulation()
            else -> {
                if (routeJson != null) {
                    val type = object : TypeToken<List<RoutePoint>>() {}.type
                    routePoints = Gson().fromJson(routeJson, type)
                    calculateTotalDistance()
                    startSimulation()
                }
            }
        }

        return START_STICKY
    }

    private fun calculateTotalDistance() {
        totalDistance = 0.0
        for (i in 0 until routePoints.size - 1) {
            val start = routePoints[i]
            val end = routePoints[i + 1]
            val startLoc = Location("").apply { latitude = start.lat; longitude = start.lon }
            val endLoc = Location("").apply { latitude = end.lat; longitude = end.lon }
            totalDistance += startLoc.distanceTo(endLoc)
        }
        println("MovementService: Total route distance: ${totalDistance.toInt()} meters")
    }

    private fun startSimulation() {
        simulationJob?.cancel()
        currentPointIndex = 0
        traveledDistance = 0.0
        isPaused = false

        simulationJob = serviceScope.launch {
            if (routePoints.size < 2) return@launch

            // Запускаем уведомление
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) { // API 34+
                startForeground(1, createNotification("Starting GPS simulation..."),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            } else {
                startForeground(1, createNotification("Starting GPS simulation..."))
            }

            // Начинаем с первой точки
            val startPoint = routePoints.first()
            println("MovementService: Starting point: lat=${startPoint.lat}, lon=${startPoint.lon}")
            PrefManager.update(true, startPoint.lat, startPoint.lon, PrefManager.getAltitude,
                speed = 0.0f, bearing = 0.0f)

            println("MovementService: Starting simulation with ${routePoints.size} points")
            println("MovementService: Route points:")
            routePoints.forEachIndexed { index, point ->
                println("MovementService: Point $index: lat=${point.lat}, lon=${point.lon}")
            }

            // Основной цикл симуляции
            while (currentPointIndex < routePoints.size - 1 && isActive) {
                if (isPaused) {
                    updateNotification("GPS simulation paused")
                    while (isPaused && isActive) {
                        delay(100)
                    }
                    updateNotification("GPS simulation resumed")
                }

                val currentPoint = routePoints[currentPointIndex]
                val nextPoint = routePoints[currentPointIndex + 1]

                // Симулируем движение между двумя точками
                simulateSegment(currentPoint, nextPoint)

                currentPointIndex++
            }

            // Завершаем симуляцию
            val lastPoint = routePoints.last()
            PrefManager.update(false, lastPoint.lat, lastPoint.lon, PrefManager.getAltitude,
                speed = 0.0f, bearing = 0.0f)

            updateNotification("GPS simulation completed")
            delay(2000) // Показываем уведомление 2 секунды
            stopSelf()
        }
    }

    private suspend fun simulateSegment(startPoint: RoutePoint, endPoint: RoutePoint) {
        val startLocation = Location("").apply {
            latitude = startPoint.lat
            longitude = startPoint.lon
        }
        val endLocation = Location("").apply {
            latitude = endPoint.lat
            longitude = endPoint.lon
        }

        val segmentDistance = startLocation.distanceTo(endLocation)
        val bearing = startLocation.bearingTo(endLocation)

        // Определяем скорость в зависимости от типа дороги (упрощенная логика)
        val speedKmh = when {
            segmentDistance > 2000 -> maxSpeedKmh // Трасса
            segmentDistance > 500 -> baseSpeedKmh // Обычная дорога
            else -> baseSpeedKmh * 0.7 // Город
        }

        val speedMps = speedKmh / 3.6 // Конвертируем в м/с
        val segmentDurationMs = (segmentDistance / speedMps * 1000).toLong()

        println("MovementService: Segment ${currentPointIndex + 1}/${routePoints.size - 1}: " +
                "distance=${segmentDistance.toInt()}m, speed=${speedKmh.toInt()}km/h, duration=${segmentDurationMs/1000}s")

        val steps = max(1, (segmentDurationMs / updateIntervalMs).toInt())
        val stepDistance = segmentDistance / steps

        for (step in 0..steps) {
            if (isPaused) break

            val fraction = step.toDouble() / steps
            val currentLat = startPoint.lat + (endPoint.lat - startPoint.lat) * fraction
            val currentLon = startPoint.lon + (endPoint.lon - startPoint.lon) * fraction

            // Добавляем небольшое отклонение для реалистичности
            val noise = 0.00001 // ~1 метр
            val noisyLat = currentLat + (Math.random() - 0.5) * noise
            val noisyLon = currentLon + (Math.random() - 0.5) * noise

            // Обновляем текущую скорость (с небольшими вариациями)
            val currentSpeed = speedMps + (Math.random() - 0.5) * 2.0

            println("MovementService: Updating GPS to: lat=$noisyLat, lon=$noisyLon, speed=$currentSpeed, bearing=$bearing")
            PrefManager.update(
                start = true,
                la = noisyLat,
                ln = noisyLon,
                alt = PrefManager.getAltitude,
                speed = currentSpeed.toFloat(),
                bearing = bearing
            )

            traveledDistance += stepDistance

            // Обновляем уведомление каждые 5 секунд
            if (step % (5000 / updateIntervalMs) == 0L) {
                val progressPercent = (traveledDistance / totalDistance * 100).toInt()
                updateNotification("GPS simulation: $progressPercent% complete")
            }

            delay(updateIntervalMs)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "gps_simulation",
                "GPS Simulation",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "GPS movement simulation notifications"
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(text: String): android.app.Notification {
        val intent = Intent(this, MapActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }

        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val pauseIntent = Intent(this, MovementService::class.java).apply {
            action = "PAUSE"
        }
        val pausePendingIntent = PendingIntent.getService(
            this, 1, pauseIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, MovementService::class.java).apply {
            action = "STOP"
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 2, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, "gps_simulation")
            .setContentTitle("GPS Setter - Route Simulation")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_play)
            .setContentIntent(pendingIntent)
            .addAction(R.drawable.ic_stop, "Stop", stopPendingIntent)
            .addAction(R.drawable.ic_play, "Pause", pausePendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val notification = createNotification(text)
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(1, notification)
    }

    private fun pauseSimulation() {
        isPaused = true
        println("MovementService: Simulation paused")
    }

    private fun resumeSimulation() {
        isPaused = false
        println("MovementService: Simulation resumed")
    }

    private fun stopSimulation() {
        simulationJob?.cancel()
        PrefManager.update(false, PrefManager.getLat, PrefManager.getLng, PrefManager.getAltitude,
            speed = 0.0f, bearing = 0.0f)
        stopSelf()
        println("MovementService: Simulation stopped")
    }

    // Public methods for external control
    fun pause() = pauseSimulation()
    fun resume() = resumeSimulation()
    fun stop() = stopSimulation()
    fun isRunning() = simulationJob?.isActive == true
    fun isSimulationPaused() = isPaused

    override fun onDestroy() {
        simulationJob?.cancel()
        super.onDestroy()
    }
}