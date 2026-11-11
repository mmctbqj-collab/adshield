package com.adshield.app.vpn.dns

/**
 * 极简 DNS 报文编解码。
 *
 * 只处理 M1 需要的能力：解析 Question 段、构造空应答/NXDOMAIN/SERVFAIL。
 * 不做完整 RFC 实现（不需要解析 Answer，也不构造 OPT）。
 */
object DnsMessageCodec {

    const val HEADER_LENGTH = 12

    const val TYPE_A = 1
    const val TYPE_AAAA = 28
    const val TYPE_HTTPS = 65

    const val CLASS_IN = 1

    const val RCODE_NO_ERROR = 0
    const val RCODE_SERVFAIL = 2
    const val RCODE_NXDOMAIN = 3

    data class Question(
        val name: String,
        val type: Int,
        val qClass: Int,
        /** Question 段结束处的绝对偏移（相对传入的 ByteArray 起点）。 */
        val endOffset: Int,
    )

    /**
     * 解析首个 Question。失败返回 null（交由调用方决定是转发还是丢弃）。
     */
    fun parseQuestion(buf: ByteArray, offset: Int, length: Int): Question? {
        if (length < HEADER_LENGTH || offset < 0 || offset + length > buf.size) return null
        val end = offset + length
        var p = offset + HEADER_LENGTH
        val name = StringBuilder(64)
        var guard = 0
        while (p < end) {
            val labelLength = buf[p].toInt() and 0xFF
            if (labelLength == 0) {
                p += 1
                break
            }
            if (labelLength and 0xC0 == 0xC0) return null // 问题段出现压缩指针属异常报文
            if (p + 1 + labelLength > end) return null
            if (name.isNotEmpty()) name.append('.')
            name.append(String(buf, p + 1, labelLength, Charsets.US_ASCII))
            p += 1 + labelLength
            if (++guard > 32 || name.length > 255) return null
        }
        if (p + 4 > end) return null
        val type = ((buf[p].toInt() and 0xFF) shl 8) or (buf[p + 1].toInt() and 0xFF)
        val qClass = ((buf[p + 2].toInt() and 0xFF) shl 8) or (buf[p + 3].toInt() and 0xFF)
        return Question(name.toString().lowercase(), type, qClass, p + 4)
    }

    enum class BlockedMode {
        /** A → 0.0.0.0，AAAA/HTTPS → 空应答。App 会快速失败并回退。 */
        NULL_ADDRESS,
        /** 直接返回 NXDOMAIN。对"检测 0.0.0.0 后反复重试"的 App 更彻底。 */
        NXDOMAIN,
    }

    fun buildBlockedResponse(
        query: ByteArray,
        offset: Int,
        length: Int,
        question: Question,
        mode: BlockedMode,
    ): ByteArray {
        val questionEnd = question.endOffset - offset
        if (questionEnd <= HEADER_LENGTH || questionEnd > length) return ByteArray(0)
        val answerCount = if (mode == BlockedMode.NULL_ADDRESS &&
            question.type == TYPE_A && question.qClass == CLASS_IN
        ) 1 else 0
        val rcode = if (mode == BlockedMode.NXDOMAIN) RCODE_NXDOMAIN else RCODE_NO_ERROR
        return buildResponse(query, offset, questionEnd, answerCount, rcode)
    }

    fun buildErrorResponse(
        query: ByteArray,
        offset: Int,
        length: Int,
        question: Question,
        rcode: Int,
    ): ByteArray {
        val questionEnd = question.endOffset - offset
        if (questionEnd <= HEADER_LENGTH || questionEnd > length) return ByteArray(0)
        return buildResponse(query, offset, questionEnd, answerCount = 0, rcode = rcode)
    }

    private fun buildResponse(
        query: ByteArray,
        offset: Int,
        questionEnd: Int,
        answerCount: Int,
        rcode: Int,
    ): ByteArray {
        val answerLength = if (answerCount > 0) 16 else 0
        val out = ByteArray(questionEnd + answerLength)
        System.arraycopy(query, offset, out, 0, questionEnd)

        // Flags: QR=1, Opcode=0, AA=0, TC=0, RD=1, RA=1, RCODE
        out[2] = 0x81.toByte()
        out[3] = (0x80 or (rcode and 0x0F)).toByte()
        out[4] = 0
        out[5] = 1                                  // QDCOUNT
        out[6] = 0
        out[7] = answerCount.toByte()               // ANCOUNT
        out[8] = 0
        out[9] = 0                                  // NSCOUNT
        out[10] = 0
        out[11] = 0                                 // ARCOUNT（丢弃原 OPT）

        if (answerCount > 0) {
            var p = questionEnd
            out[p++] = 0xC0.toByte()                // 指向 Question 中的域名
            out[p++] = 0x0C
            out[p++] = 0
            out[p++] = TYPE_A.toByte()
            out[p++] = 0
            out[p++] = CLASS_IN.toByte()
            out[p++] = 0; out[p++] = 0; out[p++] = 0; out[p++] = 60  // TTL 60s
            out[p++] = 0
            out[p++] = 4
            out[p++] = 0; out[p++] = 0; out[p++] = 0; out[p++] = 0  // 0.0.0.0
        }
        return out
    }
}