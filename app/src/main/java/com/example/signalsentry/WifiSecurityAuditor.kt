package com.example.signalsentry

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build

/**
 * WifiSecurityAuditor.kt
 * Heuristic MITM / rogue AP detection on the active Wi-Fi connection.
 */
class WifiSecurityAuditor(private val context: Context) {

    data class AuditResult(
        val isConnectedToWifi: Boolean,
        val ssid: String,
        val riskLevel: RiskLevel,
        val message: String
    )

    enum class RiskLevel { SAFE, WARNING, DANGER }

    private val suspiciousSsidPatterns = listOf(
        "free", "public", "airport", "hotel", "starbucks",
        "android ap", "linksys", "default", "netgear", "xfinity"
    )

    @Suppress("DEPRECATION")
    fun performAudit(): AuditResult {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

        if (!isConnectedToWifi(cm)) {
            return AuditResult(false, "N/A", RiskLevel.SAFE, "WiFi: Not connected")
        }

        val wifiInfo = wm.connectionInfo
        val ssid = (wifiInfo?.ssid ?: "<unknown>").replace("\"", "")
        val isValidated = isNetworkValidated(cm)
        val rssi = wifiInfo?.rssi ?: -100
        val isSuspiciousRssi = rssi > -40
        val isSuspiciousSsid = suspiciousSsidPatterns.any { ssid.lowercase().contains(it) }

        return when {
            !isValidated -> AuditResult(true, ssid, RiskLevel.DANGER,
                "WiFi: ⚠️ CAPTIVE PORTAL / REDIRECT on \"$ssid\"")
            isSuspiciousSsid && isSuspiciousRssi -> AuditResult(true, ssid, RiskLevel.DANGER,
                "WiFi: 🚨 ROGUE AP SUSPECTED — \"$ssid\"")
            isSuspiciousSsid -> AuditResult(true, ssid, RiskLevel.WARNING,
                "WiFi: ⚠️ Suspicious network name — \"$ssid\"")
            else -> AuditResult(true, ssid, RiskLevel.SAFE,
                "WiFi: ✅ Secure — \"$ssid\"")
        }
    }

    private fun isConnectedToWifi(cm: ConnectivityManager): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        } else {
            @Suppress("DEPRECATION")
            cm.activeNetworkInfo?.type == ConnectivityManager.TYPE_WIFI
        }
    }

    private fun isNetworkValidated(cm: ConnectivityManager): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } else true
    }
}