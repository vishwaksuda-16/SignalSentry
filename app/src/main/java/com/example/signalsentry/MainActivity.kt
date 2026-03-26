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
    private lateinit var startBtn: Button
    private lateinit var stopBtn: Button
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
    private var hasSignalReading = false

    // Session dedupe (avoid new heatmap cell "jumps" while standing still)
    private var lastHeatmapCell: Pair<Int, Int>? = null
    private var lastHeatmapPoint: GeoPoint? = null
    private var lastWifiAuditAtMs: Long = 0L

    // Grid Aggregation
    private data class SignalAggregate(
        var sumDbm: Double = 0.0,
        var count: Int = 0,
        val center: GeoPoint
    )
    private val historyHeatmapSegments = mutableMapOf<Pair<Int, Int>, SignalAggregate>()
    private val historyHeatmapOverlays = mutableMapOf<Pair<Int, Int>, Polygon>()

    private val sessionHeatmapSegments = mutableMapOf<Pair<Int, Int>, SignalAggregate>()
    private val sessionHeatmapOverlays = mutableMapOf<Pair<Int, Int>, Polygon>()

    companion object {
        private const val PERM_REQUEST = 101
        private val PERMISSIONS = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.READ_PHONE_STATE
        )
        private const val GRID_SIZE_M = 20.0
        private const val DEG_TO_METERS = 111320.0

        private const val MIN_HEATMAP_MOVE_M = 6.0
        private const val WIFI_AUDIT_MIN_INTERVAL_MS = 4000L

        // Used only for aggregated/session overlay coloring.
        private const val NO_SIGNAL_MIN_DBM = -110
        private const val EXCELLENT_DBM_MAX_DBM = -65
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
        startBtn        = findViewById(R.id.startBtn)
        stopBtn         = findViewById(R.id.stopBtn)
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
        stopBtn.isEnabled = false

        startBtn.setOnClickListener { startScanning() }
        stopBtn.setOnClickListener { stopScanning() }

        clearHistoryBtn.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Clear Data")
                .setMessage("Wipe heatmap and history?")
                .setPositiveButton("Clear") { _, _ ->
                    lifecycleScope.launch(Dispatchers.IO) {
                        db.signalDao().clearAll()
                        withContext(Dispatchers.Main) {
                            map.overlays.removeAll(historyHeatmapOverlays.values)
                            map.overlays.removeAll(sessionHeatmapOverlays.values)
                            historyHeatmapSegments.clear()
                            historyHeatmapOverlays.clear()
                            sessionHeatmapSegments.clear()
                            sessionHeatmapOverlays.clear()
                            lastHeatmapCell = null
                            lastHeatmapPoint = null
                            map.invalidate()
                        }
                    }
                }.setNegativeButton("Cancel", null).show()
        }
        historyBtn.setOnClickListener { showHistoryDialog() }
        exportBtn.setOnClickListener { exportHistoryAsCsv() }
    }

    private fun startScanning() {
        if (isRecording) return

        // Reset session overlay (only start..stop should be aggregated here).
        clearSessionHeatmap()

        isRecording = true
        recordingStatus.visibility = View.VISIBLE
        startBtn.isEnabled = false
        stopBtn.isEnabled = true

        detectAndStart()
    }

    private fun stopScanning() {
        if (!isRecording) return

        isRecording = false
        recordingStatus.visibility = View.GONE
        startBtn.isEnabled = true
        stopBtn.isEnabled = false

        stopHardwareListeners()
        simulationEngine?.stop()
        simulationEngine = null
    }

    private fun clearSessionHeatmap() {
        map.overlays.removeAll(sessionHeatmapOverlays.values)
        sessionHeatmapSegments.clear()
        sessionHeatmapOverlays.clear()
        lastHeatmapCell = null
        lastHeatmapPoint = null
        hasSignalReading = false
        map.invalidate()
    }

    private fun stopHardwareListeners() {
        try {
            if (signalListener != null) {
                telephonyManager.listen(
                    signalListener,
                    android.telephony.PhoneStateListener.LISTEN_NONE
                )
            }
        } catch (_: SecurityException) { }

        try {
            locationManager.removeUpdates(this)
        } catch (_: SecurityException) { }
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
        if (!isRecording) return
        hasSignalReading = true

        val (label, color) = getSignalProperties(dbm)
        signalText.text = "Signal: $dbm dBm — $label"
        signalText.setTextColor(color)
        signalBar.setBackgroundColor(color)

        updateWifiUIThrottled()
        updateSessionHeatmap(currentGeoPoint, dbm)
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

    private fun updateWifiUIThrottled() {
        val now = System.currentTimeMillis()
        if (now - lastWifiAuditAtMs < WIFI_AUDIT_MIN_INTERVAL_MS) return
        lastWifiAuditAtMs = now
        updateWifiUI()
    }

    private fun updateWifiUI() {
        val audit = wifiAuditor.performAudit()
        val barColor = when (audit.riskLevel) {
            WifiSecurityAuditor.RiskLevel.SAFE -> Color.parseColor("#2E7D32")
            WifiSecurityAuditor.RiskLevel.WARNING -> Color.parseColor("#FB8C00")
            WifiSecurityAuditor.RiskLevel.DANGER -> Color.parseColor("#D32F2F")
        }

        // Keep the WiFi line readable and actionable.
        wifiSecurity.text = audit.message
        wifiSecBar.setBackgroundColor(barColor)
    }

    private fun updateHistoryHeatmap(point: GeoPoint, dbm: Int) {
        val cell = getGridCell(point)
        val aggregate = historyHeatmapSegments.getOrPut(cell) {
            SignalAggregate(0.0, 0, getCellCenter(cell.first, cell.second, point.latitude))
        }

        aggregate.sumDbm += dbm
        aggregate.count++

        val avgDbm = (aggregate.sumDbm / aggregate.count).toInt()
        val (_, color) = getSignalProperties(avgDbm)

        val overlay = historyHeatmapOverlays.getOrPut(cell) {
            Polygon(map).apply {
                outlinePaint.strokeWidth = 1f
                outlinePaint.color = Color.TRANSPARENT
                map.overlays.add(this)
            }
        }

        overlay.points = Polygon.pointsAsCircle(aggregate.center, 25.0)
        overlay.fillPaint.color = adjustAlpha(color, 0.5f)
        overlay.outlinePaint.color = adjustAlpha(color, 0.18f)
        map.invalidate()
    }

    private fun updateSessionHeatmap(point: GeoPoint, dbm: Int) {
        val rawCell = getGridCell(point)
        val cell = resolveStableCell(rawCell, point)

        val aggregate = sessionHeatmapSegments.getOrPut(cell) {
            SignalAggregate(0.0, 0, getCellCenter(cell.first, cell.second, point.latitude))
        }

        aggregate.sumDbm += dbm
        aggregate.count++

        val avgDbm = (aggregate.sumDbm / aggregate.count).toInt()
        val color = getAggregatedSignalColor(avgDbm)

        // "Color only" update: keep circle geometry fixed; update fill color on each signal change.
        val overlay = sessionHeatmapOverlays.getOrPut(cell) {
            Polygon(map).apply {
                outlinePaint.strokeWidth = 1f
                outlinePaint.color = Color.TRANSPARENT
                points = Polygon.pointsAsCircle(aggregate.center, 25.0)
                map.overlays.add(this)
            }
        }

        overlay.fillPaint.color = adjustAlpha(color, 0.62f)
        overlay.outlinePaint.color = adjustAlpha(color, 0.2f)
        map.invalidate()
    }

    private fun resolveStableCell(candidateCell: Pair<Int, Int>, point: GeoPoint): Pair<Int, Int> {
        val prevPoint = lastHeatmapPoint
        val prevCell = lastHeatmapCell
        if (prevPoint != null && prevCell != null) {
            val dist = distanceMeters(prevPoint, point)
            if (dist < MIN_HEATMAP_MOVE_M) {
                // Standing still: do not "jump" to a new cell overlay.
                lastHeatmapPoint = point
                return prevCell
            }
        }
        lastHeatmapPoint = point
        lastHeatmapCell = candidateCell
        return candidateCell
    }

    private fun distanceMeters(a: GeoPoint, b: GeoPoint): Double {
        val r = 6371000.0

        val dLat = toRadians(b.latitude - a.latitude)
        val dLon = toRadians(b.longitude - a.longitude)
        val lat1 = toRadians(a.latitude)
        val lat2 = toRadians(b.latitude)

        val sinDLat = kotlin.math.sin(dLat / 2.0)
        val sinDLon = kotlin.math.sin(dLon / 2.0)
        val h = sinDLat * sinDLat + kotlin.math.cos(lat1) * kotlin.math.cos(lat2) * sinDLon * sinDLon
        val c = 2.0 * kotlin.math.atan2(kotlin.math.sqrt(h), kotlin.math.sqrt(1.0 - h))
        return r * c
    }

    /**
     * Aggregated coloring for a scan session.
     * Requirement: when signal spans from "green -> red" inside a region,
     * the region color should end up in the "yellow/orange" range.
     */
    private fun getAggregatedSignalColor(avgDbm: Int): Int {
        val score = ((avgDbm - NO_SIGNAL_MIN_DBM).toDouble() / (EXCELLENT_DBM_MAX_DBM - NO_SIGNAL_MIN_DBM))
            .coerceIn(0.0, 1.0)

        return when {
            score >= 0.85 -> Color.parseColor("#2E7D32") // green
            score >= 0.70 -> Color.parseColor("#66BB6A") // light green
            score >= 0.55 -> Color.parseColor("#FDD835") // yellow
            score >= 0.35 -> Color.parseColor("#FB8C00") // orange
            else -> Color.parseColor("#D32F2F") // red
        }
    }

    private fun adjustAlpha(color: Int, factor: Float): Int {
        val alpha = (255 * factor).toInt()
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
    }

    // ── Location & History ───────────────────────────────────────────────────

    override fun onLocationChanged(location: Location) {
        currentGeoPoint = GeoPoint(location.latitude, location.longitude)
        map.controller.animateTo(currentGeoPoint)
        if (isRecording) {
            updateWifiUIThrottled()
            // Ensure the whole route segment gets aggregated, even if dBm didn't change.
            if (hasSignalReading) updateSessionHeatmap(currentGeoPoint, lastDbm)
        }
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
                history.forEach { updateHistoryHeatmap(GeoPoint(it.latitude, it.longitude), it.dbm) }
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
        recordingStatus.visibility = View.GONE
        stopBtn.isEnabled = false
        startBtn.isEnabled = true
    }

    private fun detectAndStart() {
        val hasRealSim = try { telephonyManager.simState == TelephonyManager.SIM_STATE_READY } catch (e: Exception) { false }
        if (isEmulator() || !hasRealSim) startSimulation() else startHardwareListeners()
    }

    private fun isEmulator() = Build.MODEL.contains("Emulator") || Build.MODEL.contains("Android SDK")

    private fun startHardwareListeners() {
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
