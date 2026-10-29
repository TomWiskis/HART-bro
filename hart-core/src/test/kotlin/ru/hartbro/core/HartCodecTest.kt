package ru.hartbro.core

import org.junit.Assert.*
import org.junit.Test

internal fun hex(value: String): ByteArray = value.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.map { it.toInt(16).toByte() }.toByteArray()
internal val goldenIdentity = hex("FF FF FF FF FF 06 80 00 0E 00 00 FE 26 AB 05 05 01 03 20 00 12 34 56 A9")
internal val goldenPv = hex("FF FF FF FF FF 86 A6 AB 12 34 56 01 07 00 00 20 41 48 00 00 D4")

class HartCodecTest {
    @Test fun shortRequestMatchesGoldenVector() {
        assertArrayEquals(hex("FF FF FF FF FF 02 80 00 00 82"), HartCodec.request(HartAddress.Short(0), ReadCommand.IDENTITY, 5))
    }
    @Test fun longRequestMatchesGoldenVector() {
        assertArrayEquals(hex("FF FF FF FF FF 82 A6 AB 12 34 56 01 00 FE"), HartCodec.request(HartAddress.Long(0x26AB, 0x123456), ReadCommand.PRIMARY_VARIABLE, 5))
    }
    @Test fun secondaryMasterDoesNotSetPrimaryBit() {
        assertEquals(0, HartCodec.request(HartAddress.Short(0, false), ReadCommand.IDENTITY, 5)[6].u8())
    }
    @Test fun invalidAddressesAndPreamblesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { HartAddress.Short(64) }
        assertThrows(IllegalArgumentException::class.java) { HartAddress.Short(-1) }
        assertThrows(IllegalArgumentException::class.java) { HartAddress.Long(0x10000, 1) }
        assertThrows(IllegalArgumentException::class.java) { HartAddress.Long(1, 0x1000000) }
        assertThrows(IllegalArgumentException::class.java) { HartCodec.request(HartAddress.Short(0), ReadCommand.IDENTITY, 4) }
    }
    @Test fun goldenResponseDecodesStatusAddressAndPayload() {
        val frame = (HartDecoder().accept(goldenPv).single() as DecodeEvent.Frame).value
        assertTrue(frame.isResponse)
        assertArrayEquals(hex("A6 AB 12 34 56"), frame.address)
        assertEquals(1, frame.command)
        assertArrayEquals(hex("00 00 20 41 48 00 00"), frame.payload)
    }
    @Test fun everyFragmentBoundaryPreservesFrame() {
        for (split in 1 until goldenIdentity.size) {
            val decoder = HartDecoder()
            assertTrue("split $split", decoder.accept(goldenIdentity.copyOfRange(0, split)).isEmpty())
            val event = decoder.accept(goldenIdentity.copyOfRange(split, goldenIdentity.size)).single()
            assertTrue(event is DecodeEvent.Frame)
            assertFalse(decoder.hasPartialFrame)
        }
    }
    @Test fun byteAtATimeAndConsecutiveFrames() {
        val decoder = HartDecoder()
        val events = (goldenIdentity + goldenPv).flatMap { decoder.accept(byteArrayOf(it)) }
        assertEquals(2, events.filterIsInstance<DecodeEvent.Frame>().size)
    }
    @Test fun truncatedFrameRemainsPendingUntilReset() {
        val decoder = HartDecoder()
        val partial = goldenPv.dropLast(1).toByteArray()
        assertTrue(decoder.accept(partial).isEmpty())
        assertTrue(decoder.hasPartialFrame)
        assertArrayEquals(partial, decoder.reset())
        assertFalse(decoder.hasPartialFrame)
    }
    @Test fun badChecksumIsRejectedAndFollowingValidFrameRecovered() {
        val corrupt = goldenPv.copyOf().also { it[it.lastIndex] = 0 }
        val events = HartDecoder().accept(corrupt + goldenPv)
        assertTrue(events.any { it is DecodeEvent.Rejected && it.reason.contains("сумма") })
        assertEquals(1, events.filterIsInstance<DecodeEvent.Frame>().size)
    }
    @Test fun noiseIsReportedAndValidFrameRecovered() {
        val events = HartDecoder().accept(hex("12 34 56") + goldenIdentity)
        assertTrue(events.first() is DecodeEvent.Rejected)
        assertTrue(events.last() is DecodeEvent.Frame)
    }
    @Test fun responseWithoutStatusIsRejected() {
        val malformed = hex("FF FF FF FF FF 06 80 00 01 00 87")
        assertTrue(HartDecoder().accept(malformed).single() is DecodeEvent.Rejected)
    }
    @Test fun expandedHeaderRemainsBufferedUntilComplete() {
        val decoder = HartDecoder()
        assertTrue(decoder.accept(hex("FF FF FF FF FF A6")).isEmpty())
        assertEquals(6, decoder.pendingByteCount)
    }
    @Test fun excessiveUnframedInputIsBounded() {
        val events = HartDecoder().accept(ByteArray(9000) { 0xFF.toByte() } + goldenPv)
        assertTrue(events.any { it is DecodeEvent.Rejected })
        assertEquals(1, events.filterIsInstance<DecodeEvent.Frame>().size)
    }
}
