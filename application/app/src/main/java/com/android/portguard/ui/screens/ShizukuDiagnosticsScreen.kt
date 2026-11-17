package com.android.portguard.ui.screens

import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.android.portguard.data.ObservedPort
import com.android.portguard.data.PortBindingScope
import com.android.portguard.data.PortDiagnosticSnapshot
import com.android.portguard.ui.viewmodel.ShizukuDiagnosticsViewModel

private val filters = listOf("Все", "Сетевые", "Локальные", "TCP", "UDP")

@Composable
fun ShizukuDiagnosticsScreen(
    onBack: () -> Unit,
    vm: ShizukuDiagnosticsViewModel = viewModel(),
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf("Все") }
    var showRaw by rememberSaveable { mutableStateOf(false) }

    DisposableEffect(vm) {
        onDispose { vm.disconnect() }
    }

    val snapshot = state.snapshot
    val ports = snapshot?.ports.orEmpty()
    val nonLocalCount = ports.count { it.scope != PortBindingScope.LOOPBACK }
    val visible = ports.filter { port ->
        val matchesFilter = when (filter) {
            "Сетевые" -> port.scope != PortBindingScope.LOOPBACK
            "Локальные" -> port.scope == PortBindingScope.LOOPBACK
            "TCP" -> port.protocol == "tcp"
            "UDP" -> port.protocol == "udp"
            else -> true
        }
        matchesFilter && (query.isBlank() ||
            port.port.toString().contains(query.trim(), ignoreCase = true) ||
            port.address.contains(query.trim(), ignoreCase = true) ||
            port.protocol.contains(query.trim(), ignoreCase = true))
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "PortGuard",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "НАБЛЮДЕНИЕ ЗА ПОРТАМИ  ·  SHIZUKU",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )

        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "Защита неактивна",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "С Shizuku доступны чтение сетевой информации и сравнение проверок, но не блокировка соединений. " +
                        "Сетевая привязка не доказывает, что порт доступен извне.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    state.status,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }

        if (!state.connected) {
            Button(onClick = vm::connect, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                Text("Подключиться через Shizuku")
            }
        } else {
            Button(onClick = vm::runDiagnostics, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                Text(if (state.busy) "Сканирование…" else if (snapshot == null) "Проверить порты" else "Повторить проверку")
            }
        }
        if (state.busy) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CircularProgressIndicator(modifier = Modifier.width(18.dp), strokeWidth = 2.dp)
                Text("Получаем доступные данные…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        state.scanError?.let { err ->
            Text(err, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }

        if (snapshot != null) {
            Text(
                "ОБЗОР",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MetricCard("Обнаружено", ports.size.toString(), Modifier.weight(1f))
                MetricCard("Не localhost", nonLocalCount.toString(), Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MetricCard("TCP", ports.count { it.protocol == "tcp" }.toString(), Modifier.weight(1f))
                MetricCard("UDP", ports.count { it.protocol == "udp" }.toString(), Modifier.weight(1f))
            }

            state.changes?.let { delta ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Изменения после предыдущей проверки", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Появились: " + delta.added.size + "  ·  Исчезли: " + delta.disappeared.size,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (delta.added.isNotEmpty()) {
                            Text(
                                "Новые: " + delta.added.take(6).joinToString { it.protocol.uppercase() + "/" + it.port },
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        if (delta.disappeared.isNotEmpty()) {
                            Text(
                                "Исчезли: " + delta.disappeared.take(6).joinToString { it.protocol.uppercase() + "/" + it.port },
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }

            Text(
                "СЛУШАЮЩИЕ ПОРТЫ",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Поиск по порту, адресу или протоколу") },
                singleLine = true,
            )
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                filters.forEach { option ->
                    FilterChip(selected = filter == option, onClick = { filter = option }, label = { Text(option) })
                }
            }
            Text(
                "Найдено по фильтру: " + visible.size,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            if (visible.isEmpty()) {
                Text(
                    if (ports.isEmpty()) "Список портов недоступен или пуст." else "По заданному фильтру совпадений нет.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                visible.take(200).forEach { port -> PortRow(port) }
                if (visible.size > 200) {
                    Text(
                        "Показаны первые 200 записей. Уточни поиск для остальных.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (snapshot.addresses.isNotEmpty()) {
                Text("СЕТЕВЫЕ ИНТЕРФЕЙСЫ", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                    Column(
                        modifier = Modifier.padding(16.dp).fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        snapshot.addresses.filter { it.interfaceName != "lo" }.forEach { address ->
                            Column {
                                Text(address.interfaceName, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                                Text(address.address, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }

            if (snapshot.warnings.isNotEmpty()) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Ограничения Android", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Часть сведений скрыта системой. Количество обнаруженных портов может быть неполным.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        snapshot.warnings.take(4).forEach { warning ->
                            Text(warning, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            if (state.history.isNotEmpty()) {
                Text("ИСТОРИЯ ЭТОЙ СЕССИИ", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                state.history.forEach { item ->
                    Text(
                        item.time + "  ·  " + item.portsFound + " портов" +
                            (if (item.added != null) "  ·  +" + item.added + " / -" + item.disappeared else "  ·  первый снимок"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            OutlinedButton(
                onClick = {
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, formatDiagnosticsReport(snapshot))
                    }
                    context.startActivity(Intent.createChooser(intent, "Экспорт диагностики PortGuard"))
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Поделиться отчётом")
            }
            TextButton(onClick = { showRaw = !showRaw }, modifier = Modifier.fillMaxWidth()) {
                Text(if (showRaw) "Скрыть технический вывод" else "Показать технический вывод")
            }
            if (showRaw) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                    SelectionContainer {
                        Text(
                            snapshot.raw,
                            modifier = Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
        }
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Text("Вернуться к Root-режиму")
        }
    }
}

@Composable
private fun MetricCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun PortRow(port: ObservedPort) {
    val (scopeLabel, scopeDescription) = when (port.scope) {
        PortBindingScope.LOOPBACK -> "Только устройство" to "127.0.0.1 / localhost"
        PortBindingScope.ALL_INTERFACES -> "Все интерфейсы" to "Возможен доступ с других устройств; нужны сетевые проверки"
        PortBindingScope.SPECIFIC_ADDRESS -> "Конкретный адрес" to "Доступ зависит от сети и системных правил"
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    port.protocol.uppercase() + "  : " + port.port,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(port.state, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
            Text(port.address, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            Text(scopeLabel, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface)
            Text(scopeDescription, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun formatDiagnosticsReport(snapshot: PortDiagnosticSnapshot): String = buildString {
    appendLine("PortGuard - Shizuku read-only report")
    appendLine("Firewall protection: INACTIVE")
    appendLine("Port list may be incomplete; bindings are not proof of external reachability.")
    appendLine("Observed ports: " + snapshot.ports.size)
    appendLine()
    snapshot.ports.forEach { item ->
        appendLine(item.protocol.uppercase() + "/" + item.port + " " + item.address + " [" + item.scope.name + "]")
    }
    appendLine()
    appendLine("Network interfaces:")
    snapshot.addresses.forEach { item -> appendLine(item.interfaceName + " " + item.address) }
    if (snapshot.warnings.isNotEmpty()) {
        appendLine("Warnings:")
        snapshot.warnings.forEach { appendLine(it) }
    }
}
