package com.deeptalking.engine.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sweep arithmetic is pure so it can be pinned on the JVM: the phone sweeps
 * the subnet it created itself, whose range is vendor-specific (the test device
 * uses `10.84.116.0/24`, not the `192.168.42.0/24` convention).
 */
class LanDiscoveryTest {

    @Test
    fun `a slash-24 sweeps every host but the local address`() {
        val hosts = LanDiscovery.candidates("10.84.116.5", 24)
        assertEquals(253, hosts.size)
        assertEquals("10.84.116.1", hosts.first())
        assertEquals("10.84.116.254", hosts.last())
        assertTrue("10.84.116.5" !in hosts)
        assertTrue("10.84.116.0" !in hosts)
        assertTrue("10.84.116.255" !in hosts)
    }

    @Test
    fun `shorter prefixes are clamped to the surrounding slash-24`() {
        val hosts = LanDiscovery.candidates("10.127.8.138", 16)
        assertEquals(253, hosts.size)
        assertEquals("10.127.8.1", hosts.first())
        assertEquals("10.127.8.254", hosts.last())
        // The /16 hosts outside the /24 are deliberately not swept.
        assertTrue(hosts.none { it.startsWith("10.127.7.") })
    }

    @Test
    fun `the cap bounds a wide subnet`() {
        val hosts = LanDiscovery.candidates("192.168.42.10", 24, cap = 8)
        assertEquals(8, hosts.size)
        assertEquals("192.168.42.1", hosts.first())
    }

    @Test
    fun `an invalid address or prefix yields nothing`() {
        assertTrue(LanDiscovery.candidates("not-an-ip", 24).isEmpty())
        assertTrue(LanDiscovery.candidates("10.0.0.999", 24).isEmpty())
        assertTrue(LanDiscovery.candidates("10.0.0.1", 32).isEmpty())
    }

    @Test
    fun `an unusual prefix is still handled`() {
        val hosts = LanDiscovery.candidates("192.168.1.1", 25)
        assertEquals(125, hosts.size)
        assertEquals("192.168.1.2", hosts.first())
        assertEquals("192.168.1.126", hosts.last())
    }

    @Test
    fun `bluetooth pan counts as a slow link`() {
        assertTrue(LanDiscovery.isSlowLink("bnep0"))
        assertTrue(LanDiscovery.isSlowLink("BNEP1"))
        assertTrue(!LanDiscovery.isSlowLink("wlan0"))
        assertTrue(!LanDiscovery.isSlowLink("rndis0"))
        assertTrue(!LanDiscovery.isSlowLink(""))
    }

    @Test
    fun `a subnet carries the interface name it came from`() {
        assertEquals("bnep0", LocalSubnet("192.168.44.1", 24, "bnep0").interfaceName)
        assertEquals("", LocalSubnet("192.168.44.1", 24).interfaceName)
    }

    @Test
    fun `the sweep budget covers every link and stays bounded`() {
        // One link: a full /24. Three links (USB + Bluetooth + hotspot): three of them.
        assertEquals(254, LanDiscovery.scanBudget(1))
        assertEquals(762, LanDiscovery.scanBudget(3))
        // No link information at all still sweeps one /24, and the total is capped.
        assertEquals(254, LanDiscovery.scanBudget(0))
        assertEquals(1024, LanDiscovery.scanBudget(9))
    }
}
