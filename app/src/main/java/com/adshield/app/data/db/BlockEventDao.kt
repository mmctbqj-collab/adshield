package com.adshield.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface BlockEventDao {

    /** 命中已有聚合行则累加计数；返回受影响行数，0 表示该 (day,domain,pkg) 尚不存在。 */
    @Query(
        "UPDATE block_event SET hit_count = hit_count + :delta, ts = :ts, rule_id = COALESCE(:ruleId, rule_id) " +
            "WHERE day = :day AND domain = :domain AND pkg = :pkg"
    )
    suspend fun bump(day: String, domain: String, pkg: String, delta: Int, ts: Long, ruleId: String?): Int

    /** 并发插入时靠唯一索引去重，返回 -1 表示已存在。 */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: BlockEventEntity): Long

    @Query("SELECT COALESCE(SUM(hit_count), 0) FROM block_event WHERE day = :day")
    suspend fun totalForDay(day: String): Long

    @Query("SELECT COALESCE(SUM(hit_count), 0) FROM block_event WHERE day = :day AND pkg = ''")
    suspend fun unattributedForDay(day: String): Long

    @Query(
        "SELECT domain, SUM(hit_count) AS total, MAX(ts) AS last_ts FROM block_event " +
            "WHERE day = :day GROUP BY domain ORDER BY total DESC LIMIT :limit"
    )
    suspend fun topDomains(day: String, limit: Int): List<DomainAgg>

    @Query(
        "SELECT pkg, SUM(hit_count) AS total FROM block_event " +
            "WHERE day = :day AND pkg <> '' GROUP BY pkg ORDER BY total DESC LIMIT :limit"
    )
    suspend fun topPackages(day: String, limit: Int): List<PackageAgg>

    @Query("SELECT * FROM block_event WHERE day = :day ORDER BY ts DESC LIMIT :limit")
    suspend fun recent(day: String, limit: Int): List<BlockEventEntity>

    @Query("DELETE FROM block_event WHERE day < :day")
    suspend fun deleteBefore(day: String): Int
}