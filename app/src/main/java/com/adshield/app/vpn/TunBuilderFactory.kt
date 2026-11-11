package com.adshield.app.vpn

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.VpnService
import android.util.Log
import com.adshield.app.ui.MainActivity
import java.net.Inet4Address
import java.net.InetAddress

/**
 * TUN 参数组装。
 *
 * 核心约束：**只把 DNS 目标地址路由进 TUN**。
 * 若使用 0.0.0.0/0 全路由，而本模块并不实现 TCP/IP 转发，整机会断网。
 */
object TunBuilderFactory {

    private const val TAG = "AdShieldTun"

    /** 本端虚拟地址；系统 DNS 被指向它，因此它自己也要有一条 /32 路由。 */
    const val TUN_ADDRESS = "10.111.222.1"
    private const val TUN_PREFIX = 32
    private const val MTU = 1500

    /** App 硬编码 DNS 时可能是这些公共 DNS，需要一并路由进 TUN。 */
    private val PUBLIC_DNS = listOf(
        "8.8.8.8",
        "8.8.4.4",
        "1.1.1.1",
        "1.0.0.1",
        "9.9.9.9",
        "114.114.114.114",
        "114.114.115.115",
        "223.5.5.5",
        "223.6.6.6",
        "119.29.29.29",
        "180.76.76.76",
    )

    fun build(service: VpnService): VpnService.Builder {
        val builder = service.Builder()
            .setSession("AdShield")
            .addAddress(TUN_ADDRESS, TUN_PREFIX)
            .addDnsServer(TUN_ADDRESS)
            .addRoute(TUN_ADDRESS, TUN_PREFIX)
            .setMtu(MTU)
            .setBlocking(true)

        // 自身流量不过 VPN，避免规则订阅/更新回环
        runCatching { builder.addDisallowedApplication(service.packageName) }
            .onFailure { Log.w(TAG, "addDisallowedApplication failed: ${it.javaClass.simpleName}") }

        runCatching {
            builder.setConfigureIntent(
                PendingIntent.getActivity(
                    service,
                    0,
                    Intent(service, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            )
        }

        val targets = dnsTargets(service)
        for (address in targets) {
            val host = address.hostAddress ?: continue
            runCatching { builder.addRoute(host, 32) }
                .onFailure { Log.w(TAG, "addRoute $host failed") }
        }
        Log.i(TAG, "routes = ${targets.joinToString { it.hostAddress ?: "?" }}")
        return builder
    }

    /**
     * 需要被路由进 TUN 的 DNS 目标：系统当前各网络的 DNS + 常见公共 DNS。
     * M1 只处理 IPv4（IPv6 目标不加路由，保持系统默认路径，不会断网）。
     */
    fun dnsTargets(context: Context): List<Inet4Address> {
        val out = LinkedHashSet<Inet4Address>(16)
        runCatching {
            val cm = context.getSystemService(ConnectivityManager::class.java) ?: return@runCatching
            for (network in cm.allNetworks) {
                val link = cm.getLinkProperties(network) ?: continue
                for (dns in link.dnsServers) {
                    val v4 = dns as? Inet4Address ?: continue
                    if (v4.hostAddress == TUN_ADDRESS) continue
                    out.add(v4)
                }
            }
        }
        for (host in PUBLIC_DNS) {
            runCatching { InetAddress.getByName(host) as? Inet4Address }.getOrNull()?.let(out::add)
        }
        return out.toList()
    }
}