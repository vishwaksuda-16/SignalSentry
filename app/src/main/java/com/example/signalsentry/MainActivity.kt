package com.example.signalsentry

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.telephony.TelephonyManager
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import java.lang.Math.toRadians

class MainActivity : AppCompatActivity(), LocationListener {

    // UI
    private lateinit var map: MapView
    private lateinit var signalText: TextView
    private lateinit var signalBar: View
    private lateinit var securityAlert: TextView
    private lateinit var cellSecBar: View
    private lateinit var wifiSecurity: TextView
    private lateinit var wifiSecBar: View
    private lateinit var recordingStatus: TextView
    private lateinit var clearHistoryBtn: Button
    private lateinit var historyBtn: Button
    private lateinit var exportBtn: Button

    // System services
    private lateinit var telephonyManager: TelephonyManager
    private lateinit var locationManager: LocationManager

    // Modules
    private lateinit var wifiAuditor: WifiSecurityAuditor
    private lateinit var db: AppDatabase
    private var simulationEngine: SimulationEngine? = null
    private var signalListener: SignalStrengthListener? = null
    private var myLocationOverlay: MyLocationNewOverlay? = null

    // Heatmap State
    private var currentGeoPoint = GeoPoint(13.0440, 80.2223)
    private var lastDbm: Int = -120
    private var isRecording = false

    // Grid Aggregation
    private data class SignalAggregate(
        var sumDbm: Double = 0.0,
        var count: Int = 0,
        val center: GeoPoint
    )
    private val heatmapSegments = mutableMapOf<Pair<Int, Int>, SignalAggregate>()
    private val heatmapOverlays = mutableMapOf<Pair<Int, Int>, Polygon>()

    companion object {
        private const val PERM_REQUEST = 101
        private val PERMISSIONS = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.READ_PHONE_STATE
        )
        private const val GRID_SIZE_M = 20.0
        private const val DEG_TO_METERS = 111320.0
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().load(this, PreferenceManager.getDefaultSharedPreferences(this))
        setContentView(R.layout.activity_main)

        db = AppDatabase.getDatabase(this)
        bindViews()
        setupMap()

        telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        locationManager  = getSystemService(Context.LOCATION_SERVICE)  as LocationManager
        wifiAuditor      = WifiSecurityAuditor(this)

