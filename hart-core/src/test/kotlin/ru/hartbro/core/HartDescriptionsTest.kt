package ru.hartbro.core

import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class HartDescriptionsTest {
    @Test fun legacyManufacturerRetainsAllEightBits() {
        val identity = DeviceIdentity.parse(hex("FE 99 72 05 05 07 28 08 00 5F CA 7F"))
        assertEquals(0x99, identity.manufacturerCode)
        assertEquals("Метран", identity.manufacturerName)
        assertEquals(0x9972, identity.deviceType)
        assertArrayEquals(hex("99 72 5F CA 7F"), identity.address.bytes)
        assertArrayEquals(hex("19 72 5F CA 7F"), HartAddress.Long(identity.deviceType, identity.deviceId, false).bytes)
    }
    @Test fun unknownManufacturerKeepsCodeInsteadOfGuessingName() {
        val identity = DeviceIdentity.parse(hex("FE 12 34 05 07 01 03 20 00 AB CD EF 05 04 00 01 00 12 34"))
        assertNull(identity.manufacturerName)
        assertEquals("Неизвестный производитель (0x1234)", identity.manufacturerDescription)
    }
    @Test fun manufacturerIsReadOnlyWhenBothExtendedBytesExist() {
        val identity = DeviceIdentity.parse(hex("FE 12 34 05 07 01 03 20 00 AB CD EF 05 04 00 01 00 99"))
        assertNull(identity.manufacturerCode)
    }
    @Test fun pressureAndTemperatureHaveReadableNamesAndUnconvertedValues() {
        val cases = mapOf(7 to ("bar" to "бар"), 12 to ("kPa" to "килопаскаль"),
            32 to ("°C" to "градус Цельсия"), 35 to ("K" to "кельвин"), 237 to ("MPa" to "мегапаскаль"))
        cases.forEach { (code, description) ->
            val pv = PrimaryVariable.parse(byteArrayOf(code.toByte()) + hex("41 48 00 00"), Instant.EPOCH)
            assertEquals(description.first, pv.unit)
            assertEquals(description.second, pv.unitName)
            assertEquals(12.5f, pv.value, 0f)
        }
    }
    @Test fun unknownUnitDoesNotAcquireAnIncorrectPhysicalMeaning() {
        val pv = PrimaryVariable.parse(hex("FD 41 48 00 00"), Instant.EPOCH)
        assertEquals("Единица HART: 253", pv.unit)
        assertEquals("неизвестная единица HART 253", pv.unitName)
        assertNull(HartUnits.find(253))
    }
    @Test fun deviceStatusDecodesAllFlagsWithoutSendingClearCommands() {
        assertEquals(listOf("основная переменная вне пределов"), HartStatus.deviceFlags(1))
        assertEquals(listOf("неисправность прибора"), HartStatus.deviceFlags(0x80))
        assertEquals(listOf("конфигурация изменена", "холодный запуск", "токовый выход зафиксирован", "насыщение токового выхода"), HartStatus.deviceFlags(0x6C))
        assertEquals(8, HartStatus.deviceFlags(0xFF).size)
        assertTrue(HartStatus.deviceFlags(0).isEmpty())
        assertEquals("флаги состояния устройства не установлены", HartStatus.deviceDescription(0))
    }
    @Test fun responseCodeAndDeviceStatusRemainSeparateInErrors() {
        val error = HartResponseException(64, 0x80)
        assertTrue(error.message!!.contains("команда не реализована"))
        assertTrue(error.message!!.contains("неисправность прибора"))
        assertTrue(HartStatus.responseDescription(0).contains("успешный"))
        assertEquals("прибор занят", HartStatus.responseDescription(32))
        assertEquals("ошибка связи 0x88", HartStatus.responseDescription(0x88))
    }
}
