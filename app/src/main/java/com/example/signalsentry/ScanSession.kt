package com.example.signalsentry

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "scan_sessions")
data class ScanSession(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startTime: Long,
    val endTime: Long,
    val avgDbm: Int,
    val totalPoints: Int,
    val deadZones: Int,
    val snapshotPath: String? = null // Path to local storage image
)
