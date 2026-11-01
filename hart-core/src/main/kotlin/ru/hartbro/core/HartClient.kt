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

class HartTimeoutException(message: String, val malformedFrames: Boolean = false,val receivedRaw:ByteArray=byteArrayOf()) : Exception(message)
class HartWriteIOException(cause:java.io.IOException,val receivedRaw:ByteArray):java.io.IOException(cause.message,cause)

data class HartExchange(val data: ByteArray, val responseCode: Int, val deviceStatus: Int, val receivedAt: Instant,
                        val txFrame: ByteArray, val rxFrame: ByteArray)

/** One mutex serializes individual reads and complete configuration transactions on this transport. */
class HartClient(
    private val transport: HartTransport,
    private val diagnostic: (DiagnosticEntry) -> Unit = {},
    private val timeoutMillis: Long = 2500,
    private val readAttempts: Int = 2,
) {
    init { require(timeoutMillis > 0); require(readAttempts in 1..3) }
    private val mutex = Mutex()
    @Volatile private var preambles = 20
    private var readNeedsDrain = false

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
        readLocked(address,command,data)
    }
    suspend fun <T> transaction(action: suspend Transaction.() -> T): T = mutex.withLock { Transaction().action() }
    inner class Transaction internal constructor() {
        suspend fun read(address: HartAddress,command: ReadCommand,data: ByteArray = byteArrayOf()) = readLocked(address,command,data)
        suspend fun identify(): DeviceIdentity {
            val identity=DeviceIdentity.parse(read(HartAddress.Short(0),ReadCommand.IDENTITY).data)
            preambles=maxOf(20,identity.minimumRequestPreambles);return identity
        }
        /** Typed command 35 only. No retry path or arbitrary command-number API exists. */
        suspend fun writeRange(address: HartAddress.Long, command: WriteRange, beforeSend:(ByteArray)->Unit): HartExchange =
            exchange(address,35,HartCodec.writeRange(address,command,preambles),1,true,beforeSend)
    }
    private suspend fun readLocked(address:HartAddress,command:ReadCommand,data:ByteArray):HartReply {
        val reply=exchange(address,command.number,HartCodec.request(address,command,preambles,data),readAttempts,false)
        return HartReply(reply.data,reply.deviceStatus,reply.receivedAt)
    }
    private suspend fun exchange(address:HartAddress,command:Int,request:ByteArray,attempts:Int,retainCodes:Boolean,
                                 beforeSend:(ByteArray)->Unit = {}):HartExchange {
        var malformedFrames = false
        var receivedRaw=byteArrayOf()
        for (attempt in 1..attempts) {
            if (!retainCodes && readNeedsDrain) {
                // A failed read may still be arriving. Require 200 ms of silence, bounded at 1 s.
                // HART has no transaction ID; a wholly delayed same-command reply cannot be distinguished.
                val quiet = kotlinx.coroutines.withTimeoutOrNull(1000) {
                    while (true) {
                        val stale = kotlinx.coroutines.withTimeoutOrNull(200) { transport.receive() } ?: break
                        log("STALE", "Поздний фрагмент завершённого чтения", stale)
                    }
                    true
                } ?: false
                if (!quiet) throw HartTimeoutException("HART-линия не освободилась после предыдущего ответа")
                readNeedsDrain = false
            }
            transport.discardBufferedInput().forEach { log("STALE", "Накопленные данные до отправки запроса; не считаются его ответом", it) }
            val decoder = HartDecoder()
            val started = System.nanoTime()
            log("TX-FRAME", "Полный запрос: команда $command, попытка $attempt/$attempts", request)
            try {
                return withTimeout(timeoutMillis) {
                    beforeSend(request.copyOf())
                    transport.write(request)
                    var result: HartExchange? = null
                    while (result == null) {
                        val bytes = transport.receive()
                        if(retainCodes)receivedRaw=(receivedRaw+bytes).takeLast(4096).toByteArray()
                        log("RX-CHUNK", "Фрагмент Bluetooth: ${bytes.size} байт", bytes)
                        for (event in decoder.accept(bytes)) {
                            when (event) {
                                is DecodeEvent.Rejected -> { malformedFrames = true; log("FRAME", event.reason, event.raw) }
                                is DecodeEvent.Frame -> {
                                    val frame = event.value
                                    log("RX-FRAME", "Полный кадр: разделитель 0x%02X, команда %d, преамбула %d FF, адрес %s, расширение %d байт"
                                        .format(frame.delimiter, frame.command, frame.preambleCount, frame.address.hex(), frame.expansionBytes.size), frame.raw)
                                    frame.preambleWarning?.let { log("WARN", it) }
                                    if (!frame.isResponse || frame.command != command || !frame.address.contentEquals(address.bytes)) {
                                        log("IGNORE", "Эхо, burst или ответ другой команде/адресу", frame.raw)
                                        continue
                                    }
                                    val code = frame.payload[0].u8()
                                    val status = frame.payload[1].u8()
                                    log("HART", "Команда $command: код 0x%02X (%s); статус 0x%02X (%s); %.0f мс"
                                        .format(code, HartStatus.responseDescription(code), status, HartStatus.deviceDescription(status), (System.nanoTime() - started) / 1e6))
                                    if (code != 0 && !retainCodes) throw HartResponseException(code, status)
                                    result = HartExchange(frame.payload.copyOfRange(2, frame.payload.size), code, status, Instant.now(),request.copyOf(),frame.raw.copyOf())
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
                if (!retainCodes) readNeedsDrain = true
                if (decoder.hasPartialFrame) { malformedFrames = true; log("FRAME", "Неполный кадр к окончанию таймаута", decoder.reset()) }
                log("TIMEOUT", "Нет корректного ответа на команду $command за $timeoutMillis мс")
            } catch(e:java.io.IOException) { if(retainCodes)throw HartWriteIOException(e,receivedRaw) else throw e }
        }
        throw HartTimeoutException("HART-ответ отсутствует: команда $command. Проверьте питание петли, нагрузку, адрес 0 и совместимость VIATOR.", malformedFrames,receivedRaw)
    }

    private fun log(kind: String, message: String, bytes: ByteArray = byteArrayOf()) {
        diagnostic(DiagnosticEntry(Instant.now(), kind, message, bytes.copyOf()))
    }
}
