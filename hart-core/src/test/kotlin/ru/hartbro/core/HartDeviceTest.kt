package ru.hartbro.core

import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class HartDeviceTest {
    @Test fun legacyIdentityUsesManufacturerAndBuildsUniqueLongAddress() {
        val identity = DeviceIdentity.parse(hex("FE 26 AB 05 05 01 03 20 00 12 34 56"))
        assertEquals(5, identity.hartRevision)
        assertEquals(0x26, identity.manufacturerCode)
        assertEquals("26AB-123456", identity.uniqueKey)
        assertArrayEquals(hex("A6 AB 12 34 56"), identity.address.bytes)
    }
    @Test fun hart7UsesExtendedManufacturerAndFourteenBitType() {
        val identity = DeviceIdentity.parse(hex("FE 12 34 14 07 01 03 20 00 AB CD EF 05 04 00 01 00 00 26 00 26"))
        assertEquals(7, identity.hartRevision)
        assertEquals(0x1234, identity.deviceType)
        assertEquals(0x26, identity.manufacturerCode)
        assertEquals(20, identity.minimumRequestPreambles)
    }
    @Test fun missingExtendedManufacturerIsNotInferredFromDeviceType() {
        val identity = DeviceIdentity.parse(hex("FE 12 34 05 06 01 03 20 00 AB CD EF"))
        assertNull(identity.manufacturerCode)
    }
    @Test fun identityRequiresLengthMarkerAndSupportedRevision() {
        for (data in listOf(hex("FE 26"), hex("00 26 AB 05 05 01 03 20 00 12 34 56"), hex("FE 26 AB 05 04 01 03 20 00 12 34 56"))) {
            assertThrows(IllegalArgumentException::class.java) { DeviceIdentity.parse(data) }
        }
    }
    @Test fun ieee754BigEndianValueAndReceiveTimestampArePreserved() {
        val time = Instant.parse("2026-10-28T10:00:00Z")
        val pv = PrimaryVariable.parse(hex("20 41 48 00 00"), time)
        assertEquals(12.5f, pv.value, 0f)
        assertEquals("°C", pv.unit)
        assertEquals(time, pv.receivedAt)
    }
    @Test fun negativePressureIsDecodedDirectly() {
        val pv = PrimaryVariable.parse(hex("07 BF 80 00 00"), Instant.EPOCH)
        assertEquals(-1f, pv.value, 0f)
        assertEquals("bar", pv.unit)
    }
    @Test fun unknownUnitIsShownAsCode() {
        assertEquals("Единица HART: 253", PrimaryVariable.parse(hex("FD 00 00 00 00"), Instant.EPOCH).unit)
    }
    @Test fun nonfiniteAndWrongLengthMeasurementsAreRejected() {
        for (data in listOf(hex("20 7F C0 00 00"), hex("20 7F 80 00 00"), hex("20 41 48"), hex("20 41 48 00 00 00"))) {
            assertThrows(IllegalArgumentException::class.java) { PrimaryVariable.parse(data, Instant.EPOCH) }
        }
    }
}
