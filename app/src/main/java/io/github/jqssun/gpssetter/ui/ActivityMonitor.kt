package io.github.jqssun.gpssetter.ui

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.GnssStatus
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.net.wifi.rtt.RangingRequest
import android.net.wifi.rtt.RangingResult
import android.net.wifi.rtt.RangingResultCallback
import android.net.wifi.rtt.WifiRttManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.telephony.TelephonyManager
import android.view.MenuItem
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.google.android.gms.ads.identifier.AdvertisingIdClient
import com.google.android.gms.location.*
import io.github.jqssun.gpssetter.databinding.ActivityMonitorBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ActivityMonitor : AppCompatActivity(), SensorEventListener {

    private lateinit var binding: ActivityMonitorBinding

    // Location & GPS
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private lateinit var locationManager: LocationManager
    private lateinit var gnssStatusCallback: GnssStatus.Callback

    // Wi-Fi & RTT
    private lateinit var wifiManager: WifiManager
    private var wifiRttManager: WifiRttManager? = null
    private var rttSupported = false

    private val wifiScanReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val success = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false)
            } else {
                @Suppress("DEPRECATION")
                intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false)
            }
            if (success) {
                val results = displayWifiResults()
                if (rttSupported && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    startRttScan(results)
                }
            }
        }
    }

    // Bluetooth
    private lateinit var bluetoothAdapter: BluetoothAdapter
    private val bluetoothDiscoveryReceiver = object : BroadcastReceiver() {
        private val devices = mutableSetOf<String>()
        override fun onReceive(context: Context, intent: Intent) {
            when(intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    }

                    if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                        return
                    }
                    val deviceName = device?.name ?: "Unknown Device"
                    val deviceAddress = device?.address
                    devices.add("$deviceName ($deviceAddress)")
                    binding.tvBluetoothDevices.text = devices.joinToString("\n")
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    if (devices.isEmpty()) {
                        binding.tvBluetoothDevices.text = "No devices found"
                    }
                }
            }
        }
    }

    // Sensors
    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private var gyroscope: Sensor? = null
    private var magnetometer: Sensor? = null
    private var barometer: Sensor? = null
    private var gravity: Sensor? = null
    private var linearAcceleration: Sensor? = null
    private var rotationVector: Sensor? = null

    private val PERMISSIONS_REQUEST_CODE = 101

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMonitorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        // Initialize managers
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        barometer = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)
        gravity = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)
        linearAcceleration = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        rotationVector = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

        // Check RTT Support
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // ▼▼▼ ИСПРАВЛЕНИЕ ▼▼▼
            val rttManager = getSystemService(Context.WIFI_RTT_RANGING_SERVICE)
            if (rttManager is WifiRttManager) {
                wifiRttManager = rttManager
                rttSupported = packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_RTT)
                binding.tvWifiRttStatus.text = "Wi-Fi RTT (802.11mc): ${if(rttSupported) "Supported" else "Not Supported on this device"}"
            } else {
                binding.tvWifiRttStatus.text = "Wi-Fi RTT (802.11mc): Service not available"
                rttSupported = false
            }
            // ▲▲▲ КОНЕЦ ИСПРАВЛЕНИЯ ▲▲▲
        } else {
            binding.tvWifiRttStatus.text = "Wi-Fi RTT (802.11mc): Not Supported (SDK < 28)"
        }

        setupLocationCallback()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            setupGnssCallback()
        }
    }

    override fun onResume() {
        super.onResume()
        if (checkAllPermissions()) {
            startAllUpdates()
        } else {
            requestAllPermissions()
        }
    }

    override fun onPause() {
        super.onPause()
        stopAllUpdates()
    }

    @SuppressLint("MissingPermission")
    private fun startAllUpdates() {
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000).build()
        fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            locationManager.registerGnssStatusCallback(gnssStatusCallback, null)
        }
        val intentFilterWifi = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        registerReceiver(wifiScanReceiver, intentFilterWifi)
        wifiManager.startScan()
        binding.tvWifiNetworks.text = "Scanning for Wi-Fi..."
        val intentFilterBluetooth = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        registerReceiver(bluetoothDiscoveryReceiver, intentFilterBluetooth)
        if (bluetoothAdapter.isDiscovering) bluetoothAdapter.cancelDiscovery()
        bluetoothAdapter.startDiscovery()
        binding.tvBluetoothDevices.text = "Scanning for Bluetooth..."
        displayCellInfo()

        // Sensors
        accelerometer?.also { sensor ->
            sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        }
        gyroscope?.also { sensor ->
            sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        }
        magnetometer?.also { sensor ->
            sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        }
        barometer?.also { sensor ->
            sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        }
        gravity?.also { sensor ->
            sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        }
        linearAcceleration?.also { sensor ->
            sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        }
        rotationVector?.also { sensor ->
            sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        }

        displayDeviceState()
        displayIdentifiers()
        displaySystemIntegrity()
    }

    @SuppressLint("MissingPermission")
    private fun stopAllUpdates() {
        fusedLocationClient.removeLocationUpdates(locationCallback)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            locationManager.unregisterGnssStatusCallback(gnssStatusCallback)
        }
        unregisterReceiver(wifiScanReceiver)
        if (bluetoothAdapter.isDiscovering) bluetoothAdapter.cancelDiscovery()
        unregisterReceiver(bluetoothDiscoveryReceiver)
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return
        val values = event.values

        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                val valueStr = "%.2f, %.2f, %.2f".format(values.getOrElse(0) { 0f }, values.getOrElse(1) { 0f }, values.getOrElse(2) { 0f })
                binding.tvAccelerometer.text = "Accelerometer (X,Y,Z): $valueStr"
            }
            Sensor.TYPE_GYROSCOPE -> {
                val valueStr = "%.2f, %.2f, %.2f".format(values.getOrElse(0) { 0f }, values.getOrElse(1) { 0f }, values.getOrElse(2) { 0f })
                binding.tvGyroscope.text = "Gyroscope (X,Y,Z): $valueStr"
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                val valueStr = "%.2f, %.2f, %.2f".format(values.getOrElse(0) { 0f }, values.getOrElse(1) { 0f }, values.getOrElse(2) { 0f })
                binding.tvMagnetometer.text = "Magnetometer (X,Y,Z): $valueStr"
            }
            Sensor.TYPE_PRESSURE -> binding.tvBarometer.text = "Barometer (Pressure): %.2f hPa".format(values[0])
            Sensor.TYPE_GRAVITY -> {
                val valueStr = "%.2f, %.2f, %.2f".format(values.getOrElse(0) { 0f }, values.getOrElse(1) { 0f }, values.getOrElse(2) { 0f })
                binding.tvGravity.text = "Gravity (X,Y,Z): $valueStr"
            }
            Sensor.TYPE_LINEAR_ACCELERATION -> {
                val valueStr = "%.2f, %.2f, %.2f".format(values.getOrElse(0) { 0f }, values.getOrElse(1) { 0f }, values.getOrElse(2) { 0f })
                binding.tvLinearAcceleration.text = "Linear Acceleration (X,Y,Z): $valueStr"
            }
            Sensor.TYPE_ROTATION_VECTOR -> {
                val valueStr = "%.2f, %.2f, %.2f, %.2f".format(values.getOrElse(0) { 0f }, values.getOrElse(1) { 0f }, values.getOrElse(2) { 0f }, values.getOrElse(3) { 0f })
                binding.tvRotationVector.text = "Rotation Vector (x,y,z,cos): $valueStr"
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Not used for now
    }

    private fun setupLocationCallback() {
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                locationResult.lastLocation?.let { location ->
                    binding.tvProvider.text = "Provider: ${location.provider}"
                    binding.tvLatitude.text = "Latitude: ${location.latitude}"
                    binding.tvLongitude.text = "Longitude: ${location.longitude}"
                    binding.tvAltitude.text = "Altitude: ${location.altitude} m"
                    binding.tvAccuracy.text = "Accuracy: ${location.accuracy} m"
                    binding.tvSpeed.text = "Speed: ${location.speed} m/s"
                    binding.tvBearing.text = "Bearing: ${location.bearing} degrees"
                    binding.tvTime.text = "Time: ${SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(location.time))}"
                    binding.tvElapsedTime.text = "Elapsed Realtime: ${TimeUnit.NANOSECONDS.toSeconds(location.elapsedRealtimeNanos)} s"
                    binding.tvIsMock.text = "Is from Mock Provider: ${location.isFromMockProvider}"
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        binding.tvVerticalAccuracy.text = if (location.hasVerticalAccuracy()) "Vertical Accuracy: ${location.verticalAccuracyMeters} m" else "Vertical Accuracy: N/A"
                        binding.tvSpeedAccuracy.text = if (location.hasSpeedAccuracy()) "Speed Accuracy: ${location.speedAccuracyMetersPerSecond} m/s" else "Speed Accuracy: N/A"
                        binding.tvBearingAccuracy.text = if (location.hasBearingAccuracy()) "Bearing Accuracy: ${location.bearingAccuracyDegrees} degrees" else "Bearing Accuracy: N/A"
                    } else {
                        binding.tvVerticalAccuracy.text = "Vertical Accuracy: N/A (SDK < 26)"
                        binding.tvSpeedAccuracy.text = "Speed Accuracy: N/A (SDK < 26)"
                        binding.tvBearingAccuracy.text = "Bearing Accuracy: N/A (SDK < 26)"
                    }
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun setupGnssCallback() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            gnssStatusCallback = object : GnssStatus.Callback() {
                override fun onSatelliteStatusChanged(status: GnssStatus) {
                    val totalSatellites = status.satelliteCount
                    var satellitesInUse = 0
                    val satelliteDetails = StringBuilder()

                    for (i in 0 until totalSatellites) {
                        if (status.usedInFix(i)) {
                            satellitesInUse++
                        }
                        val constellation = when(status.getConstellationType(i)) {
                            GnssStatus.CONSTELLATION_GPS -> "GPS"
                            GnssStatus.CONSTELLATION_GLONASS -> "GLO"
                            GnssStatus.CONSTELLATION_BEIDOU -> "BEI"
                            GnssStatus.CONSTELLATION_GALILEO -> "GAL"
                            else -> "OTH"
                        }
                        satelliteDetails.append("ID: ${status.getSvid(i)} ($constellation), SNR: ${status.getCn0DbHz(i)}\n")
                    }
                    binding.tvGnssStatus.text = "Satellites in view/in use: $totalSatellites / $satellitesInUse"
                    binding.tvSatellitesDetails.text = satelliteDetails.toString()
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun displayWifiResults(): List<ScanResult> {
        val results: List<ScanResult> = wifiManager.scanResults
        val wifiDetails = StringBuilder()
        results.take(10).forEach {
            val rttSupport = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && it.is80211mcResponder) " (RTT)" else ""
            wifiDetails.append("${it.SSID}$rttSupport (${it.BSSID}) | Strength: ${it.level} dBm\n")
        }
        binding.tvWifiNetworks.text = wifiDetails.toString().ifEmpty { "No Wi-Fi networks found" }
        return results
    }

    @RequiresApi(Build.VERSION_CODES.P)
    @SuppressLint("MissingPermission")
    private fun startRttScan(scanResults: List<ScanResult>) {
        val rttResponders = scanResults.filter { it.is80211mcResponder }.take(5)

        if (rttResponders.isEmpty()) {
            binding.tvWifiRttResults.text = "No RTT-capable APs found."
            return
        }

        val rangingRequest = RangingRequest.Builder()
            .addAccessPoints(rttResponders)
            .build()

        wifiRttManager?.startRanging(rangingRequest, Executors.newSingleThreadExecutor(), object : RangingResultCallback() {
            override fun onRangingFailure(code: Int) {
                runOnUiThread {
                    binding.tvWifiRttResults.text = "RTT ranging failed with code: $code"
                }
            }

            override fun onRangingResults(results: List<RangingResult>) {
                val rttDetails = StringBuilder()
                results.forEach { result ->
                    if (result.status == RangingResult.STATUS_SUCCESS) {
                        rttDetails.append("AP: ${result.macAddress}, Dist: ${result.distanceMm / 1000.0}m (±${result.distanceStdDevMm / 1000.0}m)\n")
                    }
                }
                runOnUiThread {
                    binding.tvWifiRttResults.text = rttDetails.toString().ifEmpty { "RTT ranging success, but no results." }
                }
            }
        })
    }

    @SuppressLint("MissingPermission")
    private fun displayCellInfo() {
        val telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        val cellInfo = telephonyManager.allCellInfo
        val cellDetails = StringBuilder()
        if (cellInfo != null && cellInfo.isNotEmpty()) {
            cellInfo.take(5).forEach {
                cellDetails.append("$it\n")
            }
        }
        binding.tvCellInfo.text = cellDetails.toString().ifEmpty { "Cell info not available or permission denied." }
    }

    @SuppressLint("MissingPermission")
    private fun displayDeviceState() {
        val connManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = connManager.activeNetworkInfo
        val networkType = if (activeNetwork != null && activeNetwork.isConnected) {
            when (activeNetwork.type) {
                ConnectivityManager.TYPE_WIFI -> "Wi-Fi (${wifiManager.connectionInfo.ssid})"
                ConnectivityManager.TYPE_MOBILE -> "Mobile (${(getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager).networkOperatorName})"
                else -> "Other"
            }
        } else {
            "Not Connected"
        }
        binding.tvNetworkInfo.text = "Network: $networkType"

        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                val addrs = Collections.list(intf.inetAddresses)
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        binding.tvIpAddress.text = "IP Address: ${addr.hostAddress}"
                        break
                    }
                }
            }
        } catch (ex: Exception) {
            binding.tvIpAddress.text = "IP Address: N/A"
        }

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        val batteryManager = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val isCharging = batteryManager.isCharging
        val isIdle = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            powerManager.isDeviceIdleMode
        } else {
            false
        }
        binding.tvPowerState.text = "Power: ${if(isCharging) "Charging" else "Discharging"}, Idle Mode: $isIdle"

        val uptimeSeconds = SystemClock.elapsedRealtime() / 1000
        val hours = uptimeSeconds / 3600
        val minutes = (uptimeSeconds % 3600) / 60
        val seconds = uptimeSeconds % 60
        binding.tvUptime.text = "Device Uptime: %d h %d m %d s".format(hours, minutes, seconds)
    }

    @SuppressLint("HardwareIds", "MissingPermission")
    private fun displayIdentifiers() {
        val androidId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
        binding.tvAndroidId.text = "Android ID: $androidId"

        binding.tvBuildFingerprint.text = "Build Fingerprint: ${Build.FINGERPRINT}"

        binding.tvWifiMac.text = "Wi-Fi MAC: ${wifiManager.connectionInfo.macAddress ?: "N/A (restricted on modern Android)"}"
        binding.tvBtMac.text = "Bluetooth MAC: ${bluetoothAdapter.address ?: "N/A"}"

        GlobalScope.launch(Dispatchers.IO) {
            try {
                val adInfo = AdvertisingIdClient.getAdvertisingIdInfo(applicationContext)
                val adId = adInfo.id
                runOnUiThread {
                    binding.tvAdvertisingId.text = "Advertising ID: $adId"
                }
            } catch (e: Exception) {
                runOnUiThread {
                    binding.tvAdvertisingId.text = "Advertising ID: Not available"
                }
            }
        }

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
            val telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            try {
                binding.tvImei.text = "IMEI/MEID: ${if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) telephonyManager.imei else @Suppress("DEPRECATION") telephonyManager.deviceId ?: "N/A"}"
                binding.tvImsi.text = "IMSI: ${telephonyManager.subscriberId ?: "N/A"}"
                binding.tvPhoneNumber.text = "Phone Number: ${telephonyManager.line1Number ?: "N/A"}"
                binding.tvSerialNumber.text = "Hardware Serial: ${if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Build.getSerial() else @Suppress("DEPRECATION") Build.SERIAL}"
            } catch (e: SecurityException) {
                binding.tvImei.text = "IMEI/MEID: Security Exception"
                binding.tvImsi.text = "IMSI: Security Exception"
                binding.tvPhoneNumber.text = "Phone Number: Security Exception"
                binding.tvSerialNumber.text = "Hardware Serial: Security Exception"
            }
        } else {
            binding.tvImei.text = "IMEI/MEID: Permission needed"
            binding.tvImsi.text = "IMSI: Permission needed"
            binding.tvPhoneNumber.text = "Phone Number: Permission needed"
            binding.tvSerialNumber.text = "Hardware Serial: Permission needed"
        }
    }

    private fun displaySystemIntegrity() {
        // Kernel Version
        val kernelVersion = System.getProperty("os.version")
        binding.tvKernelVersion.text = "Kernel Version: $kernelVersion"

        // Build Tags
        binding.tvBuildTags.text = "Build Tags: ${Build.TAGS}"

        // Check for 'su' binary
        val suPaths = arrayOf(
            "/system/app/Superuser.apk", "/sbin/su", "/system/bin/su", "/system/xbin/su", "/data/local/xbin/su",
            "/data/local/bin/su", "/system/sd/xbin/su", "/system/bin/failsafe/su", "/data/local/su", "/su/bin/su"
        )
        var suFound = false
        for (path in suPaths) {
            if (File(path).exists()) {
                suFound = true
                break
            }
        }
        binding.tvSuExists.text = "SU Binary Found: ${if (suFound) "Yes" else "No"}"

        // Check for root management apps
        val rootApps = listOf(
            "com.noshufou.android.su", "com.thirdparty.superuser", "eu.chainfire.supersu",
            "com.koushikdutta.superuser", "com.zachspong.temprootremovejb", "com.ramdroid.appquarantine",
            "com.topjohnwu.magisk", "io.github.vvb2060.magisk", "me.weishu.kernelsu"
        )
        val foundRootApps = mutableListOf<String>()
        val packages = packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
        for (packageInfo in packages) {
            if (rootApps.contains(packageInfo.packageName)) {
                foundRootApps.add(packageInfo.packageName)
            }
        }
        binding.tvRootApps.text = if (foundRootApps.isNotEmpty()) {
            "Root Apps: ${foundRootApps.joinToString(", ")}"
        } else {
            "Root Apps: None detected"
        }
    }

    private fun checkAllPermissions(): Boolean {
        val requiredPermissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_WIFI_STATE,
            Manifest.permission.CHANGE_WIFI_STATE,
            Manifest.permission.READ_PHONE_STATE
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requiredPermissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            requiredPermissions.add(Manifest.permission.BLUETOOTH_SCAN)
            requiredPermissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            requiredPermissions.add(Manifest.permission.BLUETOOTH)
            requiredPermissions.add(Manifest.permission.BLUETOOTH_ADMIN)
        }

        return requiredPermissions.all {
            ActivityCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestAllPermissions() {
        val requiredPermissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_WIFI_STATE,
            Manifest.permission.CHANGE_WIFI_STATE,
            Manifest.permission.READ_PHONE_STATE
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requiredPermissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            requiredPermissions.add(Manifest.permission.BLUETOOTH_SCAN)
            requiredPermissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            requiredPermissions.add(Manifest.permission.BLUETOOTH)
            requiredPermissions.add(Manifest.permission.BLUETOOTH_ADMIN)
        }
        ActivityCompat.requestPermissions(this, requiredPermissions.toTypedArray(), PERMISSIONS_REQUEST_CODE)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSIONS_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                startAllUpdates()
            } else {
                Toast.makeText(this, "Permissions are required for monitoring.", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}