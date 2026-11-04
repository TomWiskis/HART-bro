package ru.hartbro.usb

import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.hartbro.core.HartTransport
import ru.hartbro.core.HartFragment

enum class RtsMode { LOW, HIGH, TRANSMIT_HIGH, TRANSMIT_LOW }
enum class SerialFlow { NONE, RTS_CTS }
/** No implicit UART profile: values must be supplied from documentation or a working configuration. */
data class UsbPortSettings(val baud: Int, val dataBits: Int, val parity: Int, val stopBits: Int,
    val flow: SerialFlow, val rts: RtsMode, val dtr: Boolean, val source: String) {
    init {
        require(baud in 300..3_000_000 && dataBits in 5..8 && parity in 0..4 && stopBits in 1..3)
        require(source.isNotBlank()) { "Укажите источник настроек порта" }
        require(flow == SerialFlow.NONE || rts in setOf(RtsMode.LOW, RtsMode.HIGH)) { "RTS/CTS несовместим с переключением RTS при передаче" }
    }
    val label get() = "$baud/$dataBits/parity=$parity/stop=$stopBits, flow=$flow, RTS=$rts, DTR=$dtr; источник: $source"
    fun wireMillis(size: Int): Long {
        val halfBits = 2 + dataBits*2 + (if(parity==0)0 else 2) + when(stopBits){2->4;3->3;else->2}
        return (size.toLong()*halfBits*500 + baud-1)/baud
    }
}
/** Narrow native port boundary also exercised with scripted ports. No HART parsing here. */
interface SerialPortIo : AutoCloseable {
    fun start(received: (ByteArray) -> Unit, failed: (Exception) -> Unit)
    fun write(bytes: ByteArray)
    fun rts(value: Boolean)
}
class UsbHartTransport(private val port: SerialPortIo, private val settings: UsbPortSettings,
    private val diagnostic: (String) -> Unit = {}, private val released: () -> Unit = {}) : HartTransport {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val incoming=Channel<HartFragment>(64)
    private val writeGate=Mutex()
    private val lock=Any()
    @Volatile private var closed=false
    @Volatile private var disconnected:(()->Unit)?=null
    override val isOpen get() = !closed
    override fun onDisconnect(listener:()->Unit) { disconnected=listener;if(closed)listener() }
    init {
        try {
            port.start({ bytes ->
                if(!closed && bytes.isNotEmpty() && incoming.trySend(HartFragment(bytes.copyOf())).isFailure)
                    fail(IOException("Переполнена очередь приёма USB; соединение закрыто"))
            },::fail)
        } catch(e:Exception) { close();throw e }
    }
    private fun fail(error:Exception) {
        if(closed)return // Native reader can report its cancellation after a deliberate close.
        diagnostic("USB READ/WRITE ERROR: ${error.javaClass.simpleName}: ${error.message}")
        incoming.close(IOException("Ошибка USB: ${error.message}",error));close()
    }
    override suspend fun write(bytes:ByteArray):Unit = writeGate.withLock {
        checkOpen()
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { close() }
            scope.launch {
                try {
                    checkOpen()
                    val switched=settings.rts in setOf(RtsMode.TRANSMIT_HIGH,RtsMode.TRANSMIT_LOW)
                    if(switched)port.rts(settings.rts==RtsMode.TRANSMIT_HIGH)
                    port.write(bytes)
                    if(switched) {
                        // USB completion precedes UART drain. Bound it by the explicitly configured line framing.
                        delay(settings.wireMillis(bytes.size)+5)
                        checkOpen();port.rts(settings.rts!=RtsMode.TRANSMIT_HIGH)
                    }
                    continuation.resumeWith(Result.success(Unit))
                } catch(e:Exception) { fail(e);continuation.resumeWith(Result.failure(e)) }
            }.invokeOnCompletion { cause -> if(cause!=null)continuation.cancel(cause) }
        }
    }
    private fun checkOpen() { if(closed)throw IOException("USB-соединение закрыто") }
    override suspend fun receive()=incoming.receive().bytes
    override suspend fun receiveStamped()=incoming.receive()
    override fun discardBufferedInput()=buildList { while(true)add(incoming.tryReceive().getOrNull()?.bytes ?: break) }
    fun detached() { diagnostic("USB DETACH: физическое отключение");close() }
    override fun close() {
        synchronized(lock) { if(closed)return;closed=true }
        diagnostic("USB CLOSE: освобождение порта")
        runCatching{port.close()}.onFailure{diagnostic("USB CLOSE ERROR: ${it.message}")}
        incoming.close(IOException("USB-соединение закрыто"));scope.cancel();released();disconnected?.invoke()
    }
}
