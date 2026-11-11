package com.adshield.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 一次"界面广告处理"记录。
 *
 * outcome：
 * - `SKIPPED`       点击成功且窗口确实变化
 * - `FAILED`        点击了但界面没变化（规则可能失效）
 * - `SAFETY_BLOCKED` 命中安全阀（危险词/频率/黑名单），只记录不点击
 */
@Entity(
    tableName = "popup_event",
    indices = [
        Index(value = ["day", "pkg"]),
        Index(value = ["ts"]),
    ],
)
data class PopupEventEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,
    @ColumnInfo(name = "ts")
    val ts: Long,
    @ColumnInfo(name = "day")
    val day: String,
    @ColumnInfo(name = "pkg")
    val pkg: String,
    @ColumnInfo(name = "activity")
    val activity: String? = null,
    @ColumnInfo(name = "rule_id")
    val ruleId: String,
    @ColumnInfo(name = "node_text")
    val nodeText: String? = null,
    @ColumnInfo(name = "outcome")
    val outcome: String,
    @ColumnInfo(name = "method")
    val method: String? = null,
    @ColumnInfo(name = "latency_ms")
    val latencyMs: Int = 0,
)

/** 按 App 聚合的跳过次数。 */
data class PopupPackageAgg(
    @ColumnInfo(name = "pkg") val pkg: String,
    @ColumnInfo(name = "total") val total: Long,
)