package com.android.portguard.data

/**
 * Read-only interpretation of the Shizuku diagnostics output.
 * These are observed listening sockets, NOT proof of reachability or a firewall verdict.
 */
enum class PortBindingScope {
    LOOPBACK,
    ALL_INTERFACES,
    SPECIFIC_ADDRESS,
}

data class ObservedPort(
    val protocol: String,
    val state: String,
    val address: String,
    val port: Int,
    val scope: PortBindingScope,
) {
    val identity: String get() = "$protocol|$address|$port"
}

data class ObservedNetworkAddress(
    val interfaceName: String,
    val address: String,
)

data class PortDiagnosticSnapshot(
    val ports: List<ObservedPort>,
    val addresses: List<ObservedNetworkAddress>,
    val warnings: List<String>,
    val raw: String,
)

data class PortChanges(
    val added: List<ObservedPort>,
    val disappeared: List<ObservedPort>,
)

object PortDiagnosticsParser {
    private val whitespace = Regex("\\s+")

    fun parse(raw: String): PortDiagnosticSnapshot {
        val ports = linkedMapOf<String, ObservedPort>()
        val addresses = linkedMapOf<String, ObservedNetworkAddress>()
        val warnings = linkedSetOf<String>()
        var section = ""

        for (line in raw.lineSequence()) {
            val trimmed = line.trim()
            when {
                trimmed.startsWith("--- Listening TCP/UDP sockets ---") -> {
                    section = "sockets"
                    continue
                }
                trimmed.startsWith("--- Network addresses ---") -> {
                    section = "addresses"
                    continue
                }
                trimmed.isEmpty() -> continue
                trimmed.contains("Permission denied", ignoreCase = true) ||
                    trimmed.startsWith("Unavailable", ignoreCase = true) ||
                    trimmed.startsWith("Timeout:", ignoreCase = true) -> {
                    warnings += trimmed.take(240)
                    continue
                }
            }

            val tokens = trimmed.split(whitespace)
            if (section == "sockets" && tokens.size >= 5) {
                val protocol = tokens[0].lowercase()
                if (protocol != "tcp" && protocol != "udp") continue
                val local = tokens[4]
                val parsedPort = local.substringAfterLast(':', "").toIntOrNull()
                if (parsedPort == null || parsedPort !in 1..65535) continue
                val host = local.substringBeforeLast(':', "").removePrefix("[").removeSuffix("]")
                if (host.isBlank()) continue

                val scope = when {
                    host == "localhost" || host == "::1" || host == "127.0.0.1" ||
                        host.startsWith("127.") -> PortBindingScope.LOOPBACK
                    host == "*" || host == "0.0.0.0" || host == "::" ->
                        PortBindingScope.ALL_INTERFACES
                    else -> PortBindingScope.SPECIFIC_ADDRESS
                }
                val item = ObservedPort(protocol, tokens[1], host, parsedPort, scope)
                ports[item.identity] = item
            } else if (section == "addresses" && tokens.size >= 4) {
                if (tokens[2] != "inet" && tokens[2] != "inet6") continue
                val item = ObservedNetworkAddress(
                    interfaceName = tokens[1].removeSuffix(":"),
                    address = tokens[3],
                )
                addresses[item.interfaceName + "|" + item.address] = item
            }
        }

        return PortDiagnosticSnapshot(
            ports = ports.values.sortedWith(compareBy<ObservedPort> { it.port }.thenBy { it.protocol }.thenBy { it.address }),
            addresses = addresses.values.toList(),
            warnings = warnings.toList(),
            raw = raw,
        )
    }

    fun compare(previous: PortDiagnosticSnapshot, current: PortDiagnosticSnapshot): PortChanges {
        val old = previous.ports.associateBy { it.identity }
        val now = current.ports.associateBy { it.identity }
        return PortChanges(
            added = current.ports.filter { it.identity !in old },
            disappeared = previous.ports.filter { it.identity !in now },
        )
    }
}
