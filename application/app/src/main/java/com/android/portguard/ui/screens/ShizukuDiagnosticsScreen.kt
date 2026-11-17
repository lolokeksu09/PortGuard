package com.android.portguard.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.android.portguard.ui.viewmodel.ShizukuDiagnosticsViewModel

@Composable
fun ShizukuDiagnosticsScreen(
    onBack: () -> Unit,
    vm: ShizukuDiagnosticsViewModel = viewModel(),
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    DisposableEffect(vm) {
        onDispose { vm.disconnect() }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Shizuku · Диагностика", style = MaterialTheme.typography.headlineSmall)
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Защита портов неактивна", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Этот режим проверяет доступную сетевую информацию через Shizuku. " +
                        "Он не устанавливает root-модуль, не изменяет iptables и не блокирует соединения. " +
                        "Некоторые сведения могут быть скрыты Android.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        Text(state.status, style = MaterialTheme.typography.bodyMedium)
        Button(onClick = vm::connect, enabled = !state.busy && !state.connected, modifier = Modifier.fillMaxWidth()) {
            Text(if (state.connected) "Подключено" else "Подключиться через Shizuku")
        }
        Button(onClick = vm::runDiagnostics, enabled = !state.busy && state.connected, modifier = Modifier.fillMaxWidth()) {
            Text("Проверить сетевые порты")
        }
        if (state.output.isNotBlank()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                SelectionContainer {
                    Text(
                        state.output,
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Text("Вернуться к Root-режиму")
        }
    }
}
