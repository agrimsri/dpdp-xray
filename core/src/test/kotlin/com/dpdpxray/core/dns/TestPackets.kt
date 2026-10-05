package com.dpdpxray.core.dns

import java.io.ByteArrayOutputStream

/** Builds raw IP/UDP/DNS packets byte-by-byte so codec tests do not depend on the codec itself. */
object TestPackets {
    val CLIENT_V4 = byteArrayOf(10, 111, 0, 2)
    val DNS_V4 = byteArrayOf(10, 111, 0, 53)
    val CLIENT_V6 = ByteArray(16) { if (it == 15) 2 else if (it == 0) 0xfd.toByte() else 0 }
    val DNS_V6 = ByteArray(16) { if (it == 15) 53 else if (it == 0) 0xfd.toByte() else 0 }

    fun dnsQuery(host: String, qtype: Int, id: Int = 0x1234): ByteArray {
        val out = ByteArrayOutputStream()
        out.write16(id)
        out.write16(0x0100) // standard query, recursion desired
        out.write16(1); out.write16(0); out.write16(0); out.write16(0)
        for (label in host.split('.')) {
            out.write(label.length)
            out.write(label.toByteArray(Charsets.US_ASCII))
        }
        out.write(0)
        out.write16(qtype)
        out.write16(1)
        return out.toByteArray()
    }

    fun ipv4Udp(payload: ByteArray, srcPort: Int = 40000, dstPort: Int = 53, protocol: Int = 17): ByteArray {
        val udpLen = 8 + payload.size
        val total = 20 + udpLen
        val out = ByteArrayOutputStream()
        out.write(0x45); out.write(0)
        out.write16(total)
        out.write16(0x0001); out.write16(0)
        out.write(64); out.write(protocol)
        out.write16(0) // checksum: codec must not rely on it
        out.write(CLIENT_V4); out.write(DNS_V4)
        out.write16(srcPort); out.write16(dstPort); out.write16(udpLen); out.write16(0)
        out.write(payload)
        return out.toByteArray()
    }

    fun ipv6Udp(payload: ByteArray, srcPort: Int = 40001, dstPort: Int = 53): ByteArray {
        val udpLen = 8 + payload.size
        val out = ByteArrayOutputStream()
        out.write(0x60); out.write(0); out.write16(0)
        out.write16(udpLen)
        out.write(17); out.write(64)
        out.write(CLIENT_V6); out.write(DNS_V6)
        out.write16(srcPort); out.write16(dstPort); out.write16(udpLen); out.write16(0)
        out.write(payload)
        return out.toByteArray()
    }

    /** A response to [query] with one A record per TTL (name = pointer to the question) and optionally an EDNS OPT record. */
    fun dnsResponse(query: ByteArray, ttls: List<Int>, withOpt: Boolean): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(query, 0, 2)
        out.write16(0x8180)
        out.write16(1); out.write16(ttls.size); out.write16(0); out.write16(if (withOpt) 1 else 0)
        out.write(query, 12, query.size - 12)
        for (ttl in ttls) {
            out.write16(0xC00C); out.write16(1); out.write16(1)
            out.write32(ttl.toLong()); out.write16(4); out.write(byteArrayOf(93, 184.toByte(), 216.toByte(), 34))
        }
        if (withOpt) {
            out.write(0); out.write16(41); out.write16(1232); out.write32(4096); out.write16(0)
        }
        return out.toByteArray()
    }

    /** TTL of every resource record after the question section, in order. */
    fun recordTtls(dns: ByteArray): List<Long> {
        var pos = 12
        while (dns[pos].toInt() != 0) pos += 1 + dns[pos]
        pos += 5
        val count = listOf(6, 8, 10).sumOf { ((dns[it].toInt() and 0xff) shl 8) or (dns[it + 1].toInt() and 0xff) }
        val out = mutableListOf<Long>()
        repeat(count) {
            pos += if (dns[pos].toInt() and 0xC0 == 0xC0) 2 else { var p = pos; while (dns[p].toInt() != 0) p += 1 + dns[p]; p - pos + 1 }
            val ttl = (0 until 4).fold(0L) { acc, i -> (acc shl 8) or (dns[pos + 4 + i].toLong() and 0xff) }
            out += ttl
            val rdlen = ((dns[pos + 8].toInt() and 0xff) shl 8) or (dns[pos + 9].toInt() and 0xff)
            pos += 10 + rdlen
        }
        return out
    }

    private fun ByteArrayOutputStream.write32(v: Long) {
        write(((v shr 24) and 0xff).toInt()); write(((v shr 16) and 0xff).toInt()); write(((v shr 8) and 0xff).toInt()); write((v and 0xff).toInt())
    }

    /** One's-complement sum over [data] (used to verify checksums: a valid header sums to 0xFFFF). */
    fun onesComplementSum(data: ByteArray, from: Int = 0, to: Int = data.size): Int {
        var sum = 0L
        var i = from
        while (i + 1 < to) {
            sum += ((data[i].toInt() and 0xff) shl 8) or (data[i + 1].toInt() and 0xff)
            i += 2
        }
        if (i < to) sum += (data[i].toInt() and 0xff) shl 8
        while (sum shr 16 != 0L) sum = (sum and 0xffff) + (sum shr 16)
        return sum.toInt()
    }

    private fun ByteArrayOutputStream.write16(v: Int) {
        write((v shr 8) and 0xff); write(v and 0xff)
    }
}
