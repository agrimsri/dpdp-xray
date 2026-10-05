package com.dpdpxray.core.dns

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class DnsPacketCodecTest {

    @Test
    fun `parses an IPv4 A query`() {
        val packet = TestPackets.ipv4Udp(TestPackets.dnsQuery("app-measurement.com", 1, id = 0xBEEF))
        val q = DnsPacketCodec.parse(packet)
        assertNotNull(q)
        q!!
        assertEquals(4, q.ipVersion)
        assertEquals("app-measurement.com", q.hostname)
        assertEquals(1, q.queryType)
        assertEquals(0xBEEF, q.dnsId)
        assertEquals(40000, q.srcPort)
        assertEquals(53, q.dstPort)
    }

    @Test
    fun `parses an IPv6 AAAA query`() {
        val packet = TestPackets.ipv6Udp(TestPackets.dnsQuery("graph.facebook.com", 28))
        val q = DnsPacketCodec.parse(packet)!!
        assertEquals(6, q.ipVersion)
        assertEquals("graph.facebook.com", q.hostname)
        assertEquals(28, q.queryType)
    }

    @Test
    fun `parses an HTTPS record query and lowercases the name`() {
        val packet = TestPackets.ipv4Udp(TestPackets.dnsQuery("Firebase-Settings.Crashlytics.COM", 65))
        val q = DnsPacketCodec.parse(packet)!!
        assertEquals("firebase-settings.crashlytics.com", q.hostname)
        assertEquals(65, q.queryType)
    }

    @Test
    fun `only reads the given length of a reused buffer`() {
        val packet = TestPackets.ipv4Udp(TestPackets.dnsQuery("example.com", 1))
        val buffer = packet + ByteArray(500) { 0x7f }
        assertEquals("example.com", DnsPacketCodec.parse(buffer, packet.size)!!.hostname)
    }

    @Test
    fun `rejects TCP packets`() {
        val packet = TestPackets.ipv4Udp(TestPackets.dnsQuery("example.com", 1), protocol = 6)
        assertNull(DnsPacketCodec.parse(packet))
    }

    @Test
    fun `rejects UDP that is not addressed to port 53`() {
        val packet = TestPackets.ipv4Udp(TestPackets.dnsQuery("example.com", 1), dstPort = 443)
        assertNull(DnsPacketCodec.parse(packet))
    }

    @Test
    fun `rejects truncated packets at every cut point`() {
        val packet = TestPackets.ipv4Udp(TestPackets.dnsQuery("app-measurement.com", 1))
        for (cut in 0 until packet.size) {
            assertNull("cut at $cut", DnsPacketCodec.parse(packet, cut))
        }
    }

    @Test
    fun `rejects a compression-pointer loop instead of hanging`() {
        val header = TestPackets.dnsQuery("a.b", 1).copyOf(12)
        // question name is a pointer to itself (offset 12)
        val loop = header + byteArrayOf(0xC0.toByte(), 12, 0, 1, 0, 1)
        assertNull(DnsPacketCodec.parseQuestion(loop))
    }

    @Test
    fun `rejects a query with zero questions`() {
        val dns = TestPackets.dnsQuery("example.com", 1)
        dns[5] = 0
        assertNull(DnsPacketCodec.parseQuestion(dns))
    }

    @Test
    fun `IPv4 response swaps endpoints and carries valid checksums`() {
        val query = DnsPacketCodec.parse(TestPackets.ipv4Udp(TestPackets.dnsQuery("example.com", 1)))!!
        val answer = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9) // odd length on purpose
        val pkt = DnsPacketCodec.buildResponse(query, answer)

        assertEquals(20 + 8 + answer.size, pkt.size)
        assertEquals(pkt.size, ((pkt[2].toInt() and 0xff) shl 8) or (pkt[3].toInt() and 0xff))
        assertArrayEquals(TestPackets.DNS_V4, pkt.copyOfRange(12, 16))
        assertArrayEquals(TestPackets.CLIENT_V4, pkt.copyOfRange(16, 20))
        assertEquals(53, ((pkt[20].toInt() and 0xff) shl 8) or (pkt[21].toInt() and 0xff))
        assertEquals(40000, ((pkt[22].toInt() and 0xff) shl 8) or (pkt[23].toInt() and 0xff))
        assertEquals(0xFFFF, TestPackets.onesComplementSum(pkt, 0, 20))
        assertEquals(0xFFFF, udpChecksumSum(pkt.copyOfRange(12, 16), pkt.copyOfRange(16, 20), pkt.copyOfRange(20, pkt.size)))
        assertArrayEquals(answer, pkt.copyOfRange(28, pkt.size))
    }

    @Test
    fun `IPv6 response carries a valid mandatory UDP checksum`() {
        val query = DnsPacketCodec.parse(TestPackets.ipv6Udp(TestPackets.dnsQuery("example.com", 28)))!!
        val answer = ByteArray(40) { it.toByte() }
        val pkt = DnsPacketCodec.buildResponse(query, answer)

        assertEquals(40 + 8 + answer.size, pkt.size)
        assertArrayEquals(TestPackets.DNS_V6, pkt.copyOfRange(8, 24))
        assertArrayEquals(TestPackets.CLIENT_V6, pkt.copyOfRange(24, 40))
        assertEquals(0xFFFF, udpChecksumSum(pkt.copyOfRange(8, 24), pkt.copyOfRange(24, 40), pkt.copyOfRange(40, pkt.size)))
    }

    @Test
    fun `SERVFAIL reply keeps the id and question and sets QR and RCODE 2`() {
        val dns = TestPackets.dnsQuery("blocked.example", 1, id = 0x0A0B)
        val fail = DnsPacketCodec.servFail(dns)
        assertEquals(0x0A, fail[0].toInt()); assertEquals(0x0B, fail[1].toInt())
        assertEquals(0x80, fail[2].toInt() and 0x80)
        assertEquals(2, fail[3].toInt() and 0x0f)
        assertEquals("blocked.example", DnsPacketCodec.parseQuestion(fail)!!.first)
    }

    @Test
    fun `zeroTtls sets every record TTL to 0 except OPT, so Android never caches lookups`() {
        val query = TestPackets.dnsQuery("graph.facebook.com", 1)
        val answer = TestPackets.dnsResponse(query, ttls = listOf(300, 3600), withOpt = true)
        val out = DnsPacketCodec.zeroTtls(answer)
        val ttls = TestPackets.recordTtls(out)
        assertEquals(listOf(0L, 0L, 4096L), ttls) // two A records zeroed; OPT's TTL field (EDNS flags) untouched
        assertEquals(answer.size, out.size)
        assertEquals("graph.facebook.com", DnsPacketCodec.parseQuestion(out)!!.first)
    }

    @Test
    fun `zeroTtls leaves malformed answers unchanged`() {
        val junk = byteArrayOf(1, 2, 3)
        assertArrayEquals(junk, DnsPacketCodec.zeroTtls(junk))
        val query = TestPackets.dnsQuery("a.example", 1)
        val truncated = TestPackets.dnsResponse(query, ttls = listOf(60), withOpt = false).let { it.copyOf(it.size - 3) }
        assertArrayEquals(truncated, DnsPacketCodec.zeroTtls(truncated))
    }

    private fun udpChecksumSum(src: ByteArray, dst: ByteArray, udp: ByteArray): Int {
        val pseudo = if (src.size == 4) {
            src + dst + byteArrayOf(0, 17, (udp.size shr 8).toByte(), udp.size.toByte())
        } else {
            src + dst + byteArrayOf(0, 0, (udp.size shr 8).toByte(), udp.size.toByte(), 0, 0, 0, 17)
        }
        return TestPackets.onesComplementSum(pseudo + udp)
    }
}
