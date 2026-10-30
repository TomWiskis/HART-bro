package ru.hartbro.app.session

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Intent
import java.io.File
import java.io.OutputStream
import android.content.pm.PackageManager
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.hartbro.app.HartApplication
import ru.hartbro.bluetooth.BluetoothSppTransport
import ru.hartbro.core.*

data class Communicator(val name: String, val address: String, val likelyViator: Boolean)
data class SessionState(
    val devices: List<Communicator> = emptyList(), val bluetoothAvailable: Boolean = true,
    val bluetoothEnabled: Boolean = false, val permissionGranted: Boolean = false,
    val pendingConnection: Communicator? = null, val monitor: MonitorState = MonitorState(),
    val diagnostics: List<DiagnosticEntry> = emptyList(), val notice: String? = null,
    val archives: List<Pair<String, SessionInfo>> = emptyList(), val saving: Boolean = false,
    val active: Boolean = false, val detailedDiagnostics: Boolean = false,
    val diagnosticIncomplete: Boolean = false, val serviceRunning: Boolean = false,
)

class SessionController(private val app: Application, private val connectionFactory: (suspend (String) -> MonitorConnection)? = null) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableState = MutableStateFlow(SessionState())
    val state = mutableState.asStateFlow()
    val history = SessionHistory(java.io.File(app.filesDir, "active-session"), java.io.File(app.filesDir, "sessions"))
    private val journal = DiagnosticJournal(File(app.filesDir, "diagnostics/current.txt"))
    private val archiveActions = Mutex()
    private var engine: MonitoringEngine? = null
    private var observer: Job? = null
    private var promptShown = false
    private val adapter get() = app.getSystemService(BluetoothManager::class.java)?.adapter
    init { history.cleanOrphans(); refreshArchives() }
    fun append(entry: DiagnosticEntry) {
        val accepted = runCatching { journal.append(entry) }.getOrElse {
            notice("Ошибка временного HEX-журнала: ${it.message}"); false
        }
        if (accepted || entry.kind != "RX-CHUNK") mutableState.update { it.copy(
            diagnostics = (it.diagnostics + entry).takeLast(512), diagnosticIncomplete = journal.incomplete) }
    }
    fun detailedDiagnostics(value: Boolean) {
        journal.detailed = value
        mutableState.update { it.copy(detailedDiagnostics = value) }
        event(if (value) "Подробная диагностика включена пользователем" else "Подробная диагностика выключена")
    }
    fun writeHex(output: OutputStream) = journal.copyTo(output)
    fun diagnosticText() = journal.text()
    fun shareHex(): File {
        val directory = File(app.cacheDir, "hex-exports").apply { mkdirs() }
        directory.listFiles()?.sortedByDescending { it.lastModified() }?.drop(2)?.forEach { it.delete() }
        return File(directory, "hart-hex-${System.currentTimeMillis()}.txt").also { file -> file.outputStream().use(::writeHex) }
    }
    fun activityLifecycle(event: String) {
        val m = state.value.monitor
        append(DiagnosticEntry(Instant.now(), "ACTIVITY", "$event; active=${state.value.active}; id=${m.identity?.uniqueKey}; started=${m.startedAt}; phase=${m.phase}; service=${state.value.serviceRunning}"))
    }
    fun serviceLifecycle(event: String, running: Boolean? = null) {
        if (running != null) mutableState.update { it.copy(serviceRunning = running) }
        append(DiagnosticEntry(Instant.now(), "SERVICE", event))
    }
    private fun event(text: String) = append(DiagnosticEntry(Instant.now(), "SESSION", text))
    fun notice(text: String) { mutableState.update { it.copy(notice = text) } }
    fun clearDiagnostics() { scope.launch { runCatching { journal.clear() }.onSuccess { mutableState.update { it.copy(diagnostics = emptyList(), diagnosticIncomplete = false) } }.onFailure { notice("Ошибка очистки: ${it.message}") } } }
    @SuppressLint("MissingPermission")
    fun refreshDevices(showPrompt: Boolean = false) {
        val granted = Build.VERSION.SDK_INT < 31 || app.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        try {
            val bt = adapter
            val enabled = granted && bt?.isEnabled == true
            val devices = if (enabled) bt!!.bondedDevices.map { d ->
                val name = d.name ?: "Без имени"
                Communicator(name, d.address, name.contains("viator", true))
            }.sortedWith(compareByDescending<Communicator> { it.likelyViator }.thenBy { it.name }) else emptyList()
            mutableState.update { it.copy(permissionGranted = granted, bluetoothAvailable = bt != null, bluetoothEnabled = enabled, devices = devices) }
            val viators = devices.filter { it.likelyViator }
            if (showPrompt && !promptShown && !state.value.active && viators.size == 1) {
                promptShown = true; requestConnection(viators.single())
            }
        } catch (e: SecurityException) { notice("Нет разрешения Bluetooth: ${e.message}") }
    }
    fun requestConnection(device: Communicator) { if (!state.value.active) mutableState.update { it.copy(pendingConnection = device) } }
    fun cancelConnectionPrompt() { mutableState.update { it.copy(pendingConnection = null) } }
    fun confirmConnection() {
        val selected = state.value.pendingConnection ?: return
        cancelConnectionPrompt()
        try {
            app.startForegroundService(Intent(app, MonitoringService::class.java).putExtra("address", selected.address).putExtra("name", selected.name))
        } catch (e: Exception) { notice("Не удалось запустить мониторинг: ${e.message}") }
    }
    @Synchronized @SuppressLint("MissingPermission")
    fun start(address: String, name: String) {
        if (engine != null) return
        mutableState.update { it.copy(active = true, notice = null) }
        event("Подтверждено подключение к $name ($address)")
        val sink = object : SessionSink {
            override fun begin(identity: DeviceIdentity, at: Instant) {
                history.begin(identity, "$name ($address)", at); event("Сессия ${identity.uniqueKey} начата")
            }
            override fun sample(batch: MeasurementBatch) = history.append(batch)
            override fun event(at: Instant, text: String) { history.event(at, text); append(DiagnosticEntry(at, "SESSION", text)) }
            override fun gap(at: Instant, text: String) { history.event(at, text, gap = true); append(DiagnosticEntry(at, "SESSION", text)) }
        }
        var reader: MeasurementReader? = null
        val newEngine = MonitoringEngine(scope, connect = {
          connectionFactory?.invoke(address) ?: run {
            val bt = adapter ?: throw IOException("Bluetooth недоступен")
            if (!bt.isEnabled) throw IOException("Bluetooth выключен")
            val device = bt.getRemoteDevice(address)
            if (device.bondState != BluetoothDevice.BOND_BONDED) throw IOException("Коммуникатор не сопряжён")
            val transport = BluetoothSppTransport.connect(device)
            val client = HartClient(transport, ::append, readAttempts = 1)
            object : MonitorConnection {
                private lateinit var identity: DeviceIdentity
                override suspend fun identify(): DeviceIdentity = client.identify().first.also { identity = it }
                override suspend fun discover(): MeasurementBatch {
                    reader = MeasurementReader(client, identity, ::event)
                    readerClient = client
                    return reader!!.discover()
                }
                override suspend fun sample(): MeasurementBatch {
                    // A replacement RFCOMM connection needs a reader bound to its new client.
                    if (readerClient !== client) { reader!!.rebind(client); readerClient = client }
                    if (!reader!!.discovered) reader!!.discover()
                    return reader!!.sample()
                }
                override fun close() { transport.close(); readerClient = null }
            }
          }
        }, sink = sink)
        engine = newEngine
        observer = scope.launch { newEngine.state.collect { monitor -> mutableState.update { it.copy(monitor = monitor) } } }
        newEngine.start()
    }
    // Tracks reader ownership, never shared concurrently: the engine is the sole connection owner.
    private var readerClient: HartClient? = null
    fun pause(value: Boolean) { scope.launch { engine?.pause(value) } }
    fun period(millis: Long) { engine?.setPeriod(millis) }
    fun save(finish: Boolean = false) {
        if (state.value.saving) return
        mutableState.update { it.copy(saving = true) }
        scope.launch {
          archiveActions.withLock {
            try {
                val id = history.snapshot()
                runCatching { history.event(Instant.now(), "Снимок сохранён: $id") }
                event("Снимок сохранён: $id")
                notice("Сессия сохранена в журнале")
                refreshArchives()
                if (finish) endNow()
            } catch (e: Exception) { notice("Ошибка сохранения: ${e.message}. Временная история сохранена до вашего решения.") }
            finally { mutableState.update { it.copy(saving = false) } }
          }
        }
    }
    fun end() { if (!state.value.saving) scope.launch { archiveActions.withLock { endNow() } } }
    private suspend fun endNow(reason: String = "Завершение пользователем") {
        event("Остановка сессии: $reason")
        engine?.stop(reason); observer?.cancelAndJoin(); observer = null; engine = null; readerClient = null
        history.discard()
        event("Сессия завершена; временная история удалена")
        mutableState.update { it.copy(active = false, monitor = MonitorState()) }
        app.stopService(Intent(app, MonitoringService::class.java))
    }
    fun serviceDestroyed() { scope.launch { archiveActions.withLock { if (state.value.active) endNow("Foreground service уничтожена системой / stopService") } } }
    fun refreshArchives() { scope.launch { try { val entries = history.list(); mutableState.update { it.copy(archives = entries) } } catch (e: Exception) { notice("Ошибка журнала: ${e.message}") } } }
    fun delete(id: String) { scope.launch { try { history.delete(id); refreshArchives() } catch (e: Exception) { notice("Ошибка удаления: ${e.message}") } } }
}

class SessionViewModel(application: Application) : AndroidViewModel(application) {
    val controller = (application as HartApplication).controller
    val state = controller.state
    fun refreshDevices(showPrompt: Boolean = false) = controller.refreshDevices(showPrompt)
}
