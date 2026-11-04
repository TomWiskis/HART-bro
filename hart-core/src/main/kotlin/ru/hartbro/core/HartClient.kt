package ru.hartbro.core

import java.time.Instant
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Transport owns connection establishment and cancellation of blocking I/O. */
interface HartTransport : AutoCloseable {
    val isOpen: Boolean get() = true
    fun onDisconnect(listener: () -> Unit) {}
    suspend fun write(bytes: ByteArray)
    suspend fun receive(): ByteArray
    suspend fun receiveStamped():HartFragment = HartFragment(receive())
    fun discardBufferedInput(): List<ByteArray>
}

data class DiagnosticEntry(val time: Instant, val kind: String, val message: String, val bytes: ByteArray = byteArrayOf()) {
    fun text(): String = "$time [$kind] $message" + if (bytes.isNotEmpty()) "\n${bytes.hex()}" else ""
}

enum class HartFailureKind(val description:String) {
    NO_DATA("после запроса не получено данных"), INCOMPLETE_FRAME("неполный HART-кадр"), CHECKSUM("неверная контрольная сумма XOR"),
    LENGTH("некорректная длина кадра"), ADDRESS("ответ с другим адресом"), COMMAND("ответ на другую команду"),
    RESPONSE("ошибка HART, возвращённая прибором"), TRANSPORT("физическая ошибка транспорта"), NO_MATCH("не получен соответствующий ответ HART")
}
class HartProtocolException(val kind:HartFailureKind,message:String,cause:Throwable?=null):Exception(message,cause)
class HartTimeoutException(message: String, val malformedFrames: Boolean = false,val receivedRaw:ByteArray=byteArrayOf(),
    val failures:Set<HartFailureKind> = setOf(if(malformedFrames)HartFailureKind.INCOMPLETE_FRAME else HartFailureKind.NO_DATA)) : Exception(message)
data class HartTimeoutPolicy(val initialIdentification:Long=3500,val walkDiscovery:Long=1500,val ordinaryRead:Long=2500,
    val recoveryIdentification:Long=3500,val recoveryWindow:Long=60000) {
    init {require(listOf(initialIdentification,walkDiscovery,ordinaryRead,recoveryIdentification,recoveryWindow).all{it>0})}
}
class HartWriteIOException(cause:java.io.IOException,val receivedRaw:ByteArray):java.io.IOException(cause.message,cause)

data class HartTransactionMetric(val command: Int, val attempt: Int, val millis: Long, val outcome: String, val failures:Set<HartFailureKind> = emptySet())

data class HartExchange(val data: ByteArray, val responseCode: Int, val deviceStatus: Int, val receivedAt: Instant,
                        val txFrame: ByteArray, val rxFrame: ByteArray)

