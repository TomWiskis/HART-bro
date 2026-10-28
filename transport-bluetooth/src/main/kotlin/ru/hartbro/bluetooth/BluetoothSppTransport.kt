package ru.hartbro.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import java.io.IOException
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import ru.hartbro.core.HartTransport

/** Native secure RFCOMM/SPP. No BLE, reflection, channel-1 fallback or modem control bytes. */
class BluetoothSppTransport private constructor(private val socket: BluetoothSocket) : HartTransport {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val incoming = Channel<ByteArray>(capacity = 64)
    @Volatile private var closed = false

    init {
        scope.launch {
            try {
                val buffer = ByteArray(512)
                while (!closed) {
                    val size = socket.inputStream.read(buffer)
                    if (size < 0) throw IOException("VIATOR закрыл Bluetooth-соединение")
                    if (size > 0) incoming.send(buffer.copyOf(size))
                }
            } catch (e: Exception) {
                incoming.close(e)
            }
        }
    }

    override suspend fun write(bytes: ByteArray): Unit = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { close() }
        scope.launch {
            try {
                if (closed) throw IOException("Bluetooth-соединение закрыто")
                socket.outputStream.write(bytes)
                socket.outputStream.flush()
                continuation.resume(Unit)
            } catch (e: Exception) {
                continuation.resumeWithException(e)
            }
        }
    }

    override suspend fun receive(): ByteArray = incoming.receive()

    override fun discardBufferedInput(): List<ByteArray> = buildList {
        while (true) add(incoming.tryReceive().getOrNull() ?: break)
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { socket.close() } // Also releases blocking connect/read/write on Android.
        incoming.close(IOException("Bluetooth-соединение закрыто"))
        scope.cancel()
    }

    companion object {
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        /** Caller must have BLUETOOTH_CONNECT on Android 12+ and explicit user consent. */
        @SuppressLint("MissingPermission")
        suspend fun connect(device: BluetoothDevice): BluetoothSppTransport = withTimeout(15_000) {
            val socket = device.createRfcommSocketToServiceRecord(SPP_UUID)
            suspendCancellableCoroutine { continuation ->
                continuation.invokeOnCancellation { runCatching { socket.close() } }
                // BluetoothSocket.connect is blocking. Closing the socket is its cancellation API.
                Thread({
                    try {
                        socket.connect()
                        val transport = BluetoothSppTransport(socket)
                        continuation.resume(transport) { _, resource, _ -> resource.close() }
                    } catch (e: Exception) {
                        runCatching { socket.close() }
                        continuation.resumeWithException(e)
                    }
                }, "viator-rfcomm-connect").start()
            }
        }
    }
}
