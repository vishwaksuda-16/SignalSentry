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

    @Query("DELETE FROM signal_history")
    suspend fun clearAll()

    // Session-based queries
    @Insert
    suspend fun insertSession(session: ScanSession): Long

    @Query("SELECT * FROM scan_sessions ORDER BY startTime DESC")
    suspend fun getAllSessions(): List<ScanSession>

    @Query("DELETE FROM scan_sessions")
    suspend fun clearAllSessions()
}