/** One mutex serializes individual reads and complete configuration transactions on this transport. */
class HartClient(
    private val transport: HartTransport,
    private val diagnostic: (DiagnosticEntry) -> Unit = {},
    private val timeoutMillis: Long = 2500,
    private val readAttempts: Int = 2,
    private val detailed: () -> Boolean = { true },
    private val observed: (HartTransactionMetric) -> Unit = {},
    private val beforeWrite: () -> Unit = {},
    private val clockMillis: () -> Long = { System.nanoTime()/1_000_000 },
    private val identificationTimeoutMillis:Long = timeoutMillis,
) {
    init { require(timeoutMillis > 0); require(readAttempts in 1..3) }
    private val mutex = Mutex()
    @Volatile private var preambles = 20
    private var readNeedsDrain = false

    suspend fun identify(responseTimeout:Long=identificationTimeoutMillis): Pair<DeviceIdentity, Int> {
        val reply = mutex.withLock{readLocked(HartAddress.Short(0),ReadCommand.IDENTITY,byteArrayOf(),responseTimeout)}
        val identity = parseIdentity(reply.data)
        preambles = maxOf(20, identity.minimumRequestPreambles)
        log("IDENTITY", "HART ${identity.hartRevision}; ${identity.manufacturerDescription}; тип 0x%04X; ID %06X; ПО %d; ревизия устройства %d"
            .format(identity.deviceType, identity.deviceId, identity.softwareRevision, identity.deviceRevision))
        HartDeviceCatalog.entry(identity)?.let { log("MODEL","${it.name}; Manufacturer=${identity.manufacturerDescription}; Rev=${identity.deviceRevision}; source=${it.sourceUrl}") }
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
            val identity=parseIdentity(read(HartAddress.Short(0),ReadCommand.IDENTITY).data)
            preambles=maxOf(20,identity.minimumRequestPreambles);return identity
        }
        /** Typed command 35 only. No retry path or arbitrary command-number API exists. */
        suspend fun writeRange(address: HartAddress.Long, command: WriteRange, beforeSend:(ByteArray)->Unit): HartExchange =
            exchange(address,35,HartCodec.writeRange(address,command,preambles),1,true,beforeSend)
        suspend fun writeSensor(address: HartAddress.Long, command: SensorWrite, beforeSend: (ByteArray) -> Unit): HartExchange =
            exchange(address,command.number,HartCodec.writeSensor(address,command,preambles),1,true,beforeSend)
    }
    private suspend fun readLocked(address:HartAddress,command:ReadCommand,data:ByteArray,responseTimeout:Long=if(command==ReadCommand.IDENTITY)identificationTimeoutMillis else timeoutMillis):HartReply {
        val reply=exchange(address,command.number,HartCodec.request(address,command,preambles,data),readAttempts,false,responseTimeout=responseTimeout)
        return HartReply(reply.data,reply.deviceStatus,reply.receivedAt)
    }
    private suspend fun exchange(address:HartAddress,command:Int,request:ByteArray,attempts:Int,retainCodes:Boolean,
                                 beforeSend:(ByteArray)->Unit = {},responseTimeout:Long=timeoutMillis):HartExchange {
        var malformedFrames = false
        var receivedRaw=byteArrayOf()
        val failures=linkedSetOf<HartFailureKind>()
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
            var receivedAny=false
            val attemptFailures=linkedSetOf<HartFailureKind>()
            val started = clockMillis()
            log("TX-FRAME", "Полный запрос: команда $command, попытка $attempt/$attempts", request)
            try {
                return withTimeout(responseTimeout) {
                    if(retainCodes)beforeWrite()
                    beforeSend(request.copyOf())
                    transport.write(request)
                    var result: HartExchange? = null
                    while (result == null) {
                        val bytes = transport.receive()
                        if(bytes.isNotEmpty())receivedAny=true
                        if(retainCodes)receivedRaw=(receivedRaw+bytes).takeLast(4096).toByteArray()
                        if(detailed())log("RX-CHUNK", "Фрагмент транспорта: ${bytes.size} байт", bytes)
                        for (event in decoder.accept(bytes)) {
                            when (event) {
                                is DecodeEvent.Rejected -> { malformedFrames = true;attemptFailures+=event.failure;log("HART-FAULT", "${event.failure}: ${event.reason}", event.raw) }
                                is DecodeEvent.Frame -> {
                                    val frame = event.value
                                    log("RX-FRAME", "Полный кадр: разделитель 0x%02X, команда %d, преамбула %d FF, адрес %s, расширение %d байт"
                                        .format(frame.delimiter, frame.command, frame.preambleCount, frame.address.hex(), frame.expansionBytes.size), frame.raw)
                                    frame.preambleWarning?.let { log("WARN", it) }
                                    if (!frame.isResponse) {log("IGNORE",if(frame.isBurst)"BURST: не ответ на этот запрос" else "Эхо запроса: не ответ прибора",frame.raw);continue}
                                    if(frame.command!=command || !frame.address.contentEquals(address.bytes)) {
                                        if(frame.command!=command)attemptFailures+=HartFailureKind.COMMAND
                                        if(!frame.address.contentEquals(address.bytes))attemptFailures+=HartFailureKind.ADDRESS
                                        log("HART-FAULT", "${attemptFailures.joinToString()}: ожидалась команда $command, адрес ${address.bytes.hex()}; получены ${frame.command}, ${frame.address.hex()}",frame.raw)
                                        log("IGNORE","Ответ другой команде/адресу",frame.raw)
                                        continue
                                    }
                                    val code = frame.payload[0].u8()
                                    val status = frame.payload[1].u8()
                                    log("HART", "Команда $command: код 0x%02X (%s); статус 0x%02X (%s); %.0f мс"
                                        .format(code, HartStatus.responseDescription(code), status, HartStatus.deviceDescription(status), (clockMillis() - started).toDouble()))
                                    observed(HartTransactionMetric(command,attempt,clockMillis()-started,if(code==0)"OK" else "RESPONSE_$code"))
                                    if (code != 0 && !retainCodes) {log("HART-FAULT","RESPONSE: код $code; ${HartStatus.responseDescription(code)}");throw HartResponseException(code, status)}
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
                if(!transport.isOpen) {
                    val failure=java.io.IOException("Транспорт закрыт во время выполнения команды $command",timeout)
                    observed(HartTransactionMetric(command,attempt,clockMillis()-started,"IO_ERROR",setOf(HartFailureKind.TRANSPORT)))
                    log("TRANSPORT-FAULT",failure.message!!)
                    if(retainCodes)throw HartWriteIOException(failure,receivedRaw) else throw failure
                }
                if(decoder.hasPartialFrame)attemptFailures+=HartFailureKind.INCOMPLETE_FRAME
                if(!receivedAny)attemptFailures+=HartFailureKind.NO_DATA
                if(attemptFailures.isEmpty())attemptFailures+=HartFailureKind.NO_MATCH
                failures+=attemptFailures
                observed(HartTransactionMetric(command,attempt,clockMillis()-started,"TIMEOUT",attemptFailures.toSet()))
                readNeedsDrain = true
                if (decoder.hasPartialFrame) { malformedFrames = true; log("FRAME", "Неполный кадр к окончанию таймаута", decoder.reset()) }
                log("TIMEOUT", "Команда $command, $responseTimeout мс: ${attemptFailures.joinToString {it.description}}")
            } catch(e:java.io.IOException) { observed(HartTransactionMetric(command,attempt,clockMillis()-started,"IO_ERROR",setOf(HartFailureKind.TRANSPORT)));log("TRANSPORT-FAULT",e.message ?: "Потеря транспорта");if(retainCodes)throw HartWriteIOException(e,receivedRaw) else throw e }
        }
        throw HartTimeoutException("Команда $command: ${failures.joinToString {it.description}}. Транспорт не закрывается из-за ошибки HART.", malformedFrames,receivedRaw,failures.toSet())
    }

    private fun parseIdentity(data:ByteArray):DeviceIdentity = try{DeviceIdentity.parse(data)}catch(e:IllegalArgumentException){
        log("HART-FAULT","LENGTH: некорректные данные команды 0: ${e.message}",data)
        throw HartProtocolException(HartFailureKind.LENGTH,"Невозможно разобрать идентификацию: ${e.message}",e)
    }
    private fun log(kind: String, message: String, bytes: ByteArray = byteArrayOf()) {
        diagnostic(DiagnosticEntry(Instant.now(), kind, message, bytes.copyOf()))
    }
}
