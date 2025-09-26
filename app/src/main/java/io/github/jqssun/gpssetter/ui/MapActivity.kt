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
        if (!mMarker?.isVisible!!) {
            return true
        }
        return false
    }
    private fun updateMarker(it: LatLng) {
        mMarker?.position = it!!
        mMarker?.isVisible = true
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
    override fun onMapReady(googleMap: GoogleMap) {
        mMap = googleMap
        startMarker = mMap.addMarker(
            MarkerOptions().position(LatLng(0.0, 0.0)).visible(false)
                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN)).title("Start")
        )
        endMarker = mMap.addMarker(
            MarkerOptions().position(LatLng(0.0, 0.0)).visible(false)
                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED)).title("End")
        )
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
            mapType = viewModel.mapType

            val zoom = 12.0f
            lat = viewModel.getLat
            lon  = viewModel.getLng
            mLatLng = LatLng(lat, lon)
            mLatLng.let {
                mMarker = addMarker(
                    MarkerOptions().position(it!!).draggable(false).icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED)).visible(false)
                )
                mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(it, zoom))
            }

            setOnMapClickListener(this@MapActivity)
            if (viewModel.isStarted){
                mMarker?.let {}
            }
        }
    }
    override fun onMapClick(latLng: LatLng) {
        when (routeState) {
            RouteState.IDLE -> {
                mLatLng = latLng
                mMarker?.let { marker ->
                    updateMarker(latLng)
                    mMap.animateCamera(CameraUpdateFactory.newLatLng(latLng))
                    lat = latLng.latitude
                    lon = latLng.longitude
                    viewModel.fetchAltitude(lat, lon)
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
                showToast("End point selected. Building route...")
                viewModel.findRoute(
                    RoutePoint(startPoint!!.latitude, startPoint!!.longitude),
                    RoutePoint(endPoint!!.latitude, endPoint!!.longitude)
                )
                routeState = RouteState.IDLE
            }
        }
    }

    override fun getActivityInstance(): BaseMapActivity {
        return this@MapActivity
    }

    @SuppressLint("MissingPermission")
    override fun setupButtons(){
        viewModel.altitude.observe(this) { altitude ->
            currentAltitude = altitude
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
                routePolyline?.remove()
                if (points != null) {
                    if (points.isNotEmpty()) {
                        val polylineOptions = PolylineOptions().width(10f).color(Color.BLUE).geodesic(true)
                        // points - это уже List<LatLng>, добавляем его напрямую
                        polylineOptions.addAll(points)
                        routePolyline = mMap.addPolyline(polylineOptions)

                        val bounds = LatLngBounds.builder()
                        points.forEach { point -> bounds.include(point) }
                        mMap.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds.build(), 100))
                    } else {
                        showToast("Could not find a route.")
                    }
                }
            }
        }

        binding.addfavorite.setOnClickListener {
            addFavoriteDialog()
        }
        binding.getlocation.setOnClickListener {
            getLastLocation()
        }

        if (viewModel.isStarted) {
            binding.startButton.visibility = View.GONE
            binding.stopButton.visibility = View.VISIBLE
        }

        binding.startButton.setOnClickListener {
            val currentRoute = viewModel.route.value
            if (currentRoute != null && currentRoute.isNotEmpty()) {
                // Конвертируем List<LatLng> в List<RoutePoint> для сериализации
                val routePoints = currentRoute.map { RoutePoint(it.latitude, it.longitude) }
                val intent = Intent(this, MovementService::class.java).apply {
                    putExtra("ROUTE_POINTS", Gson().toJson(routePoints))
                }
                startService(intent)
                showToast("Route simulation started!")
            } else {
                viewModel.update(true, lat, lon, currentAltitude)
                mLatLng?.let { latLng -> updateMarker(latLng) }
                lifecycleScope.launch {
                    mLatLng?.getAddress(getActivityInstance())?.let { address ->
                        address.collect{ value ->
                            showStartNotification(value)
                        }
                    }
                }
                showToast(getString(R.string.location_set))
            }
            binding.startButton.visibility = View.GONE
            binding.stopButton.visibility = View.VISIBLE
        }
        binding.stopButton.setOnClickListener {
            stopService(Intent(this, MovementService::class.java))
            mLatLng.let {
                viewModel.update(false, it!!.latitude, it.longitude, currentAltitude)
            }
            removeMarker()
            binding.stopButton.visibility = View.GONE
            binding.startButton.visibility = View.VISIBLE
            cancelNotification()
            showToast(getString(R.string.location_unset))
        }
    }
}