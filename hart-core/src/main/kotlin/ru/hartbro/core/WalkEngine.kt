package ru.hartbro.core

import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class WalkPhase { STOPPED, CONNECTING, WAITING, IDENTIFYING, READING, RECEIVED, RECOVERING, DECISION, INTERRUPTED }
data class WalkState(val phase: WalkPhase = WalkPhase.STOPPED, val transportConnected: Boolean = false,
                     val message: String = "Обход не начат", val book: WalkBook? = null, val lastReading: PassportReading? = null,
                     val lastRowId: String? = null, val recovery: RecoveryProgress? = null, val intervalMillis: Long = 1000)

/** Separate read-only actor. Missing HART probes are expected; only transport I/O loss uses the 60-second reconnect budget. */
class WalkEngine(private val scope: CoroutineScope, private val connect: suspend () -> MonitorConnection,
                 val catalog: WalkCatalog = WalkCatalog(), private val changed: (WalkBook) -> Unit = {},
                 private val diagnostic: (String) -> Unit = {}, private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
                 private val failurePauseMillis: Long = 400, private val transportLossReason: LossReason = LossReason.BLUETOOTH) {
    private val mutable = MutableStateFlow(WalkState(book = catalog.snapshot()))
    val state = mutable.asStateFlow()
    @Volatile private var connection: MonitorConnection? = null
    private var job: Job? = null
    private val ioGate=Mutex()
    @Volatile private var configuring=false
    @Volatile private var configurationPaused=false
    @Volatile private var forced = false
    init { require(failurePauseMillis in 300..500) }
    private var reappeared = catalog.snapshot().rows.isNotEmpty()
    private var currentKey: String? = null
    private var pendingId: String? = null
    private var confirmedRowForPresence: String? = null
    private var lastReadMillis = Long.MIN_VALUE
    private fun publish(phase: WalkPhase, message: String) { mutable.update { it.copy(phase = phase, message = message,
        transportConnected = connection?.isOpen == true, book = catalog.snapshot(), recovery = if (phase == WalkPhase.RECOVERING) it.recovery else null) } }
    private fun watchConnection() {
        val opened=connection ?: return
        opened.onDisconnect { if(connection===opened)mutable.update{it.copy(transportConnected=false)} }
    }
    fun start(initial: MonitorConnection? = null) {
        check(job == null)
        connection = initial
        job = scope.launch {
            try {
                if (connection == null) { publish(WalkPhase.CONNECTING, "Подключение коммуникатора…");connection = connect() }
                watchConnection();connection!!.prepareWalk()
                publish(WalkPhase.WAITING, "Ожидание следующего датчика")
                val walkStarted=clock();diagnostic("PERF WALK START ms=$walkStarted")
                var missing = 0
                while (isActive) {
                    if(configurationPaused) { delay(100);continue }
                    var failed=false
                    ioGate.withLock {
                    if(configuring) return@withLock
                    try {
                        publish(WalkPhase.IDENTIFYING, "Идентификация HART…")
                        val discoveryStarted=clock()
                        val identity = connection!!.identify();val key = passportKey(identity)
                        diagnostic("PERF DISCOVERY OK durationMs=${clock()-discoveryStarted}; key=$key")
                        missing = 0
                        if (key != currentKey || forced || clock() - lastReadMillis >= 30_000) {
                            if(currentKey!=null && key!=currentKey)reappeared=true
                            if (key != currentKey) { pendingId = null;confirmedRowForPresence=null }
                            if(currentKey!=key || reappeared)connection!!.preparePassportPresence()
                            currentKey = key;forced = false
                            publish(WalkPhase.READING, "Чтение паспортных данных…")
                            val configStarted=clock()
                            val reading = connection!!.passport(identity)
                            diagnostic("PERF CONFIG durationMs=${clock()-configStarted}; errors=${reading.errors.keys}")
                            val rowsBefore=catalog.snapshot().rows.size
                            pendingId?.let { catalog.takeResolvedRow(it) }?.let { confirmedRowForPresence=it;pendingId=null }
                            val result = try { confirmedRowForPresence?.let { catalog.refreshConfirmed(it,reading) } ?: catalog.ingest(reading, pendingId,reappeared) } catch (e: Exception) {
                                close();publish(WalkPhase.DECISION, "Ведомость сохранена в памяти: ${e.message}");return@launch
                            };pendingId = result.pendingId;reappeared=false
                            diagnostic("PERF ROWS added=${catalog.snapshot().rows.size-rowsBefore}; total=${catalog.snapshot().rows.size}; duplicateDecision=${result.pendingId!=null}")
                            if(result.pendingId!=null) confirmedRowForPresence=null
                            lastReadMillis = clock()
                            try { changed(catalog.snapshot()) } catch (e: Exception) {
                                close();publish(WalkPhase.DECISION, "Ошибка черновика: ${e.message}. Строки сохранены в памяти; экспортируйте ведомость.");return@launch
                            }
                            mutable.update { it.copy(lastReading = reading, lastRowId = result.rowId, book = catalog.snapshot()) }
                            diagnostic("Прибор ${reading.key}; строка=${result.rowId}; проверка ID=${result.pendingId}; ошибок полей=${reading.errors.size}")
                            publish(WalkPhase.RECEIVED, if (result.pendingId == null) "Данные получены · приборов ${catalog.snapshot().rows.size}" else "Совпадение идентификатора: требуется проверка строки")
                            if (reading.transportLost || connection?.isOpen != true) {
                                close();if (!recover()) return@launch
                            }
                        } else publish(WalkPhase.RECEIVED, "Прибор определён · готов к следующему")
                    } catch (e: CancellationException) { throw e
                    } catch (e: IOException) {
                        failed=true;diagnostic("Transport I/O: ${e.message}");close();if (!recover()) return@launch
                    } catch (e: Exception) {
                        failed=true;missing++
                        diagnostic("PERF DISCOVERY FAILED; ${e.javaClass.simpleName}")
                        if (missing >= 2) { reappeared=true;currentKey = null;pendingId = null;confirmedRowForPresence=null }
                        diagnostic("HART: ожидание датчика; ${e.message}")
                        if (connection?.isOpen != true) { close();if (!recover()) return@launch }
                        else publish(WalkPhase.WAITING, "Ожидание следующего датчика")
                    }
                    }
                    delay(if(failed) failurePauseMillis else state.value.intervalMillis)
                }
            } catch (e: TimeoutCancellationException) {
                currentCoroutineContext().ensureActive();close();publish(WalkPhase.DECISION, "Таймаут транспорта. Подключитесь повторно; строки сохранены.")
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                diagnostic("Обход остановлен: ${e.message}");close();publish(WalkPhase.DECISION, "Подключение не выполнено: ${e.message}. Полученные строки сохранены.")
            }
        }
    }
    private suspend fun recover(): Boolean {
        reappeared=true;currentKey = null;pendingId = null;confirmedRowForPresence=null
        val started = clock();val deadline = started + 60_000
        mutable.update { it.copy(recovery = RecoveryProgress(transportLossReason, started, deadline, started)) }
        publish(WalkPhase.RECOVERING, "Восстановление транспорта коммуникатора…")
        try {
            return withTimeout((deadline - clock()).coerceAtLeast(1)) {
                val ticker = launch { while (isActive) { mutable.update { it.copy(recovery = it.recovery?.copy(nowMillis = clock())) };delay(1000) } }
                try {
                    var attempts = 0
                    while (true) {
                        attempts++;mutable.update { it.copy(recovery = it.recovery?.copy(attempt = attempts, nowMillis = clock(), lastAttempt = "Подключение коммуникатора")) }
                        diagnostic("Восстановление транспорта обхода: попытка $attempts")
                        try {
                            connection = connect();watchConnection();connection!!.prepareWalk()
                            publish(WalkPhase.WAITING, "Соединение восстановлено. Ожидание следующего датчика");return@withTimeout true
                        } catch (e: TimeoutCancellationException) { currentCoroutineContext().ensureActive();close()
                        } catch (e: CancellationException) { throw e
                        } catch (e: Exception) { close();diagnostic("Восстановление транспорта: ${e.message}") }
                        delay(1500)
                    }
                    @Suppress("UNREACHABLE_CODE") false
                } finally { ticker.cancel() }
            }
        } catch (e: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive();close();publish(WalkPhase.DECISION, "транспорта не восстановлен за 60 секунд. Можно подключиться повторно или завершить обход.")
            return false
        }
    }
    suspend fun acquireConfiguration():MonitorConnection? {
        check(!configuring && state.value.phase !in setOf(WalkPhase.CONNECTING,WalkPhase.RECOVERING,WalkPhase.STOPPED)) { "Дождитесь завершения подключения" }
        configuring=true
        try { ioGate.lock() } catch(e:Throwable) { configuring=false;throw e }
        publish(WalkPhase.INTERRUPTED,"Опрос приостановлен для настройки диапазона")
        return connection
    }
    fun releaseConfiguration(replacement:MonitorConnection?,resume:Boolean,message:String) {
        check(configuring);connection=replacement;currentKey=null;pendingId=null;confirmedRowForPresence=null
        configurationPaused=!resume
        publish(if(resume)WalkPhase.WAITING else WalkPhase.INTERRUPTED,message)
        configuring=false;ioGate.unlock()
    }
    fun setInterval(millis: Long) { require(millis in listOf(500L, 1000L, 2000L));mutable.update { it.copy(intervalMillis = millis) } }
    fun repeatCurrent() { forced = true }
    fun documentChanged() { val book = catalog.snapshot();mutable.update { it.copy(book = book) };changed(book) }
    suspend fun stop(keepConnection: Boolean = false, finish: Boolean = false): MonitorConnection? {
        diagnostic("PERF WALK END ms=${clock()}; rows=${catalog.snapshot().rows.size}")
        job?.cancelAndJoin();job = null
        var draftFailure:Exception?=null
        if (finish) { catalog.finish();try { changed(catalog.snapshot()) } catch(e:Exception) { draftFailure=e } }
        val handed = connection?.takeIf { keepConnection && it.isOpen }
        if (handed == null) close() else connection = null
        publish(WalkPhase.STOPPED, "Обход завершён")
        draftFailure?.let { throw it }
        return handed
    }
    private fun close() { connection?.close();connection = null }
}
