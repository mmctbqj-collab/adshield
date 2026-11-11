package com.adshield.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 域名拦截聚合记录。
 *
 * 去重键 = (day, domain, pkg)：同一天内同一 App 对同一域名的多次请求合并为一行，
 * 用 [hitCount] 累加，避免高频 DNS 查询把表写爆。
 *
 * pkg 为空串表示"未归因"（设备/ROM 无法确定来源 App），不使用 NULL，
 * 以便唯一索引真正生效（SQLite 唯一索引对 NULL 不去重）。
 */
@Entity(
    tableName = "block_event",
    indices = [
        Index(value = ["day", "domain", "pkg"], unique = true),
        Index(value = ["day", "pkg"]),
    ],
)
data class BlockEventEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,
    @ColumnInfo(name = "ts")
    val ts: Long,
    @ColumnInfo(name = "day")
    val day: String,
    @ColumnInfo(name = "domain")
    val domain: String,
    @ColumnInfo(name = "pkg")
    val pkg: String = "",
    @ColumnInfo(name = "uid")
    val uid: Int = -1,
    @ColumnInfo(name = "rule_id")
    val ruleId: String? = null,
    @ColumnInfo(name = "hit_count")
    val hitCount: Int = 1,
)

/** 域名维度聚合查询结果（非实体）。 */
data class DomainAgg(
    @ColumnInfo(name = "domain") val domain: String,
    @ColumnInfo(name = "total") val total: Long,
    @ColumnInfo(name = "last_ts") val lastTs: Long,
)

/** App 维度聚合查询结果（非实体）。 */
data class PackageAgg(
    @ColumnInfo(name = "pkg") val pkg: String,
    @ColumnInfo(name = "total") val total: Long,
)