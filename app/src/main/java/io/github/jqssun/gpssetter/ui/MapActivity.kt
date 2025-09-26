package io.github.jqssun.gpssetter.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.view.View
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.*
import com.google.gson.Gson
import io.github.jqssun.gpssetter.R
import io.github.jqssun.gpssetter.api.RoutePoint
import io.github.jqssun.gpssetter.utils.MovementService
import io.github.jqssun.gpssetter.utils.ext.getAddress
import io.github.jqssun.gpssetter.utils.ext.showToast
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

typealias CustomLatLng = LatLng

class MapActivity: BaseMapActivity(), OnMapReadyCallback, GoogleMap.OnMapClickListener {

    private lateinit var mMap: GoogleMap
    private var mLatLng: LatLng? = null
    private var mMarker: Marker? = null
    private var currentAltitude: Float = 0.0F

    private enum class RouteState { IDLE, SETTING_START, SETTING_END }
    private var routeState = RouteState.IDLE
    private var startPoint: LatLng? = null
    private var endPoint: LatLng? = null
    private var startMarker: Marker? = null
    private var endMarker: Marker? = null
    private var routePolyline: Polyline? = null

    override fun hasMarker(): Boolean {
        return mMarker?.isVisible ?: false
    }
    private fun updateMarker(it: LatLng) {
        mMarker?.position = it!!
        mMarker?.isVisible = true
        println("MapActivity: Marker updated to position: ${it.latitude}, ${it.longitude}, visible: ${mMarker?.isVisible}")
    }
    private fun removeMarker() {
        mMarker?.isVisible = false
    }
    override fun initializeMap() {
        val mapFragment = SupportMapFragment.newInstance()
        supportFragmentManager.beginTransaction()
            .replace(R.id.map, mapFragment)
            .commit()
        mapFragment?.getMapAsync(this)
    }
    override fun moveMapToNewLocation(moveNewLocation: Boolean) {
        if (moveNewLocation) {
            mLatLng = LatLng(lat, lon)
            mLatLng.let { latLng ->
                mMap.animateCamera(CameraUpdateFactory.newCameraPosition(
                    CameraPosition.Builder()
                        .target(latLng!!)
                        .zoom(12.0f)
                        .bearing(0f)
                        .tilt(0f)
                        .build()
                ))
                mMarker?.apply {
                    position = latLng
                    isVisible = true
                    showInfoWindow()
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Привязываемся к сервису если он работает
        if (isMovementServiceRunning()) {
            getMovementService()
        }
    }

    override fun onStop() {
        super.onStop()
        // Отвязываемся от сервиса
        try {
            unbindService(serviceConnection)
        } catch (e: Exception) {
            // Сервис мог быть не привязан
        }
        movementService = null
    }

    override fun onResume() {
        super.onResume()
        updateSimulationButtons()
    }

    override fun onMapReady(googleMap: GoogleMap) {
        mMap = googleMap
        println("MapActivity: Map ready, initializing markers and settings")

        startMarker = mMap.addMarker(
            MarkerOptions().position(LatLng(0.0, 0.0)).visible(false)
                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN)).title("Start")
        )
        endMarker = mMap.addMarker(
            MarkerOptions().position(LatLng(0.0, 0.0)).visible(false)
                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED)).title("End")
        )

        println("MapActivity: Markers created - start: ${startMarker?.id}, end: ${endMarker?.id}")

