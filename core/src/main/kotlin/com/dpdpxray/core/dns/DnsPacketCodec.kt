package com.dpdpxray.core.dns

/** A DNS query read off the VPN tun interface. Addresses and ports are as sent by the app. */
class DnsQueryPacket(
    val ipVersion: Int,
    val srcAddress: ByteArray,
    val dstAddress: ByteArray,
    val srcPort: Int,
    val dstPort: Int,
    val dnsPayload: ByteArray,
    val dnsId: Int,
    val hostname: String,
    val queryType: Int,
)

/**
 * Minimal, allocation-light parser/builder for the only traffic our VPN routes into the tun:
 * UDP DNS queries to the fake resolver address. Anything else is rejected with null.
 */
object DnsPacketCodec {
    private const val PROTO_UDP = 17
    private const val DNS_PORT = 53
    private const val MAX_POINTER_JUMPS = 16
    private const val MAX_NAME_LENGTH = 255

    fun parse(packet: ByteArray, length: Int = packet.size): DnsQueryPacket? {
        if (length < 1 || length > packet.size) return null
        return when ((packet[0].toInt() and 0xf0) shr 4) {
            4 -> parseV4(packet, length)
            6 -> parseV6(packet, length)
            else -> null
        }
    }

    private fun parseV4(p: ByteArray, length: Int): DnsQueryPacket? {
        if (length < 20) return null
        val ihl = (p[0].toInt() and 0x0f) * 4
        if (ihl < 20 || length < ihl + 8) return null
        if (u8(p, 9) != PROTO_UDP) return null
        val totalLength = u16(p, 2)
        if (totalLength > length || totalLength < ihl + 8) return null
        val fragment = u16(p, 6) and 0x3fff
        if (fragment != 0) return null
        return parseUdp(4, p.copyOfRange(12, 16), p.copyOfRange(16, 20), p, ihl, totalLength)
    }

    private fun parseV6(p: ByteArray, length: Int): DnsQueryPacket? {
        if (length < 48) return null
        if (u8(p, 6) != PROTO_UDP) return null // extension headers are not expected for DNS
        val end = 40 + u16(p, 4)
        if (end > length) return null
        return parseUdp(6, p.copyOfRange(8, 24), p.copyOfRange(24, 40), p, 40, end)
    }

    private fun parseUdp(version: Int, src: ByteArray, dst: ByteArray, p: ByteArray, off: Int, end: Int): DnsQueryPacket? {
        if (end < off + 8) return null
        val srcPort = u16(p, off)
        val dstPort = u16(p, off + 2)
        if (dstPort != DNS_PORT) return null
        val udpLength = u16(p, off + 4)
        if (udpLength < 8 || off + udpLength > end) return null
        val dns = p.copyOfRange(off + 8, off + udpLength)
        val (host, qtype) = parseQuestion(dns) ?: return null
        return DnsQueryPacket(version, src, dst, srcPort, dstPort, dns, u16(dns, 0), host, qtype)
    }

    /** Returns the first question's (lowercased name, qtype), or null if the message is malformed. */
    fun parseQuestion(dns: ByteArray): Pair<String, Int>? {
        if (dns.size < 12) return null
        if (u16(dns, 4) < 1) return null
        val name = StringBuilder()
        var pos = 12
        var jumps = 0
        var endOfQuestion = -1
        while (true) {
            if (pos >= dns.size) return null
            val len = u8(dns, pos)
            when {
                len == 0 -> {
                    if (endOfQuestion < 0) endOfQuestion = pos + 1
                    break
                }
                len and 0xC0 == 0xC0 -> {
                    if (pos + 1 >= dns.size) return null
                    if (++jumps > MAX_POINTER_JUMPS) return null
                    if (endOfQuestion < 0) endOfQuestion = pos + 2
                    val target = ((len and 0x3f) shl 8) or u8(dns, pos + 1)
                    if (target >= pos) return null // pointers must point backwards
                    pos = target
                }
                len and 0xC0 != 0 -> return null
                else -> {
                    if (pos + 1 + len > dns.size) return null
                    if (name.isNotEmpty()) name.append('.')
                    for (i in pos + 1..pos + len) name.append((dns[i].toInt() and 0xff).toChar())
                    if (name.length > MAX_NAME_LENGTH) return null
                    pos += 1 + len
                }
            }
        }
        if (endOfQuestion + 4 > dns.size) return null
        if (name.isEmpty()) return null
        return name.toString().lowercase() to u16(dns, endOfQuestion)
    }

