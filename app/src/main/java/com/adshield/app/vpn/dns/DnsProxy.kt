package com.adshield.app.vpn.dns

import android.os.ParcelFileDescriptor
import android.util.Log
import com.adshield.app.rules.DomainMatcher
import com.adshield.app.vpn.attr.UidResolver
import java.io.Closeable
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * TUN 上的 DNS 代理。
 *
 * 线程模型：
 * - 一个阻塞读线程负责从 TUN 读 IP 包并切包；
 * - 固定 4 线程池处理 DNS 报文（解析、匹配、上游转发）；
 * - 所有写回串行化（TUN 的写不能交错）。
 *
 * 只处理 IPv4 + UDP/53。IPv6 包与 TCP/53 直接跳过（M1 不路由它们，不会断网）。
 */
class DnsProxy(
    tun: ParcelFileDescriptor,
    private val matcherProvider: () -> DomainMatcher,
    private val upstream: UpstreamResolver,
    private val uidResolver: UidResolver,
    private val onBlocked: (domain: String, pkg: String?, uid: Int, ruleId: String?) -> Unit,
    private val blockedMode: DnsMessageCodec.BlockedMode = DnsMessageCodec.BlockedMode.NULL_ADDRESS,
    private val onFatal: (reason: String) -> Unit = {},
) : Closeable {

    private val input: InputStream = FileInputStream(tun.fileDescriptor)
    private val output: OutputStream = FileOutputStream(tun.fileDescriptor)

    private val writeLock = Any()
    private val packetId = AtomicInteger(1)

    private val pool = Executors.newFixedThreadPool(POOL_SIZE) { runnable ->
        Thread(runnable, "adshield-dns-worker").apply { isDaemon = true }
    }

    private val blockedCount = AtomicLong(0)
    private val queriesCount = AtomicLong(0)

    @Volatile
    private var running = true

    fun start() {
        Thread({ readLoop() }, "adshield-tun-reader").apply { isDaemon = true }.start()
    }

    fun stats(): Pair<Long, Long> = queriesCount.get() to blockedCount.get()

    private fun readLoop() {
        val buffer = ByteArray(MAX_PACKET_BYTES)
        while (running) {
            val read = try {
                input.read(buffer)
            } catch (e: IOException) {
                if (running) Log.w(TAG, "tun read failed: ${e.javaClass.simpleName}")
                break
            } catch (t: Throwable) {
                Log.w(TAG, "tun read error: ${t.javaClass.simpleName}")
                break
            }
            if (read <= 0) continue

            var offset = 0
            while (offset < read && running) {
                val consumed = try {
                    handlePacket(buffer, offset, read - offset)
                } catch (t: Throwable) {
                    Log.w(TAG, "handlePacket error: ${t.javaClass.simpleName}")
                    0
                }
                if (consumed <= 0) break
                offset += consumed
            }
        }
        // 非主动关闭却退出读循环：TUN 仍在但没有消费方，DNS 会全部超时。
        // 必须通知 Service 立即停掉 VPN，把网络还原给系统。
        if (running) {
            running = false
            onFatal("TUN 读取循环意外退出")
        }
    }

    /**
     * @return 本包占用的字节数；<=0 表示无法解析，停止处理当前缓冲。
     */
    private fun handlePacket(buf: ByteArray, offset: Int, available: Int): Int {
        if (available < IPV4_MIN_HEADER) return 0
        val versionAndIhl = buf[offset].toInt() and 0xFF
        val version = versionAndIhl ushr 4

        if (version == 6) {
            if (available < IPV6_HEADER) return 0
            val payloadLength = readU16(buf, offset + 4)
            return IPV6_HEADER + payloadLength
        }
        if (version != 4) return 0

        val ihl = (versionAndIhl and 0x0F) * 4
        if (ihl < IPV4_MIN_HEADER || available < ihl + UDP_HEADER) return 0

        val totalLength = readU16(buf, offset + 2)
        if (totalLength < ihl + UDP_HEADER || totalLength > available) return 0

        val protocol = buf[offset + 9].toInt() and 0xFF
        if (protocol != IPPROTO_UDP) return totalLength // TCP/53 在 M1 不处理

        val srcPort = readU16(buf, offset + ihl)
        val dstPort = readU16(buf, offset + ihl + 2)
        if (dstPort != DNS_PORT) return totalLength

        val payloadOffset = offset + ihl + UDP_HEADER
        val payloadLength = totalLength - ihl - UDP_HEADER
        if (payloadLength < DnsMessageCodec.HEADER_LENGTH) return totalLength

        val srcIp = buf.copyOfRange(offset + 12, offset + 16)
        val dstIp = buf.copyOfRange(offset + 16, offset + 20)
        val payload = buf.copyOfRange(payloadOffset, payloadOffset + payloadLength)

        queriesCount.incrementAndGet()
        pool.execute {
            try {
                processDns(payload, srcIp, srcPort, dstIp, dstPort)
            } catch (t: Throwable) {
                Log.w(TAG, "process dns failed: ${t.javaClass.simpleName}")
            }
        }
        return totalLength
    }

    private fun processDns(
        payload: ByteArray,
        srcIp: ByteArray,
        srcPort: Int,
        dstIp: ByteArray,
        dstPort: Int,
    ) {
        val question = DnsMessageCodec.parseQuestion(payload, 0, payload.size)
        if (question == null) {
            forward(payload, srcIp, srcPort, dstIp, dstPort)
            return
        }

        val verdict = matcherProvider().match(question.name)
        if (!verdict.blocked) {
            forward(payload, srcIp, srcPort, dstIp, dstPort)
            return
        }

        val response = DnsMessageCodec.buildBlockedResponse(
            payload,
            0,
            payload.size,
            question,
            blockedMode,
        )
        if (response.isNotEmpty()) {
            writeBack(response, srcIp, srcPort, dstIp, dstPort)
        }
        blockedCount.incrementAndGet()

        val owner = uidResolver.resolveUdp(srcIp, srcPort, dstIp, dstPort)
        onBlocked(question.name, owner.pkg, owner.uid, verdict.ruleId)
    }

    private fun forward(
        payload: ByteArray,
        srcIp: ByteArray,
        srcPort: Int,
        dstIp: ByteArray,
        dstPort: Int,
    ) {
        val response = upstream.query(payload, UPSTREAM_TIMEOUT_MS)
        if (response == null) {
            val question = DnsMessageCodec.parseQuestion(payload, 0, payload.size)
            if (question != null) {
                val failure = DnsMessageCodec.buildErrorResponse(
                    payload,
                    0,
                    payload.size,
                    question,
                    DnsMessageCodec.RCODE_SERVFAIL,
                )
                if (failure.isNotEmpty()) writeBack(failure, srcIp, srcPort, dstIp, dstPort)
            }
            return
        }
        writeBack(response, srcIp, srcPort, dstIp, dstPort)
    }

    /** 构造 IPv4 + UDP 包写回 TUN。源/目的地址与端口都相对原请求反转。 */
    private fun writeBack(
        payload: ByteArray,
        srcIp: ByteArray,
        srcPort: Int,
        dstIp: ByteArray,
        dstPort: Int,
    ) {
        val udpLength = UDP_HEADER + payload.size
        val totalLength = IPV4_MIN_HEADER + udpLength
        if (totalLength > MAX_PACKET_BYTES) return

        val packet = ByteArray(totalLength)
        packet[0] = 0x45
        packet[1] = 0
        packet[2] = (totalLength ushr 8).toByte()
        packet[3] = totalLength.toByte()
        val id = packetId.incrementAndGet() and 0xFFFF
        packet[4] = (id ushr 8).toByte()
        packet[5] = id.toByte()
        packet[6] = 0x40 // DF
        packet[7] = 0
        packet[8] = 64
        packet[9] = IPPROTO_UDP.toByte()
        System.arraycopy(dstIp, 0, packet, 12, 4)
        System.arraycopy(srcIp, 0, packet, 16, 4)
        val headerChecksum = checksum(packet, 0, IPV4_MIN_HEADER)
        packet[10] = (headerChecksum ushr 8).toByte()
        packet[11] = headerChecksum.toByte()

        packet[20] = (dstPort ushr 8).toByte()
        packet[21] = dstPort.toByte()
        packet[22] = (srcPort ushr 8).toByte()
        packet[23] = srcPort.toByte()
        packet[24] = (udpLength ushr 8).toByte()
        packet[25] = udpLength.toByte()
        packet[26] = 0
        packet[27] = 0 // IPv4 允许 UDP 校验和为 0

        System.arraycopy(payload, 0, packet, 28, payload.size)

        synchronized(writeLock) {
            if (!running) return
            try {
                output.write(packet)
                output.flush()
            } catch (t: Throwable) {
                Log.w(TAG, "tun write failed: ${t.javaClass.simpleName}")
            }
        }
    }

    override fun close() {
        running = false
        runCatching { pool.shutdownNow() }
        runCatching { input.close() }
        runCatching { output.close() }
    }

    private fun readU16(buf: ByteArray, index: Int): Int =
        ((buf[index].toInt() and 0xFF) shl 8) or (buf[index + 1].toInt() and 0xFF)

    /** 标准 IPv4 头校验和。 */
    private fun checksum(data: ByteArray, offset: Int, length: Int): Int {
        var sum = 0L
        var i = offset
        val end = offset + length
        while (i + 1 < end) {
            sum += (((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)).toLong()
            i += 2
        }
        if (i < end) sum += ((data[i].toInt() and 0xFF) shl 8).toLong()
        while (sum shr 16 != 0L) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }
        return sum.inv().toInt() and 0xFFFF
    }

    companion object {
        private const val TAG = "AdShieldDns"
        private const val POOL_SIZE = 4
        private const val DNS_PORT = 53
        private const val IPPROTO_UDP = 17
        private const val IPV4_MIN_HEADER = 20
        private const val IPV6_HEADER = 40
        private const val UDP_HEADER = 8
        private const val MAX_PACKET_BYTES = 32767
        private const val UPSTREAM_TIMEOUT_MS = 3000
    }
}