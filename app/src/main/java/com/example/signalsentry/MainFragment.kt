package com.example.signalsentry

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
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
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.core.app.ActivityCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.example.signalsentry.databinding.FragmentMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import java.io.File
import java.io.FileOutputStream
import java.util.*
import kotlin.math.cos
import kotlin.math.sin
import java.lang.Math.toRadians

class MainFragment : Fragment(), LocationListener {

    private var _binding: FragmentMainBinding? = null
    private val binding get() = _binding!!

    private lateinit var telephonyManager: TelephonyManager
    private lateinit var locationManager: LocationManager
    private lateinit var wifiAuditor: WifiSecurityAuditor
    private lateinit var db: AppDatabase

    private var simulationEngine: SimulationEngine? = null
    private var signalListener: Any? = null
    private var myLocationOverlay: MyLocationNewOverlay? = null
    private var sessionPolyline: Polyline? = null

    private var currentGeoPoint = GeoPoint(13.0440, 80.2223)
    private var lastLocation: Location? = null
    private var lastDbm: Int = -120
    private var isRecording = false
    private var hasSignalReading = false
    private var sessionStartTime = 0L

    private var lastHeatmapCell: Pair<Int, Int>? = null
    private var lastHeatmapPoint: GeoPoint? = null
    private var lastWifiAuditAtMs: Long = 0L

    private val sessionHeatmapSegments = mutableMapOf<Pair<Int, Int>, SignalAggregate>()
    private val sessionHeatmapOverlays = mutableMapOf<Pair<Int, Int>, Polygon>()

    private data class SignalAggregate(
        var sumDbm: Double = 0.0,
        var count: Int = 0,
        val center: GeoPoint
    )

    companion object {
        private const val PERM_REQUEST = 101
        private val PERMISSIONS = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.READ_PHONE_STATE
        )
        private const val GRID_SIZE_M = 12.0
        private const val DEG_TO_METERS = 111320.0
        private const val MIN_HEATMAP_MOVE_M = 3.0
        private const val WIFI_AUDIT_MIN_INTERVAL_MS = 2000L
        private const val NO_SIGNAL_MIN_DBM = -110
        private const val EXCELLENT_DBM_MAX_DBM = -65
        private const val TWO_MINUTES = 1000 * 60 * 2
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentMainBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        db = AppDatabase.getDatabase(requireContext())
        telephonyManager = requireContext().getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        locationManager = requireContext().getSystemService(Context.LOCATION_SERVICE) as LocationManager
        wifiAuditor = WifiSecurityAuditor(requireContext())

        setupMap()
        setupButtons()
        checkPermissions()
        
