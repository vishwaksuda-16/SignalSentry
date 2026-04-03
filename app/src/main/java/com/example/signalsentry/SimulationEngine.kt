package com.example.signalsentry

import android.os.Handler
import android.os.Looper
import org.osmdroid.util.GeoPoint

/**
 * SimulationEngine.kt
 * Drives the emulator demo with a pre-defined GPS route and
 * simulated signal strength + network type changes.
 */
class SimulationEngine(
    private val onLocationUpdate: (GeoPoint) -> Unit,
    private val onSignalUpdate: (Int) -> Unit,
    private val onNetworkTypeUpdate: (SimNetworkType) -> Unit
) {
    enum class SimNetworkType { LTE, FIVE_G, EDGE_2G, HSPA_3G }

    data class SimWaypoint(
        val geoPoint: GeoPoint,
        val dbm: Int,
        val networkType: SimNetworkType,
        val delayMs: Long = 2000L
    )

    // Chennai campus route: good signal → degraded → IMSI catcher zone → recovery
    private val route = listOf(
        SimWaypoint(GeoPoint(13.0440, 80.2223), dbm = -68, networkType = SimNetworkType.FIVE_G),
        SimWaypoint(GeoPoint(13.0443, 80.2228), dbm = -74, networkType = SimNetworkType.LTE),
        SimWaypoint(GeoPoint(13.0447, 80.2234), dbm = -82, networkType = SimNetworkType.LTE),
        SimWaypoint(GeoPoint(13.0451, 80.2240), dbm = -90, networkType = SimNetworkType.HSPA_3G),
        SimWaypoint(GeoPoint(13.0455, 80.2246), dbm = -97, networkType = SimNetworkType.HSPA_3G),
        // ⚠️ IMSI Catcher zone
        SimWaypoint(GeoPoint(13.0459, 80.2252), dbm = -105, networkType = SimNetworkType.EDGE_2G, delayMs = 3000L),
        SimWaypoint(GeoPoint(13.0463, 80.2258), dbm = -108, networkType = SimNetworkType.EDGE_2G, delayMs = 3000L),
        SimWaypoint(GeoPoint(13.0467, 80.2264), dbm = -110, networkType = SimNetworkType.EDGE_2G, delayMs = 3000L),
        // Recovery
        SimWaypoint(GeoPoint(13.0471, 80.2270), dbm = -93, networkType = SimNetworkType.LTE),
        SimWaypoint(GeoPoint(13.0475, 80.2276), dbm = -80, networkType = SimNetworkType.LTE),
        SimWaypoint(GeoPoint(13.0479, 80.2282), dbm = -71, networkType = SimNetworkType.LTE),
        SimWaypoint(GeoPoint(13.0483, 80.2288), dbm = -65, networkType = SimNetworkType.FIVE_G)
    )

    private val handler = Handler(Looper.getMainLooper())
    private var waypointIndex = 0
    private var isRunning = false

    fun start() {
        if (isRunning) return
        isRunning = true
        waypointIndex = 0
        scheduleNextWaypoint()
    }

    fun stop() {
        isRunning = false
        handler.removeCallbacksAndMessages(null)
    }

    private fun scheduleNextWaypoint() {
        if (!isRunning) return
        if (waypointIndex >= route.size) {
            waypointIndex = 0 // loop
            scheduleNextWaypoint()
            return
        }
        val waypoint = route[waypointIndex]
        handler.postDelayed({
            if (!isRunning) return@postDelayed
            onLocationUpdate(waypoint.geoPoint)
            onSignalUpdate(waypoint.dbm)
            onNetworkTypeUpdate(waypoint.networkType)
            waypointIndex++
            scheduleNextWaypoint()
        }, waypoint.delayMs)
    }
}