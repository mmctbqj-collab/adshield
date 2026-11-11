package com.adshield.app.data

import com.adshield.app.data.db.BlockEventDao
import com.adshield.app.data.db.BlockEventEntity
import com.adshield.app.data.db.DomainAgg
import com.adshield.app.data.db.PackageAgg
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 拦截统计仓库。
 *
 * 写入路径（热路径）：DNS 线程只写内存 HashMap + StateFlow，绝不阻塞在磁盘上。
 * 落库路径：后台协程每 5 秒把内存聚合批量写入 Room（UPDATE 累加，不存在则 INSERT）。
 *
 * 内存计数 [_todayTotal] 与数据库可能出现 5 秒内不一致，这是刻意的取舍：
 * 首页要求"点了就有数字"，而持久化宁可延迟也不能拖慢 DNS 处理。
 */
class StatsRepository(
    private val dao: BlockEventDao,
    private val scope: CoroutineScope,
) {

    data class Snapshot(
        val day: String,
        val total: Long,
        val unattributed: Long = 0,
        val topDomains: List<DomainAgg> = emptyList(),
        val topPackages: List<PackageAgg> = emptyList(),
        val recent: List<BlockEventEntity> = emptyList(),
    )

    private data class Key(val day: String, val domain: String, val pkg: String)

    private class Pending(var count: Int, var ts: Long, var uid: Int, var ruleId: String?) {
        fun merge(other: Pending) {
            count += other.count
            if (other.ts > ts) ts = other.ts
            if (other.uid >= 0) uid = other.uid
            if (other.ruleId != null) ruleId = other.ruleId
        }
    }

    private val lock = Any()
    private val pending = HashMap<Key, Pending>(128)

    private val _todayTotal = MutableStateFlow(0L)
    val todayTotal: StateFlow<Long> = _todayTotal

    /** 每当有新事件写入时更新，UI 可据此触发列表刷新。 */
    private val _liveTick = MutableStateFlow(0L)
    val liveTick: StateFlow<Long> = _liveTick

    @Volatile
    private var currentDay: String = today()

    init {
        scope.launch {
            runCatching { _todayTotal.value = dao.totalForDay(currentDay) }
        }
        scope.launch { flushLoop() }
        scope.launch { dailyReloadLoop() }
    }

    /**
     * 记录一次拦截。由 DNS 线程调用，必须极快（微秒级）。
     * @param pkg 归因到的包名，null 表示未归因
     */
    fun record(
        domain: String,
        pkg: String?,
        uid: Int,
        ruleId: String?,
        ts: Long = System.currentTimeMillis(),
    ) {
        val day = dayOf(ts)
        if (day != currentDay) {
            currentDay = day
            _todayTotal.value = 0L
        }
        val key = Key(day, domain, pkg ?: "")
        synchronized(lock) {
            val existing = pending[key]
            if (existing == null) {
                pending[key] = Pending(1, ts, uid, ruleId)
            } else {
                existing.count++
                if (ts > existing.ts) existing.ts = ts
                if (uid >= 0) existing.uid = uid
                if (ruleId != null) existing.ruleId = ruleId
            }
        }
        _todayTotal.update { it + 1 }
        _liveTick.value = ts
    }

    private suspend fun flushLoop() {
        var ticks = 0L
        while (scope.isActive) {
            delay(FLUSH_INTERVAL_MS)
            runCatching { flush() }
            ticks++
            if (ticks % 1728L == 0L) { // 约 24 小时（5s * 1728）
                runCatching {
                    dao.deleteBefore(today().minusDays(RETENTION_DAYS).format(DATE_FORMAT))
                }
            }
        }
    }

    /** 跨天后重新加载当天总数（进程常驻时）。 */
    private suspend fun dailyReloadLoop() {
        while (scope.isActive) {
            delay(60_000L)
            val day = dayOf(System.currentTimeMillis())
            if (day != currentDay) {
                currentDay = day
                runCatching { _todayTotal.value = dao.totalForDay(day) }
            }
        }
    }

    private suspend fun flush() {
        val batch: Map<Key, Pending> = synchronized(lock) {
            if (pending.isEmpty()) return
            HashMap(pending).also { pending.clear() }
        }
        for ((key, value) in batch) {
            try {
                val updated = dao.bump(key.day, key.domain, key.pkg, value.count, value.ts, value.ruleId)
                if (updated == 0) {
                    val id = dao.insert(
                        BlockEventEntity(
                            ts = value.ts,
                            day = key.day,
                            domain = key.domain,
                            pkg = key.pkg,
                            uid = value.uid,
                            ruleId = value.ruleId,
                            hitCount = value.count,
                        )
                    )
                    if (id == -1L) {
                        // 并发下另一路径已插入：改为累加
                        dao.bump(key.day, key.domain, key.pkg, value.count, value.ts, value.ruleId)
                    }
                }
            } catch (t: Throwable) {
                // 写失败则放回内存，下一轮重试，确保不丢数据
                synchronized(lock) {
                    val existing = pending[key]
                    if (existing == null) pending[key] = value else existing.merge(value)
                }
            }
        }
    }

    suspend fun snapshot(topLimit: Int = 6, recentLimit: Int = 40): Snapshot {
        val day = dayOf(System.currentTimeMillis())
        val total = maxOf(_todayTotal.value, runCatching { dao.totalForDay(day) }.getOrDefault(0L))
        val unattributed = runCatching { dao.unattributedForDay(day) }.getOrDefault(0L)
        val topDomains = runCatching { dao.topDomains(day, topLimit) }.getOrDefault(emptyList())
        val topPackages = runCatching { dao.topPackages(day, topLimit) }.getOrDefault(emptyList())
        val recent = runCatching { dao.recent(day, recentLimit) }.getOrDefault(emptyList())
        return Snapshot(day, total, unattributed, topDomains, topPackages, recent)
    }

    companion object {
        private const val FLUSH_INTERVAL_MS = 5_000L
        private const val RETENTION_DAYS = 90L

        private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE

        fun today(): LocalDate = LocalDate.now()

        fun dayOf(epochMillis: Long): String =
            Instant.ofEpochMilli(epochMillis)
                .atZone(ZoneId.systemDefault())
                .toLocalDate()
                .format(DATE_FORMAT)
    }
}