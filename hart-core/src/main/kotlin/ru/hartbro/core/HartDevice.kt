package ru.hartbro.core

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant

data class DeviceIdentity(
    val hartRevision: Int,
    val deviceType: Int,
    val deviceId: Int,
    val manufacturerCode: Int?,
    val minimumRequestPreambles: Int,
    val softwareRevision: Int,
    val raw: ByteArray,
) {
    val address: HartAddress.Long get() = HartAddress.Long(deviceType, deviceId)
    val uniqueKey: String get() = "%04X-%06X".format(deviceType, deviceId)

    companion object {
        fun parse(data: ByteArray): DeviceIdentity {
            require(data.size >= 12) { "Команда 0: требуется не менее 12 байтов, получено ${data.size}" }
            require(data[0].u8() == 254) { "Команда 0: неверный признак идентификации (ожидается FE)" }
            val revision = data[4].u8()
            require(revision in 5..7) { "HART $revision пока не поддержан (реализованы ревизии 5–7)" }
            // Rev. 5 uses the legacy manufacturer byte; rev. 6/7 use expanded device type.
            val manufacturer = when {
                revision == 5 -> data[1].u8() and 0x3F
                data.size >= 19 -> (data[17].u8() shl 8) or data[18].u8()
                else -> null
            }
            return DeviceIdentity(
                revision, ((data[1].u8() and 0x3F) shl 8) or data[2].u8(),
                (data[9].u8() shl 16) or (data[10].u8() shl 8) or data[11].u8(),
                manufacturer, data[3].u8().coerceAtLeast(5), data[6].u8(), data.copyOf(),
            )
        }
    }
}

data class PrimaryVariable(val value: Float, val unitCode: Int, val receivedAt: Instant, val raw: ByteArray) {
    val unit: String get() = when (unitCode) {
        1 -> "inH₂O (68 °F)"; 2 -> "inHg (0 °C)"; 3 -> "ftH₂O (68 °F)"; 4 -> "mmH₂O (68 °F)"
        5 -> "mmHg (0 °C)"; 6 -> "psi"; 7 -> "bar"; 8 -> "mbar"; 9 -> "g/cm²"; 10 -> "kg/cm²"
        11 -> "Pa"; 12 -> "kPa"; 13 -> "torr"; 14 -> "atm"; 32 -> "°C"; 33 -> "°F"; 34 -> "°R"; 35 -> "K"
        else -> "Единица HART: $unitCode"
    }
    companion object {
        fun parse(data: ByteArray, receivedAt: Instant): PrimaryVariable {
            require(data.size == 5) { "Команда 1: ожидается 5 байтов, получено ${data.size}" }
            val value = ByteBuffer.wrap(data, 1, 4).order(ByteOrder.BIG_ENDIAN).float
            require(value.isFinite()) { "Прибор вернул недостоверную величину (NaN/Infinity)" }
            return PrimaryVariable(value, data[0].u8(), receivedAt, data.copyOf())
        }
    }
}

data class HartReply(val data: ByteArray, val deviceStatus: Int, val receivedAt: Instant)
class HartResponseException(val responseCode: Int, val deviceStatus: Int) : Exception(
    if (responseCode and 0x80 != 0) "HART: ошибка связи 0x%02X; статус 0x%02X".format(responseCode, deviceStatus)
    else "HART: код ответа %d; статус 0x%02X".format(responseCode, deviceStatus),
)