        checkPermissions()
        loadHistoryToHeatmap()
        setupButtons()
    }

    private fun bindViews() {
        map             = findViewById(R.id.map)
        signalText      = findViewById(R.id.signalText)
        signalBar       = findViewById(R.id.signalBar)
        securityAlert   = findViewById(R.id.securityAlert)
        cellSecBar      = findViewById(R.id.cellSecBar)
        wifiSecurity    = findViewById(R.id.wifiSecurity)
        wifiSecBar      = findViewById(R.id.wifiSecBar)
        recordingStatus = findViewById(R.id.recordingStatus)
        clearHistoryBtn = findViewById(R.id.clearHistoryBtn)
        historyBtn      = findViewById(R.id.historyBtn)
        exportBtn       = findViewById(R.id.exportBtn)
    }

    private fun setupMap() {
        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setMultiTouchControls(true)
        map.controller.setZoom(18.0)
        map.controller.setCenter(currentGeoPoint)
    }

    private fun setupButtons() {
        clearHistoryBtn.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Clear Data")
                .setMessage("Wipe heatmap and history?")
                .setPositiveButton("Clear") { _, _ ->
                    lifecycleScope.launch(Dispatchers.IO) {
                        db.signalDao().clearAll()
                        withContext(Dispatchers.Main) {
                            map.overlays.removeAll(heatmapOverlays.values)
                            heatmapSegments.clear()
                            heatmapOverlays.clear()
                            map.invalidate()
                        }
                    }
                }.setNegativeButton("Cancel", null).show()
        }
        historyBtn.setOnClickListener { showHistoryDialog() }
        exportBtn.setOnClickListener { exportHistoryAsCsv() }
    }

    // ── Grid Calculation ─────────────────────────────────────────────────────

    private fun getGridCell(point: GeoPoint): Pair<Int, Int> {
        val latIdx = (point.latitude * DEG_TO_METERS / GRID_SIZE_M).toInt()
        val lonIdx = (point.longitude * DEG_TO_METERS * cos(toRadians(point.latitude)) / GRID_SIZE_M).toInt()
        return latIdx to lonIdx
    }

    private fun getCellCenter(latIdx: Int, lonIdx: Int, refLat: Double): GeoPoint {
        val lat = (latIdx * GRID_SIZE_M) / DEG_TO_METERS
        val lon = (lonIdx * GRID_SIZE_M) / (DEG_TO_METERS * cos(toRadians(refLat)))
        return GeoPoint(lat, lon)
    }

    // ── Signal Logic ─────────────────────────────────────────────────────────

    private fun updateSignalUI(dbm: Int) {
        val (label, color) = getSignalProperties(dbm)
        signalText.text = "Signal: $dbm dBm — $label"
        signalText.setTextColor(color)
        signalBar.setBackgroundColor(color)

        updateHeatmap(currentGeoPoint, dbm)
    }

    private fun getSignalProperties(dbm: Int): Pair<String, Int> {
        return when {
            dbm >= -65 -> "Excellent"    to Color.parseColor("#2E7D32")
            dbm >= -75 -> "Good"         to Color.parseColor("#66BB6A")
            dbm >= -85 -> "Moderate"     to Color.parseColor("#FDD835")
            dbm >= -95 -> "Poor"         to Color.parseColor("#FB8C00")
            else       -> "Very Poor"    to Color.parseColor("#D32F2F")
        }
    }

    private fun updateHeatmap(point: GeoPoint, dbm: Int) {
        val cell = getGridCell(point)
        val aggregate = heatmapSegments.getOrPut(cell) {
            SignalAggregate(0.0, 0, getCellCenter(cell.first, cell.second, point.latitude))
        }

        aggregate.sumDbm += dbm
        aggregate.count++

        val avgDbm = (aggregate.sumDbm / aggregate.count).toInt()
        val (_, color) = getSignalProperties(avgDbm)
        
        // Render or Update Overlay
        val overlay = heatmapOverlays.getOrPut(cell) {
            Polygon(map).apply {
                outlinePaint.color = Color.TRANSPARENT
                map.overlays.add(this)
            }
        }

        // Requirement 5: Radius slightly larger than grid size (25m) to ensure overlap/continuity
        overlay.points = Polygon.pointsAsCircle(aggregate.center, 25.0)
        overlay.fillPaint.color = adjustAlpha(color, 0.5f)
        map.invalidate()
    }

    private fun adjustAlpha(color: Int, factor: Float): Int {
        val alpha = (255 * factor).toInt()
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
    }

    // ── Location & History ───────────────────────────────────────────────────

    override fun onLocationChanged(location: Location) {
        currentGeoPoint = GeoPoint(location.latitude, location.longitude)
        map.controller.animateTo(currentGeoPoint)
        saveCurrentSignalState()
    }

    private fun saveCurrentSignalState() {
        if (!isRecording) return
        val signalData = SignalData(
            timestamp = System.currentTimeMillis(),
            latitude = currentGeoPoint.latitude,
            longitude = currentGeoPoint.longitude,
            dbm = lastDbm,
            networkType = getNetworkType(),
            isDeadZone = lastDbm <= -110
        )
        lifecycleScope.launch(Dispatchers.IO) { db.signalDao().insert(signalData) }
    }

    private fun loadHistoryToHeatmap() {
        lifecycleScope.launch(Dispatchers.IO) {
            val history = db.signalDao().getAllHistory()
            withContext(Dispatchers.Main) {
                history.forEach { updateHeatmap(GeoPoint(it.latitude, it.longitude), it.dbm) }
            }
        }
    }

    // ── Standard Boilerplate ────────────────────────────────────────────────

    private fun checkPermissions() {
        val missing = PERMISSIONS.filter {
            ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) onPermissionsGranted()
        else ActivityCompat.requestPermissions(this, missing.toTypedArray(), PERM_REQUEST)
    }

    private fun onPermissionsGranted() {
        myLocationOverlay = MyLocationNewOverlay(GpsMyLocationProvider(this), map).apply {
            enableMyLocation()
            runOnFirstFix { runOnUiThread { if (myLocation != null) map.controller.animateTo(myLocation) } }
        }
        map.overlays.add(myLocationOverlay)
        detectAndStart()
    }

    private fun detectAndStart() {
        val hasRealSim = try { telephonyManager.simState == TelephonyManager.SIM_STATE_READY } catch (e: Exception) { false }
        if (isEmulator() || !hasRealSim) startSimulation() else startHardwareListeners()
    }

    private fun isEmulator() = Build.MODEL.contains("Emulator") || Build.MODEL.contains("Android SDK")

    private fun startHardwareListeners() {
        isRecording = true
        recordingStatus.visibility = View.VISIBLE
        try {
            signalListener = SignalStrengthListener { dbm -> runOnUiThread { lastDbm = dbm; updateSignalUI(dbm) } }
            telephonyManager.listen(signalListener, android.telephony.PhoneStateListener.LISTEN_SIGNAL_STRENGTHS)
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 3000L, 5f, this)
            }
        } catch (e: SecurityException) { }
    }

    private fun startSimulation() {
        simulationEngine = SimulationEngine(
            onLocationUpdate = { geo -> currentGeoPoint = geo; map.controller.animateTo(geo) },
            onSignalUpdate = { dbm -> lastDbm = dbm; updateSignalUI(dbm) },
            onNetworkTypeUpdate = { }
        ).apply { start() }
    }

    private fun getNetworkType(): String = "LTE" // Simplified for logic

    private fun showHistoryDialog() { /* Implementation as before */ }
    private fun exportHistoryAsCsv() { /* Implementation as before */ }

    override fun onResume() { super.onResume(); map.onResume() }
    override fun onPause() { super.onPause(); map.onPause() }
    override fun onProviderDisabled(p0: String) {}
    override fun onProviderEnabled(p0: String) {}
    override fun onStatusChanged(p0: String, p1: Int, p2: Bundle?) {}
}
