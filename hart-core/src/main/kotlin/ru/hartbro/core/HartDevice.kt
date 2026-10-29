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
    val deviceRevision: Int,
    val hardwareRevision: Int,
    val physicalSignalingCode: Int,
    val extendedDeviceStatus: Int?,
) {
    val address: HartAddress.Long get() = HartAddress.Long(deviceType, deviceId)
    val uniqueKey: String get() = "%04X-%06X".format(deviceType, deviceId)
    val manufacturerName: String? get() = manufacturerCode?.let(HartManufacturers::name)
    val manufacturerDescription: String get() = manufacturerCode?.let {
        "${manufacturerName ?: "Неизвестный производитель"} (0x%04X)".format(it)
    } ?: "не сообщён в доступной части ответа"

    companion object {
        fun parse(data: ByteArray): DeviceIdentity {
            require(data.size >= 12) { "Команда 0: требуется не менее 12 байтов, получено ${data.size}" }
            require(data[0].u8() == 254) { "Команда 0: неверный признак идентификации (ожидается FE)" }
            val revision = data[4].u8()
            require(revision in 5..7) { "HART $revision пока не поддержан (реализованы ревизии 5–7)" }
            // Rev. 5 uses the legacy manufacturer byte; rev. 6/7 use expanded device type.
            val manufacturer = when {
                revision == 5 -> data[1].u8()
                data.size >= 19 -> (data[17].u8() shl 8) or data[18].u8()
                else -> null
            }
            return DeviceIdentity(
                revision, (data[1].u8() shl 8) or data[2].u8(),
                (data[9].u8() shl 16) or (data[10].u8() shl 8) or data[11].u8(),
                manufacturer, data[3].u8().coerceAtLeast(5), data[6].u8(), data.copyOf(),
                data[5].u8(), data[7].u8() shr 3, data[7].u8() and 7,
                if (revision >= 6 && data.size >= 17) data[16].u8() else null,
            )
        }
    }
}

data class PrimaryVariable(val value: Float, val unitCode: Int, val receivedAt: Instant, val raw: ByteArray) {
    val unit: String get() = HartUnits.find(unitCode)?.symbol ?: "Единица HART: $unitCode"
    val unitName: String get() = HartUnits.find(unitCode)?.name ?: "неизвестная единица HART $unitCode"
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
    "HART: ${HartStatus.responseDescription(responseCode)} (код 0x%02X); статус 0x%02X: %s"
        .format(responseCode, deviceStatus, HartStatus.deviceDescription(deviceStatus)),
)
