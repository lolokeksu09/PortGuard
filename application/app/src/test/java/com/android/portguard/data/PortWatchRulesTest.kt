package com.android.portguard.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PortWatchRulesTest {
    private fun snapshot(vararg entries: Pair<String, Int>) =
        PortDiagnosticSnapshot(
            ports = entries.map { (protocol, number) ->
                ObservedPort(protocol, "LISTEN", "127.0.0.1", number, PortBindingScope.LOOPBACK)
            },
            addresses = emptyList(),
            warnings = emptyList(),
            raw = "",
        )

    @Test fun rejectsInvalidWatchKeysAndPortRanges() {
        assertEquals("tcp:5555", PortWatchRules.key("tcp", 5555))
        assertNull(PortWatchRules.key("tcp", 0))
        assertNull(PortWatchRules.key("udp", 65536))
        assertNull(PortWatchRules.key("ssh", 22))
        assertTrue(PortWatchRules.isValidKey("udp:53"))
        assertFalse(PortWatchRules.isValidKey("tcp:0"))
        assertFalse(PortWatchRules.isValidKey("tcp:65536"))
        assertFalse(PortWatchRules.isValidKey("tcp:00053"))
        assertFalse(PortWatchRules.isValidKey("TCP:53"))
    }

    @Test fun firesOnlyForNewlyObservedWatchedPorts() {
        val old = snapshot("tcp" to 5555, "tcp" to 8000)
        val current = snapshot("tcp" to 5555, "tcp" to 9000, "udp" to 53)
        val notices = PortWatchRules.newlyObserved(
            old, current, setOf("tcp:5555", "tcp:9000", "udp:53", "udp:80")
        )
        assertEquals(listOf("tcp:9000", "udp:53"), notices)
    }

    @Test fun doesNotAlertForAddressOnlyChangesOrUnwatchedPorts() {
        val old = snapshot("tcp" to 5555)
        val changedAddress = old.copy(
            ports = listOf(ObservedPort("tcp", "LISTEN", "0.0.0.0", 5555, PortBindingScope.ALL_INTERFACES))
        )
        assertTrue(PortWatchRules.newlyObserved(old, changedAddress, setOf("tcp:5555")).isEmpty())
        assertTrue(PortWatchRules.newlyObserved(old, snapshot("tcp" to 5555, "udp" to 80), setOf("tcp:5555")).isEmpty())
    }
}
