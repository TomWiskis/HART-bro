package ru.hartbro.core

import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test

/** Captured command 0 supplied by the user. Never used as live application data. */
internal val metranIdentity = hex(requireNotNull(
    HartCodecTest::class.java.getResourceAsStream("/metran-command0.hex"),
).bufferedReader().use { it.readText() })

/** Synthetic command 1: 12.5 bar, generated only for the test transport. Not a sensor capture. */
internal val metranSyntheticPv = hex("FF FF FF FF FF 86 99 72 5F CA 7F 01 07 00 00 07 41 48 00 00 8F")

internal fun randomChunks(bytes: ByteArray, seed: Int): List<ByteArray> {
    val random = Random(seed)
    val parts = mutableListOf<ByteArray>()
    var offset = 0
    while (offset < bytes.size) {
        val next = minOf(bytes.size, offset + random.nextInt(1, 10))
        parts += bytes.copyOfRange(offset, next)
        offset = next
    }
    return parts
}

/** Independent fixture builder for framing edge cases; golden vectors above are fixed captures. */
private fun fixtureFrame(delimiter: Int, address: ByteArray, expansion: ByteArray, command: Int, payload: ByteArray): ByteArray {
    val body = byteArrayOf(delimiter.toByte()) + address + expansion + byteArrayOf(command.toByte(), payload.size.toByte()) + payload
    var xor = 0
    body.forEach { xor = xor xor (it.toInt() and 255) }
    return hex("FF FF FF FF") + body + xor.toByte()
}

class MetranRegressionTest {
    private fun assertCapturedFrame(chunks: List<ByteArray>) {
        val decoder = HartDecoder()
        val events = chunks.flatMap(decoder::accept)
        assertFalse(events.any { it is DecodeEvent.Rejected })
        val frame = events.filterIsInstance<DecodeEvent.Frame>().single().value
        assertEquals(0x06, frame.delimiter)
        assertEquals(4, frame.preambleCount)
        assertNotNull(frame.preambleWarning)
        assertEquals(0, frame.command)
        assertArrayEquals(hex("80"), frame.address)
        assertEquals(24, frame.payload.size)
        assertArrayEquals(hex("00 00 FE 99 72 05 07 07 28 08 00 5F CA 7F 05 09 00 2C 00 00 99 00 99 01"), frame.payload)
        assertArrayEquals(metranIdentity, frame.raw)
        assertFalse(decoder.hasPartialFrame)
    }

    @Test fun realCaptureHasVerifiedLengthAndXor65() {
        assertEquals(33, metranIdentity.size)
        assertEquals(0x18, metranIdentity[7].u8())
        assertEquals(0x65, metranIdentity.last().u8())
        var checksum = 0
        for (i in 4 until metranIdentity.lastIndex) checksum = checksum xor metranIdentity[i].u8()
        assertEquals(0x65, checksum)
        assertCapturedFrame(listOf(metranIdentity))
    }

    @Test fun realCaptureArrivesOneByteAtATime() {
        assertCapturedFrame(metranIdentity.map { byteArrayOf(it) })
    }

    @Test fun realCaptureSurvivesEveryTwoPartSplitWithoutDroppingPrefix() {
        for (split in 1 until metranIdentity.size) {
            val decoder = HartDecoder()
            assertTrue("split $split", decoder.accept(metranIdentity.copyOfRange(0, split)).isEmpty())
            assertEquals(split, decoder.pendingByteCount)
            // Empty Bluetooth reads must not reset an in-progress packet either.
            assertTrue(decoder.accept(byteArrayOf()).isEmpty())
            assertEquals(split, decoder.pendingByteCount)
            val frame = (decoder.accept(metranIdentity.copyOfRange(split, metranIdentity.size)).single() as DecodeEvent.Frame).value
            assertArrayEquals(metranIdentity, frame.raw)
        }
    }

    @Test fun realCaptureSurvives250DeterministicRandomFragmentations() {
        repeat(250) { seed -> assertCapturedFrame(randomChunks(metranIdentity, seed)) }
    }

    @Test fun incompleteCaptureIsNeverAcceptedAndResetsOnlyExplicitly() {
        for (length in 1 until metranIdentity.size) {
            val decoder = HartDecoder()
            val prefix = metranIdentity.copyOf(length)
            assertTrue(decoder.accept(prefix).isEmpty())
            assertTrue(decoder.hasPartialFrame)
            assertArrayEquals(prefix, decoder.reset())
            assertEquals(0, decoder.pendingByteCount)
        }
    }

    @Test fun corruptChecksumAndCorruptPayloadAreRejectedThenResynchronize() {
        for (corrupt in listOf(
            metranIdentity.copyOf().also { it[it.lastIndex] = 0x64 },
            metranIdentity.copyOf().also { it[20] = (it[20].u8() xor 1).toByte() },
        )) {
            val events = HartDecoder().accept(corrupt + metranIdentity)
            assertTrue(events.any { it is DecodeEvent.Rejected && it.reason.contains("сумма") })
            assertArrayEquals(metranIdentity, events.filterIsInstance<DecodeEvent.Frame>().single().value.raw)
        }
    }

    @Test fun wrongByteCountIsNotAcceptedAndFollowingCaptureIsRecovered() {
        for (count in listOf(0x17, 0x19)) {
            val wrong = metranIdentity.copyOf().also { it[7] = count.toByte() }
            val events = HartDecoder().accept(wrong + metranIdentity)
            assertTrue(events.any { it is DecodeEvent.Rejected })
            assertArrayEquals(metranIdentity, events.filterIsInstance<DecodeEvent.Frame>().single().value.raw)
        }
    }

