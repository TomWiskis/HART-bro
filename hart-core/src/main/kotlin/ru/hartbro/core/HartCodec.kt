package ru.hartbro.core

/** No Android or modem-specific dependencies. Only normal, unexpanded HART frames in phase 1. */
sealed class HartAddress(val bytes: ByteArray) {
    class Short(pollingAddress: Int, primaryMaster: Boolean = true) : HartAddress(
        byteArrayOf((pollingAddress.also { require(it in 0..63) } or if (primaryMaster) 0x80 else 0).toByte()),
    )
    class Long(deviceType: Int, deviceId: Int, primaryMaster: Boolean = true) : HartAddress(
        byteArrayOf(
            (((deviceType.also { require(it in 0..0x3FFF) } shr 8) and 0x3F) or if (primaryMaster) 0x80 else 0).toByte(),
            deviceType.toByte(),
            (deviceId.also { require(it in 0..0xFFFFFF) } shr 16).toByte(),
            (deviceId shr 8).toByte(), deviceId.toByte(),
        ),
    )
}

enum class ReadCommand(val number: Int) { IDENTITY(0), PRIMARY_VARIABLE(1) }

data class HartFrame(
    val delimiter: Int,
    val address: ByteArray,
    val command: Int,
    /** Response payload includes response code + device status, unlike a request payload. */
    val payload: ByteArray,
    val raw: ByteArray,
) {
    val isResponse: Boolean get() = (delimiter and 7) == 6
    val isBurst: Boolean get() = (delimiter and 7) == 1
}

sealed interface DecodeEvent {
    data class Frame(val value: HartFrame) : DecodeEvent
    data class Rejected(val reason: String, val raw: ByteArray) : DecodeEvent
}

object HartCodec {
    /** No general-purpose command sender is exposed: production can send only reads 0 and 1. */
    fun request(address: HartAddress, command: ReadCommand, preambles: Int = 20): ByteArray {
        require(preambles in 5..255)
        val body = byteArrayOf(if (address is HartAddress.Long) 0x82.toByte() else 0x02) +
            address.bytes + byteArrayOf(command.number.toByte(), 0)
        return ByteArray(preambles) { 0xFF.toByte() } + body + checksum(body)
    }

    fun checksum(bytes: ByteArray): Byte = bytes.fold(0) { acc, b -> acc xor b.u8() }.toByte()
}

/** Streaming decoder; partial headers and payloads remain buffered between Bluetooth reads. */
class HartDecoder {
    private var buffer = byteArrayOf()
    val hasPartialFrame: Boolean get() = buffer.isNotEmpty()
    fun reset(): ByteArray = buffer.also { buffer = byteArrayOf() }

    fun accept(bytes: ByteArray): List<DecodeEvent> {
        val events = mutableListOf<DecodeEvent>()
        buffer += bytes
        if (buffer.size > 8192) {
            events += DecodeEvent.Rejected("Переполнение буфера HART", buffer.copyOfRange(0, 64))
            buffer = buffer.takeLast(1024).toByteArray()
        }
        while (buffer.isNotEmpty()) {
            val start = buffer.indexOfFirst { it.u8() == 255 }
            if (start < 0) {
                events += DecodeEvent.Rejected("Данные без преамбулы", buffer)
                buffer = byteArrayOf(); break
            }
            if (start > 0) {
                events += DecodeEvent.Rejected("Шум перед преамбулой", buffer.copyOfRange(0, start))
                buffer = buffer.copyOfRange(start, buffer.size)
            }
            val preambles = buffer.indexOfFirst { it.u8() != 255 }
            if (preambles < 0) break
            val delimiter = buffer[preambles].u8()
            if (preambles < 5 || delimiter !in setOf(0x02, 0x82, 0x06, 0x86, 0x01, 0x81)) {
                events += DecodeEvent.Rejected("Неподдерживаемый разделитель или короткая преамбула", buffer.copyOfRange(0, preambles + 1))
                buffer = buffer.copyOfRange(preambles + 1, buffer.size); continue
            }
            val addressSize = if (delimiter and 0x80 != 0) 5 else 1
            val headerSize = preambles + 1 + addressSize + 2
            if (buffer.size < headerSize) break
            val count = buffer[headerSize - 1].u8()
            val total = headerSize + count + 1
            if (buffer.size < total) break
            val raw = buffer.copyOfRange(0, total)
            if (HartCodec.checksum(raw.copyOfRange(preambles, total)) != 0.toByte()) {
                events += DecodeEvent.Rejected("Неверная контрольная сумма", raw)
                // Search again, including inside corrupt frames with an invalid byte count.
                buffer = buffer.copyOfRange(1, buffer.size); continue
            }
            buffer = buffer.copyOfRange(total, buffer.size)
            val response = delimiter and 7 in setOf(6, 1)
            if (response && count < 2) {
                events += DecodeEvent.Rejected("В ответе отсутствуют код ответа и статус", raw); continue
            }
            events += DecodeEvent.Frame(HartFrame(
                delimiter, raw.copyOfRange(preambles + 1, preambles + 1 + addressSize),
                raw[headerSize - 2].u8(), raw.copyOfRange(headerSize, headerSize + count), raw,
            ))
        }
        return events
    }
}

internal fun Byte.u8(): Int = toInt() and 255
fun ByteArray.hex(): String = joinToString(" ") { "%02X".format(it.u8()) }
