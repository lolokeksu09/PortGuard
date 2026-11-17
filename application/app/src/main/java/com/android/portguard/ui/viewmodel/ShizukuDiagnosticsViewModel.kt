package com.android.portguard.ui.viewmodel

import android.app.Application
import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.android.portguard.data.PortChanges
import com.android.portguard.data.PortDiagnosticSnapshot
import com.android.portguard.data.PortDiagnosticsParser
import com.android.portguard.data.PortWatchRules
import com.android.portguard.shizuku.IPortDiagnosticsService
import com.android.portguard.shizuku.PortDiagnosticsService
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku

data class DiagnosticsScanRecord(
    val time: String,
    val portsFound: Int,
    val added: Int?,
    val disappeared: Int?,
)

/** A separate, read-only experience. The root-only firewall controls are never exposed. */
data class ShizukuDiagnosticsUiState(
    val status: String = "Проверяем Shizuku…",
    val connected: Boolean = false,
    val busy: Boolean = false,
    val snapshot: PortDiagnosticSnapshot? = null,
    val changes: PortChanges? = null,
    val history: List<DiagnosticsScanRecord> = emptyList(),
    val lastScanTime: String = "",
    val scanError: String? = null,
    val monitoring: Boolean = false,
    val monitorIntervalSeconds: Int = 30,
    val watchedPorts: Set<String> = emptySet(),
    val watchAlerts: List<String> = emptyList(),
)

class ShizukuDiagnosticsViewModel(app: Application) : AndroidViewModel(app) {
    private val watchPreferences = app.getSharedPreferences("portguard_shizuku_diagnostics", Application.MODE_PRIVATE)
    private val _uiState = MutableStateFlow(
        ShizukuDiagnosticsUiState(
            watchedPorts = watchPreferences.getStringSet("watched_ports", emptySet())
                .orEmpty().filter(PortWatchRules::isValidKey).toSet()
        )
    )
    val uiState: StateFlow<ShizukuDiagnosticsUiState> = _uiState.asStateFlow()

    private val args = Shizuku.UserServiceArgs(
        ComponentName(app.packageName, PortDiagnosticsService::class.java.name)
    ).daemon(false).processNameSuffix("portguard_diagnostics").version(1)

    private var service: IPortDiagnosticsService? = null
    private var bound = false
    private var scanGeneration: Long = 0
    private var monitorJob: Job? = null
    private var lastUsableSnapshot: PortDiagnosticSnapshot? = null

