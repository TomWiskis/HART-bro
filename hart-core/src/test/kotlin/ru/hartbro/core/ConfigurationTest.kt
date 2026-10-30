package ru.hartbro.core

import java.io.*
import java.nio.ByteBuffer
import java.time.Instant
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

internal fun range030(lower: Float = -50.125f, upper: Float = 150.75f, unit: Int = 32): ByteArray =
    ByteBuffer.allocate(18).put(1).put(0).put(unit.toByte()).putFloat(upper).putFloat(lower).putFloat(0.125f).put(0).put(0).put(1).array()

class ConfigurationTest {
    @Test fun command15ReadsConfiguredRangeInProtocolOrderWithoutConvertingPrecision() {
        val raw = range030(); val params = ConfigurationDecoder.deviceInformation(raw, 7)
        val config = ConfigurationSnapshot(params)
        assertEquals(-50.125f, config.range!!.lower, 0f); assertEquals(150.75f, config.range!!.upper, 0f)
        assertEquals(32, config.range!!.unitCode); assertEquals("0.125", params.getValue("damping").value)
        assertEquals("−50,125…150,75 °C", config.range!!.label)
        assertTrue(params.values.all { it.command == 15 })
    }
    @Test fun revision5BaseLengthAndRevision6ExtensionAreBothAccepted() {
        assertFalse(ConfigurationDecoder.deviceInformation(range030().copyOf(17), 5).containsKey("analogChannelFlags"))
        assertEquals("1", ConfigurationDecoder.deviceInformation(range030(), 6)["analogChannelFlags"]!!.value)
    }
    @Test fun reversedConfiguredRangeIsPreservedForReverseActingDevices() {
        val config = ConfigurationSnapshot(ConfigurationDecoder.deviceInformation(range030(100f, -10f), 7))
        assertEquals(100f, config.range!!.lower, 0f); assertEquals(-10f, config.range!!.upper, 0f)
    }
    @Test fun truncatedNanAndUnknownRangeDoNotProducePlausibleDefaults() {
        for (raw in listOf(range030().copyOf(16), range030(Float.NaN), range030(unit = 250)))
            assertThrows(IllegalArgumentException::class.java) { ConfigurationDecoder.deviceInformation(raw, 7) }
        assertNull(ConfigurationSnapshot().range)
    }
    @Test fun packedTagAndAsciiLongTagAreDecodedByTheirOwnProtocolFormats() {
        // Packed six-bit A=1 B=2 C=3 SPACE=32; independent fixed vector.
        assertEquals("ABC", ConfigurationDecoder.packedAscii(hex("04 20 E0")))
        val reply = hex("04 20 E0 82 08 20") + ByteArray(12) { 0x82.toByte() } + byteArrayOf(1, 2, 126)
        assertEquals("ABC", ConfigurationDecoder.tagDescription(reply)["tag"]!!.value)
        assertEquals("METRAN-150", ConfigurationDecoder.longTag("METRAN-150".padEnd(32, '\u0000').toByteArray())["longTag"]!!.value)
        assertThrows(IllegalArgumentException::class.java) { ConfigurationDecoder.longTag(ByteArray(31)) }
    }
    @Test fun snapshotRoundTripPreservesRawBytesSourceCodesAndMissingCommandErrors() {
        val a = ConfigurationSnapshot(ConfigurationDecoder.deviceInformation(range030(), 7), listOf(ConfigResponse(15, range030().hex(), "2026-10-30T00:00:00Z", 64), ConfigResponse(20, "", "t", null, "unsupported")))
        val bytes = ByteArrayOutputStream().also { DataOutputStream(it).use(a::write) }.toByteArray()
        val b = DataInputStream(ByteArrayInputStream(bytes)).use(ConfigurationSnapshot::read)
        assertEquals(a, b)
    }
    @Test fun comparisonHighlightsKnownDifferencesButNotAbsentParametersOrMeasurements() {
        val a = ConfigurationSnapshot(mapOf("tag" to ConfigValue("A", 13), "damping" to ConfigValue("1.0", 15)))
        val b = ConfigurationSnapshot(mapOf("tag" to ConfigValue("B", 13)))
        val rows = compareConfigurations(a, b)
        assertTrue(rows.first { it.key == "tag" }.different)
        assertFalse(rows.first { it.key == "damping" }.different)
        assertFalse(rows.any { it.key == "PV" })
    }
    @Test fun unsupportedOptionalCommandsDoNotAbortSnapshotAndFragmented15ValidatesXor() = runTest {
        val channel = Channel<ByteArray>(Channel.UNLIMITED); val commands = mutableListOf<Int>()
        val transport = object : HartTransport {
            override suspend fun write(bytes: ByteArray) {
                val request = (HartDecoder().accept(bytes).single() as DecodeEvent.Frame).value
                commands += request.command
                val payload = if (request.command == 15) byteArrayOf(0, 0) + range030() else byteArrayOf(64, 0)
                val body = byteArrayOf(0x86.toByte()) + request.address + byteArrayOf(request.command.toByte(), payload.size.toByte()) + payload
                val response = ByteArray(4) { 0xFF.toByte() } + body + HartCodec.checksum(body)
                response.forEach { channel.send(byteArrayOf(it)) }
            }
            override suspend fun receive() = channel.receive()
            override fun discardBufferedInput() = emptyList<ByteArray>()
            override fun close() {}
        }
        val config = readConfiguration(HartClient(transport, readAttempts = 1), metran02)
        assertEquals(listOf(13, 15, 20, 48, 50), commands)
        assertNotNull(config.range); assertEquals(4, config.responses.count { it.error != null })
        assertFalse(commands.any { it == 38 }); assertEquals(range030().hex(), config.responses.first { it.command == 15 }.rawHex)
    }
}
