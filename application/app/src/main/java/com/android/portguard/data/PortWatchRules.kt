package com.android.portguard.data

/**
 * A watch entry is a TCP/UDP port number, irrespective of interface/address.
 * Results are observations; neither firewall rules nor external reachability is inferred.
 */
object PortWatchRules {
    private val watchedFormat = Regex("^(tcp|udp):([1-9][0-9]{0,4})$")

    fun key(protocol: String, port: Int): String? {
        if (protocol !in setOf("tcp", "udp") || port !in 1..65535) return null
        return "$protocol:$port"
    }

    fun isValidKey(value: String): Boolean {
        val match = watchedFormat.matchEntire(value) ?: return false
        return match.groupValues[2].toIntOrNull()?.let { it in 1..65535 } == true
    }

    /** Only a transition absent → observed generates an alert. No initial-scan alert. */
    fun newlyObserved(
        previous: PortDiagnosticSnapshot,
        current: PortDiagnosticSnapshot,
        watches: Set<String>,
    ): List<String> {
        val oldKeys = previous.ports.mapNotNull { key(it.protocol, it.port) }.toSet()
        val newKeys = current.ports.mapNotNull { key(it.protocol, it.port) }.toSet()
        return (newKeys - oldKeys).filter { it in watches }.sorted()
    }
}