        // Initial cellular status update
        updateRealNetworkType()
    }

    private fun setupMap() {
        binding.map.setTileSource(TileSourceFactory.MAPNIK)
        binding.map.setMultiTouchControls(true)
        binding.map.controller.setZoom(19.0)
        binding.map.controller.setCenter(currentGeoPoint)
        binding.map.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        
        // Immediate location initialization from last known
        if (ActivityCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            val lastKnown = locationManager.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER)
                ?: locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            lastKnown?.let {
                currentGeoPoint = GeoPoint(it.latitude, it.longitude)
                binding.map.controller.setCenter(currentGeoPoint)
            }
        }
    }

    private fun setupButtons() {
        binding.startBtn.setOnClickListener { startScanning() }
        binding.stopBtn.setOnClickListener { stopScanning() }
        binding.viewHistoryBtn.setOnClickListener { 
            findNavController().navigate(R.id.action_mainFragment_to_historyFragment)
        }
        binding.clearMapBtn.setOnClickListener { clearMap() }
    }

    private fun startScanning() {
        if (isRecording) return
        clearSessionHeatmap()
        sessionStartTime = System.currentTimeMillis()
        
        sessionPolyline = Polyline(binding.map).apply {
            outlinePaint.color = Color.parseColor("#424242")
            outlinePaint.strokeWidth = 10f
            binding.map.overlays.add(this)
        }

        isRecording = true
        binding.recordingStatus.visibility = View.VISIBLE
        binding.startBtn.isEnabled = false
        binding.stopBtn.isEnabled = true
        
        detectAndInitializeSimulation()
        simulationEngine?.start()
        Toast.makeText(requireContext(), "Scanning Started", Toast.LENGTH_SHORT).show()
    }

    private fun stopScanning() {
        if (!isRecording) return
        isRecording = false
        binding.recordingStatus.visibility = View.GONE
        binding.startBtn.isEnabled = true
        binding.stopBtn.isEnabled = false
        
        simulationEngine?.stop()

        lifecycleScope.launch(Dispatchers.IO) {
            val totalPoints = sessionHeatmapSegments.values.sumOf { it.count }
            val avgDbm = if (totalPoints > 0) (sessionHeatmapSegments.values.sumOf { it.sumDbm } / totalPoints).toInt() else -120
            val deadZones = sessionHeatmapSegments.values.count { (it.sumDbm / it.count) <= -95 }

            // Capture map snapshot before potentially clearing or navigating
            val snapshotPath = withContext(Dispatchers.Main) { captureMapSnapshot() }

            val session = ScanSession(
                startTime = sessionStartTime,
                endTime = System.currentTimeMillis(),
                avgDbm = avgDbm,
                totalPoints = totalPoints,
                deadZones = deadZones,
                snapshotPath = snapshotPath
            )
            db.signalDao().insertSession(session)

            withContext(Dispatchers.Main) {
                // Remove ephemeral session overlays
                binding.map.overlays.removeAll(sessionHeatmapOverlays.values)
                if (sessionPolyline != null) binding.map.overlays.remove(sessionPolyline)
                sessionHeatmapSegments.clear()
                sessionHeatmapOverlays.clear()
                sessionPolyline = null
                binding.map.invalidate()
                Toast.makeText(requireContext(), "Scan Saved to History", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun captureMapSnapshot(): String? {
        return try {
            val width = binding.map.width
            val height = binding.map.height
            if (width <= 0 || height <= 0) return null
            
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            binding.map.draw(canvas)
            
            val file = File(requireContext().filesDir, "snap_${System.currentTimeMillis()}.jpg")
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 70, out)
            }
            file.absolutePath
        } catch (e: Exception) {
            null
        }
    }

    private fun clearMap() {
        binding.map.overlays.clear()
        if (myLocationOverlay != null) binding.map.overlays.add(myLocationOverlay)
        sessionHeatmapSegments.clear()
        sessionHeatmapOverlays.clear()
        sessionPolyline = null
        binding.map.invalidate()
        Toast.makeText(requireContext(), "Map Cleared", Toast.LENGTH_SHORT).show()
    }

    private fun clearSessionHeatmap() {
        binding.map.overlays.removeAll(sessionHeatmapOverlays.values)
        if (sessionPolyline != null) binding.map.overlays.remove(sessionPolyline)
        sessionHeatmapSegments.clear()
        sessionHeatmapOverlays.clear()
        sessionPolyline = null
        hasSignalReading = false
        binding.map.invalidate()
    }

    private fun updateSignalUI(dbm: Int) {
        lastDbm = dbm
        val (label, color) = getSignalProperties(dbm)
        
        binding.signalText.text = if (isRecording) "Signal: $dbm dBm — $label" else "Signal: $dbm dBm (Ready)"
        binding.signalText.setTextColor(if (isRecording) color else Color.parseColor("#212121"))
        binding.signalBar.setBackgroundColor(color)

        updateWifiUIThrottled()
        updateRealNetworkType() // Fixed: updates the "Cellular: Scanning..." part

        if (isRecording) {
            hasSignalReading = true
            updateSessionHeatmap(currentGeoPoint, dbm)
        }
    }

    private fun updateRealNetworkType() {
        if (simulationEngine != null && isRecording) return // Simulation handles UI if active
        
        if (ActivityCompat.checkSelfPermission(requireContext(), Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            binding.securityAlert.text = "Cellular: Permission Missing"
            return
        }

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            telephonyManager.dataNetworkType
        } else {
            @Suppress("DEPRECATION")
            telephonyManager.networkType
        }

        val typeStr = when (type) {
            TelephonyManager.NETWORK_TYPE_NR -> "5G"
            TelephonyManager.NETWORK_TYPE_LTE -> "LTE"
            TelephonyManager.NETWORK_TYPE_HSPAP, TelephonyManager.NETWORK_TYPE_HSPA, TelephonyManager.NETWORK_TYPE_UMTS -> "3G"
            TelephonyManager.NETWORK_TYPE_EDGE, TelephonyManager.NETWORK_TYPE_GPRS -> "2G"
            else -> "Searching..."
        }

        val (msg, color) = when {
            typeStr == "5G" || typeStr == "LTE" -> "Cellular: Secure ($typeStr)" to Color.parseColor("#2E7D32")
            typeStr == "3G" -> "Cellular: Warning (3G)" to Color.parseColor("#FB8C00")
            typeStr == "2G" -> "Cellular: 🚨 DANGER (2G)" to Color.parseColor("#D32F2F")
            else -> "Cellular: $typeStr" to Color.parseColor("#616161")
        }
        binding.securityAlert.text = msg
        binding.cellSecBar.setBackgroundColor(color)
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
        val audit = wifiAuditor.performAudit()
        val barColor = when (audit.riskLevel) {
            WifiSecurityAuditor.RiskLevel.SAFE -> Color.parseColor("#2E7D32")
            WifiSecurityAuditor.RiskLevel.WARNING -> Color.parseColor("#FB8C00")
            WifiSecurityAuditor.RiskLevel.DANGER -> Color.parseColor("#D32F2F")
        }
        binding.wifiSecurity.text = audit.message
        binding.wifiSecBar.setBackgroundColor(barColor)
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
            Polygon(binding.map).apply {
                outlinePaint.strokeWidth = 2f
                points = generateCirclePoints(aggregate.center, 20.0) // Fixed: radar marks points
                binding.map.overlays.add(this)
            }
        }

        overlay.fillPaint.color = adjustAlpha(color, 0.65f)
        overlay.outlinePaint.color = adjustAlpha(color, 0.2f)
        sessionPolyline?.addPoint(point)
        binding.map.invalidate()
    }

    private fun generateCirclePoints(center: GeoPoint, radiusMeters: Double): List<GeoPoint> {
        val points = mutableListOf<GeoPoint>()
        for (i in 0 until 360 step 20) {
            val angle = toRadians(i.toDouble())
            val lat = center.latitude + (radiusMeters / DEG_TO_METERS) * cos(angle)
            val lon = center.longitude + (radiusMeters / (DEG_TO_METERS * cos(toRadians(center.latitude)))) * sin(angle)
            points.add(GeoPoint(lat, lon))
        }
        return points
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

    override fun onLocationChanged(location: Location) {
        if (isBetterLocation(location, lastLocation)) {
            lastLocation = location
            currentGeoPoint = GeoPoint(location.latitude, location.longitude)
            
            if (!isRecording) {
                binding.map.controller.animateTo(currentGeoPoint)
            } else {
                binding.map.controller.setCenter(currentGeoPoint) // Keep centered while tracking
            }
            
            if (isRecording) {
                updateWifiUIThrottled()
                if (hasSignalReading) updateSessionHeatmap(currentGeoPoint, lastDbm)
                saveCurrentSignalState()
            }
        }
    }

    private fun isBetterLocation(location: Location, currentBestLocation: Location?): Boolean {
        if (currentBestLocation == null) return true
        val timeDelta: Long = location.time - currentBestLocation.time
        val isSignificantlyNewer: Boolean = timeDelta > TWO_MINUTES
        if (isSignificantlyNewer) return true
        val accuracyDelta: Int = (location.accuracy - currentBestLocation.accuracy).toInt()
        return accuracyDelta <= 0 || (timeDelta > 0 && accuracyDelta < 20)
    }

    private fun saveCurrentSignalState() {
        val signalData = SignalData(
            timestamp = System.currentTimeMillis(),
            latitude = currentGeoPoint.latitude,
            longitude = currentGeoPoint.longitude,
            dbm = lastDbm,
            networkType = binding.securityAlert.text.toString(),
            isDeadZone = lastDbm <= -95
        )
        lifecycleScope.launch(Dispatchers.IO) { db.signalDao().insert(signalData) }
    }

    private fun checkPermissions() {
        val missing = PERMISSIONS.filter {
            ActivityCompat.checkSelfPermission(requireContext(), it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) onPermissionsGranted()
        else requestPermissions(missing.toTypedArray(), PERM_REQUEST)
    }

    private fun onPermissionsGranted() {
        myLocationOverlay = MyLocationNewOverlay(GpsMyLocationProvider(requireContext()), binding.map).apply {
            enableMyLocation()
            runOnFirstFix { activity?.runOnUiThread { if (myLocation != null) binding.map.controller.animateTo(myLocation) } }
        }
        binding.map.overlays.add(myLocationOverlay)
        startPassiveMonitoring()
    }

    private fun startPassiveMonitoring() {
        if (ActivityCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return

        try {
            // High frequency updates for responsive "radar" marks
            locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 1500L, 2f, this)
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1500L, 2f, this)
            
            setupSignalListener()
            detectAndInitializeSimulation()
            updateRealNetworkType()
        } catch (e: SecurityException) { }
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
                activity?.runOnUiThread { updateSignalUI(dbm) }
            }
        }
        signalListener = callback
        telephonyManager.registerTelephonyCallback(requireContext().mainExecutor, callback)
    }

    @Suppress("DEPRECATION")
    private fun registerPhoneStateListener() {
        val listener = object : PhoneStateListener() {
            override fun onSignalStrengthsChanged(signalStrength: SignalStrength) {
                val dbm = getDbmFromSignalStrength(signalStrength)
                activity?.runOnUiThread { updateSignalUI(dbm) }
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
        return -120
    }

    private fun detectAndInitializeSimulation() {
        val isEmulator = Build.MODEL.contains("Emulator") || Build.MODEL.contains("Android SDK")
        val hasRealSim = try { telephonyManager.simState == TelephonyManager.SIM_STATE_READY } catch (e: Exception) { false }
        
        if (isEmulator || !hasRealSim) {
            if (simulationEngine == null) {
                simulationEngine = SimulationEngine(
                    onLocationUpdate = { geo -> 
                        val loc = Location("sim").apply {
                            latitude = geo.latitude
                            longitude = geo.longitude
                            accuracy = 5f
                            time = System.currentTimeMillis()
                        }
                        onLocationChanged(loc)
                    },
                    onSignalUpdate = { dbm -> updateSignalUI(dbm) },
                    onNetworkTypeUpdate = { type -> updateCellSecSimUI(type) }
                )
            }
            if (!isRecording && lastDbm == -120) updateSignalUI(-70)
        }
    }

    private fun updateCellSecSimUI(type: SimulationEngine.SimNetworkType) {
        val (msg, color) = when(type) {
            SimulationEngine.SimNetworkType.FIVE_G, SimulationEngine.SimNetworkType.LTE -> "Cellular: Secure (${type.name})" to Color.parseColor("#2E7D32")
            SimulationEngine.SimNetworkType.HSPA_3G -> "Cellular: Warning (3G)" to Color.parseColor("#FB8C00")
            SimulationEngine.SimNetworkType.EDGE_2G -> "Cellular: 🚨 DANGER (2G)" to Color.parseColor("#D32F2F")
        }
        activity?.runOnUiThread {
            if (_binding != null) {
                binding.securityAlert.text = msg
                binding.cellSecBar.setBackgroundColor(color)
            }
        }
    }

    private fun distanceMeters(a: GeoPoint, b: GeoPoint): Double {
        val r = 6371000.0
        val dLat = toRadians(b.latitude - a.latitude)
        val dLon = toRadians(b.longitude - a.longitude)
        val lat1 = toRadians(a.latitude)
        val lat2 = toRadians(b.latitude)
        val aVal = sin(dLat / 2.0) * sin(dLat / 2.0) +
                   cos(lat1) * cos(lat2) * sin(dLon / 2.0) * sin(dLon / 2.0)
        return r * 2.0 * Math.atan2(Math.sqrt(aVal), Math.sqrt(1.0 - aVal))
    }

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

    override fun onResume() { super.onResume(); binding.map.onResume() }
    override fun onPause() { super.onPause(); binding.map.onPause() }
    override fun onDestroyView() {
        super.onDestroyView()
        locationManager.removeUpdates(this)
        _binding = null
    }

    override fun onProviderDisabled(p0: String) {}
    override fun onProviderEnabled(p0: String) {}
    override fun onStatusChanged(p0: String, p1: Int, p2: Bundle?) {}
}
