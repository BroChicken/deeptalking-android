package com.deeptalking.core.network

import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * [Dns] that resolves hostnames over DNS-over-HTTPS (RFC 8484 wire format),
 * falling back to the system resolver.
 *
 * Why: some carriers hijack plaintext DNS for specific domains (e.g. returning a
 * bogus, unreachable IP for `opencode.ai`) while leaving encrypted DNS intact.
 * Chromium/WebView survived such networks because it uses Secure DNS; OkHttp uses
 * the system resolver. This class gives the native HTTP clients the same escape
 * hatch, using China-reachable providers (AliDNS / DNSPod) with a pinned bootstrap
 * IP so the DoH endpoint itself never has to be resolved via the hijacked system DNS.
 *
 * Every lookup falls back to [Dns.SYSTEM] on any failure, so behaviour on
 * unrestricted networks is unchanged (aside from one cached DoH round-trip).
 */
class DohDns(
    private val policy: () -> Policy,
) : Dns {

    /** Runtime policy: whether DoH is enabled and which provider to prefer. */
    data class Policy(val enabled: Boolean, val provider: String = PROVIDER_ALIDNS)

    private data class Provider(val id: String, val url: String, val bootstrapIps: List<String>)
    private data class CacheEntry(val addresses: List<InetAddress>, val expiresAt: Long)

    private val cache = ConcurrentHashMap<String, CacheEntry>()

    /** DoH transport: the endpoint host is resolved by pinned IP, never the DoH path itself. */
    private val dohClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS)
        .dns(BootstrapDns)
        .build()

    override fun lookup(hostname: String): List<InetAddress> {
        if (isIpLiteral(hostname)) return Dns.SYSTEM.lookup(hostname)
        val current = policy()
        if (!current.enabled) return Dns.SYSTEM.lookup(hostname)

        val now = System.currentTimeMillis()
        cache[hostname]?.let { if (it.expiresAt > now) return it.addresses }

        for (provider in providersFor(current.provider)) {
            val resolved = runCatching { resolve(provider, hostname) }.getOrNull()
            if (!resolved.isNullOrEmpty()) {
                cache[hostname] = CacheEntry(resolved, now + TTL_MS)
                return resolved
            }
        }
        return Dns.SYSTEM.lookup(hostname)
    }

    private fun resolve(provider: Provider, hostname: String): List<InetAddress> {
        val ipv4 = query(provider, hostname, TYPE_A)
        if (ipv4.isNotEmpty()) return ipv4
        return query(provider, hostname, TYPE_AAAA)
    }

    private fun query(provider: Provider, hostname: String, type: Int): List<InetAddress> {
        val body = DnsWire.encodeQuery(hostname, type).toRequestBody(DNS_MEDIA_TYPE)
        val request = Request.Builder()
            .url(provider.url)
            .header("Accept", "application/dns-message")
            .post(body)
            .build()
        return dohClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use emptyList()
            val bytes = response.body?.bytes() ?: return@use emptyList()
            DnsWire.decodeAddresses(bytes, hostname)
        }
    }

    private fun providersFor(provider: String): List<Provider> = when (provider) {
        PROVIDER_TENCENT -> listOf(TENCENT, ALIDNS)
        else -> listOf(ALIDNS, TENCENT)
    }

    /** Resolves the two DoH endpoint hostnames by their well-known IPs. */
    private object BootstrapDns : Dns {
        private val pinned: Map<String, List<String>> = mapOf(
            "dns.alidns.com" to ALIDNS.bootstrapIps,
            "doh.pub" to TENCENT.bootstrapIps,
        )

        override fun lookup(hostname: String): List<InetAddress> {
            val ips = pinned[hostname] ?: return Dns.SYSTEM.lookup(hostname)
            return ips.mapNotNull { ip ->
                runCatching { InetAddress.getByAddress(hostname, ipToBytes(ip)) }.getOrNull()
            }
        }
    }

    companion object {
        const val PROVIDER_ALIDNS = "alidns"
        const val PROVIDER_TENCENT = "tencent"

        private val DNS_MEDIA_TYPE = "application/dns-message".toMediaType()
        private const val TYPE_A = 1
        private const val TYPE_AAAA = 28
        private const val TTL_MS = 5 * 60 * 1000L

        private val ALIDNS = Provider(PROVIDER_ALIDNS, "https://dns.alidns.com/dns-query", listOf("223.5.5.5", "223.6.6.6"))
        private val TENCENT = Provider(PROVIDER_TENCENT, "https://doh.pub/dns-query", listOf("1.12.12.12", "120.53.53.53"))

        private val IPV4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")
        private val IPV6 = Regex("^[0-9a-fA-F:]+$")

        /** True for IPv4/IPv6 literals, which must bypass DoH entirely. */
        internal fun isIpLiteral(host: String): Boolean =
            IPV4.matches(host) || (host.contains(':') && IPV6.matches(host))

        /** IPv4 dotted-quad → 4 bytes (trusted bootstrap literals only). */
        internal fun ipToBytes(ip: String): ByteArray =
            ip.split('.').map { it.toInt().toByte() }.toByteArray()
    }
}

