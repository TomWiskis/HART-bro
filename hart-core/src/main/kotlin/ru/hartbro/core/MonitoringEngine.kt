package ru.hartbro.core

import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

interface MonitorConnection : AutoCloseable {
    suspend fun identify(): DeviceIdentity
    suspend fun discover(): MeasurementBatch
    suspend fun sample(): MeasurementBatch
}
enum class MonitorPhase { IDLE, CONNECTING, MONITORING, PAUSED, RECOVERING, DECISION, DIFFERENT_DEVICE, ERROR }
data class MonitorState(
    val phase: MonitorPhase = MonitorPhase.IDLE, val identity: DeviceIdentity? = null,
    val latest: MeasurementBatch? = null, val bluetoothConnected: Boolean = false,
    val message: String = "Сессия не начата", val periodMillis: Long = 2000,
    val startedAt: Instant? = null, val revision: Long = 0,
)
interface SessionSink {
    fun begin(identity: DeviceIdentity, at: Instant)
    fun sample(batch: MeasurementBatch)
    fun event(at: Instant, text: String)
}

/** One coroutine owns connection, queue, polling and recovery. No outstanding timer/request queue. */
class MonitoringEngine(
    private val scope: CoroutineScope, private val connect: suspend () -> MonitorConnection,
    private val sink: SessionSink, private val wallTime: () -> Instant = Instant::now,
    private val monotonicMillis: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private val mutableState = MutableStateFlow(MonitorState())
    val state = mutableState.asStateFlow()
    private var job: Job? = null
    private val controls = Any()
    @Volatile private var connection: MonitorConnection? = null
    @Volatile private var paused = false
    private fun publish(phase: MonitorPhase, message: String, latest: MeasurementBatch? = null) {
        mutableState.update { it.copy(phase = phase, message = message, latest = latest,
            bluetoothConnected = connection != null, revision = it.revision + 1) }
    }
    fun start() {
        check(job == null) { "Сессия уже начата" }
        job = scope.launch {
            try {
                publish(MonitorPhase.CONNECTING, "Подключение Bluetooth и идентификация HART…")
                connection = connect()
                val identity = connection!!.identify()
                val now = wallTime()
                sink.begin(identity, now)
                mutableState.update { it.copy(identity = identity, startedAt = now) }
                try {
                    val initial = connection!!.discover()
                    synchronized(controls) {
                        sink.sample(initial)
                        publish(MonitorPhase.MONITORING, "Мониторинг", initial)
                    }
                } catch (e: CancellationException) { throw e
                } catch (e: Exception) {
                    if (e is HistoryLimitException) throw e
                    sink.event(wallTime(), "Первоначальное чтение: ${e.message}")
                    if (e is IOException) closeConnection()
                    if (!recover(identity)) return@launch
                }
                var errors = 0
                while (isActive) {
                    if (paused) { delay(100); continue }
                    val start = monotonicMillis()
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
                        sink.event(wallTime(), "Ошибка обмена: ${e.message}")
                        publish(if (paused) MonitorPhase.PAUSED else MonitorPhase.MONITORING, "Нет текущих данных: ${e.message}")
                        errors++
                        if (e is IOException || errors >= 3) {
                            if (e is IOException) closeConnection()
                            if (!recover(identity)) return@launch
                            errors = 0
                        }
                    }
                    // Interval is start-to-start, with no catch-up; cancellation remains cooperative.
                    delay(maxOf(1, state.value.periodMillis - (monotonicMillis() - start)))
                }
            } catch (e: TimeoutCancellationException) {
                currentCoroutineContext().ensureActive()
                closeConnection()
                publish(MonitorPhase.ERROR, "Таймаут подключения Bluetooth")
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                closeConnection()
                runCatching { sink.event(wallTime(), "Остановка: ${e.message}") }
                publish(if (state.value.identity != null) MonitorPhase.DECISION else MonitorPhase.ERROR, "Остановка: ${e.message}")
            }
        }
    }
    private suspend fun recover(expected: DeviceIdentity): Boolean {
        sink.event(wallTime(), "Связь потеряна. Восстановление, максимум 60 секунд")
        publish(MonitorPhase.RECOVERING, "Связь потеряна. Восстановление…")
        try {
            return withTimeout(60_000) {
                while (true) {
                    try {
                        sink.event(wallTime(), "Попытка восстановления")
                        if (connection == null) connection = connect()
                        val found = connection!!.identify()
                        if (found.uniqueKey != expected.uniqueKey || found.manufacturerCode != expected.manufacturerCode) {
                            sink.event(wallTime(), "Другой прибор: ${found.uniqueKey}. Данные не объединены")
                            closeConnection()
                            publish(MonitorPhase.DIFFERENT_DEVICE, "Обнаружен другой прибор ${found.uniqueKey}. Сохраните или завершите старую сессию.")
                            return@withTimeout false
                        }
                        val batch = connection!!.sample()
                        sink.event(wallTime(), "Связь восстановлена; идентификатор проверен")
                        if (!paused) sink.sample(batch)
                        publish(if (paused) MonitorPhase.PAUSED else MonitorPhase.MONITORING, "Связь восстановлена", batch.takeUnless { paused })
                        return@withTimeout true
                    } catch (e: TimeoutCancellationException) {
                        currentCoroutineContext().ensureActive()
                        closeConnection()
                    } catch (e: CancellationException) { throw e
                    } catch (e: Exception) {
                        if (e is HistoryLimitException) throw e
                        sink.event(wallTime(), "Восстановление: ${e.message}")
                        if (e is IOException) closeConnection()
                    }
                    delay(1500)
                }
                @Suppress("UNREACHABLE_CODE") false
            }
        } catch (e: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            sink.event(wallTime(), "Связь не восстановлена за 60 секунд")
            closeConnection()
            publish(MonitorPhase.DECISION, "Не удалось восстановить связь с датчиком в течение 60 секунд. Что сделать с текущей сессией?")
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
        try { sink.event(wallTime(), if (value) "Пауза" else "Возобновление") }
        catch (e: Exception) {
            paused = true
            publish(MonitorPhase.DECISION, "Ошибка временного хранилища: ${e.message}")
            job?.cancel()
            closeConnection()
            return
        }
        publish(if (value) MonitorPhase.PAUSED else MonitorPhase.MONITORING, if (value) "Опрос приостановлен" else "Опрос возобновлён")
    }
    suspend fun stop() {
        job?.cancelAndJoin(); job = null
        closeConnection()
        publish(MonitorPhase.IDLE, "Сессия завершена")
    }
    private fun closeConnection() { connection?.close(); connection = null }
}