        with(mMap){
            if (ActivityCompat.checkSelfPermission(this@MapActivity, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                isMyLocationEnabled = true
            } else {
                ActivityCompat.requestPermissions(this@MapActivity, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 99);
            }
            isTrafficEnabled = true
            uiSettings.isMyLocationButtonEnabled = false
            uiSettings.isZoomControlsEnabled = false
            uiSettings.isCompassEnabled = false
            setPadding(0,80,0,0)
            mapType = when (viewModel.mapType) {
                1 -> GoogleMap.MAP_TYPE_NORMAL
                2 -> GoogleMap.MAP_TYPE_SATELLITE
                3 -> GoogleMap.MAP_TYPE_TERRAIN
                4 -> GoogleMap.MAP_TYPE_HYBRID
                else -> GoogleMap.MAP_TYPE_NORMAL
            }

            println("MapActivity: Map type set to: ${mapType}")

            val zoom = 12.0f
            lat = viewModel.getLat
            lon  = viewModel.getLng
            mLatLng = LatLng(lat, lon)
            mLatLng.let {
                println("MapActivity: Creating main marker at: $lat, $lon, isStarted: ${viewModel.isStarted}")
                mMarker = addMarker(
                    MarkerOptions().position(it!!).draggable(false).icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED)).visible(viewModel.isStarted)
                )
                println("MapActivity: Main marker created, visible: ${mMarker?.isVisible}")
                mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(it, zoom))
            }

            setOnMapClickListener(this@MapActivity)
            if (viewModel.isStarted){
                mMarker?.let {
                    println("MapActivity: GPS is started, making marker visible")
                    mMarker?.isVisible = true
                }
            }
        }
    }
    override fun onMapClick(latLng: LatLng) {
        println("MapActivity: Map clicked at: ${latLng.latitude}, ${latLng.longitude}")
        when (routeState) {
            RouteState.IDLE -> {
                println("MapActivity: Setting fixed GPS spoofing location")
                mLatLng = latLng
                mMarker?.let { marker ->
                    println("MapActivity: Marker exists, updating position and starting GPS spoof")
                    updateMarker(latLng)
                    mMap.animateCamera(CameraUpdateFactory.newLatLng(latLng))
                    lat = latLng.latitude
                    lon = latLng.longitude
                    println("MapActivity: Coordinates set to: $lat, $lon")

                    // Ставим на паузу любую активную симуляцию маршрута при установке фиксированной точки
                    if (isMovementServiceRunning()) {
                        getMovementService()?.pause()
                        showToast("Route simulation paused for fixed GPS spoofing")
                    }

                    // Сначала получаем высоту, потом запускаем спуфинг
                    viewModel.fetchAltitude(lat, lon)

                    // Запускаем GPS-спуфинг с текущей высотой (может быть старая, но обновится)
                    viewModel.update(true, lat, lon, currentAltitude)

                    lifecycleScope.launch {
                        mLatLng?.getAddress(getActivityInstance())?.let { address ->
                            address.collect{ value ->
                                showStartNotification(value)
                            }
                        }
                    }
                    showToast("GPS spoofed to selected location")
                    updateSimulationButtons()
                } ?: run {
                    println("MapActivity: ERROR - Marker is null!")
                }
            }
            RouteState.SETTING_START -> {
                startPoint = latLng
                startMarker?.position = latLng
                startMarker?.isVisible = true
                showToast("Start point selected. Now select end point.")
                routeState = RouteState.SETTING_END
            }
            RouteState.SETTING_END -> {
                endPoint = latLng
                endMarker?.position = latLng
                endMarker?.isVisible = true

                // Проверяем минимальное расстояние между точками
                val start = startPoint!!
                val end = endPoint!!
                val distance = FloatArray(1)
                android.location.Location.distanceBetween(
                    start.latitude, start.longitude,
                    end.latitude, end.longitude,
                    distance
                )

                if (distance[0] < 100) { // Минимум 100 метров
                    showToast("Points too close. Please select points at least 100m apart.")
                    routeState = RouteState.SETTING_START
                } else {
                    showToast("End point selected. Building route...")
                    viewModel.findRoute(
                        RoutePoint(start.latitude, start.longitude),
                        RoutePoint(end.latitude, end.longitude)
                    )
                    routeState = RouteState.IDLE
                }
            }
        }
    }

    private var movementService: MovementService? = null
    private var serviceConnection = object : android.content.ServiceConnection {
        override fun onServiceConnected(name: android.content.ComponentName?, service: android.os.IBinder?) {
            val binder = service as? MovementService.LocalBinder
            movementService = binder?.getService()
            println("MapActivity: MovementService connected")
        }

        override fun onServiceDisconnected(name: android.content.ComponentName?) {
            movementService = null
            println("MapActivity: MovementService disconnected")
        }
    }

    private fun getMovementService(): MovementService? {
        if (movementService == null) {
            try {
                val intent = Intent(this, MovementService::class.java)
                bindService(intent, serviceConnection, BIND_AUTO_CREATE)
            } catch (e: Exception) {
                println("MapActivity: Failed to bind MovementService: ${e.message}")
            }
        }
        return movementService
    }

    private fun updateSimulationButtons() {
        val isSimulatingRoute = isMovementServiceRunning()
        val isPaused = getMovementService()?.isSimulationPaused() ?: false
        val hasRoute = viewModel.route.value?.isNotEmpty() == true
        val isSpoofingFixed = viewModel.isStarted && !isSimulatingRoute

        // Обновляем статус
        binding.statusChip.apply {
            when {
                isSimulatingRoute && isPaused -> {
                    text = "Симуляция на паузе"
                    setChipIconResource(R.drawable.ic_stop)
                    setChipBackgroundColorResource(R.color.colorWarning)
                }
                isSimulatingRoute -> {
                    text = "Симуляция активна"
                    setChipIconResource(R.drawable.ic_play)
                    setChipBackgroundColorResource(R.color.colorSuccess)
                }
                isSpoofingFixed -> {
                    text = "GPS спуфинг активен"
                    setChipIconResource(R.drawable.ic_baseline_my_location_24)
                    setChipBackgroundColorResource(R.color.colorSuccess)
                }
                else -> {
                    text = "Готов к работе"
                    setChipIconResource(R.drawable.ic_baseline_my_location_24)
                    setChipBackgroundColorResource(R.color.primary)
                }
            }
        }

        if (isSimulatingRoute) {
            // Во время симуляции маршрута показываем кнопки паузы и остановки
            binding.startButton.visibility = View.GONE
            binding.pauseResumeButton.visibility = View.VISIBLE
            binding.stopButton.visibility = View.VISIBLE

            // Обновляем иконку кнопки паузы/возобновления
            binding.pauseResumeButton.setIconResource(
                if (isPaused) R.drawable.ic_play else R.drawable.ic_stop
            )
            binding.pauseResumeButton.contentDescription =
                if (isPaused) "Возобновить симуляцию" else "Пауза симуляции"
        } else {
            // В режиме ожидания показываем кнопку старта
            binding.startButton.visibility = View.VISIBLE
            binding.pauseResumeButton.visibility = View.GONE
            binding.stopButton.visibility = View.GONE

            // Меняем текст и иконку кнопки старта в зависимости от состояния
            if (isSpoofingFixed) {
                binding.startButton.setIconResource(R.drawable.ic_stop)
                binding.startButton.contentDescription = "Остановить GPS спуфинг"
            } else {
                binding.startButton.setIconResource(R.drawable.ic_play)
                binding.startButton.contentDescription = "Начать GPS спуфинг"
            }
        }
    }

    private fun isMovementServiceRunning(): Boolean {
        val manager = getSystemService(android.app.Activity.ACTIVITY_SERVICE) as android.app.ActivityManager
        for (service in manager.getRunningServices(Integer.MAX_VALUE)) {
            if (MovementService::class.java.name == service.service.className) {
                return true
            }
        }
        return false
    }

    override fun getActivityInstance(): BaseMapActivity {
        return this@MapActivity
    }

    @SuppressLint("MissingPermission")
    override fun setupButtons(){
        viewModel.altitude.observe(this) { altitude ->
            currentAltitude = altitude
            // Автоматически обновляем высоту в PrefManager, если GPS уже спуфится
            if (viewModel.isStarted && mLatLng != null) {
                viewModel.update(true, mLatLng!!.latitude, mLatLng!!.longitude, altitude)
                println("MapActivity: Altitude updated to: ${altitude}m")
            }
            showToast("Altitude: %.2f m".format(altitude))
        }

        binding.routeButton.setOnClickListener {
            if (routeState == RouteState.IDLE) {
                routeState = RouteState.SETTING_START
                startMarker?.isVisible = false
                endMarker?.isVisible = false
                routePolyline?.remove()
                viewModel.clearRoute()
                showToast("Select start point on the map")
            } else {
                routeState = RouteState.IDLE
                showToast("Route mode cancelled")
            }
        }

        lifecycleScope.launch {
            viewModel.route.collectLatest { points ->
                println("MapActivity: Route updated with ${points?.size ?: 0} points")
                routePolyline?.remove()

                // Проверяем, что карта инициализирована
                if (::mMap.isInitialized) {
                    if (points != null) {
                        if (points.isNotEmpty()) {
                            println("MapActivity: Drawing route with ${points.size} points")

                            // Добавляем небольшую задержку для уверенности, что карта готова
                            kotlinx.coroutines.delay(100)

                            val polylineOptions = PolylineOptions()
                                .width(20f)
                                .color(Color.RED)
                                .geodesic(true)
                                .jointType(JointType.ROUND)
                                .startCap(RoundCap())
                                .endCap(RoundCap())

                            polylineOptions.addAll(points)
                            routePolyline = mMap.addPolyline(polylineOptions)
                            println("MapActivity: Polyline created with ${points.size} points, id: ${routePolyline?.id}")

                            // Добавляем маркеры начала и конца маршрута
                            startMarker?.position = points.first()
                            startMarker?.isVisible = true
                            endMarker?.position = points.last()
                            endMarker?.isVisible = true
                            println("MapActivity: Markers set - start: ${points.first()}, end: ${points.last()}")

                            // Центрируем карту на маршруте
                            val bounds = LatLngBounds.builder()
                            points.forEach { point -> bounds.include(point) }
                            mMap.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds.build(), 100))
                            showToast("Route drawn with ${points.size} points - RED LINE")
                        } else {
                            println("MapActivity: Route is empty")
                            showToast("Could not find a route.")
                        }
                    } else {
                        println("MapActivity: Route is null")
                    }
                } else {
                    println("MapActivity: Map not initialized yet, skipping route drawing")
                }
            }
        }

        binding.addfavorite.setOnClickListener {
            addFavoriteDialog()
        }
        binding.getlocation.setOnClickListener {
            // Останавливаем GPS-спуфинг и возвращаем реальное местоположение
            stopService(Intent(this, MovementService::class.java))
            mLatLng.let {
                viewModel.update(false, it?.latitude ?: lat, it?.longitude ?: lon, currentAltitude)
            }
            removeMarker()
            cancelNotification()
            showToast("GPS spoofing stopped, returning to real location")
            updateSimulationButtons()

            // Получаем реальное местоположение устройства
            getLastLocation()
        }

        // Инициализация состояния кнопок
        updateSimulationButtons()

        binding.startButton.setOnClickListener {
            val currentRoute = viewModel.route.value
            val isSimulatingRoute = isMovementServiceRunning()

            println("MapActivity: Start button clicked, route exists: ${currentRoute != null && currentRoute.isNotEmpty()}, simulating: $isSimulatingRoute")

            if (currentRoute != null && currentRoute.isNotEmpty()) {
                // Если есть маршрут - запускаем симуляцию движения
                val routePoints = currentRoute.map { RoutePoint(it.latitude, it.longitude) }
                val intent = Intent(this, MovementService::class.java).apply {
                    putExtra("ROUTE_POINTS", Gson().toJson(routePoints))
                }
                startService(intent)
                showToast("Route simulation started!")
            } else {
                // Если маршрута нет - проверяем, активен ли спуфинг
                if (viewModel.isStarted) {
                    // GPS-спуфинг активен - останавливаем его
                    println("MapActivity: Stopping GPS spoofing")
                    stopService(Intent(this, MovementService::class.java))
                    mLatLng.let {
                        viewModel.update(false, it?.latitude ?: lat, it?.longitude ?: lon, currentAltitude)
                    }
                    removeMarker()
                    cancelNotification()
                    showToast("GPS spoofing stopped (alt: ${currentAltitude}m)")
                } else {
                    // GPS-спуфинг не активен - устанавливаем фиксированную точку
                    if (mLatLng != null) {
                        println("MapActivity: Starting GPS spoofing at: $lat, $lon")
                        viewModel.update(true, lat, lon, currentAltitude)
                        mLatLng?.let { latLng ->
                            updateMarker(latLng)
                            println("MapActivity: Fixed location marker updated")
                        }

                        lifecycleScope.launch {
                            mLatLng?.getAddress(getActivityInstance())?.let { address ->
                                address.collect{ value ->
                                    showStartNotification(value)
                                    println("MapActivity: Address resolved: $value")
                                }
                            }
                        }
                        showToast("GPS spoofing started (alt: ${currentAltitude}m)")
                    } else {
                        showToast("Please select a location on the map first")
                    }
                }
            }
            updateSimulationButtons()
        }

        binding.pauseResumeButton.setOnClickListener {
            val movementService = getMovementService()
            val hasRoute = viewModel.route.value?.isNotEmpty() == true

            println("MapActivity: Pause/Resume clicked, service=$movementService, hasRoute=$hasRoute, isPaused=${movementService?.isSimulationPaused()}")

            if (movementService != null) {
                if (movementService.isSimulationPaused()) {
                    // Возобновляем симуляцию маршрута
                    println("MapActivity: Resuming route simulation")
                    movementService.resume()
                    showToast("Симуляция возобновлена")
                } else {
                    // Ставим симуляцию на паузу
                    println("MapActivity: Pausing route simulation")
                    movementService.pause()
                    showToast("Симуляция на паузе")
                }
            } else if (hasRoute) {
                // Если маршрута нет в памяти, но он был построен - запускаем симуляцию
                println("MapActivity: Starting new route simulation")
                val routePoints = viewModel.route.value?.map { RoutePoint(it.latitude, it.longitude) }
                if (routePoints != null) {
                    val intent = Intent(this, MovementService::class.java).apply {
                        putExtra("ROUTE_POINTS", Gson().toJson(routePoints))
                    }
                    startService(intent)
                    showToast("Симуляция маршрута начата")
                }
            } else {
                println("MapActivity: No route available for simulation")
                showToast("Сначала постройте маршрут")
            }
            updateSimulationButtons()
        }

        binding.stopButton.setOnClickListener {
            stopService(Intent(this, MovementService::class.java))
            mLatLng.let {
                viewModel.update(false, it?.latitude ?: lat, it?.longitude ?: lon, currentAltitude)
            }
            removeMarker()
            updateSimulationButtons()
            cancelNotification()
            showToast(getString(R.string.location_unset))
        }
    }
}