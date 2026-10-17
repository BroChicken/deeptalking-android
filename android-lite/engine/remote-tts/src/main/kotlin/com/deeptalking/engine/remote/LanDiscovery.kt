package com.deeptalking.engine.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URI

/** One local IPv4 interface address plus its subnet width and interface name. */
data class LocalSubnet(
    val address: String,
    val prefixLength: Int,
    /** `rndis0` (USB tethering), `bnep0` (Bluetooth PAN), `wlan0`/`ap0` (hotspot AP), ... */
    val interfaceName: String = "",
)

/**
 * Address arithmetic for the "find the PC" sweep.
 *
 * The server sits on a link the *phone* created - USB tethering, Bluetooth PAN
 * (`bnep0`) or the phone's own hotspot AP - so the phone already knows the subnet
 * to sweep: no discovery protocol, no mDNS, no manual IP entry. The subnet is taken
 * from the phone's own interface prefix instead of being hard-coded, because Android
 * uses a different range per vendor and per link (measured on the test device:
 * `10.84.116.0/24` over USB tethering, `192.168.44.0/24` over Bluetooth PAN).
 */
object LanDiscovery {

    /** `cosyvoice-server` default port. */
    const val DEFAULT_PORT = 8080

    /** Upper bound on hosts swept per subnet (a /24 has 254 usable). */
    const val MAX_HOSTS = 254

    /** Hard bound on one sweep across all links (three /24s = 762 candidates). */
    const val MAX_SCAN_HOSTS = 1024

    /**
     * Sweep budget: every link that is up gets a full /24 (several can be up at once -
     * USB tethering, Bluetooth PAN and a hotspot), bounded by [MAX_SCAN_HOSTS].
     */
    fun scanBudget(subnetCount: Int): Int =
        (subnetCount.coerceAtLeast(1) * MAX_HOSTS).coerceAtMost(MAX_SCAN_HOSTS)

    /** Local IPv4 subnets, excluding loopback and link-local (169.254/16) addresses. */
    fun localSubnets(): List<LocalSubnet> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().flatMap { nif ->
            runCatching { nif.interfaceAddresses }.getOrDefault(emptyList()).mapNotNull { ia ->
                val address = ia.address as? Inet4Address ?: return@mapNotNull null
                if (address.isLoopbackAddress || address.isLinkLocalAddress) return@mapNotNull null
                val host = address.hostAddress ?: return@mapNotNull null
                LocalSubnet(host, ia.networkPrefixLength.toInt(), nif.name.orEmpty())
            }
        }
    }.getOrDefault(emptyList())

    /**
     * True for links whose round-trip is far longer than Wi-Fi or Ethernet. Bluetooth
     * PAN (Android exposes it as `bnep0`) has a ~30-100 ms RTT and a nominal 3 Mbps, so
     * the fast sweep timings can time out before the PC answers; those subnets get the
     * slower probe profile instead.
     */
    fun isSlowLink(interfaceName: String): Boolean =
        interfaceName.startsWith("bnep", ignoreCase = true)

    /**
     * Host addresses inside [localIp]/[prefixLength], excluding [localIp] itself
     * and the network/broadcast addresses.
     *
     * Prefixes shorter than /24 are clamped to the surrounding /24 — scanning a
     * /16 would mean 65k probes — and the result is capped at [cap] entries.
     */
    fun candidates(localIp: String, prefixLength: Int, cap: Int = MAX_HOSTS): List<String> {
        val base = ipv4ToLong(localIp) ?: return emptyList()
        val prefix = prefixLength.takeIf { it in 1..32 } ?: 24
        val hostBits = 32 - maxOf(prefix, 24)
        val size = 1L shl hostBits
        val mask = (0xFFFFFFFFL shl hostBits) and 0xFFFFFFFFL
        val network = base and mask
        val out = ArrayList<String>()
        var host = 1L
        while (host < size - 1 && out.size < cap) {
            val candidate = longToIpv4(network + host)
            if (candidate != localIp) out += candidate
            host++
        }
        return out
    }

    private fun ipv4ToLong(ip: String): Long? {
        val parts = ip.trim().split('.')
        if (parts.size != 4) return null
        var value = 0L
        for (part in parts) {
            val octet = part.toIntOrNull() ?: return null
            if (octet !in 0..255) return null
            value = (value shl 8) or octet.toLong()
        }
        return value
    }

    private fun longToIpv4(value: Long): String =
        "${(value shr 24) and 0xFF}.${(value shr 16) and 0xFF}.${(value shr 8) and 0xFF}.${value and 0xFF}"
}

/**
 * Sweeps the phone's own subnets for a reachable TTS server. The configured URL
 * is tried first, so a working setup never pays for the scan.
 *
 * Several links can be up at once (USB + Bluetooth + hotspot), so candidate subnets
 * are interleaved - a slow subnet cannot delay the one that actually holds the PC -
 * and the sweep budget grows with the number of subnets.
 */
class TtsServerFinder(private val client: RemoteTtsClient) {

    /**
     * Returns `http://<host>:<port>` for the first server that answers, or null.
     */
    suspend fun find(preferred: String?, token: String?): String? = withContext(Dispatchers.IO) {
        val preferredBase = preferred?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() }
        if (preferredBase != null &&
            client.reachable(preferredBase, token, ProbeProfile.INTERACTIVE)
        ) {
            return@withContext preferredBase
        }
        val port = preferredBase?.let(::portOf) ?: LanDiscovery.DEFAULT_PORT
        val subnets = LanDiscovery.localSubnets()
        val perSubnet = subnets.map { subnet ->
            val profile = if (LanDiscovery.isSlowLink(subnet.interfaceName)) {
                ProbeProfile.SLOW_SCAN
            } else {
                ProbeProfile.SCAN
            }
            LanDiscovery.candidates(subnet.address, subnet.prefixLength)
                .map { Target("http://$it:$port", profile) }
        }
        val budget = LanDiscovery.scanBudget(subnets.size)
        val targets = interleave(perSubnet)
            .filter { it.url != preferredBase }
            .take(budget)
        if (targets.isEmpty()) return@withContext null
        for (batch in targets.chunked(SCAN_CONCURRENCY)) {
            val hit = batch
                .map { target -> async { target.url.takeIf { client.reachable(it, token, target.profile) } } }
                .awaitAll()
                .firstOrNull { it != null }
            if (hit != null) return@withContext hit
        }
        null
    }

    private fun interleave(groups: List<List<Target>>): List<Target> {
        if (groups.isEmpty()) return emptyList()
        val longest = groups.maxOf { it.size }
        return buildList {
            for (index in 0 until longest) {
                for (group in groups) if (index < group.size) add(group[index])
            }
        }
    }

    private fun portOf(base: String): Int =
        runCatching { URI(base).port.takeIf { it > 0 } ?: LanDiscovery.DEFAULT_PORT }
            .getOrDefault(LanDiscovery.DEFAULT_PORT)

    /** One probe target: its URL plus the timing profile its link needs. */
    private data class Target(val url: String, val profile: ProbeProfile)

    private companion object {
        /** Parallel probes per batch (matches the scan client's dispatcher limit). */
        const val SCAN_CONCURRENCY = 48
    }
}
