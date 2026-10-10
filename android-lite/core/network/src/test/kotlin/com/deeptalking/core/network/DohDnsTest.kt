package com.deeptalking.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class DohDnsTest {

    /** Builds a DNS response with the given answers (name pointer + A records). */
    private fun response(answers: List<Pair<String, Int>>, rcode: Int = 0): ByteArray {
        val out = ByteArrayOutputStream()
        fun u16(v: Int) { out.write((v shr 8) and 0xFF); out.write(v and 0xFF) }
        u16(0x1234)
        u16(0x8180 or rcode) // QR=1, RD/RA, given rcode
        u16(1) // QDCOUNT
        u16(answers.size) // ANCOUNT
        u16(0); u16(0)
        // question: example.com A IN
        listOf("example", "com").forEach { label ->
            out.write(label.length); out.write(label.toByteArray(Charsets.US_ASCII))
        }
        out.write(0)
        u16(1); u16(1)
        // answers, all name-compressed to offset 12
        answers.forEach { (ip, type) ->
            out.write(0xC0); out.write(0x0C)
            u16(type); u16(1); u16(1); out.write(0); out.write(60) // TTL (low 2 bytes)
            val rdata = ip.split('.').map { it.toInt().toByte() }.toByteArray()
            u16(rdata.size)
            out.write(rdata)
        }
        return out.toByteArray()
    }

    @Test
    fun decodesIpv4Answers() {
        val bytes = response(listOf("1.2.3.4" to 1, "5.6.7.8" to 1))
        val addresses = DnsWire.decodeAddresses(bytes, "example.com")
        assertEquals(listOf("1.2.3.4", "5.6.7.8"), addresses.map { it.hostAddress })
    }

    @Test
    fun nonZeroRcodeYieldsNoAddresses() {
        val bytes = response(listOf("1.2.3.4" to 1), rcode = 3) // NXDOMAIN
        assertTrue(DnsWire.decodeAddresses(bytes, "example.com").isEmpty())
    }

    @Test
    fun encodeQueryWritesHeaderAndQuestion() {
        val query = DnsWire.encodeQuery("opencode.ai", 1)
        assertEquals(1, query[2].toInt()) // RD flag
        assertEquals(0, query[4].toInt()); assertEquals(1, query[5].toInt()) // QDCOUNT = 1
        // QTYPE at the tail is 0x00 0x01
        assertEquals(0, query[query.size - 4].toInt())
        assertEquals(1, query[query.size - 3].toInt())
    }

    @Test
    fun ipLiteralDetection() {
        assertTrue(DohDns.isIpLiteral("223.5.5.5"))
        assertTrue(DohDns.isIpLiteral("2606:4700:78::90:0:143"))
        assertFalse(DohDns.isIpLiteral("opencode.ai"))
    }

    @Test
    fun ipToBytesConvertsDottedQuad() {
        assertEquals(listOf<Byte>(1, 2, 3, 4), DohDns.ipToBytes("1.2.3.4").toList())
    }
}
