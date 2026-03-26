package com.example.signalsentry

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface SignalDao {
    @Insert
    suspend fun insert(signalData: SignalData)

    @Query("SELECT * FROM signal_history ORDER BY timestamp DESC")
    suspend fun getAllHistory(): List<SignalData>

    @Query("SELECT * FROM signal_history ORDER BY timestamp DESC LIMIT 10")
    suspend fun getLastTen(): List<SignalData>

    @Query("SELECT * FROM signal_history WHERE isDeadZone = 1 ORDER BY timestamp DESC")
    suspend fun getDeadZones(): List<SignalData>

    @Query("DELETE FROM signal_history")
    suspend fun clearAll()
}
