package ru.hartbro.core

import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class WalkPhase { STOPPED, CONNECTING, WAITING, IDENTIFYING, READING, RECEIVED, RECOVERING, DECISION, INTERRUPTED }
data class WalkState(val phase: WalkPhase = WalkPhase.STOPPED, val bluetoothConnected: Boolean = false,
                     val message: String = "Обход не начат", val book: WalkBook? = null, val lastReading: PassportReading? = null,
                     val lastRowId: String? = null, val recovery: RecoveryProgress? = null, val intervalMillis: Long = 1000)

/** Separate read-only actor. Missing HART probes are expected; only RFCOMM I/O loss uses the 60-second reconnect budget. */
class WalkEngine(private val scope: CoroutineScope, private val connect: suspend () -> MonitorConnection,
                 val catalog: WalkCatalog = WalkCatalog(), private val changed: (WalkBook) -> Unit = {},
                 private val diagnostic: (String) -> Unit = {}, private val clock: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private val mutable = MutableStateFlow(WalkState(book = catalog.snapshot()))
    val state = mutable.asStateFlow()
    @Volatile private var connection: MonitorConnection? = null
    private var job: Job? = null
    @Volatile private var forced = false
    private var currentKey: String? = null
    private var pendingId: String? = null
    private var confirmedRowForPresence: String? = null
    private var lastReadMillis = Long.MIN_VALUE
    private fun publish(phase: WalkPhase, message: String) { mutable.update { it.copy(phase = phase, message = message,
        bluetoothConnected = connection?.isOpen == true, book = catalog.snapshot(), recovery = if (phase == WalkPhase.RECOVERING) it.recovery else null) } }
    fun start(initial: MonitorConnection? = null) {
        check(job == null)
        connection = initial
        job = scope.launch {
            try {
                if (connection == null) { publish(WalkPhase.CONNECTING, "Подключение VIATOR…");connection = connect() }
                connection!!.prepareWalk()
                publish(WalkPhase.WAITING, "Ожидание следующего датчика")
                var missing = 0
                while (isActive) {
                    try {
                        publish(WalkPhase.IDENTIFYING, "Идентификация HART…")
                        val identity = connection!!.identify();val key = passportKey(identity)
                        missing = 0
                        if (key != currentKey || forced || clock() - lastReadMillis >= 30_000) {
                            if (key != currentKey) { pendingId = null;confirmedRowForPresence=null }
                            currentKey = key;forced = false
                            publish(WalkPhase.READING, "Чтение паспортных данных…")
                            val reading = connection!!.passport(identity)
                            pendingId?.let { catalog.takeResolvedRow(it) }?.let { confirmedRowForPresence=it;pendingId=null }
                            val result = try { confirmedRowForPresence?.let { catalog.refreshConfirmed(it,reading) } ?: catalog.ingest(reading, pendingId) } catch (e: Exception) {
                                close();publish(WalkPhase.DECISION, "Ведомость сохранена в памяти: ${e.message}");return@launch
                            };pendingId = result.pendingId
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
                        diagnostic("Bluetooth I/O: ${e.message}");close();if (!recover()) return@launch
                    } catch (e: Exception) {
                        missing++
                        if (missing >= 2) { currentKey = null;pendingId = null;confirmedRowForPresence=null }
                        diagnostic("HART: ожидание датчика; ${e.message}")
                        if (connection?.isOpen != true) { close();if (!recover()) return@launch }
                        else publish(WalkPhase.WAITING, "Ожидание следующего датчика")
                    }
                    delay(state.value.intervalMillis)
                }
            } catch (e: TimeoutCancellationException) {
                currentCoroutineContext().ensureActive();close();publish(WalkPhase.DECISION, "Таймаут Bluetooth. Подключитесь повторно; строки сохранены.")
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                diagnostic("Обход остановлен: ${e.message}");close();publish(WalkPhase.DECISION, "Подключение не выполнено: ${e.message}. Полученные строки сохранены.")
            }
        }
    }
    private suspend fun recover(): Boolean {
        currentKey = null;pendingId = null;confirmedRowForPresence=null
        val started = clock();val deadline = started + 60_000
        mutable.update { it.copy(recovery = RecoveryProgress(LossReason.BLUETOOTH, started, deadline, started)) }
        publish(WalkPhase.RECOVERING, "Восстановление Bluetooth VIATOR…")
        try {
            return withTimeout((deadline - clock()).coerceAtLeast(1)) {
                val ticker = launch { while (isActive) { mutable.update { it.copy(recovery = it.recovery?.copy(nowMillis = clock())) };delay(1000) } }
                try {
                    var attempts = 0
                    while (true) {
                        attempts++;mutable.update { it.copy(recovery = it.recovery?.copy(attempt = attempts, nowMillis = clock(), lastAttempt = "Подключение VIATOR")) }
                        diagnostic("Восстановление Bluetooth обхода: попытка $attempts")
                        try {
                            connection = connect();connection!!.prepareWalk()
                            publish(WalkPhase.WAITING, "Bluetooth восстановлен. Ожидание следующего датчика");return@withTimeout true
                        } catch (e: TimeoutCancellationException) { currentCoroutineContext().ensureActive();close()
                        } catch (e: CancellationException) { throw e
                        } catch (e: Exception) { close();diagnostic("Восстановление Bluetooth: ${e.message}") }
                        delay(1500)
                    }
                    @Suppress("UNREACHABLE_CODE") false
                } finally { ticker.cancel() }
            }
        } catch (e: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive();close();publish(WalkPhase.DECISION, "Bluetooth не восстановлен за 60 секунд. Можно подключиться повторно или завершить обход.")
            return false
        }
    }
    fun setInterval(millis: Long) { require(millis in listOf(500L, 1000L, 2000L));mutable.update { it.copy(intervalMillis = millis) } }
    fun repeatCurrent() { forced = true }
    fun documentChanged() { val book = catalog.snapshot();mutable.update { it.copy(book = book) };changed(book) }
    suspend fun stop(keepConnection: Boolean = false, finish: Boolean = false): MonitorConnection? {
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
