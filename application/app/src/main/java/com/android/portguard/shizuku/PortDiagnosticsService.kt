package com.android.portguard.shizuku

import android.os.Process
import androidx.annotation.Keep
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/**
 * Runs in a Shizuku UserService process (ADB shell or root identity).
 * No firewall configuration, privileged writes, or arbitrary UI commands.
 */
@Keep
class PortDiagnosticsService : IPortDiagnosticsService.Stub() {
    override fun destroy() {
        exitProcess(0)
    }

    override fun runReadOnlyDiagnostics(): String = buildString {
        val uid = Process.myUid()
        appendLine("PortGuard · Shizuku diagnostics")
        appendLine("Process UID: $uid")
        appendLine("Firewall protection: INACTIVE (diagnostics only)")
        appendLine("The socket list may be incomplete or unavailable on this Android build.")
        appendLine()

        val probes = listOf(
            "Listening TCP/UDP sockets" to "ss -ltnu",
            "Network addresses" to "ip -o addr show",
        )
        for ((title, command) in probes) {
            appendLine("--- $title ---")
            appendLine(runProbe(command))
            appendLine()
        }
    }

    private fun runProbe(command: String): String = try {
        // Commands are hard-coded: the caller cannot inject shell input.
        val process = ProcessBuilder("/system/bin/sh", "-c", command)
            .redirectErrorStream(true)
            .start()
        if (!process.waitFor(5, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            "Timeout: command did not finish within 5 seconds"
        } else {
            val output = process.inputStream.bufferedReader().use { it.readText().take(12_000) }
            if (process.exitValue() == 0) {
                output.ifBlank { "No data returned" }
            } else {
                "Unavailable (exit ${process.exitValue()}): ${output.ifBlank { "permission denied or utility missing" }}"
            }
        }
    } catch (e: Exception) {
        "Unavailable: ${e.javaClass.simpleName}: ${e.message.orEmpty()}"
    }
}
