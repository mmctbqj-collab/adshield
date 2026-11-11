package com.adshield.app.data

import com.adshield.app.data.db.PopupEventDao
import com.adshield.app.data.db.PopupEventEntity
import com.adshield.app.data.db.PopupPackageAgg
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 界面广告（弹窗 / 开屏跳过）记录仓库。
 *
 * 写入量远低于 DNS（每个界面最多几条），因此直接异步落库，不做批量聚合。
 */
class PopupRepository(
    private val dao: PopupEventDao,
    private val scope: CoroutineScope,
) {

    data class Snapshot(
        val skippedToday: Long,
        val totalToday: Long,
        val recent: List<PopupEventEntity>,
        val topPackages: List<PopupPackageAgg>,
    )

    private val _todaySkipped = MutableStateFlow(0L)
    val todaySkipped: StateFlow<Long> = _todaySkipped

    init {
        scope.launch {
            runCatching {
                _todaySkipped.value = dao.countSkipped(StatsRepository.today().toString())
            }
        }
    }

    fun record(event: PopupEventEntity) {
        if (event.outcome == OUTCOME_SKIPPED) {
            _todaySkipped.update { it + 1 }
        }
        scope.launch {
            runCatching { dao.insert(event) }
        }
    }

    suspend fun snapshot(recentLimit: Int = 20): Snapshot {
        val day = StatsRepository.today().toString()
        val skipped = maxOf(_todaySkipped.value, runCatching { dao.countSkipped(day) }.getOrDefault(0L))
        val total = runCatching { dao.countAll(day) }.getOrDefault(0L)
        val recent = runCatching { dao.recent(day, recentLimit) }.getOrDefault(emptyList())
        val top = runCatching { dao.topPackages(day, 6) }.getOrDefault(emptyList())
        return Snapshot(skipped, total, recent, top)
    }

    companion object {
        const val OUTCOME_SKIPPED = "SKIPPED"
        const val OUTCOME_FAILED = "FAILED"
        const val OUTCOME_SAFETY_BLOCKED = "SAFETY_BLOCKED"
    }
}