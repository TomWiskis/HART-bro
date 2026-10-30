package ru.hartbro.core

import java.time.Instant
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Transport owns connection establishment and cancellation of blocking I/O. */
interface HartTransport : AutoCloseable {
    suspend fun write(bytes: ByteArray)
    suspend fun receive(): ByteArray
    fun discardBufferedInput(): List<ByteArray>
}

data class DiagnosticEntry(val time: Instant, val kind: String, val message: String, val bytes: ByteArray = byteArrayOf()) {
    fun text(): String = "$time [$kind] $message" + if (bytes.isNotEmpty()) "\n${bytes.hex()}" else ""
}

class HartTimeoutException(message: String, val malformedFrames: Boolean = false) : Exception(message)

/** A single serialized request queue per connection. There is deliberately no write-command API. */
class HartClient(
    private val transport: HartTransport,
    private val diagnostic: (DiagnosticEntry) -> Unit = {},
    private val timeoutMillis: Long = 2500,
    private val readAttempts: Int = 2,
) {
    init { require(timeoutMillis > 0); require(readAttempts in 1..3) }
    private val mutex = Mutex()
    @Volatile private var preambles = 20

    suspend fun identify(): Pair<DeviceIdentity, Int> {
        val reply = read(HartAddress.Short(0), ReadCommand.IDENTITY)
        val identity = DeviceIdentity.parse(reply.data)
        preambles = maxOf(20, identity.minimumRequestPreambles)
        log("IDENTITY", "HART ${identity.hartRevision}; ${identity.manufacturerDescription}; тип 0x%04X; ID %06X; ПО %d; ревизия устройства %d"
            .format(identity.deviceType, identity.deviceId, identity.softwareRevision, identity.deviceRevision))
        return identity to reply.deviceStatus
    }

    suspend fun primaryVariable(identity: DeviceIdentity): Pair<PrimaryVariable, Int> {
        val reply = read(identity.address, ReadCommand.PRIMARY_VARIABLE)
        return PrimaryVariable.parse(reply.data, reply.receivedAt) to reply.deviceStatus
    }

    suspend fun read(address: HartAddress, command: ReadCommand, data: ByteArray = byteArrayOf()): HartReply = mutex.withLock {
        var malformedFrames = false
        for (attempt in 1..readAttempts) {
            transport.discardBufferedInput().forEach { log("STALE", "Накопленные данные до отправки запроса; не считаются его ответом", it) }
            val decoder = HartDecoder()
            val request = HartCodec.request(address, command, preambles, data)
            val started = System.nanoTime()
            log("TX-FRAME", "Полный запрос: команда ${command.number}, попытка $attempt/$readAttempts", request)
            try {
                return@withLock withTimeout(timeoutMillis) {
                    transport.write(request)
                    var result: HartReply? = null
                    while (result == null) {
                        val bytes = transport.receive()
                        log("RX-CHUNK", "Фрагмент Bluetooth: ${bytes.size} байт", bytes)
                        for (event in decoder.accept(bytes)) {
                            when (event) {
                                is DecodeEvent.Rejected -> { malformedFrames = true; log("FRAME", event.reason, event.raw) }
                                is DecodeEvent.Frame -> {
                                    val frame = event.value
                                    log("RX-FRAME", "Полный кадр: разделитель 0x%02X, команда %d, преамбула %d FF, адрес %s, расширение %d байт"
                                        .format(frame.delimiter, frame.command, frame.preambleCount, frame.address.hex(), frame.expansionBytes.size), frame.raw)
                                    frame.preambleWarning?.let { log("WARN", it) }
                                    if (!frame.isResponse || frame.command != command.number || !frame.address.contentEquals(address.bytes)) {
                                        log("IGNORE", "Эхо, burst или ответ другой команде/адресу", frame.raw)
                                        continue
                                    }
                                    val code = frame.payload[0].u8()
                                    val status = frame.payload[1].u8()
                                    log("HART", "Команда ${command.number}: код 0x%02X (%s); статус 0x%02X (%s); %.0f мс"
                                        .format(code, HartStatus.responseDescription(code), status, HartStatus.deviceDescription(status), (System.nanoTime() - started) / 1e6))
                                    if (code != 0) throw HartResponseException(code, status)
                                    result = HartReply(frame.payload.copyOfRange(2, frame.payload.size), status, Instant.now())
                                    break
                                }
                            }
                        }
                    }
                    result
                }
            } catch (timeout: TimeoutCancellationException) {
                // Do not swallow cancellation of the caller's enclosing timeout.
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                if (decoder.hasPartialFrame) { malformedFrames = true; log("FRAME", "Неполный кадр к окончанию таймаута", decoder.reset()) }
                log("TIMEOUT", "Нет корректного ответа на команду ${command.number} за $timeoutMillis мс")
            }
        }
        throw HartTimeoutException("HART-ответ отсутствует: команда ${command.number}. Проверьте питание петли, нагрузку, адрес 0 и совместимость VIATOR.", malformedFrames)
    }

    private fun log(kind: String, message: String, bytes: ByteArray = byteArrayOf()) {
        diagnostic(DiagnosticEntry(Instant.now(), kind, message, bytes.copyOf()))
    }
}