    @Test fun oversizedDeclaredLengthWaitsForTimeoutWithoutGuessingPayloadBoundaries() {
        val wrong = metranIdentity.copyOf().also { it[7] = 0xFF.toByte() }
        val decoder = HartDecoder()
        assertTrue(decoder.accept(wrong).isEmpty())
        assertEquals(wrong.size, decoder.pendingByteCount)
        assertArrayEquals(wrong, decoder.reset())
    }

    @Test fun concatenatedNoiseAndMultipleFramesRemainSeparate() {
        val bytes = hex("12 34") + metranIdentity + metranSyntheticPv + metranIdentity
        val decoder = HartDecoder()
        val events = randomChunks(bytes, 77).flatMap(decoder::accept)
        val frames = events.filterIsInstance<DecodeEvent.Frame>().map { it.value }
        assertEquals(listOf(0, 1, 0), frames.map { it.command })
        assertArrayEquals(metranIdentity, frames[0].raw)
        assertArrayEquals(metranSyntheticPv, frames[1].raw)
        assertArrayEquals(metranIdentity, frames[2].raw)
    }

    @Test fun shortLongAckBurstAndRequestDelimitersAreSupported() {
        for (delimiter in listOf(0x01, 0x02, 0x06, 0x81, 0x82, 0x86)) {
            val address = if (delimiter and 0x80 == 0) hex("80") else hex("99 72 5F CA 7F")
            val payload = if (delimiter and 7 == 2) byteArrayOf() else hex("00 00")
            val raw = fixtureFrame(delimiter, address, byteArrayOf(), 0, payload)
            val frame = (HartDecoder().accept(raw).single() as DecodeEvent.Frame).value
            assertArrayEquals(address, frame.address)
            assertArrayEquals(raw, frame.raw)
            assertEquals(delimiter and 7 == 6, frame.isResponse)
            assertEquals(delimiter and 7 == 1, frame.isBurst)
        }
    }

    @Test fun expansionByteCountsAreIncludedInHeaderLengthAndChecksum() {
        for (base in listOf(0x06, 0x86)) for (size in 1..3) {
            val address = if (base == 0x06) hex("80") else hex("99 72 5F CA 7F")
            val expansion = ByteArray(size) { (0x11 + it).toByte() }
            val raw = fixtureFrame(base or (size shl 5), address, expansion, 1, hex("00 00 07 41 48 00 00"))
            val decoder = HartDecoder()
            val events = raw.flatMap { decoder.accept(byteArrayOf(it)) }
            val frame = (events.single() as DecodeEvent.Frame).value
            assertEquals(1, frame.command)
            assertArrayEquals(expansion, frame.expansionBytes)
            assertArrayEquals(hex("00 00 07 41 48 00 00"), frame.payload)
            assertArrayEquals(raw, frame.raw)
        }
    }

    @Test fun nestedPreambleInsideIncompletePayloadDoesNotDiscardOuterPacket() {
        val raw = fixtureFrame(0x86, hex("99 72 5F CA 7F"), byteArrayOf(), 99, hex("00 00") + metranIdentity)
        val decoder = HartDecoder()
        val beforeChecksum = raw.dropLast(1).toByteArray()
        assertTrue(decoder.accept(beforeChecksum).isEmpty())
        assertEquals(beforeChecksum.size, decoder.pendingByteCount)
        assertArrayEquals(raw, (decoder.accept(byteArrayOf(raw.last())).single() as DecodeEvent.Frame).value.raw)
    }

    @Test fun fourPreamblesAreAcceptedFiveAreNormalAndThreeAreRejected() {
        val four = (HartDecoder().accept(metranIdentity).single() as DecodeEvent.Frame).value
        assertNotNull(four.preambleWarning)
        val five = (HartDecoder().accept(byteArrayOf(0xFF.toByte()) + metranIdentity).single() as DecodeEvent.Frame).value
        assertNull(five.preambleWarning)
        val events = HartDecoder().accept(metranIdentity.drop(1).toByteArray())
        assertFalse(events.any { it is DecodeEvent.Frame })
        assertTrue(events.any { it is DecodeEvent.Rejected && it.reason.contains("Короткая преамбула") })
    }

    @Test fun invalidDelimiterIsDistinctFromPreambleWarning() {
        val bad = metranIdentity.copyOf().also { it[4] = 0x07 }
        val events = HartDecoder().accept(bad)
        assertFalse(events.any { it is DecodeEvent.Frame })
        assertTrue(events.any { it is DecodeEvent.Rejected && it.reason.contains("разделитель 0x07") })
    }

    @Test fun metranIdentificationPreservesTypeManufacturerAndRevisionFields() {
        val frame = (HartDecoder().accept(metranIdentity).single() as DecodeEvent.Frame).value
        val identity = DeviceIdentity.parse(frame.payload.drop(2).toByteArray())
        assertEquals(7, identity.hartRevision)
        assertEquals(0x0099, identity.manufacturerCode)
        assertEquals("Метран", identity.manufacturerName)
        assertEquals(0x9972, identity.deviceType)
        assertEquals(0x5FCA7F, identity.deviceId)
        assertEquals("9972-5FCA7F", identity.uniqueKey)
        assertEquals(7, identity.deviceRevision)
        assertEquals(40, identity.softwareRevision)
        assertEquals(1, identity.hardwareRevision)
        assertEquals(0, identity.physicalSignalingCode)
        assertEquals(0, identity.extendedDeviceStatus)
        assertArrayEquals(hex("99 72 5F CA 7F"), identity.address.bytes)
        assertArrayEquals(hex("FF FF FF FF FF 82 99 72 5F CA 7F 01 00 82"), HartCodec.request(identity.address, ReadCommand.PRIMARY_VARIABLE, 5))
    }
}
