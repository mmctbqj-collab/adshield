package com.adshield.app.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.adshield.app.R
import com.adshield.app.appContainer
import com.adshield.app.ui.MainActivity
import com.adshield.app.vpn.dns.DnsProxy
import com.adshield.app.vpn.dns.UpstreamResolver

/**
 * 本地 DNS 过滤服务。
 *
 * 注意：`VpnService` 同一时刻只能存在一个。若用户已开启其它 VPN，
 * `establish()` 会失败或抢占，必须在 UI 层做冲突提示。
 */
class AdShieldVpnService : VpnService() {

    private val mainHandler = Handler(Looper.getMainLooper())

    private var tun: ParcelFileDescriptor? = null
    private var proxy: DnsProxy? = null
    private var upstream: UpstreamResolver? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopVpn()
            stopSelf()
            return START_NOT_STICKY
        }

        ensureChannel()
        startForegroundCompat()

        if (!startEngine()) {
            stopVpn()
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    private fun startEngine(): Boolean {
        if (proxy != null) return true

        val container = appContainer
        val builder = try {
            TunBuilderFactory.build(this)
        } catch (t: Throwable) {
            Log.e(TAG, "build tun failed", t)
            VpnState.markError(buildString { append("TUN 配置失败: "); append(t.javaClass.simpleName) })
            return false
        }

        val descriptor = try {
            builder.establish()
        } catch (t: Throwable) {
            Log.e(TAG, "establish failed", t)
            null
        }

        if (descriptor == null) {
            VpnState.markError(
                "无法建立 VPN。可能原因：未授权、已存在其它 VPN、或被系统策略拦截。"
            )
            return false
        }

        tun = descriptor

        val resolver = UpstreamResolver(this) { socket -> protect(socket) }
        upstream = resolver

        val dnsProxy = DnsProxy(
            tun = descriptor,
            matcherProvider = { container.rules.current() },
            upstream = resolver,
            uidResolver = container.uidResolver,
            onBlocked = { domain, pkg, uid, ruleId ->
                container.stats.record(
                    domain = domain,
                    pkg = pkg,
                    uid = uid,
                    ruleId = ruleId,
                )
            },
            onFatal = { reason ->
                mainHandler.post {
                    VpnState.markError(reason + "，已自动关闭过滤以恢复上网")
                    stopVpn()
                    stopSelf()
                }
            },
        )
        proxy = dnsProxy
        dnsProxy.start()

        VpnState.markRunning()
        container.settings.vpnWanted = true
        Log.i(TAG, "engine started, rules = ${container.rules.current().blockedCount}")
        return true
    }

    private fun stopVpn() {
        runCatching { proxy?.close() }
        proxy = null
        upstream = null
        runCatching { tun?.close() }
        tun = null

        VpnState.markStopped()
        runCatching { appContainer.settings.vpnWanted = false }
        runCatching {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        }
    }

    override fun onRevoke() {
        Log.w(TAG, "vpn revoked")
        stopVpn()
        stopSelf()
        super.onRevoke()
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }

    // ---------- 通知 ----------

    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val existing = manager.getNotificationChannel(CHANNEL_ID)
        if (existing != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.noti_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.noti_channel_desc)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, AdShieldVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_shield)
            .setContentTitle(getString(R.string.noti_title))
            .setContentText("本地过滤已开启 · 点击查看统计")
            .setOngoing(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(contentIntent)
            .addAction(0, getString(R.string.noti_action_stop), stopIntent)
            .build()
    }

    private fun startForegroundCompat() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val TAG = "AdShieldVpnService"
        private const val CHANNEL_ID = "adshield_vpn"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.adshield.app.action.VPN_START"
        const val ACTION_STOP = "com.adshield.app.action.VPN_STOP"

        /** 需在已获得 VpnService 授权的前提下调用（见 MainActivity 的 prepare 流程）。 */
        fun start(context: Context) {
            val intent = Intent(context, AdShieldVpnService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, AdShieldVpnService::class.java)) }
        }
    }
}