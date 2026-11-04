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
import ru.hartbro.usb.*
import ru.hartbro.core.*

enum class ConnectionMode(val label:String) { MONITOR("Мониторинг"), WALK("Обход датчиков"), LISTEN("Приём без запросов") }
enum class TransportKind { BLUETOOTH, USB }
data class Communicator(val name: String, val address: String, val likelyViator: Boolean,
    val kind: TransportKind = if(address.startsWith("usb:"))TransportKind.USB else TransportKind.BLUETOOTH)
data class SensorDiagnostics(val identity: DeviceIdentity, val configuration: ConfigurationSnapshot,
    val measurements: MeasurementBatch?, val range: RangeSnapshot? = null, val errors: List<String> = emptyList())
data class SessionState(
    val captureActive:Boolean=false,val capture:CaptureState=CaptureState(),val analysisVisible:Boolean=false,
    val analysis:HartAnalysis?=null,val analysisBaseline:HartAnalysis?=null,val analysisFiles:List<String> = emptyList(),
    val zeroPreparation:ZeroPreparation?=null, val sensorEdit: SensorEdit? = null, val editConfirm: Boolean = false, val editBusy: Boolean = false, val editError: String? = null,
    val sensorRecords: List<SensorRecord> = emptyList(),
    val communicator: Communicator? = null, val chartViewport: ChartViewport = ChartViewport(),
    val diagnosticVisible: Boolean = false, val diagnosticLoading: Boolean = false,
    val sensorDiagnostics: SensorDiagnostics? = null, val diagnosticError: String? = null,
    val usbDevices: List<UsbTarget> = emptyList(), val usbPermissionPending: Boolean = false, val connectionRequestPending: Boolean = false,
    val devices: List<Communicator> = emptyList(), val bluetoothAvailable: Boolean = true,
    val bluetoothEnabled: Boolean = false, val permissionGranted: Boolean = false,
    val pendingConnection: Communicator? = null, val monitor: MonitorState = MonitorState(),
    val diagnostics: List<DiagnosticEntry> = emptyList(), val notice: String? = null,
    val archives: List<Pair<String, SessionInfo>> = emptyList(), val saving: Boolean = false,
    val active: Boolean = false, val detailedDiagnostics: Boolean = false,
    val diagnosticIncomplete: Boolean = false, val serviceRunning: Boolean = false,
    val requestedMode: ConnectionMode = ConnectionMode.MONITOR, val walkActive: Boolean = false,
    val rangeHistoryOnly:Boolean=false, val rangeTransportOpen:Boolean=false, val range:RangeState=RangeState(),val rangeVisible:Boolean=false,val rangeOwned:Boolean=false,val rangeRecords:List<RangeRecord> = emptyList(),
    val walk: WalkState = WalkState(), val savedWalks: List<WalkBook> = emptyList(), val walkDraftAvailable: Boolean = false,
)

