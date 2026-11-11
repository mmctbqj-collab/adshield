package com.adshield.app

import android.app.Application
import android.content.Context
import com.adshield.app.data.AppDatabase
import com.adshield.app.data.PopupDatabase
import com.adshield.app.data.PopupRepository
import com.adshield.app.data.SettingsStore
import com.adshield.app.data.StatsRepository
import com.adshield.app.rules.RuleRepository
import com.adshield.app.vpn.VpnState
import com.adshield.app.vpn.attr.UidResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * 极简手工依赖容器（M1 不引入 Hilt，减少构建风险）。
 * 所有组件与两个 Service 同进程，直接单例共享。
 */
class AppContainer(context: Context) {

    private val appContext: Context = context.applicationContext

    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settings: SettingsStore = SettingsStore(appContext)

    private val database: AppDatabase = AppDatabase.get(appContext)

    val stats: StatsRepository = StatsRepository(database.blockEventDao(), appScope)

    private val popupDatabase: PopupDatabase = PopupDatabase.get(appContext)

    val popups: PopupRepository = PopupRepository(popupDatabase.popupEventDao(), appScope)

    val rules: RuleRepository = RuleRepository(settings, appScope)

    val uidResolver: UidResolver = UidResolver(appContext)

    val vpnState: VpnState = VpnState
}

class AdShieldApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

/** 便于 Service / UI 获取容器。 */
val Context.appContainer: AppContainer
    get() = (applicationContext as AdShieldApp).container