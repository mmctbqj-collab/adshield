package com.adshield.app.vpn.dns

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import com.adshield.app.vpn.TunBuilderFactory
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress

/**
 * 上游 DNS 转发器。
 *
 * 所有 socket 必须经 [protector]（即 `VpnService.protect`）排除在 VPN 之外，
 * 否则上游查询会再次进入 TUN，形成无限递归。
 */
class UpstreamResolver(
    private val context: Context,
    private val protector: (DatagramSocket) -> Boolean,
) {

    @Volatile
    private var upstreams: List<Inet4Address> = emptyList()

    init {
        refresh()
    }

    fun refresh() {
        val found = LinkedHashSet<Inet4Address>(8)
        runCatching {
            val cm = context.getSystemService(ConnectivityManager::class.java) ?: return@runCatching
            for (network in cm.allNetworks) {
                val link = cm.getLinkProperties(network) ?: continue
                for (dns in link.dnsServers) {
                    val v4 = dns as? Inet4Address ?: continue
                    // 必须排除自己的虚拟地址，否则会转给自己
                    if (v4.hostAddress == TunBuilderFactory.TUN_ADDRESS) continue
                    found.add(v4)
                }
            }
        }
        for (host in FALLBACK_DNS) {
            val v4 = runCatching { InetAddress.getByName(host) as? Inet4Address }.getOrNull() ?: continue
            found.add(v4)
        }
        upstreams = found.toList()
        Log.i(TAG, "upstreams = ${upstreams.joinToString { it.hostAddress ?: "?" }}")
    }

    fun query(query: ByteArray, timeoutMs: Int): ByteArray? {
        val targets = upstreams.ifEmpty {
            refresh()
            upstreams
        }
        if (targets.isEmpty()) return null

        for (address in targets) {
            val socket = runCatching { DatagramSocket() }.getOrNull() ?: continue
            try {
                if (!protector(socket)) {
                    Log.w(TAG, "protect() returned false, skip ${address.hostAddress}")
                    continue
                }
                socket.soTimeout = timeoutMs
                socket.send(DatagramPacket(query, query.size, address, DNS_PORT))
                val buffer = ByteArray(MAX_RESPONSE_BYTES)
                val packet = DatagramPacket(buffer, buffer.size)
                socket.receive(packet)
                if (packet.length >= DnsMessageCodec.HEADER_LENGTH) {
                    return buffer.copyOf(packet.length)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "query ${address.hostAddress} failed: ${t.javaClass.simpleName}")
            } finally {
                runCatching { socket.close() }
            }
        }
        return null
    }

    companion object {
        private const val TAG = "AdShieldUpstream"
        private const val DNS_PORT = 53
        private const val MAX_RESPONSE_BYTES = 4096

        private val FALLBACK_DNS = listOf(
            "223.5.5.5",
            "119.29.29.29",
            "1.1.1.1",
            "8.8.8.8",
        )
    }
}