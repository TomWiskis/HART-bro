package ru.hartbro.core

import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface MonitorConnection : AutoCloseable {
    suspend fun identify(): DeviceIdentity
    suspend fun discover(): MeasurementBatch
    suspend fun sample(): MeasurementBatch
    suspend fun configuration(): ConfigurationSnapshot = ConfigurationSnapshot()
    suspend fun editSensor(edit: SensorEdit, journal: SensorJournal): SensorEditResult = throw UnsupportedOperationException("Запись параметров недоступна")
    suspend fun passport(identity: DeviceIdentity): PassportReading = PassportReading(identity, emptyMap(), emptyMap(), null, java.time.Instant.now(), mapOf("connection" to "Паспортное чтение недоступно"))
    suspend fun rangeSnapshot():RangeSnapshot = throw UnsupportedOperationException("Настройка диапазона недоступна")
    suspend fun applyRange(prepared:RangeSnapshot,requested:ConfiguredRange,beforeSend:(ByteArray)->Unit,onReply:(HartExchange)->Unit,onInvalidRx:(ByteArray)->Unit = {}):RangeAttempt = throw UnsupportedOperationException("Настройка диапазона недоступна")
    suspend fun verifyRange(expected:DeviceIdentity):RangeVerification = throw UnsupportedOperationException("Проверка диапазона недоступна")
    fun prepareWalk() {}
    fun preparePassportPresence() {}
    fun prepareMonitoring() {}
    fun resetSession() {}
    fun onDisconnect(listener: () -> Unit) {}
    val isOpen: Boolean get() = true
}
enum class MonitorPhase { IDLE, CONNECTING, READY, MONITORING, PAUSED, RECOVERING, DECISION, DIFFERENT_DEVICE, ERROR }
enum class LossReason { BLUETOOTH, USB, HART, UNKNOWN }
data class RecoveryProgress(val reason: LossReason, val startedMillis: Long, val deadlineMillis: Long,
                            val nowMillis: Long, val attempt: Int = 0, val lastAttempt: String = "Подготовка") {
    val elapsedMillis: Long get() = (nowMillis - startedMillis).coerceIn(0, deadlineMillis - startedMillis)
    val remainingMillis: Long get() = (deadlineMillis - nowMillis).coerceIn(0, deadlineMillis - startedMillis)
}
data class MonitorState(
    val phase: MonitorPhase = MonitorPhase.IDLE, val identity: DeviceIdentity? = null,
    val latest: MeasurementBatch? = null, val lastKnown: MeasurementBatch? = null, val lastHartSuccess: Instant? = null, val transportConnected: Boolean = false,
    val identificationResponding: Boolean = false,
    val message: String = "Сессия не начата", val periodMillis: Long = 2000,
    val configuration: ConfigurationSnapshot = ConfigurationSnapshot(),
    val startedAt: Instant? = null, val revision: Long = 0, val recovery: RecoveryProgress? = null,
)
interface SessionSink {
    fun begin(identity: DeviceIdentity, at: Instant)
    fun configuration(snapshot: ConfigurationSnapshot) {}
    fun sample(batch: MeasurementBatch)
    fun event(at: Instant, text: String)
    fun gap(at: Instant, text: String) = event(at, text)
}

