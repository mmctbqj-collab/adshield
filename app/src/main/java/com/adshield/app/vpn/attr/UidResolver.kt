package com.adshield.app.vpn.attr

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.system.OsConstants
import android.util.Log
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap

/**
 * 把 TUN 中读到的 DNS 请求归属到具体 App。
 *
 * Android 10+ 提供 `ConnectivityManager.getConnectionOwnerUid`，这是免 root 条件下
 * 唯一可行的路径。很多 ROM / 系统代理场景会返回 INVALID_UID，此时只能如实归为"未归因"。
 */
class UidResolver(context: Context) {

    data class Owner(val uid: Int, val pkg: String?)

    private val connectivity: ConnectivityManager? =
        context.getSystemService(ConnectivityManager::class.java)

    private val packageManager: PackageManager = context.packageManager

    private class Entry(val owner: Owner, val expiresAt: Long)

    private val cache = ConcurrentHashMap<Key, Entry>(256)

    private data class Key(val srcAddr: Int, val srcPort: Int, val dstAddr: Int, val dstPort: Int)

    @Volatile
    var supported: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        private set

    fun resolveUdp(
        srcIp: ByteArray,
        srcPort: Int,
        dstIp: ByteArray,
        dstPort: Int,
    ): Owner {
        if (!supported) return UNKNOWN
        val key = keyOf(srcIp, srcPort, dstIp, dstPort)
        val now = SystemClock.elapsedRealtime()
        cache[key]?.let { if (it.expiresAt > now) return it.owner }

        val owner = query(srcIp, srcPort, dstIp, dstPort)
        if (cache.size > MAX_CACHE_SIZE) cache.clear()
        cache[key] = Entry(owner, now + CACHE_TTL_MS)
        return owner
    }

    private fun query(
        srcIp: ByteArray,
        srcPort: Int,
        dstIp: ByteArray,
        dstPort: Int,
    ): Owner {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return UNKNOWN
        val cm = connectivity ?: return UNKNOWN
        return try {
            val uid = cm.getConnectionOwnerUid(
                OsConstants.IPPROTO_UDP,
                InetSocketAddress(InetAddress.getByAddress(srcIp), srcPort),
                InetSocketAddress(InetAddress.getByAddress(dstIp), dstPort),
            )
            if (uid == Process.INVALID_UID) {
                UNKNOWN
            } else {
                val pkgs = packageManager.getPackagesForUid(uid)
                val pkg = pkgs?.firstOrNull()
                if (pkg == null) {
                    Owner(uid, null)
                } else {
                    Owner(uid, pkg)
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "getConnectionOwnerUid failed: ${t.javaClass.simpleName}")
            UNKNOWN
        }
    }

    private fun keyOf(a: ByteArray, aPort: Int, b: ByteArray, bPort: Int): Key =
        Key(
            ((a[0].toInt() and 0xFF) shl 24) or ((a[1].toInt() and 0xFF) shl 16) or
                ((a[2].toInt() and 0xFF) shl 8) or (a[3].toInt() and 0xFF),
            aPort,
            ((b[0].toInt() and 0xFF) shl 24) or ((b[1].toInt() and 0xFF) shl 16) or
                ((b[2].toInt() and 0xFF) shl 8) or (b[3].toInt() and 0xFF),
            bPort,
        )

    companion object {
        private const val TAG = "AdShieldUid"
        private const val CACHE_TTL_MS = 10_000L
        private const val MAX_CACHE_SIZE = 4096

        val UNKNOWN = Owner(-1, null)
    }
}