/**
 * Minimal RFC 1035 / RFC 8484 codec: encodes an A/AAAA query and extracts the
 * address records (and their minimum TTL) from a response. Hand-written to avoid
 * pulling in a DNS dependency.
 */
internal object DnsWire {

    /** Encodes a single-question query for [hostname] of [type] (1=A, 28=AAAA). */
    fun encodeQuery(hostname: String, type: Int): ByteArray {
        val out = ArrayList<Byte>(64)
        val id = Random.nextInt(0, 0xFFFF)
        out.add((id shr 8).toByte())
        out.add(id.toByte())
        out.add(0x01) // flags: RD=1
        out.add(0x00)
        out.add(0x00); out.add(0x01) // QDCOUNT = 1
        out.add(0x00); out.add(0x00) // ANCOUNT
        out.add(0x00); out.add(0x00) // NSCOUNT
        out.add(0x00); out.add(0x00) // ARCOUNT
        for (label in hostname.split('.')) {
            val bytes = label.toByteArray(Charsets.US_ASCII)
            out.add(bytes.size.toByte())
            bytes.forEach { out.add(it) }
        }
        out.add(0x00) // root label
        out.add(0x00); out.add(type.toByte()) // QTYPE
        out.add(0x00); out.add(0x01) // QCLASS = IN
        return out.toByteArray()
    }

    /**
     * Parses the answer records of [response], returning the A/AAAA addresses
     * (IPv4 first). Returns empty on a non-zero RCODE or a malformed packet.
     */
    fun decodeAddresses(response: ByteArray, hostname: String): List<InetAddress> {
        if (response.size < 12) return emptyList()
        val rcode = response[3].toInt() and 0x0F
        if (rcode != 0) return emptyList()
        val qdCount = u16(response, 4)
        val anCount = u16(response, 6)
        var offset = 12
        repeat(qdCount) { offset = skipName(response, offset) + 4 }
        val ipv4 = mutableListOf<InetAddress>()
        val ipv6 = mutableListOf<InetAddress>()
        repeat(anCount) {
            offset = skipName(response, offset)
            if (offset + 10 > response.size) return@repeat
            val type = u16(response, offset)
            val rdLength = u16(response, offset + 8)
            val rdStart = offset + 10
            val rdEnd = rdStart + rdLength
            if (rdEnd > response.size) return@repeat
            val rdata = response.copyOfRange(rdStart, rdEnd)
            when {
                type == 1 && rdLength == 4 -> runCatching { InetAddress.getByAddress(hostname, rdata) }.getOrNull()?.let(ipv4::add)
                type == 28 && rdLength == 16 -> runCatching { InetAddress.getByAddress(hostname, rdata) }.getOrNull()?.let(ipv6::add)
            }
            offset = rdEnd
        }
        return ipv4 + ipv6
    }

    /** Advances past a (possibly compressed) name, returning the index after it. */
    private fun skipName(data: ByteArray, start: Int): Int {
        var offset = start
        while (offset < data.size) {
            val length = data[offset].toInt() and 0xFF
            when {
                length == 0 -> return offset + 1
                length and 0xC0 == 0xC0 -> return offset + 2 // compression pointer
                else -> offset += 1 + length
            }
        }
        return offset
    }

    private fun u16(data: ByteArray, offset: Int): Int {
        if (offset + 1 >= data.size) return 0
        return ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
    }
}