/** One coroutine owns connection, queue, polling and recovery. No outstanding timer/request queue. */
class MonitoringEngine(
    private val scope: CoroutineScope, private val connect: suspend () -> MonitorConnection,
    private val sink: SessionSink, private val wallTime: () -> Instant = Instant::now,
    private val monotonicMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    private val transportLossReason: LossReason = LossReason.BLUETOOTH,
) {
    private val mutableState = MutableStateFlow(MonitorState())
    val state = mutableState.asStateFlow()
    private var job: Job? = null
    private val sessionStarts = Channel<Unit>(Channel.CONFLATED)
    private val controls = Any()
    private val ioGate=Mutex()
    @Volatile private var configuring=false
    private var wasPaused=false
    private var resumeBlockedByConfiguration=false
    @Volatile private var connection: MonitorConnection? = null
    @Volatile private var paused = false
    @Volatile private var startingSession = false
    private fun publish(phase: MonitorPhase, message: String, latest: MeasurementBatch? = null) {
        mutableState.update { it.copy(phase = phase, message = message, latest = latest, lastKnown = latest ?: it.lastKnown,
            lastHartSuccess = latest?.receivedAt ?: it.lastHartSuccess,
            transportConnected = connection?.isOpen == true, revision = it.revision + 1,
            recovery = if (phase == MonitorPhase.RECOVERING) it.recovery else null) }
    }
    fun startSession() = synchronized(controls) {
        if (state.value.phase != MonitorPhase.READY || !state.value.transportConnected || !state.value.identificationResponding || startingSession || configuring) return@synchronized
        startingSession=true
        if (job?.isActive != true) start() else sessionStarts.trySend(Unit)
    }
    fun start(automaticSession: Boolean = true) = synchronized(controls) {
        check(job?.isActive != true) { "Подключение уже начато" }
        publish(MonitorPhase.CONNECTING, "Подключение коммуникатора и идентификация HART…")
        job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                publish(MonitorPhase.CONNECTING, "Подключение коммуникатора и идентификация HART…")
                var (identity,config)=ioGate.withLock {
                    if (connection?.isOpen != true) { closeConnection(); connection = openConnection() }
                    publish(MonitorPhase.CONNECTING, "Коммуникатор подключён. Идентификация HART…")
                    connection!!.identify() to connection!!.configuration()
                }
                mutableState.update { it.copy(identity = identity, configuration = config, lastHartSuccess = wallTime(), identificationResponding = true) }
                if (!automaticSession) {
                    publish(MonitorPhase.READY, "Прибор определён. Быстрый просмотр")
                    if (!waitForSession()) return@launch
                    publish(MonitorPhase.CONNECTING, "Проверка прибора перед новой сессией…")
                    ioGate.withLock { identity = connection!!.identify();config = connection!!.configuration() }
                    mutableState.update { it.copy(identity = identity, configuration = config, lastHartSuccess = wallTime(), identificationResponding = true) }
                }
                ioGate.withLock {
                connection!!.resetSession()
                sink.configuration(config)
                val now = wallTime()
                sink.begin(identity, now)
                mutableState.update { it.copy(identity = identity, configuration = config, startedAt = now) }
                startingSession=false
                try {
                    val initial = connection!!.discover()
                    synchronized(controls) {
                        sink.sample(initial)
                        publish(MonitorPhase.MONITORING, "Мониторинг", initial)
                    }
                } catch (e: CancellationException) { throw e
                } catch (e: Exception) {
                    if (e is HistoryLimitException) throw e
                    sink.gap(wallTime(), "Первоначальное чтение: ${e.message}")
                    if (e is IOException) closeConnection()
                    if (!recover(identity, if (e is IOException) transportLossReason else LossReason.HART)) return@launch
                }
                }
                var errors = 0
                while (isActive) {
                    if (paused) { delay(100); continue }
                    val start = monotonicMillis()
                    ioGate.withLock {
                    if(configuring) return@withLock
                    try {
                        val batch = connection!!.sample()
                        synchronized(controls) {
                            if (!paused) {
                                sink.sample(batch)
                                publish(MonitorPhase.MONITORING, "Ответ HART получен", batch)
                            }
                        }
                        errors = 0
                    } catch (e: CancellationException) { throw e
                    } catch (e: Exception) {
                        if (e is HistoryLimitException) throw e
                        sink.gap(wallTime(), "Ошибка обмена: ${e.message}")
                        publish(if (paused) MonitorPhase.PAUSED else MonitorPhase.MONITORING, "Нет текущих данных: ${e.message}")
                        errors++
                        if (!configuring && (e is IOException || errors >= 3)) {
                            if (e is IOException) closeConnection()
                            if (!recover(identity, if (e is IOException) transportLossReason else LossReason.HART)) return@launch
                            errors = 0
                        }
                    }
                    }
                    // Interval is start-to-start, with no catch-up; cancellation remains cooperative.
                    delay(maxOf(1, state.value.periodMillis - (monotonicMillis() - start)))
                }
            } catch (e: TimeoutCancellationException) {
                currentCoroutineContext().ensureActive()
                closeConnection()
                publish(MonitorPhase.ERROR, "Таймаут подключения коммуникатора")
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                if ((e !is HartTimeoutException && e !is HartResponseException) || connection?.isOpen != true) closeConnection()
                mutableState.update { it.copy(identificationResponding = false) }
                runCatching { sink.event(wallTime(), "Остановка: ${e.message}") }
                publish(if (state.value.startedAt != null) MonitorPhase.DECISION else MonitorPhase.ERROR, "Остановка: ${e.message}")
            } finally {
                val finishedJob = currentCoroutineContext()[Job]
                synchronized(controls) {
                    if (job === finishedJob) { startingSession=false; job=null }
                }
            }
        }
        job!!.start()
    }
    suspend fun retryIdentification() {
        val failedJob = synchronized(controls) {
            if (state.value.phase != MonitorPhase.ERROR || state.value.startedAt != null || configuring) return
            job
        }
        // ERROR can be observed just before the failing coroutine finishes its finally block.
        failedJob?.join()
        synchronized(controls) {
            if (state.value.phase == MonitorPhase.ERROR && state.value.startedAt == null && !configuring) start(false)
        }
    }
    private suspend fun openConnection(): MonitorConnection = connect().also(::observeConnection)
    private fun observeConnection(opened: MonitorConnection) {
        opened.onDisconnect {
            if (connection === opened) mutableState.update { it.copy(transportConnected = false, identificationResponding = false, latest = null) }
        }
    }
    /** No PV, current, history or measurement session is created by these presence checks. */
    private suspend fun waitForSession(): Boolean {
        var errors = 0
        while (currentCoroutineContext().isActive) {
            if (withTimeoutOrNull(1500) { sessionStarts.receive(); true } == true) return true
            ioGate.withLock {
                if (configuring) return@withLock
                try {
                    checkQuickConnection()
                    val found = connection!!.identify()
                    val changed = state.value.identity?.let(::passportKey) != passportKey(found)
                    val config = if (changed) connection!!.configuration() else state.value.configuration
                    mutableState.update { it.copy(identity = found, configuration = config, lastHartSuccess = wallTime(), identificationResponding = true) }
                    publish(MonitorPhase.READY, if (changed) "Подключён другой датчик. Конфигурация обновлена" else "Быстрый просмотр")
                    errors = 0
                } catch (e: CancellationException) { throw e
                } catch (e: Exception) {
                    mutableState.update { it.copy(identificationResponding = false) }
                    publish(MonitorPhase.READY, "Датчик не отвечает: ${e.message}")
                    errors++
                    if (e is IOException || errors >= 3) {
                        if (e is IOException) closeConnection()
                        if (!recover(state.value.identity!!, if (e is IOException) transportLossReason else LossReason.HART, quick = true)) return false
                        errors = 0
                    }
                }
            }
        }
        return false
    }
    private fun checkQuickConnection() { if (connection?.isOpen != true) throw IOException("Соединение закрыто") }
    private suspend fun recover(expected: DeviceIdentity, reason: LossReason, quick: Boolean = false): Boolean {
        val started = monotonicMillis()
        val deadline = started + 60_000
        mutableState.update { it.copy(recovery = RecoveryProgress(reason, started, deadline, started)) }
        sink.gap(wallTime(), "Связь потеряна: $reason. Восстановление, максимум 60 секунд")
        publish(MonitorPhase.RECOVERING, "Связь потеряна. Восстановление…")
        fun progress(attempt: Int? = null, status: String? = null) {
            mutableState.update { state -> state.copy(recovery = state.recovery?.let {
                it.copy(nowMillis = monotonicMillis(), attempt = attempt ?: it.attempt, lastAttempt = status ?: it.lastAttempt)
            }) }
        }
        try {
            return withTimeout((deadline - monotonicMillis()).coerceAtLeast(1)) {
                val ticker = launch { while (isActive) { progress(); delay(1000) } }
                try {
                    var attempt = 0
                    while (true) {
                        try {
                            attempt++
                            progress(attempt, "Подключение / идентификация")
                            sink.event(wallTime(), "Попытка восстановления $attempt")
                            if (connection?.isOpen != true) { closeConnection(); connection = openConnection() }
                            val found = connection!!.identify()
                            if (!quick && (found.uniqueKey != expected.uniqueKey || found.manufacturerCode != expected.manufacturerCode)) {
                                sink.event(wallTime(), "Другой прибор: ${found.uniqueKey}. Данные не объединены")
                                closeConnection()
                                publish(MonitorPhase.DIFFERENT_DEVICE, "Обнаружен другой прибор ${found.uniqueKey}. Сохраните или завершите старую сессию.")
                                return@withTimeout false
                            }
                            if (quick) {
                                val config = if (passportKey(found) != state.value.identity?.let(::passportKey)) connection!!.configuration() else state.value.configuration
                                mutableState.update { it.copy(identity = found, configuration = config, lastHartSuccess = wallTime(), identificationResponding = true) }
                                publish(MonitorPhase.READY, "Связь восстановлена. Быстрый просмотр")
                                return@withTimeout true
                            }
                            progress(status = "ID проверен; чтение")
                            val batch = connection!!.sample()
                            sink.event(wallTime(), "Связь восстановлена; идентификатор проверен")
                            if (!paused) sink.sample(batch)
                            publish(if (paused) MonitorPhase.PAUSED else MonitorPhase.MONITORING, "Связь восстановлена", batch.takeUnless { paused })
                            return@withTimeout true
                        } catch (e: TimeoutCancellationException) {
                            currentCoroutineContext().ensureActive()
                            progress(status = "Таймаут подключения")
                            closeConnection()
                        } catch (e: CancellationException) { throw e
                        } catch (e: Exception) {
                            if (e is HistoryLimitException) throw e
                            progress(status = e.message ?: "Ошибка")
                            sink.event(wallTime(), "Восстановление: ${e.message}")
                            if (e is IOException) closeConnection()
                        }
                        delay(1500)
                    }
                    @Suppress("UNREACHABLE_CODE") false
                } finally { ticker.cancel() }
            }
        } catch (e: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            sink.event(wallTime(), "Связь не восстановлена за 60 секунд")
            if (!quick || connection?.isOpen != true) closeConnection()
            mutableState.update { it.copy(identificationResponding = false) }
            publish(if (quick) MonitorPhase.ERROR else MonitorPhase.DECISION, if (quick) "Связь не восстановлена за 60 секунд. Повторите опрос датчика" else "Не удалось восстановить связь с датчиком в течение 60 секунд. Что сделать с текущей сессией?")
            return false
        }
    }
    fun setPeriod(millis: Long) {
        require(millis in listOf(1000L, 2000L, 5000L, 10000L))
        mutableState.update { it.copy(periodMillis = millis) }
    }
    fun pause(value: Boolean) = synchronized(controls) {
        if (state.value.phase !in setOf(MonitorPhase.MONITORING, MonitorPhase.PAUSED)) return
        paused = value
        try { if (value) sink.gap(wallTime(), "Пауза") else sink.event(wallTime(), "Возобновление") }
        catch (e: Exception) {
            paused = true
            publish(MonitorPhase.DECISION, "Ошибка временного хранилища: ${e.message}")
            job?.cancel()
            closeConnection()
            return
        }
        publish(if (value) MonitorPhase.PAUSED else MonitorPhase.MONITORING, if (value) "Опрос приостановлен" else "Опрос возобновлён")
    }
    suspend fun acquireConfiguration():MonitorConnection? {
        check(!configuring && !startingSession && state.value.identity!=null) { "Дождитесь идентификации и завершения текущей операции" }
        configuring=true
        try { ioGate.lock() } catch(e:Throwable) { configuring=false;throw e }
        try {
        if(!resumeBlockedByConfiguration)wasPaused=paused
        paused=true
        if(state.value.startedAt!=null) sink.gap(wallTime(),"Опрос приостановлен для настройки диапазона")
        publish(if(state.value.startedAt!=null) MonitorPhase.PAUSED else MonitorPhase.READY,"Настройка диапазона; опрос приостановлен")
        } catch(e:Exception) {
            configuring=false;ioGate.unlock();publish(MonitorPhase.DECISION,"Ошибка временной истории: ${e.message}");throw e
        }
        return connection
    }
    fun releaseConfiguration(replacement:MonitorConnection?,verification:RangeVerification?,resume:Boolean,message:String,differentDevice:Boolean=false) {
        check(configuring)
        try {
        connection=replacement
        replacement?.let(::observeConnection)
        val same=!differentDevice && (verification?.identity?.let { passportKey(it)==state.value.identity?.let(::passportKey) } ?: true)
        if(verification!=null && same) {
            val current=state.value.configuration
            val updated=current.copy(parameters=current.parameters+verification.configuration.parameters,responses=current.responses.filterNot{old->verification.configuration.responses.any{it.command==old.command}}+verification.configuration.responses)
            mutableState.update { it.copy(configuration=updated, lastHartSuccess=verification.readAt) };sink.configuration(updated)
        }
        if(state.value.startedAt!=null) sink.event(wallTime(),message)
        paused=wasPaused || !resume || !same
        publish(when { !same->MonitorPhase.DIFFERENT_DEVICE;!resume->MonitorPhase.DECISION;state.value.startedAt==null->MonitorPhase.READY;paused->MonitorPhase.PAUSED;else->MonitorPhase.MONITORING },message)
        resumeBlockedByConfiguration=!resume || !same
        } finally { configuring=false;ioGate.unlock() }
    }
    suspend fun detachReadyConnection(): MonitorConnection? {
        check(state.value.startedAt == null && !startingSession) { "Сессия запускается или уже начата. Дождитесь её начала и завершите перед переходом в обход." }
        job?.cancelAndJoin();job = null
        val result = connection?.takeIf { it.isOpen };if (result == null) connection?.close();connection = null
        publish(MonitorPhase.IDLE, "Соединение передано режиму обхода")
        return result
    }
    suspend fun finishSession() {
        job?.cancelAndJoin(); job = null
        paused = false;startingSession=false
        while (sessionStarts.tryReceive().isSuccess) { /* no queued duplicate starts */ }
        if (connection?.isOpen != true) closeConnection()
        mutableState.update { it.copy(startedAt = null, latest = null, lastKnown = null, recovery = null) }
        publish(if (connection != null) MonitorPhase.READY else MonitorPhase.IDLE,
            if (connection != null) "Сессия завершена. Коммуникатор подключён" else "Сессия завершена. Подключите коммуникатор")
        if (connection != null) start(false)
    }
    suspend fun stop(reason: String = "Завершено пользователем") {
        runCatching { sink.event(wallTime(), "Остановка мониторинга: $reason") }
        job?.cancelAndJoin(); job = null
        closeConnection()
        publish(MonitorPhase.IDLE, "Сессия завершена")
    }
    private fun closeConnection() { connection?.close(); connection = null }
}
