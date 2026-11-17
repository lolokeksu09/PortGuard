package com.android.portguard.ui.viewmodel

import android.app.Application
import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.android.portguard.shizuku.IPortDiagnosticsService
import com.android.portguard.shizuku.PortDiagnosticsService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku

/** Independent of the root module, so no root-only action can be reached via this screen. */
data class ShizukuDiagnosticsUiState(
    val status: String = "Проверяем Shizuku…",
    val connected: Boolean = false,
    val busy: Boolean = false,
    val output: String = "",
)

class ShizukuDiagnosticsViewModel(app: Application) : AndroidViewModel(app) {
    private val _uiState = MutableStateFlow(ShizukuDiagnosticsUiState())
    val uiState: StateFlow<ShizukuDiagnosticsUiState> = _uiState.asStateFlow()

    private val args = Shizuku.UserServiceArgs(
        ComponentName(app.packageName, PortDiagnosticsService::class.java.name)
    ).daemon(false).processNameSuffix("portguard_diagnostics").version(1)

    private var service: IPortDiagnosticsService? = null
    private var bound = false

    private val binderReceived = Shizuku.OnBinderReceivedListener { refreshStatus() }
    private val binderDead = Shizuku.OnBinderDeadListener {
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
            else setStatus("Доступ к Shizuku отклонён. Разрешение можно изменить в Shizuku.")
        }
    }
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = IPortDiagnosticsService.Stub.asInterface(binder)
            _uiState.value = _uiState.value.copy(
                status = "Shizuku подключён (UID ${runCatching { Shizuku.getUid() }.getOrDefault(-1)}). Защита портов выключена.",
                connected = true,
                busy = false,
            )
        }

        override fun onServiceDisconnected(name: ComponentName) {
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
        }.getOrElse { "Shizuku недоступен: ${it.message.orEmpty()}" }
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
            setStatus("Не удалось подключиться: ${e.message.orEmpty()}")
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
            _uiState.value = _uiState.value.copy(busy = false, status = "Ошибка подключения: ${e.message.orEmpty()}")
        }
    }

    fun runDiagnostics() {
        val activeService = service ?: return
        _uiState.value = _uiState.value.copy(busy = true, output = "Выполняется диагностика…")
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching { activeService.runReadOnlyDiagnostics() }
                .getOrElse { "Ошибка диагностики: ${it.message.orEmpty()}" }
            _uiState.value = _uiState.value.copy(busy = false, output = result)
        }
    }

    private fun setStatus(text: String) {
        _uiState.value = _uiState.value.copy(status = text)
    }

    fun disconnect() {
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
