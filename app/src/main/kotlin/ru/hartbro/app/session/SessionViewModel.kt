package ru.hartbro.app.session

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.pm.PackageManager
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.hartbro.bluetooth.BluetoothSppTransport
import ru.hartbro.core.DeviceIdentity
import ru.hartbro.core.DiagnosticEntry
import ru.hartbro.core.HartClient
import ru.hartbro.core.PrimaryVariable

data class Communicator(val name: String, val address: String, val likelyViator: Boolean)
data class SessionState(
    val devices: List<Communicator> = emptyList(),
    val bluetoothAvailable: Boolean = true,
    val bluetoothEnabled: Boolean = false,
    val permissionGranted: Boolean = false,
    val pendingConnection: Communicator? = null,
    val busy: Boolean = false,
    val connected: Boolean = false,
    val status: String = "Подключение не выполнено",
    val error: String? = null,
    val identity: DeviceIdentity? = null,
    val primaryVariable: PrimaryVariable? = null,
    val deviceStatus: Int? = null,
    val diagnostics: List<DiagnosticEntry> = emptyList(),
)

/** Phase 1: one foreground session, command 0 then one command 1; further reads are manual. */
class SessionViewModel(application: Application) : AndroidViewModel(application) {
    private val mutableState = MutableStateFlow(SessionState())
    val state = mutableState.asStateFlow()
    private val adapter get() = getApplication<Application>().getSystemService(BluetoothManager::class.java)?.adapter
    private var transport: BluetoothSppTransport? = null
    private var client: HartClient? = null
    private var job: Job? = null
    private var connectionPromptShown = false

    @SuppressLint("MissingPermission")
    fun refreshDevices(showPrompt: Boolean = false) {
        val app = getApplication<Application>()
        val permission = Build.VERSION.SDK_INT < 31 || app.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        if (!permission) {
            mutableState.update { it.copy(permissionGranted = false, devices = emptyList(), error = "Разрешите доступ к ранее сопряжённым Bluetooth-устройствам.") }
            return
        }
        try {
            val bt = adapter
            val enabled = bt?.isEnabled == true
            val devices = if (enabled) bt.bondedDevices.map { d ->
                val name = d.name ?: "Без имени"
                Communicator(name, d.address, name.contains("viator", ignoreCase = true))
            }.sortedWith(compareByDescending<Communicator> { it.likelyViator }.thenBy { it.name }) else emptyList()
            mutableState.update { it.copy(permissionGranted = true, bluetoothAvailable = bt != null, bluetoothEnabled = enabled, devices = devices, error = null) }
            val viators = devices.filter { it.likelyViator }
            if (showPrompt && !connectionPromptShown && !state.value.connected && !state.value.busy && viators.size == 1) {
                connectionPromptShown = true
                requestConnection(viators.single())
            }
        } catch (e: SecurityException) { fail("Нет разрешения Bluetooth. Разрешите доступ в настройках Android.", e) }
    }

    fun requestConnection(device: Communicator) {
        if (state.value.busy || state.value.connected) return
        mutableState.update { it.copy(pendingConnection = device) }
    }

    fun cancelConnectionPrompt() { mutableState.update { it.copy(pendingConnection = null) } }

    @SuppressLint("MissingPermission")
    fun confirmConnection() {
        val selected = state.value.pendingConnection ?: return
        cancelConnectionPrompt()
        if (state.value.busy || state.value.connected) return
        job = viewModelScope.launch {
            mutableState.update { it.copy(busy = true, error = null, identity = null, primaryVariable = null, deviceStatus = null, status = "Подключение RFCOMM…") }
            append(DiagnosticEntry(Instant.now(), "SESSION", "Пользователь подтвердил SPP-подключение к ${selected.name} (${selected.address})"))
            try {
                val bt = adapter ?: throw IOException("Bluetooth недоступен на этом устройстве")
                if (!bt.isEnabled) throw IOException("Bluetooth выключен")
                val device = bt.getRemoteDevice(selected.address)
                if (device.bondState != BluetoothDevice.BOND_BONDED) throw IOException("VIATOR не сопряжён. Выполните сопряжение в настройках Android.")
                transport = BluetoothSppTransport.connect(device)
                val newClient = HartClient(transport!!, ::append)
                client = newClient
                mutableState.update { it.copy(connected = true, status = "Bluetooth подключён. Идентификация HART…") }
                val (identity, status) = newClient.identify()
                mutableState.update { it.copy(identity = identity, deviceStatus = status, status = "Чтение цифровой основной переменной…") }
                readPrimaryVariable(newClient, identity)
            } catch (e: TimeoutCancellationException) {
                closeConnection()
                fail("Таймаут Bluetooth: VIATOR выключен, вне зоны связи или SPP-соединение отклонено.", e)
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                closeConnection()
                fail(e.message ?: "Ошибка подключения", e)
            } finally { mutableState.update { it.copy(busy = false) } }
        }
    }

    fun readAgain() {
        val currentClient = client ?: return
        val identity = state.value.identity ?: return
        if (state.value.busy) return
        job = viewModelScope.launch {
            mutableState.update { it.copy(busy = true, error = null, primaryVariable = null, status = "Чтение основной переменной…") }
            try { readPrimaryVariable(currentClient, identity)
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                closeConnection()
                fail(e.message ?: "Ошибка чтения", e)
            } finally { mutableState.update { it.copy(busy = false) } }
        }
    }

    private suspend fun readPrimaryVariable(currentClient: HartClient, identity: DeviceIdentity) {
        val (pv, status) = currentClient.primaryVariable(identity)
        mutableState.update { it.copy(primaryVariable = pv, deviceStatus = status, status = "Ответ HART получен. Разовое чтение завершено.") }
    }

    fun disconnect(reason: String = "Соединение завершено пользователем") {
        job?.cancel()
        closeConnection()
        if (state.value.connected || state.value.busy) append(DiagnosticEntry(Instant.now(), "SESSION", reason))
        mutableState.update { it.copy(busy = false, connected = false, primaryVariable = null, status = reason, pendingConnection = null) }
    }

    private fun closeConnection() { transport?.close(); transport = null; client = null }
    private fun append(entry: DiagnosticEntry) { mutableState.update { it.copy(diagnostics = (it.diagnostics + entry).takeLast(256)) } }
    private fun fail(message: String, error: Exception) {
        append(DiagnosticEntry(Instant.now(), "ERROR", "${error.javaClass.simpleName}: $message"))
        mutableState.update { it.copy(error = message, connected = false, primaryVariable = null, status = "Нет достоверных данных") }
    }

    fun clearDiagnostics() { mutableState.update { it.copy(diagnostics = emptyList()) } }
    override fun onCleared() { closeConnection(); super.onCleared() }
}
