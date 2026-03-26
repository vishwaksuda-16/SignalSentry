package com.example.signalsentry

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "signal_history")
data class SignalData(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val timestamp: Long,
    val latitude: Double,
    val longitude: Double,
    val dbm: Int,
    val networkType: String,
    val isDeadZone: Boolean
)