    private val binderReceived = Shizuku.OnBinderReceivedListener { refreshStatus() }
    private val binderDead = Shizuku.OnBinderDeadListener {
        stopMonitoring()
        scanGeneration++
        service = null
        bound = false
        _uiState.value = _uiState.value.copy(
            status = "Shizuku остановлен. Запусти его снова.",
            connected = false,
            busy = false,
        )
    }
    private val permissionResult = Shizuku.OnRequestPermissionResultListener { requestCode, result ->
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (result == PackageManager.PERMISSION_GRANTED) bindDiagnosticsService()
            else setStatus("Доступ отклонён. Разрешение можно изменить в приложении Shizuku.")
        }
    }
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = IPortDiagnosticsService.Stub.asInterface(binder)
            val uid = runCatching { Shizuku.getUid() }.getOrDefault(-1)
            _uiState.value = _uiState.value.copy(
                status = "Shizuku подключён · UID " + uid + " · защита неактивна",
                connected = true,
                busy = false,
            )
        }

        override fun onServiceDisconnected(name: ComponentName) {
            stopMonitoring()
            scanGeneration++
            service = null
            bound = false
            _uiState.value = _uiState.value.copy(
                status = "Служба диагностики отключена.",
                connected = false,
                busy = false,
            )
        }
    }

    init {
        Shizuku.addBinderReceivedListenerSticky(binderReceived)
        Shizuku.addBinderDeadListener(binderDead)
        Shizuku.addRequestPermissionResultListener(permissionResult)
        refreshStatus()
    }

    fun refreshStatus() {
        if (service != null) return
        val status = runCatching {
            when {
                !Shizuku.pingBinder() -> "Shizuku не запущен. Запусти Shizuku и вернись сюда."
                Shizuku.isPreV11() -> "Версия Shizuku слишком старая: нужен API 11+."
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED ->
                    "Shizuku работает. Нажми «Подключиться» для диагностики."
                else -> "Shizuku работает. Требуется разрешение доступа."
            }
        }.getOrElse { "Shizuku недоступен: " + it.message.orEmpty() }
        setStatus(status)
    }

    fun connect() {
        try {
            if (!Shizuku.pingBinder()) {
                setStatus("Shizuku не запущен. Открой приложение Shizuku и запусти службу.")
                return
            }
            if (Shizuku.isPreV11()) {
                setStatus("Нужен Shizuku API 11 или новее.")
                return
            }
            when {
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> bindDiagnosticsService()
                Shizuku.shouldShowRequestPermissionRationale() ->
                    setStatus("Доступ запрещён. Разреши PortGuard в настройках Shizuku.")
                else -> Shizuku.requestPermission(PERMISSION_REQUEST_CODE)
            }
        } catch (e: Exception) {
            setStatus("Не удалось подключиться: " + e.message.orEmpty())
        }
    }

    private fun bindDiagnosticsService() {
        if (bound) return
        _uiState.value = _uiState.value.copy(busy = true, status = "Подключаем службу диагностики…")
        try {
            bound = true
            Shizuku.bindUserService(args, connection)
        } catch (e: Exception) {
            bound = false
            _uiState.value = _uiState.value.copy(busy = false, status = "Ошибка подключения: " + e.message.orEmpty())
        }
    }

    fun runDiagnostics() {
        val activeService = service ?: return
        if (_uiState.value.busy) return
        val generation = ++scanGeneration
        _uiState.value = _uiState.value.copy(busy = true, scanError = null)

        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching { PortDiagnosticsParser.parse(activeService.runReadOnlyDiagnostics()) }
            // Ignore a stale Binder response if Shizuku disconnected while scanning.
            if (generation != scanGeneration || service !== activeService) return@launch

            result.onSuccess { current ->
                // Do not interpret a failed socket query as "all ports disappeared".
                if (current.ports.isEmpty() && current.warnings.isNotEmpty()) {
                    _uiState.value = _uiState.value.copy(
                        busy = false,
                        scanError = "Android ограничил доступ к списку портов. Проверь системные сообщения в отчёте.",
                        snapshot = current,
                        changes = null,
                    )
                    return@onSuccess
                }

                val previous = lastUsableSnapshot
                val delta = previous?.let { PortDiagnosticsParser.compare(it, current) }
                lastUsableSnapshot = current
                val now = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date())
                val noticed = previous?.let { PortWatchRules.newlyObserved(it, current, _uiState.value.watchedPorts) }.orEmpty()
                val alerts = noticed.map { "$now · В списке появился " + it.substringBefore(':').uppercase() + "/" + it.substringAfter(':') }
                val record = DiagnosticsScanRecord(
                    time = now,
                    portsFound = current.ports.size,
                    added = delta?.added?.size,
                    disappeared = delta?.disappeared?.size,
                )
                _uiState.value = _uiState.value.copy(
                    busy = false,
                    snapshot = current,
                    changes = delta,
                    lastScanTime = now,
                    scanError = null,
                    history = (listOf(record) + _uiState.value.history).take(8),
                    watchAlerts = (alerts + _uiState.value.watchAlerts).take(12),
                )
            }.onFailure { error ->
                _uiState.value = _uiState.value.copy(
                    busy = false,
                    scanError = "Не удалось выполнить диагностику: " + error.message.orEmpty(),
                )
            }
        }
    }

    /** Foreground monitoring runs only while this diagnostics screen is present. */
    fun setMonitoring(enabled: Boolean) {
        if (!enabled) {
            stopMonitoring()
            return
        }
        if (service == null || !_uiState.value.connected) {
            setStatus("Сначала подключись через Shizuku.")
            return
        }
        if (monitorJob?.isActive == true) return
        _uiState.value = _uiState.value.copy(monitoring = true)
        monitorJob = viewModelScope.launch {
            while (isActive && _uiState.value.monitoring && _uiState.value.connected) {
                runDiagnostics()
                delay(_uiState.value.monitorIntervalSeconds * 1_000L)
            }
        }
    }

    fun setMonitorIntervalSeconds(seconds: Int) {
        if (seconds !in setOf(15, 30, 60)) return
        _uiState.value = _uiState.value.copy(monitorIntervalSeconds = seconds)
    }

    private fun stopMonitoring() {
        monitorJob?.cancel()
        monitorJob = null
        _uiState.value = _uiState.value.copy(monitoring = false)
    }

    fun toggleWatchedPort(protocol: String, port: Int) {
        val key = PortWatchRules.key(protocol, port) ?: return
        val current = _uiState.value.watchedPorts
        val next = if (key in current) current - key else current + key
        storeWatchedPorts(next)
    }

    fun removeWatchedPort(key: String) {
        if (PortWatchRules.isValidKey(key)) {
            storeWatchedPorts(_uiState.value.watchedPorts - key)
        }
    }

    private fun storeWatchedPorts(next: Set<String>) {
        // Copy the set: SharedPreferences can retain references to a mutable value.
        watchPreferences.edit().putStringSet("watched_ports", next.toSet()).apply()
        _uiState.value = _uiState.value.copy(watchedPorts = next.toSet())
    }

    fun clearWatchAlerts() {
        _uiState.value = _uiState.value.copy(watchAlerts = emptyList())
    }

    private fun setStatus(text: String) {
        _uiState.value = _uiState.value.copy(status = text)
    }

    fun disconnect() {
        stopMonitoring()
        scanGeneration++
        if (bound) runCatching { Shizuku.unbindUserService(args, connection, true) }
        bound = false
        service = null
        _uiState.value = _uiState.value.copy(connected = false, busy = false)
    }

    override fun onCleared() {
        disconnect()
        Shizuku.removeBinderReceivedListener(binderReceived)
        Shizuku.removeBinderDeadListener(binderDead)
        Shizuku.removeRequestPermissionResultListener(permissionResult)
        super.onCleared()
    }

    companion object {
        private const val PERMISSION_REQUEST_CODE = 10902
    }
}
