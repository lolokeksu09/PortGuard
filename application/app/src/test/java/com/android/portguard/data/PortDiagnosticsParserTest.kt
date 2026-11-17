package com.android.portguard.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PortDiagnosticsParserTest {
    private val example = """
        PortGuard · Shizuku diagnostics
        Process UID: 2000
        --- Listening TCP/UDP sockets ---
        Netid State Recv-Q Send-Q Local Address:Port Peer Address:Port
        Cannot open netlink socket: Permission denied
        tcp LISTEN 0 0 127.0.0.1:8765 0.0.0.0:*
        tcp LISTEN 0 0 *:5555 *:*
        udp UNCONN 0 0 [fe80::abcd]:50748 *:*
        --- Network addresses ---
        1: lo inet 127.0.0.1/8 scope host lo
        25: wlan0 inet 192.168.0.169/24 brd 192.168.0.255 scope global wlan0
        259: tun0 inet 10.10.14.1/30 scope global tun0
    """.trimIndent()

    @Test fun separatesScopeProtocolAndInterfaces() {
        val result = PortDiagnosticsParser.parse(example)
        assertEquals(3, result.ports.size)
        assertEquals(PortBindingScope.LOOPBACK, result.ports.first { it.port == 8765 }.scope)
        assertEquals(PortBindingScope.ALL_INTERFACES, result.ports.first { it.port == 5555 }.scope)
        assertEquals(PortBindingScope.SPECIFIC_ADDRESS, result.ports.first { it.port == 50748 }.scope)
        assertEquals("udp", result.ports.first { it.port == 50748 }.protocol)
        assertEquals(3, result.addresses.size)
        assertTrue(result.addresses.any { it.interfaceName == "tun0" })
        assertTrue(result.warnings.any { it.contains("Permission denied") })
    }

    @Test fun parsesOnlyExplicitProcessMetadataWithoutInventingApplicationOwners() {
        val raw = """
            --- Listening TCP/UDP sockets ---
            tcp LISTEN 0 0 0.0.0.0:5555 0.0.0.0:* users:(("adbd",pid=1234,fd=9))
            tcp LISTEN 0 0 127.0.0.1:8765 0.0.0.0:*
        """.trimIndent()
        val snapshot = PortDiagnosticsParser.parse(raw)
        val owned = snapshot.ports.first { it.port == 5555 }
        assertEquals("adbd", owned.processName)
        assertEquals(1234, owned.processPid)
        val unknown = snapshot.ports.first { it.port == 8765 }
        assertEquals(null, unknown.processName)
        assertEquals(null, unknown.processPid)
    }

    @Test fun tracksNewAndDisappearingPorts() {
        val previous = PortDiagnosticsParser.parse(example)
        val next = PortDiagnosticsParser.parse(
            example.replace("tcp LISTEN 0 0 *:5555 *:*", "tcp LISTEN 0 0 *:6000 *:*")
        )
        val diff = PortDiagnosticsParser.compare(previous, next)
        assertEquals(listOf(6000), diff.added.map { it.port })
        assertEquals(listOf(5555), diff.disappeared.map { it.port })
    }

    @Test fun ignoresHeaderInvalidPortsAndDuplicates() {
        val value = """
            --- Listening TCP/UDP sockets ---
            Netid State Recv-Q Send-Q Local Address:Port Peer Address:Port
            tcp LISTEN 0 0 127.0.0.1:1234 0.0.0.0:*
            tcp LISTEN 0 0 127.0.0.1:1234 0.0.0.0:*
            tcp LISTEN 0 0 *:not-a-port *:*
            udp UNCONN 0 0 *:65536 *:*
        """.trimIndent()
        val parsed = PortDiagnosticsParser.parse(value)
        assertEquals(1, parsed.ports.size)
        assertEquals(1234, parsed.ports.single().port)
    }
}
