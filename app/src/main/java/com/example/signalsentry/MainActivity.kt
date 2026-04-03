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
import android.telephony.PhoneStateListener
import android.telephony.SignalStrength
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline
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
    private var signalListener: Any? = null
    private var myLocationOverlay: MyLocationNewOverlay? = null

    // Session Route
    private var sessionPolyline: Polyline? = null

    // Heatmap State
    private var currentGeoPoint = GeoPoint(13.0440, 80.2223)
    private var lastDbm: Int = -120
    private var isRecording = false
    private var hasSignalReading = false

    // Session dedupe
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
        private const val NO_SIGNAL_MIN_DBM = -110
        private const val EXCELLENT_DBM_MAX_DBM = -65
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().userAgentValue = packageName
        Configuration.getInstance().load(this, PreferenceManager.getDefaultSharedPreferences(this))
        setContentView(R.layout.activity_main)

        db = AppDatabase.getDatabase(this)
        bindViews()
        setupMap()

        telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        locationManager  = getSystemService(Context.LOCATION_SERVICE)  as LocationManager
        wifiAuditor      = WifiSecurityAuditor(this)

        checkPermissions()
        // DO NOT load history automatically at start anymore to keep map fresh
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
            // Only clear visual overlays from the map
            map.overlays.removeAll(historyHeatmapOverlays.values)
            map.overlays.removeAll(sessionHeatmapOverlays.values)
            if (sessionPolyline != null) map.overlays.remove(sessionPolyline)
            
            historyHeatmapSegments.clear()
            historyHeatmapOverlays.clear()
            sessionHeatmapSegments.clear()
            sessionHeatmapOverlays.clear()
            sessionPolyline = null
            
            lastHeatmapCell = null
            lastHeatmapPoint = null
            map.invalidate()
            Toast.makeText(this, "Map Overlays Cleared", Toast.LENGTH_SHORT).show()
        }

        historyBtn.setOnClickListener { showAnalysisDialog() }
        exportBtn.setOnClickListener { exportHistoryAsCsv() }
    }

    private fun startScanning() {
        if (isRecording) return
        clearSessionHeatmap()
        
        sessionPolyline = Polyline(map).apply {
            outlinePaint.color = Color.DKGRAY
            outlinePaint.strokeWidth = 8f
            map.overlays.add(this)
        }

        isRecording = true
        recordingStatus.visibility = View.VISIBLE
        startBtn.isEnabled = false
        stopBtn.isEnabled = true
        
        if (simulationEngine == null) detectAndInitializeSimulation()
        simulationEngine?.start()
    }

    private fun stopScanning() {
        if (!isRecording) return
        isRecording = false
        recordingStatus.visibility = View.GONE
        startBtn.isEnabled = true
        stopBtn.isEnabled = false
        
        simulationEngine?.stop()

        lifecycleScope.launch(Dispatchers.Main) {
            sessionHeatmapSegments.forEach { (cell, agg) ->
                val avgDbm = (agg.sumDbm / agg.count).toInt()
                updateHistoryHeatmap(agg.center, avgDbm)
            }
            map.overlays.removeAll(sessionHeatmapOverlays.values)
            sessionHeatmapSegments.clear()
            sessionHeatmapOverlays.clear()
            map.invalidate()
            Toast.makeText(this@MainActivity, "Scan Complete", Toast.LENGTH_SHORT).show()
        }
    }

    private fun clearSessionHeatmap() {
        map.overlays.removeAll(sessionHeatmapOverlays.values)
        if (sessionPolyline != null) {
            map.overlays.remove(sessionPolyline)
            sessionPolyline = null
        }
        sessionHeatmapSegments.clear()
        sessionHeatmapOverlays.clear()
        lastHeatmapCell = null
        lastHeatmapPoint = null
        hasSignalReading = false
        map.invalidate()
    }

    // ── Signal Logic ─────────────────────────────────────────────────────────

    private fun updateSignalUI(dbm: Int) {
        lastDbm = dbm
        val (label, color) = getSignalProperties(dbm)
        
        if (!isRecording) {
            signalText.text = "Signal: $dbm dBm (Ready)"
            signalText.setTextColor(Color.parseColor("#757575"))
            signalBar.setBackgroundColor(Color.parseColor("#BDBDBD"))
            return
        }
        
        hasSignalReading = true
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
        wifiSecurity.text = audit.message
        wifiSecBar.setBackgroundColor(barColor)
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

        val overlay = sessionHeatmapOverlays.getOrPut(cell) {
            Polygon(map).apply {
                outlinePaint.strokeWidth = 1f
                outlinePaint.color = Color.TRANSPARENT
                points = Polygon.pointsAsCircle(aggregate.center, 28.0)
                map.overlays.add(this)
            }
        }

        overlay.fillPaint.color = adjustAlpha(color, 0.65f)
        overlay.outlinePaint.color = adjustAlpha(color, 0.2f)
        sessionPolyline?.addPoint(point)
        map.invalidate()
    }

    private fun resolveStableCell(candidateCell: Pair<Int, Int>, point: GeoPoint): Pair<Int, Int> {
        val prevPoint = lastHeatmapPoint
        val prevCell = lastHeatmapCell
        if (prevPoint != null && prevCell != null) {
            val dist = distanceMeters(prevPoint, point)
            if (dist < MIN_HEATMAP_MOVE_M) {
                lastHeatmapPoint = point
                return prevCell
            }
        }
        lastHeatmapPoint = point
        lastHeatmapCell = candidateCell
        return candidateCell
    }

    private fun getAggregatedSignalColor(avgDbm: Int): Int {
        val score = ((avgDbm - NO_SIGNAL_MIN_DBM).toDouble() / (EXCELLENT_DBM_MAX_DBM - NO_SIGNAL_MIN_DBM))
            .coerceIn(0.0, 1.0)

        return when {
            score >= 0.85 -> Color.parseColor("#2E7D32")
            score >= 0.70 -> Color.parseColor("#66BB6A")
            score >= 0.55 -> Color.parseColor("#FDD835")
            score >= 0.35 -> Color.parseColor("#FB8C00")
            else -> Color.parseColor("#D32F2F")
        }
    }

    private fun adjustAlpha(color: Int, factor: Float): Int {
        val alpha = (255 * factor).toInt()
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
    }

    // ── Location ─────────────────────────────────────────────────────────────

    override fun onLocationChanged(location: Location) {
        currentGeoPoint = GeoPoint(location.latitude, location.longitude)
        map.controller.animateTo(currentGeoPoint)
        if (isRecording) {
            updateWifiUIThrottled()
            if (hasSignalReading) updateSessionHeatmap(currentGeoPoint, lastDbm)
            saveCurrentSignalState()
        }
    }

    private fun saveCurrentSignalState() {
        val signalData = SignalData(
            timestamp = System.currentTimeMillis(),
            latitude = currentGeoPoint.latitude,
            longitude = currentGeoPoint.longitude,
            dbm = lastDbm,
            networkType = "LTE",
            isDeadZone = lastDbm <= -95
        )
        lifecycleScope.launch(Dispatchers.IO) { db.signalDao().insert(signalData) }
    }

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
        startPassiveMonitoring()
        startBtn.isEnabled = true
    }

    private fun startPassiveMonitoring() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return

        try {
            // Immediate Location Fix: Try multiple providers for the fastest possible point
            val providers = listOf(LocationManager.PASSIVE_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
            var bestLocation: Location? = null
            for (p in providers) {
                val l = locationManager.getLastKnownLocation(p) ?: continue
                if (bestLocation == null || l.accuracy < bestLocation!!.accuracy) bestLocation = l
            }
            bestLocation?.let {
                currentGeoPoint = GeoPoint(it.latitude, it.longitude)
                map.controller.setCenter(currentGeoPoint)
                onLocationChanged(it)
            }

            locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 1500L, 0f, this)
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000L, 0f, this)
            
            setupSignalListener()
            updateWifiUI()
            securityAlert.text = "Cellular: Monitoring active"
            
            detectAndInitializeSimulation()
            // Simulation starts but we handle its updates carefully in updateSignalUI
        } catch (e: SecurityException) { 
            securityAlert.text = "Cellular: Permission error"
        }
    }

    private fun setupSignalListener() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            registerTelephonyCallback()
        } else {
            registerPhoneStateListener()
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun registerTelephonyCallback() {
        val callback = object : TelephonyCallback(), TelephonyCallback.SignalStrengthsListener {
            override fun onSignalStrengthsChanged(signalStrength: SignalStrength) {
                val dbm = getDbmFromSignalStrength(signalStrength)
                runOnUiThread { updateSignalUI(dbm) }
            }
        }
        signalListener = callback
        telephonyManager.registerTelephonyCallback(mainExecutor, callback)
    }

    @Suppress("DEPRECATION")
    private fun registerPhoneStateListener() {
        val listener = object : PhoneStateListener() {
            override fun onSignalStrengthsChanged(signalStrength: SignalStrength) {
                val dbm = getDbmFromSignalStrength(signalStrength)
                runOnUiThread { updateSignalUI(dbm) }
            }
        }
        signalListener = listener
        telephonyManager.listen(listener, PhoneStateListener.LISTEN_SIGNAL_STRENGTHS)
    }

    private fun getDbmFromSignalStrength(signalStrength: SignalStrength): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val strengths = signalStrength.cellSignalStrengths
            for (s in strengths) {
                if (s.dbm != Int.MAX_VALUE && s.dbm < 0) return s.dbm
            }
        }
        return try {
            val method = signalStrength.javaClass.getMethod("getDbm")
            val dbm = method.invoke(signalStrength) as Int
            if (dbm == Int.MAX_VALUE || dbm >= 0) -120 else dbm
        } catch (e: Exception) {
            -120
        }
    }

    private fun detectAndInitializeSimulation() {
        val isEmulator = Build.MODEL.contains("Emulator") || Build.MODEL.contains("Android SDK")
        val hasRealSim = try { telephonyManager.simState == TelephonyManager.SIM_STATE_READY } catch (e: Exception) { false }
        
        if (isEmulator || !hasRealSim) {
            if (simulationEngine == null) {
                simulationEngine = SimulationEngine(
                    onLocationUpdate = { geo -> onLocationChanged(locationFromGeo(geo)) },
                    onSignalUpdate = { dbm -> updateSignalUI(dbm) },
                    onNetworkTypeUpdate = { type -> updateCellularSecuritySimUI(type) }
                )
            }
            if (!isRecording && lastDbm == -120) updateSignalUI(-70)
        }
    }

    private fun updateCellularSecuritySimUI(type: SimulationEngine.SimNetworkType) {
        val (msg, color) = when(type) {
            SimulationEngine.SimNetworkType.FIVE_G, SimulationEngine.SimNetworkType.LTE -> 
                "Cellular: Secure (${type.name})" to Color.parseColor("#2E7D32")
            SimulationEngine.SimNetworkType.HSPA_3G -> 
                "Cellular: Warning (Legacy 3G)" to Color.parseColor("#FB8C00")
            SimulationEngine.SimNetworkType.EDGE_2G -> 
                "Cellular: 🚨 DANGER (2G Detected)" to Color.parseColor("#D32F2F")
        }
        runOnUiThread {
            securityAlert.text = msg
            cellSecBar.setBackgroundColor(color)
        }
    }

    private fun locationFromGeo(geo: GeoPoint) = Location("sim").apply {
        latitude = geo.latitude
        longitude = geo.longitude
        accuracy = 10f
        time = System.currentTimeMillis()
    }

    // ── Grid Math ────────────────────────────────────────────────────────────

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

    private fun distanceMeters(a: GeoPoint, b: GeoPoint): Double {
        val r = 6371000.0
        val dLat = toRadians(b.latitude - a.latitude)
        val dLon = toRadians(b.longitude - a.longitude)
        val lat1 = toRadians(a.latitude)
        val lat2 = toRadians(b.latitude)
        val aVal = Math.sin(dLat / 2.0) * Math.sin(dLat / 2.0) +
                   Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLon / 2.0) * Math.sin(dLon / 2.0)
        return r * 2.0 * Math.atan2(Math.sqrt(aVal), Math.sqrt(1.0 - aVal))
    }

    private fun loadHistoryToHeatmap() {
        lifecycleScope.launch(Dispatchers.IO) {
            val history = db.signalDao().getAllHistory()
            if (history.isEmpty()) return@launch

            val aggregated = mutableMapOf<Pair<Int, Int>, SignalAggregate>()
            history.forEach {
                val point = GeoPoint(it.latitude, it.longitude)
                val cell = getGridCell(point)
                val agg = aggregated.getOrPut(cell) {
                    SignalAggregate(0.0, 0, getCellCenter(cell.first, cell.second, it.latitude))
                }
                agg.sumDbm += it.dbm
                agg.count++
            }
            
            withContext(Dispatchers.Main) {
                aggregated.forEach { (cell, agg) ->
                    val avgDbm = (agg.sumDbm / agg.count).toInt()
                    val (_, color) = getSignalProperties(avgDbm)
                    val overlay = Polygon(map).apply {
                        outlinePaint.strokeWidth = 1f
                        outlinePaint.color = Color.TRANSPARENT
                        points = Polygon.pointsAsCircle(agg.center, 25.0)
                        fillPaint.color = adjustAlpha(color, 0.45f)
                    }
                    historyHeatmapOverlays[cell] = overlay
                    historyHeatmapSegments[cell] = agg
                    map.overlays.add(overlay)
                }
                map.invalidate()
            }
        }
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
        overlay.fillPaint.color = adjustAlpha(color, 0.45f)
        map.invalidate()
    }

    private fun showAnalysisDialog() {
        lifecycleScope.launch(Dispatchers.IO) {
            val history = db.signalDao().getAllHistory()
            withContext(Dispatchers.Main) {
                if (history.isEmpty()) {
                    Toast.makeText(this@MainActivity, "No data collected yet.", Toast.LENGTH_SHORT).show()
                    return@withContext
                }

                val avgDbm = history.map { it.dbm }.average().toInt()
                val deadZones = history.count { it.isDeadZone }
                val totalPoints = history.size
                val deadZonePercent = (deadZones.toDouble() / totalPoints * 100).toInt()
                val (label, _) = getSignalProperties(avgDbm)

                val message = """
                    OVERALL SIGNAL ANALYSIS
                    
                    Summary:
                    • Total Measurements: $totalPoints
                    • Avg. Strength: $avgDbm dBm ($label)
                    • Signal Integrity: ${100 - deadZonePercent}%
                    
                    Reliability:
                    • Weak Spots Found: $deadZones
                    ${if (deadZonePercent > 15) "• ⚠️ WARNING: High density of weak spots." else "• ✅ SUCCESS: Stable network detected."}
                """.trimIndent()

                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Signal Integrity Report")
                    .setMessage(message)
                    .setPositiveButton("OK", null)
                    .setNeutralButton("Export Details") { _, _ -> exportHistoryAsCsv() }
                    .show()
            }
        }
    }

    private fun exportHistoryAsCsv() {
        lifecycleScope.launch(Dispatchers.IO) {
            val history = db.signalDao().getAllHistory()
            if (history.isEmpty()) {
                withContext(Dispatchers.Main) { Toast.makeText(this@MainActivity, "Nothing to export", Toast.LENGTH_SHORT).show() }
                return@launch
            }
            try {
                val folder = File(getExternalFilesDir(null), "exports")
                if (!folder.exists()) folder.mkdirs()
                val file = File(folder, "SignalSentry_Report_${System.currentTimeMillis()}.csv")
                file.printWriter().use { out ->
                    out.println("Timestamp,Latitude,Longitude,dBm,NetworkType,WeakSpot")
                    history.forEach { out.println("${it.timestamp},${it.latitude},${it.longitude},${it.dbm},${it.networkType},${it.isDeadZone}") }
                }
                withContext(Dispatchers.Main) {
                    val uri = FileProvider.getUriForFile(this@MainActivity, "$packageName.fileprovider", file)
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/csv"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(Intent.createChooser(intent, "Share Analysis Report"))
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { Toast.makeText(this@MainActivity, "Export failed: ${e.message}", Toast.LENGTH_SHORT).show() }
            }
        }
    }

    override fun onResume() { super.onResume(); map.onResume() }
    override fun onPause() { super.onPause(); map.onPause() }
    override fun onProviderDisabled(p: String) {}
    override fun onProviderEnabled(p: String) {}
    override fun onStatusChanged(p: String, s: Int, e: Bundle?) {}
}