class SessionController(private val app: Application, private val captureFactory:(suspend(String)->HartTransport)?=null, private val connectionFactory: (suspend (String) -> MonitorConnection)? = null) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableState = MutableStateFlow(SessionState())
    val state = mutableState.asStateFlow()
    val history = SessionHistory(java.io.File(app.filesDir, "active-session"), java.io.File(app.filesDir, "sessions"))
    private val journal = DiagnosticJournal(File(app.filesDir, "diagnostics/current.txt"))
    private val archiveActions = Mutex()
    private var captureEngine:PassiveCaptureEngine?=null
    private var captureObserver:Job?=null
    private var captureLogFile:File?=null
    private var analysisRawFile:File?=null
    private val analysisDirectory=File(app.filesDir,"hart-analyses")
    private var engine: MonitoringEngine? = null
    private var observer: Job? = null
    private var promptShown = false
    val walkStore = WalkStore(File(app.filesDir, "walks"))
    private var walkCatalog = WalkCatalog()
    private var walkEngine: WalkEngine? = null
    private var walkObserver: Job? = null
    private var communicator: Communicator? = null
    val rangeJournal=RangeJournal(File(app.filesDir,"range-operations"))
    val sensorJournal=SensorJournal(File(app.filesDir,"sensor-operations"))
    private var rangeEngine:RangeEngine?=null
    private var rangeObserver:Job?=null
    private var rangeContext:ConnectionMode?=null
    private var rangeLeaseActive=false
    private var rangeToken=""
    private var rangeRecordLimit=100
    private var reader: MeasurementReader? = null
    @Volatile private var lastTx: Instant? = null
    @Volatile private var lastRx: Instant? = null
    @Volatile private var lastMeasurement: Instant? = null
    @Volatile private var socketState = "нет соединения"
    @Volatile private var serviceLease = 0L
    private val usbHost = UsbHostController(app,{text->append(DiagnosticEntry(Instant.now(),"USB",text))},
        {devices->mutableState.update{it.copy(usbDevices=devices)}})
    private val adapter get() = app.getSystemService(BluetoothManager::class.java)?.adapter
    init { history.cleanOrphans(); scope.launch { runCatching{rangeJournal.recoverInterrupted();sensorJournal.recoverInterrupted()}.onFailure{notice("Журнал записей: ${it.message}")};refreshRangeRecords() };refreshArchives(); refreshWalks();mutableState.update { it.copy(walkDraftAvailable = walkStore.hasDraft()) } }
    fun append(entry: DiagnosticEntry) {
        if (entry.kind == "TX-FRAME") lastTx = entry.time
        if (entry.kind == "RX-CHUNK" || entry.kind == "RX-FRAME") lastRx = entry.time
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
        append(DiagnosticEntry(Instant.now(), "ACTIVITY", "$event; active=${state.value.active}; walk=${state.value.walk.phase}; walkRows=${state.value.walk.book?.rows?.size}; id=${m.identity?.uniqueKey}; started=${m.startedAt}; phase=${m.phase}; service=${state.value.serviceRunning}; socket=$socketState; lastTX=$lastTx; lastRX=$lastRx; lastMeasurement=$lastMeasurement; noMeasurementMs=${lastMeasurement?.let { java.time.Duration.between(it, Instant.now()).toMillis() }}"))
    }
    @Synchronized fun serviceCreated(): Long {
        serviceLease++
        serviceLifecycle("onCreate; lease=$serviceLease", true)
        return serviceLease
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
        usbHost.refresh()
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
            if (showPrompt && !promptShown && !state.value.active && !state.value.connectionRequestPending && !state.value.walkDraftAvailable && state.value.monitor.startedAt == null && viators.size == 1) {
                promptShown = true; requestConnection(viators.single())
            }
        } catch (e: SecurityException) { notice("Нет разрешения Bluetooth: ${e.message}") }
    }
    fun requestUsbConnection(target:UsbTarget,settings:UsbPortSettings,port:Int) {
        if(state.value.active || state.value.usbPermissionPending)return
        try {
            usbHost.select(target,port,settings)
            requestConnection(Communicator("ЭнИ-406 / ${target.name}","usb:${target.ids}:$port",false))
        } catch(e:Exception){notice("USB: ${e.message}")}
    }
    fun requestConnection(device: Communicator) { if (!state.value.active && !state.value.connectionRequestPending && state.value.monitor.startedAt == null) mutableState.update { it.copy(pendingConnection = device) } }
    fun cancelConnectionPrompt() { mutableState.update { it.copy(pendingConnection = null) } }
    fun confirmConnection() {
        val selected = state.value.pendingConnection ?: return
        val mode=state.value.requestedMode
        mutableState.update{it.copy(pendingConnection=null,connectionRequestPending=true)}
        scope.launch {
        try {
            if(selected.kind==TransportKind.USB) {
                mutableState.update{it.copy(usbPermissionPending=true)}
                usbHost.authorizeSelection()
            }
            app.startForegroundService(Intent(app, MonitoringService::class.java).putExtra("address", selected.address).putExtra("name", selected.name).putExtra("mode", mode.name))
            withTimeoutOrNull(5000){state.first{it.active}}
        } catch (e: Exception) {
            val message = "Не удалось запустить подключение: ${e.javaClass.simpleName}: ${e.message}"
            append(DiagnosticEntry(Instant.now(), "SERVICE", "startForegroundService отклонён: $message"))
            mutableState.update { it.copy(notice = message, monitor = MonitorState(phase = MonitorPhase.ERROR, message = message)) }
        } finally { mutableState.update{it.copy(usbPermissionPending=false,connectionRequestPending=false)} }
        }
    }
    fun start(address:String,name:String) { scope.launch { archiveActions.withLock { startMonitoringNow(address,name) } } }
    @SuppressLint("MissingPermission")
    private fun startMonitoringNow(address: String, name: String, initial: MonitorConnection? = null) {
        if (state.value.captureActive || rangeLeaseActive || engine != null || walkEngine != null) { initial?.close();return }
        communicator = Communicator(name,address,true)
        initial?.prepareMonitoring()
        mutableState.update { it.copy(active = true, notice = null, communicator = communicator, sensorDiagnostics = null) }
        lastTx = null; lastRx = null; lastMeasurement = null; socketState = "подготовка"
        event("Подтверждено подключение к $name ($address)")
        val sink = object : SessionSink {
            private var configuration = ConfigurationSnapshot()
            override fun configuration(snapshot: ConfigurationSnapshot) { configuration = snapshot }
            override fun begin(identity: DeviceIdentity, at: Instant) {
                history.begin(identity, "$name ($address)", at, configuration); event("Сессия ${identity.uniqueKey} начата")
            }
            override fun sample(batch: MeasurementBatch) {
                val previous = lastMeasurement
                lastMeasurement = batch.receivedAt
                history.append(batch)
                if (previous != null && java.time.Duration.between(previous, batch.receivedAt).toMillis() > maxOf(10000L, state.value.monitor.periodMillis * 2))
                    event("Измерение после перерыва ${java.time.Duration.between(previous, batch.receivedAt).toMillis()} мс; причина не определена автоматически")
            }
            override fun event(at: Instant, text: String) { history.event(at, text); append(DiagnosticEntry(at, "SESSION", text)) }
            override fun gap(at: Instant, text: String) { history.event(at, text, gap = true); append(DiagnosticEntry(at, "SESSION", text)) }
        }
        var handed = initial
        val newEngine = MonitoringEngine(scope, connect = {
            handed?.also { handed = null } ?: createConnection(address)
        }, sink = sink, transportLossReason = if(communicator?.kind==TransportKind.USB)LossReason.USB else LossReason.BLUETOOTH)
        engine = newEngine
        observer = scope.launch { newEngine.state.collect { monitor ->
            val old = state.value.monitor
            val oldIdentity=old.identity;val newIdentity=monitor.identity
            val changed=oldIdentity!=null && newIdentity!=null && passportKey(oldIdentity)!=passportKey(newIdentity)
            mutableState.update { it.copy(monitor = monitor,
                sensorDiagnostics=if(changed)null else it.sensorDiagnostics,
                diagnosticError=if(changed && it.diagnosticVisible)"Подключён другой датчик. Обновите диагностику" else it.diagnosticError,
                sensorEdit=if(changed && !it.editBusy)null else it.sensorEdit) }
            if (old.phase != monitor.phase) append(DiagnosticEntry(Instant.now(), "STATE", "${old.phase} → ${monitor.phase}: ${monitor.message}; socket=$socketState; lastTX=$lastTx; lastRX=$lastRx; lastMeasurement=$lastMeasurement"))
        } }
        newEngine.start(automaticSession = false)
    }
    @SuppressLint("MissingPermission")
    private suspend fun openTransport(address:String):HartTransport {
        val isUsb=communicator?.kind==TransportKind.USB
        val transport:HartTransport = if(isUsb) {
            socketState="USB: открытие"
            usbHost.open().also{socketState="USB открыт"}
        } else {
            val bt = adapter ?: throw IOException("Bluetooth недоступен")
            if (!bt.isEnabled) throw IOException("Bluetooth выключен")
            val device = bt.getRemoteDevice(address)
            if (device.bondState != BluetoothDevice.BOND_BONDED) throw IOException("Коммуникатор не сопряжён")
            socketState = "подключение"
            BluetoothSppTransport.connect(device) { text -> socketState=text;append(DiagnosticEntry(Instant.now(), "BLUETOOTH", text)) }.also {
                socketState="RFCOMM открыт"
                val actualName=device.name ?: communicator?.name ?: "VIATOR"
                communicator=Communicator(actualName,address,actualName.contains("viator",true))
                mutableState.update{state->state.copy(communicator=communicator)}
            }
        }
        return transport
    }
    @SuppressLint("MissingPermission")
    private suspend fun createConnection(address: String): MonitorConnection {
        connectionFactory?.let { return it(address) }
        val isUsb=communicator?.kind==TransportKind.USB
        val transport=openTransport(address)
        return object : MonitorConnection {
            private lateinit var identity: DeviceIdentity
            private val timeouts=HartTimeoutPolicy()
            private val qualification=UsbReadQualification()
            private fun observe(metric:HartTransactionMetric) {
                append(DiagnosticEntry(Instant.now(),"PERF","command=${metric.command}; attempt=${metric.attempt}; durationMs=${metric.millis}; outcome=${metric.outcome}"))
                qualification.observe(metric)
            }
            private fun newClient(timeout:Long=timeouts.ordinaryRead,idTimeout:Long=timeouts.initialIdentification)=HartClient(transport,::append,timeoutMillis=timeout,readAttempts=1,
                detailed={state.value.detailedDiagnostics},observed=::observe,identificationTimeoutMillis=idTimeout,
                beforeWrite={if(isUsb)qualification.requireWriteAllowed(transport.isOpen)})
            private var client = newClient()
            private var passportReader = PassportReader(client, report = ::event)
            override suspend fun identify() = client.identify().first.also {
                qualification.identified(it);identity = it
            }
            override suspend fun identifyForRecovery()=client.identify(timeouts.recoveryIdentification).first.also{qualification.identified(it);identity=it}
            override val isOpen get() = transport.isOpen
            override fun onDisconnect(listener: () -> Unit) = transport.onDisconnect(listener)
            override fun prepareWalk() {
                reader=null;readerClient=null
                client=newClient(timeouts.ordinaryRead,timeouts.walkDiscovery)
                passportReader=PassportReader(client, report=::event)
            }
            override fun prepareMonitoring() { reader=null;readerClient=null;client=newClient() }
            override fun preparePassportPresence() {passportReader=PassportReader(client,report=::event)}
            override fun resetSession() { reader=null;readerClient=null }
            override suspend fun passport(identity: DeviceIdentity) = passportReader.read(identity)
            override suspend fun rangeSnapshot()=RangeOperations(client).prepare().also{identity=it.identity}
            override suspend fun applyRange(prepared:RangeSnapshot,requested:ConfiguredRange,beforeSend:(ByteArray)->Unit,onReply:(HartExchange)->Unit,onInvalidRx:(ByteArray)->Unit)=RangeOperations(client).apply(prepared,requested,beforeSend,onReply,onInvalidRx)
            override suspend fun verifyRange(expected:DeviceIdentity)=RangeOperations(client).verify(expected).also{identity=it.identity}
            override suspend fun configuration() = readConfiguration(client,identity,::event)
            override suspend fun prepareZero(identity:DeviceIdentity)=SensorOperations(client).prepareZero(identity)
            override suspend fun editSensor(edit: SensorEdit, journal: SensorJournal) = SensorOperations(client).apply(edit,journal)
            override suspend fun discover(): MeasurementBatch {
                reader=MeasurementReader(client,identity,::event);readerClient=client;return reader!!.discover()
            }
            override suspend fun sample(): MeasurementBatch {
                reader=reader?.forDevice(client,identity) ?: MeasurementReader(client,identity,::event)
                readerClient=client
                if(!reader!!.discovered) reader!!.discover()
                return reader!!.sample()
            }
            override fun close() { transport.close();socketState="соединение закрыто";readerClient=null }
        }
    }
    // Tracks reader ownership, never shared concurrently: the engine is the sole connection owner.
    private var readerClient: HartClient? = null
    fun retryIdentification() { scope.launch { archiveActions.withLock {
        if (!rangeLeaseActive && !state.value.walkActive) engine?.retryIdentification()
    } } }
    fun chartViewport(value: ChartViewport) { mutableState.update { it.copy(chartViewport = value) } }
    fun openSensorEdit(field: SensorField) {
        val s=state.value
        if(s.editBusy || s.rangeOwned || s.walkActive || s.captureActive || s.diagnosticLoading) { notice("Дождитесь завершения текущей операции");return }
        val identity=s.monitor.identity ?: return
        val config=s.sensorDiagnostics?.takeIf { passportKey(it.identity)==passportKey(identity) }?.configuration ?: s.monitor.configuration
        val original=when(field) { SensorField.TAG->config.parameters["tag"]?.value.orEmpty();SensorField.LONG_TAG->config.parameters["longTag"]?.value.orEmpty();SensorField.DATE->config.productionDate?.let(HartDate::format).orEmpty();SensorField.ZERO->"" }
        mutableState.update { it.copy(sensorEdit=SensorEdit(identity,field,original,original,communicator=s.communicator?.let{"${it.name} · ${it.kind} · ${it.address}"}.orEmpty()),zeroPreparation=null,editConfirm=false,editError=null) }
    }
    fun closeSensorEdit() { if(!state.value.editBusy)mutableState.update { it.copy(sensorEdit=null,zeroPreparation=null,editConfirm=false,editError=null) } }
    fun prepareZeroEdit(options:ZeroOptions) {
        val edit=state.value.sensorEdit?.takeIf{it.field==SensorField.ZERO} ?: return
        if(state.value.editBusy)return
        mutableState.update{it.copy(editBusy=true,editError=null)}
        scope.launch {archiveActions.withLock {
            var conn:MonitorConnection?=null;var acquired=false
            try {
                check(options.conditionsConfirmed && options.pressureConfirmed){"Подтвердите условия и тип прибора"}
                val owner=engine ?: error("Нет подключения")
                conn=owner.acquireConfiguration();acquired=true;check(conn?.isOpen==true){"Транспорт отключён"}
                val prep=conn!!.prepareZero(edit.identity)
                mutableState.update{it.copy(sensorEdit=edit.copy(original=prep.before?.text.orEmpty(),zero=options.copy(unitCode=prep.unitCode)),zeroPreparation=prep,editConfirm=true)}
            }catch(e:CancellationException){throw e}catch(e:Exception){mutableState.update{it.copy(editError=e.message,editConfirm=false)}}
            finally {if(acquired)runCatching{engine?.releaseConfiguration(conn,null,true,"Предварительное чтение PV завершено",false)};mutableState.update{it.copy(editBusy=false)}}
        }}
    }
    fun sensorEditError(message:String){mutableState.update{it.copy(editError=message)}}
    fun prepareSensorEdit(value: String) {
        val edit=state.value.sensorEdit ?: return
        runCatching {
            when(edit.field) { SensorField.TAG->SensorWrite.TagDate.changeTag(ByteArray(21),value);SensorField.LONG_TAG->SensorWrite.LongTag(value);SensorField.DATE->HartDate.parse(value);SensorField.ZERO->Unit }
            require(value!=edit.original || edit.field==SensorField.ZERO) { "Значение не изменено" }
        }.onSuccess { mutableState.update { it.copy(sensorEdit=edit.copy(requested=value),editConfirm=true,editError=null) } }
            .onFailure { error->mutableState.update { it.copy(editError=error.message) } }
    }
    @Synchronized fun confirmSensorEdit() {
        val s=state.value;val edit=s.sensorEdit ?: return
        if(s.editBusy || !s.editConfirm)return
        if(edit.field==SensorField.ZERO && (s.zeroPreparation==null || !edit.zero.conditionsConfirmed || !edit.zero.pressureConfirmed))return
        mutableState.update { it.copy(editBusy=true,editError=null) }
        scope.launch { archiveActions.withLock {
            var conn:MonitorConnection?=null;var acquired=false;var verification:RangeVerification?=null;var different=false;var resume=true
            try {
                check(!rangeLeaseActive && !state.value.walkActive) { "Коммуникатор занят" }
                val owner=engine ?: error("Нет подключения")
                check(owner.state.value.identity?.let(::passportKey)==passportKey(edit.identity)) { "Датчик заменён. Откройте редактор заново" }
                conn=owner.acquireConfiguration();acquired=true
                check(conn?.isOpen==true) { "Транспорт отключён. Дождитесь восстановления соединения" }
                val result=conn!!.editSensor(edit,sensorJournal)
                different=result.identity?.let { passportKey(it)!=passportKey(edit.identity) } ?: false
                resume=!different && result.identity!=null && (result.configuration!=null || result.record.succeeded || result.record.outcome==SensorOutcome.ACCEPTED_UNVERIFIED)
                val updated=owner.state.value.configuration.copy(parameters=owner.state.value.configuration.parameters+result.configuration?.parameters.orEmpty(),
                    responses=owner.state.value.configuration.responses.filterNot { old->result.configuration?.responses?.any{it.command==old.command}==true }+result.configuration?.responses.orEmpty())
                if(result.configuration!=null && updated.range!=null)verification=RangeVerification(edit.identity,updated)
                mutableState.update { it.copy(sensorDiagnostics=it.sensorDiagnostics?.copy(configuration=updated),notice=if(edit.field==SensorField.ZERO)result.record.resultText+(result.record.error.takeIf{it.isNotBlank()}?.let{". $it"} ?: "") else if(result.record.succeeded) "${edit.field.label}: подтверждено чтением" else "Результат не подтверждён: ${result.record.error}",sensorEdit=null,editConfirm=false) }
            } catch(e:CancellationException) { throw e }
            catch(e:Exception) { if(e is RangeDeviceChanged){different=true;resume=false};mutableState.update { it.copy(editError=e.message ?: "Операция не выполнена",editConfirm=false) } }
            finally {
                if(acquired)runCatching { engine?.releaseConfiguration(conn,verification,resume,"Настройка параметров завершена",different) }
                mutableState.update { it.copy(editBusy=false) };refreshRangeRecords()
            }
        } }
    }
    fun deleteSensorRecord(id:String) { scope.launch { archiveActions.withLock {
        if(!state.value.editBusy)runCatching { sensorJournal.delete(id) }.onSuccess { refreshRangeRecords() }.onFailure { notice("Удаление: ${it.message}") }
    } } }
    fun closeSensorDiagnostics() { mutableState.update { it.copy(diagnosticVisible = false) } }
    fun openSensorDiagnostics() {
        if (state.value.diagnosticLoading) return
        mutableState.update { it.copy(diagnosticVisible = true, diagnosticLoading = true, sensorDiagnostics = null, diagnosticError = null) }
        scope.launch { archiveActions.withLock {
            var conn: MonitorConnection? = null
            var acquired = false
            var verification: RangeVerification? = null
            var different = false
            try {
                check(!rangeLeaseActive && !state.value.walkActive) { "Сначала завершите текущую операцию" }
                val owner = engine ?: error("Сначала подключите прибор")
                val expected = owner.state.value.identity ?: error("Сначала выполните идентификацию")
                conn = owner.acquireConfiguration(); acquired = true
                check(conn?.isOpen == true) { "Bluetooth отключён" }
                val identity = conn!!.identify()
                if(passportKey(identity) != passportKey(expected)) { different = true; throw RangeDeviceChanged(expected, identity) }
                val config = conn!!.configuration()
                val errors = mutableListOf<String>()
                val snapshot = try { conn!!.rangeSnapshot() } catch(e: CancellationException) { throw e }
                    catch(e: IOException) { throw e } catch(e: Exception) { errors += "Диапазон: ${e.message}"; null }
                if(snapshot != null && passportKey(snapshot.identity) != passportKey(expected)) { different = true; throw RangeDeviceChanged(expected,snapshot.identity) }
                val measurements = try { conn!!.sample() } catch(e: CancellationException) { throw e }
                    catch(e: IOException) { throw e } catch(e: Exception) { errors += "Измерения: ${e.message}"; null }
                val updated = config.copy(parameters = config.parameters + snapshot?.configuration?.parameters.orEmpty())
                if(updated.range != null) verification = RangeVerification(identity, updated)
                mutableState.update { it.copy(sensorDiagnostics = SensorDiagnostics(identity, updated, measurements, snapshot, errors)) }
            } catch(e: CancellationException) { throw e }
            catch(e: Exception) { mutableState.update { it.copy(diagnosticError = e.message ?: "Чтение диагностики не выполнено") } }
            finally {
                if(acquired) runCatching { engine?.releaseConfiguration(conn, verification, !different,
                    if(different) "Обнаружен другой прибор" else "Диагностическое чтение завершено", different) }
                    .onFailure { error -> mutableState.update { it.copy(diagnosticError="Ошибка завершения чтения: ${error.message}") } }
                mutableState.update { it.copy(diagnosticLoading = false) }
            }
        } }
    }
    fun deleteRangeRecord(id: String? = null) { scope.launch { archiveActions.withLock {
        if(rangeLeaseActive || state.value.range.busy) { notice("Дождитесь завершения операции"); return@withLock }
        runCatching { if(id == null) { rangeJournal.deleteAll();sensorJournal.deleteAll() } else rangeJournal.delete(id) }
            .onSuccess {
                mutableState.update { it.copy(range=if(id==null || it.range.record?.id==id) it.range.copy(record=null,phase=if(it.range.phase==RangePhase.RESULT)RangePhase.CLOSED else it.range.phase) else it.range) }
                refreshRangeRecords()
            }.onFailure { notice("Удаление: ${it.message}") }
    } } }
    fun beginSession() { scope.launch { archiveActions.withLock { if (rangeLeaseActive || state.value.walkActive) { notice("Сначала завершите настройку или обход");return@withLock };if(engine?.state?.value?.phase == MonitorPhase.READY) chartViewport(ChartViewport());engine?.startSession() ?: notice("Сначала подключите коммуникатор на вкладке «Связь»") } } }
    fun pause(value: Boolean) { scope.launch { archiveActions.withLock { if(!rangeLeaseActive)engine?.pause(value) } } }
    fun period(millis: Long) { engine?.setPeriod(millis) }
    fun save(finish: Boolean = false) {
        if (state.value.saving) return
        mutableState.update { it.copy(saving = true) }
        scope.launch {
          archiveActions.withLock {
            if(state.value.walkActive || finish && rangeLeaseActive) { mutableState.update { it.copy(saving=false) };return@withLock }
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
    fun end() { if (!state.value.saving) scope.launch { archiveActions.withLock { if(!state.value.walkActive && !rangeLeaseActive) endNow() } } }
    private suspend fun endNow(reason: String = "Завершение пользователем", switching: Boolean = false) {
        event("Остановка сессии: $reason")
        engine?.finishSession()
        history.discard()
        lastMeasurement = null
        event("Сессия завершена; временная история удалена; Соединение сохранено, если доступно")
        if (!switching && engine?.state?.value?.transportConnected != true) disconnectNow()
    }
    fun disconnect() { scope.launch { archiveActions.withLock {
        if (rangeLeaseActive || state.value.walkActive || state.value.monitor.startedAt != null) { notice("Сначала сохраните или завершите сессию / обход"); return@withLock }
        if(state.value.captureActive || captureEngine!=null)stopCaptureNow() else disconnectNow()
    } } }
    private suspend fun disconnectNow() {
        engine?.stop("Явное отключение / завершение службы"); observer?.cancelAndJoin(); observer = null; engine = null; readerClient = null
        app.stopService(Intent(app, MonitoringService::class.java))
        mutableState.update { it.copy(active = false, communicator = null, monitor = MonitorState(), diagnosticVisible = false) }
    }
    fun serviceDestroyed(lease: Long) { scope.launch { archiveActions.withLock {
        if (lease != serviceLease) { serviceLifecycle("Устаревший onDestroy lease=$lease; текущий=$serviceLease; сессия сохранена"); return@withLock }
        serviceLifecycle("onDestroy; lease=$lease", false)
        if(rangeLeaseActive) {
            rangeEngine?.stop();rangeObserver?.cancelAndJoin();rangeObserver=null
            releaseRangeLease(null,rangeEngine?.connection,false,"Служба остановлена во время настройки; требуется проверочное чтение")
            rangeToken="";refreshRangeRecords()
            mutableState.update{it.copy(rangeOwned=false,range=rangeEngine?.state?.value ?: RangeState())}
        }
        if(captureEngine!=null){stopCaptureNow();return@withLock}
        if (state.value.walkActive) {
            walkEngine?.stop();walkObserver?.cancelAndJoin();walkObserver=null;walkEngine=null
            mutableState.update { it.copy(active=false,walkActive=false,walkDraftAvailable=walkStore.hasDraft(),walk=it.walk.copy(phase=WalkPhase.INTERRUPTED,transportConnected=false,recovery=null,message="Служба остановлена. Ведомость сохранена в черновике; продолжение — по вашему решению.")) }
        } else if (state.value.active) {
            engine?.stop("Foreground service onDestroy; источник остановки Android не сообщает")
            val previous = engine?.state?.value ?: state.value.monitor
            observer?.cancelAndJoin(); observer = null; engine = null; readerClient = null
            mutableState.update { it.copy(active = false, monitor = previous.copy(phase = if (previous.startedAt != null) MonitorPhase.DECISION else MonitorPhase.ERROR,
                transportConnected = false, latest = null, recovery = null, message = if (previous.startedAt != null) "Служба остановлена. Временная история сохранена до вашего решения" else "Служба остановлена. Подключите коммуникатор повторно")) }
        }
    } } }
    fun showRangeHistory(){mutableState.update{it.copy(rangeVisible=true,rangeHistoryOnly=!it.rangeOwned && !it.range.busy)};refreshRangeRecords()}
    fun showRangeResult(){mutableState.update{it.copy(rangeVisible=true,rangeHistoryOnly=false)}}
    fun openRange(recordId:String?=null,acceptCurrentDevice:Boolean=false) { scope.launch { archiveActions.withLock {
        if(rangeEngine?.state?.value?.busy==true) { showRangeResult();return@withLock }
        try {
            if(rangeLeaseActive) {
                val old=rangeEngine?.state?.value
                rangeEngine?.stop()
                releaseRangeLease(old?.verification ?: old?.snapshot?.let{RangeVerification(it.identity,it.configuration)},rangeEngine?.connection,old?.record?.outcome !in setOf(RangeOutcome.UNKNOWN,RangeOutcome.DIFFERENT_DEVICE),"Возврат от предыдущей настройки")
            }
            rangeObserver?.cancelAndJoin();rangeObserver=null
            val selected=communicator ?: run {notice("Сначала подключите и идентифицируйте коммуникатор");return@withLock}
            val record=recordId?.let(rangeJournal::load)
            val initial=when {
                state.value.walkActive && walkEngine!=null -> {rangeContext=ConnectionMode.WALK;walkEngine!!.acquireConfiguration()}
                engine!=null -> {rangeContext=ConnectionMode.MONITOR;engine!!.acquireConfiguration()}
                else -> {notice("Сначала подключите и идентифицируйте прибор");return@withLock}
            }
            rangeLeaseActive=true;rangeToken=java.util.UUID.randomUUID().toString();val token=rangeToken
            val expected=record?.identity ?: if(acceptCurrentDevice)null else if(rangeContext==ConnectionMode.MONITOR)state.value.monitor.identity else state.value.walk.lastReading?.identity
            val worker=RangeEngine(scope,initial,{createConnection(selected.address)},rangeJournal,expected,finished={r,v,connection,resume ->
                scope.launch { archiveActions.withLock {
                    if(token!=rangeToken || !rangeLeaseActive)return@withLock
                    releaseRangeLease(v,connection,resume, r?.let{RangeEngine.resultText(it)+"; ${it.original.label} → ${it.actual?.label ?: "не подтверждено"}; запрошено ${it.requested.label}"} ?: "Настройка завершена")
                    refreshRangeRecords()
                } }
            })
            rangeEngine=worker
            mutableState.update{it.copy(rangeHistoryOnly=false,rangeOwned=true,rangeVisible=true,range=worker.state.value,notice=null)}
            rangeObserver=scope.launch{worker.state.collect{r->mutableState.update{it.copy(range=r,rangeTransportOpen=r.transportConnected)}}}
            if(record==null) worker.open() else worker.verifyOnly(record)
        } catch(e:Exception) {
            if(rangeLeaseActive)releaseRangeLease(null,rangeEngine?.connection,false,"Настройка не открыта: ${e.message}")
            notice("Настройка диапазона: ${e.message}")
        }
    } } }
    private fun releaseRangeLease(verification:RangeVerification?,connection:MonitorConnection?,resume:Boolean,message:String) {
        if(!rangeLeaseActive)return
        val canResume=resume && connection?.isOpen==true
        try {
            if(rangeContext==ConnectionMode.MONITOR)engine?.releaseConfiguration(connection,verification,canResume,message,rangeEngine?.state?.value?.otherDevice!=null)
            else walkEngine?.releaseConfiguration(connection,canResume,message)
        } catch(e:Exception) {notice("Ошибка сохранения события настройки: ${e.message}. История остаётся приостановленной.")}
        finally {rangeLeaseActive=false;mutableState.update{it.copy(rangeOwned=false)}}
    }
    fun closeRange(){
        val target=rangeEngine;val token=rangeToken
        mutableState.update{it.copy(rangeVisible=false)}
        if(target?.state?.value?.busy==true && target.cancelBeforeSend()!=true)return
        scope.launch {archiveActions.withLock {
            // An older close must not close a newly opened configuration lease.
            if(rangeLeaseActive && rangeToken==token && rangeEngine===target) {
                val r=target?.state?.value
                target?.stop(closeConnection=false)
                val verification=r?.verification ?: r?.snapshot?.let{RangeVerification(it.identity,it.configuration)}
                releaseRangeLease(verification,target?.connection,r?.otherDevice==null && r?.record?.outcome !in setOf(RangeOutcome.UNKNOWN,RangeOutcome.DIFFERENT_DEVICE),"Возврат от настройки диапазона без повторной записи")
            }
            if(rangeToken==token && state.value.diagnosticVisible)openSensorDiagnostics()
        }}
    }
    fun selectRange(lower:String,upper:String,unitCode:Int=state.value.range.snapshot?.range?.unitCode ?: -1){rangeEngine?.select(lower,upper,unitCode)}
    fun cancelRangeConfirmation(){rangeEngine?.cancelConfirmation()}
    fun confirmRange(token:String){if(rangeLeaseActive)rangeEngine?.confirm(token)}
    fun moreRangeRecords(){rangeRecordLimit+=100;refreshRangeRecords()}
    fun refreshRangeRecords(){scope.launch{runCatching{rangeJournal.list(rangeRecordLimit) to sensorJournal.list(rangeRecordLimit)}.onSuccess{(records,sensor)->mutableState.update{it.copy(rangeRecords=records,sensorRecords=sensor)}}.onFailure{notice("Журнал записей: ${it.message}")}}}
    fun writeRangeReport(id:String,output:OutputStream){val record=rangeEngine?.state?.value?.record?.takeIf{it.id==id} ?: rangeJournal.load(id);output.write(record.text().toByteArray(Charsets.UTF_8))}
    fun showAnalysis(visible:Boolean=true){mutableState.update{it.copy(analysisVisible=visible,analysisFiles=analysisDirectory.listFiles()?.filter{f->f.extension=="txt"}?.sortedByDescending{f->f.lastModified()}?.map{f->f.name}.orEmpty())}}
    fun importAnalysis(text:String,baseline:Boolean=false,source:String="Внешний HEX") {scope.launch {
        try {
            check(baseline || !state.value.captureActive){"Остановите приём перед открытием другого журнала"}
            val analysis=HartLogImport.parse(text,source)
            if(!baseline) {
                analysisDirectory.mkdirs();val file=File(analysisDirectory,"${java.util.UUID.randomUUID()}.txt");file.writeText(text);analysisRawFile=file
            }
            mutableState.update{if(baseline)it.copy(analysisBaseline=analysis,analysisVisible=true)else it.copy(analysis=analysis,analysisVisible=true)};showAnalysis()
        }catch(e:Exception){notice("Импорт HEX: ${e.message}")}
    }}
    fun openAnalysisFile(name:String){scope.launch {
        try {require(name.matches(Regex("[0-9a-f-]{36}\\.txt")));val file=File(analysisDirectory,name);require(file.length()<=HartLogImport.MAX_BYTES)
            val analysis=HartLogImport.parse(file.readText(),name);check(!state.value.captureActive){"Сначала остановите приём"}
            analysisRawFile=file;mutableState.update{it.copy(analysis=analysis,analysisVisible=true)}
        }catch(e:Exception){notice("Открытие анализа: ${e.message}")}
    }}
    private fun analysisText():String = if(analysisRawFile!=null) {
        captureEngine?.takeIf{analysisRawFile==captureLogFile}?.snapshotText() ?: analysisRawFile!!.readText()
    } else state.value.analysis?.text() ?: "HART-бро: кадры не получены\n"
    fun writeAnalysis(output:OutputStream){output.write(analysisText().toByteArray())}
    fun saveAnalysisSnapshot(){scope.launch {
        try {analysisDirectory.mkdirs();val file=File(analysisDirectory,"${java.util.UUID.randomUUID()}.txt");file.writeText(analysisText());notice("Сценарий сохранён: ${file.name}");showAnalysis()}
        catch(e:Exception){notice("Сохранение анализа: ${e.message}")}
    }}
    fun startPassiveConnection(address:String,name:String){scope.launch{archiveActions.withLock{
        if(engine!=null || walkEngine!=null || rangeLeaseActive || captureEngine!=null)return@withLock
        communicator=Communicator(name,address,false)
        val file=File(app.filesDir,"hart-capture-live/${java.util.UUID.randomUUID()}.txt");captureLogFile=file;analysisRawFile=file
        mutableState.update{it.copy(active=true,captureActive=true,communicator=communicator,requestedMode=ConnectionMode.LISTEN,analysisVisible=true,monitor=MonitorState(),notice=null)}
        val worker=PassiveCaptureEngine(scope,{captureFactory?.invoke(address) ?: openTransport(address)},file,::append)
        captureEngine=worker
        captureObserver=scope.launch{worker.state.collect{capture->
            mutableState.update{it.copy(capture=capture,analysis=HartAnalysis("Приём ${communicator?.name}",capture.records,
                listOf("Экран: последние 2000 событий; экспорт/сохранение содержат весь файл до 16 МиБ. Видимость чужих запросов и ответов проверяется отдельно на оборудовании.")))}
            if(capture.phase==CapturePhase.ERROR){notice("Приём остановлен: ${capture.error}");app.stopService(Intent(app,MonitoringService::class.java))}
        }}
        event("Начат приём без собственных запросов. HART TX запрещена; аппаратная возможность полного пассивного приёма не подтверждена")
        worker.start()
    }}}
    fun stopCapture(){scope.launch{archiveActions.withLock{stopCaptureNow()}}}
    private suspend fun stopCaptureNow(){
        captureEngine?.stop();captureObserver?.cancelAndJoin();captureObserver=null;captureEngine=null
        val analysis=captureLogFile?.takeIf{it.exists()}?.let{runCatching{HartLogImport.parse(it.readText(),it.name)}.getOrNull()}
        mutableState.update{it.copy(active=false,captureActive=false,capture=it.capture.copy(phase=CapturePhase.STOPPED,transportConnected=false),analysis=analysis ?: it.analysis)}
        app.stopService(Intent(app,MonitoringService::class.java));event("Приём завершён. Собственные HART-запросы не отправлялись")
    }
    fun chooseMode(mode: ConnectionMode) { if(!state.value.active)mutableState.update { it.copy(requestedMode=mode) } }
    fun requestWalk(saveSession: Boolean? = null) { scope.launch { archiveActions.withLock {
        if (rangeLeaseActive) { notice("Настройка диапазона ещё не завершена");return@withLock }
        if (state.value.walkActive) return@withLock
        if(state.value.walkDraftAvailable && state.value.walk.book==null) { notice("Сначала восстановите или удалите черновик на вкладке «Обход»");return@withLock }
        if (state.value.monitor.startedAt != null) {
            if (saveSession == null) { notice("Сначала сохраните или завершите измерительную сессию");return@withLock }
            if (saveSession) { try { history.snapshot();refreshArchives() } catch(e:Exception) { notice("Сессия не сохранена: ${e.message}");return@withLock } }
            endNow("Переход в обход",switching=true)
        }
        mutableState.update { it.copy(requestedMode=ConnectionMode.WALK) }
        val selected=communicator
        if (selected==null || !state.value.active) { notice("Выберите коммуникатор на вкладке «Связь» и подтвердите подключение для обхода");return@withLock }
        val initial=try { engine?.detachReadyConnection() } catch(e:IllegalStateException) { notice(e.message ?: "Дождитесь начала сессии");return@withLock }
        observer?.cancelAndJoin();observer=null;engine=null;reader=null;readerClient=null
        if (walkCatalog.snapshot().endedAt != null) walkCatalog=WalkCatalog()
        startWalkNow(selected,initial)
    } } }
    fun startWalkConnection(address:String,name:String) { scope.launch { archiveActions.withLock {
        if (state.value.captureActive || captureEngine!=null || rangeLeaseActive || engine!=null || walkEngine!=null) return@withLock
        if(state.value.walkDraftAvailable && state.value.walk.book==null) { notice("Сначала восстановите или удалите черновик на вкладке «Обход»");app.stopService(Intent(app,MonitoringService::class.java));return@withLock }
        if (walkCatalog.snapshot().endedAt != null) walkCatalog=WalkCatalog()
        startWalkNow(Communicator(name,address,true))
    } } }
    private fun startWalkNow(selected:Communicator,initial:MonitorConnection?=null) {
        communicator=selected
        mutableState.update { it.copy(communicator=selected,active=true,walkActive=true,requestedMode=ConnectionMode.WALK,monitor=MonitorState(),notice=null) }
        event("Обход начат: ${selected.name} (${selected.address}); только чтение; сохранение ведомости вручную")
        val worker=WalkEngine(scope,{createConnection(selected.address)},walkCatalog,
            changed={ _ -> walkStore.writeDraft(walkCatalog::snapshot);mutableState.update { it.copy(walkDraftAvailable=true) } }, diagnostic=::event,transportLossReason=if(selected.kind==TransportKind.USB)LossReason.USB else LossReason.BLUETOOTH)
        walkEngine=worker
        walkObserver=scope.launch { worker.state.collect { walk -> mutableState.update { it.copy(walk=walk) } } }
        worker.start(initial)
    }
    fun retryWalk() { scope.launch { archiveActions.withLock {
        if(rangeLeaseActive || !state.value.walkActive || walkEngine==null || engine!=null || state.value.walk.phase!=WalkPhase.DECISION) return@withLock
        val selected=communicator ?: return@withLock
        walkEngine?.stop();walkObserver?.cancelAndJoin();walkObserver=null;walkEngine=null
        startWalkNow(selected)
    } } }
    fun walkInterval(millis:Long) { walkEngine?.setInterval(millis) }
    fun rereadWalk() { walkEngine?.repeatCurrent() }
    fun restoreWalkDraft() { scope.launch { archiveActions.withLock {
        if (state.value.walkActive || state.value.monitor.startedAt!=null) { notice("Сначала завершите текущий опрос");return@withLock }
        runCatching { walkStore.restoreDraft() }.onSuccess { book ->
            walkCatalog=WalkCatalog(book.copy(endedAt=null));mutableState.update { it.copy(walk=WalkState(phase=WalkPhase.INTERRUPTED,book=walkCatalog.snapshot(),message="Черновик восстановлен. Нажмите «Начать обход» и подтвердите коммуникатор."),walkDraftAvailable=false,requestedMode=ConnectionMode.WALK) }
        }.onFailure { notice("Черновик не прочитан: ${it.message}") }
    } } }
    fun discardWalkDraft() { scope.launch { archiveActions.withLock {
        if(state.value.walkActive) return@withLock
        runCatching { walkStore.clearDraft() }.onSuccess { walkCatalog=WalkCatalog();mutableState.update { it.copy(walk=WalkState(),walkDraftAvailable=false) } }.onFailure { notice("Ошибка удаления черновика: ${it.message}") }
    } } }
    fun saveWalk() { scope.launch { archiveActions.withLock {
        if(walkCatalog.snapshot().let { it.rows.isEmpty() && it.pending.isEmpty() }) { notice("В ведомости пока нет приборов");return@withLock }
        runCatching { walkStore.save(walkCatalog.snapshot()) }.onSuccess { refreshWalks();notice("Ведомость сохранена. Обход продолжается, если был запущен.") }.onFailure { notice("Ошибка сохранения ведомости: ${it.message}") }
    } } }
    fun finishWalk(save:Boolean,monitorNext:Boolean=false) { scope.launch { archiveActions.withLock {
        // Ignore stale/double UI actions after ownership has already moved to ordinary monitoring.
        if(rangeLeaseActive || engine!=null || !state.value.walkActive && walkCatalog.snapshot().endedAt!=null) return@withLock
        walkEngine?.stop();walkObserver?.cancelAndJoin();walkObserver=null;walkEngine=null
        val finishedAt=Instant.now()
        if(save) try { walkStore.save(walkCatalog.snapshot().copy(endedAt=finishedAt)) } catch(e:Exception) {
            mutableState.update { it.copy(walkActive=false,walk=it.walk.copy(phase=WalkPhase.INTERRUPTED,transportConnected=false,message="Ведомость в памяти; не удалось сохранить: ${e.message}")) };notice("Сохранение не выполнено. Повторите или экспортируйте ведомость.");return@withLock
        }
        walkCatalog.finish(finishedAt)
        // The explicit finish releases RFCOMM. Permanent earlier snapshots remain after discard.
        try { walkStore.clearDraft() } catch(e:Exception) { notice("Обход остановлен; черновик не удалён: ${e.message}") }
        val completed=if(save) walkCatalog.snapshot() else null
        if(!save) walkCatalog=WalkCatalog();mutableState.update { it.copy(active=false,walkActive=false,walkDraftAvailable=false,walk=WalkState(book=completed,message=if(save) "Ведомость сохранена; обход завершён" else "Обход завершён без сохранения"),requestedMode=ConnectionMode.MONITOR) }
        refreshWalks()
        if(monitorNext && communicator!=null) { val d=communicator!!;startMonitoringNow(d.address,d.name) }
        else app.stopService(Intent(app,MonitoringService::class.java))
    } } }
    fun editWalk(rowId:String,values:Map<PassportField,String>,savedId:String?=null) = mutateWalk(savedId) { edit(rowId,values) }
    fun acceptWalkRange(rowId:String,savedId:String?=null) = mutateWalk(savedId) { acceptRange(rowId) }
    fun resolveWalkIdentity(pendingId:String,rowId:String?,savedId:String?=null) = mutateWalk(savedId) { resolvePending(pendingId,rowId) }
    fun separateWalk(rowId:String,savedId:String?=null) = mutateWalk(savedId) { separate(rowId) }
    fun renameWalk(title:String,savedId:String?=null) = mutateWalk(savedId) { rename(title) }
    private fun mutateWalk(savedId:String?,action:WalkCatalog.()->Unit) { scope.launch { archiveActions.withLock {
        runCatching {
            if(savedId!=null && savedId!=walkCatalog.snapshot().id) {
                val catalog=WalkCatalog(walkStore.load(savedId));catalog.action();walkStore.save(catalog.snapshot());refreshWalks()
            } else {
                // A saved snapshot of the currently active walk is the same document, not a second writable copy.
                walkCatalog.action();walkStore.writeDraft(walkCatalog::snapshot);walkEngine?.documentChanged()
                if(savedId!=null || walkCatalog.snapshot().endedAt!=null) { walkStore.save(walkCatalog.snapshot());refreshWalks() }
                mutableState.update { it.copy(walk=it.walk.copy(book=walkCatalog.snapshot()),walkDraftAvailable=true) }
            }
        }.onFailure { notice("Изменение ведомости не выполнено: ${it.message}") }
    } } }
    fun deleteWalk(id:String) { scope.launch { archiveActions.withLock {
        runCatching { walkStore.delete(id);refreshWalks() }.onFailure { notice("Ошибка удаления ведомости: ${it.message}") }
    } } }
    fun refreshWalks() { scope.launch {
        runCatching { walkStore.list() }.onSuccess { books -> mutableState.update { it.copy(savedWalks=books) } }.onFailure { notice("Ошибка журнала обходов: ${it.message}") }
    } }
    fun edit(id: String, title: String, comment: String) { scope.launch { archiveActions.withLock {
        runCatching { history.edit(id, title, comment) }.onSuccess { refreshArchives() }.onFailure { notice("Ошибка изменения: ${it.message}") }
    } } }
    fun deleteMany(ids: Set<String>) { scope.launch { archiveActions.withLock {
        runCatching { ids.forEach(history::delete) }.onSuccess { refreshArchives() }.onFailure { notice("Ошибка удаления: ${it.message}") }
    } } }
    fun refreshArchives() { scope.launch { archiveActions.withLock { try { val entries = history.list(); mutableState.update { it.copy(archives = entries) } } catch (e: Exception) { notice("Ошибка журнала: ${e.message}") } } } }
    fun delete(id: String) = deleteMany(setOf(id))
}

class SessionViewModel(application: Application) : AndroidViewModel(application) {
    val controller = (application as HartApplication).controller
    val state = controller.state
    fun refreshDevices(showPrompt: Boolean = false) = controller.refreshDevices(showPrompt)
}