    /** Wraps [dnsResponse] in IP/UDP headers addressed back to the querying app. */
    fun buildResponse(query: DnsQueryPacket, dnsResponse: ByteArray): ByteArray {
        val udpLength = 8 + dnsResponse.size
        return if (query.ipVersion == 4) {
            val pkt = ByteArray(20 + udpLength)
            pkt[0] = 0x45
            put16(pkt, 2, pkt.size)
            put16(pkt, 6, 0x4000) // don't fragment
            pkt[8] = 64
            pkt[9] = PROTO_UDP.toByte()
            query.dstAddress.copyInto(pkt, 12)
            query.srcAddress.copyInto(pkt, 16)
            put16(pkt, 10, checksum(pkt, 0, 20, 0))
            writeUdp(pkt, 20, query, dnsResponse)
            val pseudo = query.dstAddress + query.srcAddress + byteArrayOf(0, PROTO_UDP.toByte(), (udpLength shr 8).toByte(), udpLength.toByte())
            put16(pkt, 26, udpChecksum(pseudo, pkt, 20))
            pkt
        } else {
            val pkt = ByteArray(40 + udpLength)
            pkt[0] = 0x60
            put16(pkt, 4, udpLength)
            pkt[6] = PROTO_UDP.toByte()
            pkt[7] = 64
            query.dstAddress.copyInto(pkt, 8)
            query.srcAddress.copyInto(pkt, 24)
            writeUdp(pkt, 40, query, dnsResponse)
            val pseudo = query.dstAddress + query.srcAddress +
                byteArrayOf(0, 0, (udpLength shr 8).toByte(), udpLength.toByte(), 0, 0, 0, PROTO_UDP.toByte())
            put16(pkt, 46, udpChecksum(pseudo, pkt, 40))
            pkt
        }
    }

    /**
     * Returns a copy of [dns] with the TTL of every answer/authority/additional record set to 0 (EDNS OPT records are
     * skipped: their TTL field holds flags). Android does not cache TTL-0 answers, so every repeat lookup during the
     * audit reaches the tunnel — without this, a tracker contacted again after "Reject" would be answered from cache
     * and stay invisible. Malformed input is returned unchanged.
     */
    fun zeroTtls(dns: ByteArray): ByteArray {
        if (dns.size < 12) return dns
        val out = dns.copyOf()
        var pos = 12
        repeat(u16(dns, 4)) { pos = skipName(dns, pos) ?: return dns; pos += 4 }
        val records = u16(dns, 6) + u16(dns, 8) + u16(dns, 10)
        repeat(records) {
            pos = skipName(dns, pos) ?: return dns
            if (pos + 10 > dns.size) return dns
            val type = u16(dns, pos)
            if (type != 41) { out[pos + 4] = 0; out[pos + 5] = 0; out[pos + 6] = 0; out[pos + 7] = 0 }
            pos += 10 + u16(dns, pos + 8)
            if (pos > dns.size) return dns
        }
        return out
    }

    private fun skipName(dns: ByteArray, start: Int): Int? {
        var pos = start
        while (pos < dns.size) {
            val len = u8(dns, pos)
            when {
                len == 0 -> return pos + 1
                len and 0xC0 == 0xC0 -> return (pos + 2).takeIf { it <= dns.size }
                else -> pos += 1 + len
            }
        }
        return null
    }

    /** A SERVFAIL answer for [dnsQuery], so the app fails fast instead of waiting for a timeout. */
    fun servFail(dnsQuery: ByteArray): ByteArray {
        val questionEnd = questionEnd(dnsQuery) ?: dnsQuery.size
        val out = dnsQuery.copyOf(questionEnd)
        out[2] = (0x80 or (u8(dnsQuery, 2) and 0x01)).toByte() // QR=1, keep RD
        out[3] = 0x82.toByte() // RA=1, RCODE=2
        put16(out, 6, 0); put16(out, 8, 0); put16(out, 10, 0)
        return out
    }

    private fun questionEnd(dns: ByteArray): Int? {
        var pos = 12
        while (pos < dns.size) {
            val len = u8(dns, pos)
            if (len == 0) return (pos + 5).takeIf { it <= dns.size }
            if (len and 0xC0 == 0xC0) return (pos + 6).takeIf { it <= dns.size }
            pos += 1 + len
        }
        return null
    }

    private fun writeUdp(pkt: ByteArray, off: Int, query: DnsQueryPacket, payload: ByteArray) {
        put16(pkt, off, query.dstPort)
        put16(pkt, off + 2, query.srcPort)
        put16(pkt, off + 4, 8 + payload.size)
        payload.copyInto(pkt, off + 8)
    }

    private fun udpChecksum(pseudo: ByteArray, pkt: ByteArray, udpOff: Int): Int {
        val sum = checksum(pkt, udpOff, pkt.size, partialSum(pseudo, 0, pseudo.size))
        return if (sum == 0) 0xffff else sum
    }

    private fun checksum(data: ByteArray, from: Int, to: Int, initial: Long): Int {
        var sum = initial + partialSum(data, from, to)
        while (sum shr 16 != 0L) sum = (sum and 0xffff) + (sum shr 16)
        return (sum.inv() and 0xffff).toInt()
    }

    private fun partialSum(data: ByteArray, from: Int, to: Int): Long {
        var sum = 0L
        var i = from
        while (i + 1 < to) {
            sum += (u8(data, i) shl 8) or u8(data, i + 1)
            i += 2
        }
        if (i < to) sum += u8(data, i) shl 8
        return sum
    }

    private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xff
    private fun u16(b: ByteArray, i: Int) = (u8(b, i) shl 8) or u8(b, i + 1)
    private fun put16(b: ByteArray, i: Int, v: Int) {
        b[i] = (v shr 8).toByte(); b[i + 1] = v.toByte()
    }
}
