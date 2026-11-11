package com.adshield.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface PopupEventDao {

    @Insert
    suspend fun insert(event: PopupEventEntity): Long

    @Query("SELECT COUNT(*) FROM popup_event WHERE day = :day AND outcome = 'SKIPPED'")
    suspend fun countSkipped(day: String): Long

    @Query("SELECT COUNT(*) FROM popup_event WHERE day = :day")
    suspend fun countAll(day: String): Long

    @Query("SELECT * FROM popup_event WHERE day = :day ORDER BY ts DESC LIMIT :limit")
    suspend fun recent(day: String, limit: Int): List<PopupEventEntity>

    @Query(
        "SELECT pkg, COUNT(*) AS total FROM popup_event " +
            "WHERE day = :day AND outcome = 'SKIPPED' GROUP BY pkg ORDER BY total DESC LIMIT :limit"
    )
    suspend fun topPackages(day: String, limit: Int): List<PopupPackageAgg>

    @Query("DELETE FROM popup_event WHERE day < :day")
    suspend fun deleteBefore(day: String): Int
}