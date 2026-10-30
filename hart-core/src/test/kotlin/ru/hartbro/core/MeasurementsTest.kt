package ru.hartbro.core

import java.time.Instant
import java.nio.ByteBuffer
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

private val stamp = Instant.parse("2026-10-29T12:00:00Z")
private fun reply(s: String, status: Int = 0) = HartReply(hex(s), status, stamp)
internal val metran02 = DeviceIdentity.parse(hex("FE 99 72 05 07 07 28 08 00 5F CA 7F 05 09 00 2C 00 00 99 00 99 01"))
internal fun sample02(at: Long = 0, value: Float = 1f, quality: Quality = Quality.GOOD) = MeasurementBatch(
    listOf(Measurement("PV", value, 12, quality, Instant.ofEpochMilli(at), 9)), 0, Instant.ofEpochMilli(at))

private class ReadFixture(private val responses: Map<Int, ByteArray>, private val responseCodes: Map<Int,Int> = emptyMap(), private val silent: Set<Int> = emptySet(), private val corrupt: Set<Int> = emptySet()) : HartTransport {
    val commands = mutableListOf<Int>()
    val requests = mutableListOf<ByteArray>()
    private val input = Channel<ByteArray>(Channel.UNLIMITED)
    override suspend fun write(bytes: ByteArray) {
        requests += bytes
        val request = (HartDecoder().accept(bytes).single() as DecodeEvent.Frame).value
        commands += request.command
        if (request.command in silent) return
        val data = responses[request.command]
        val code = responseCodes[request.command] ?: if (data == null) 64 else 0
        val payload = if (code != 0) byteArrayOf(code.toByte(), 0) else byteArrayOf(0, 0) + data!!
        val body = byteArrayOf(0x86.toByte()) + request.address + byteArrayOf(request.command.toByte(), payload.size.toByte()) + payload
        val frame = ByteArray(4) { 0xFF.toByte() } + body + HartCodec.checksum(body)
        if (request.command in corrupt) frame[frame.lastIndex] = (frame.last().toInt() xor 1).toByte()
        // Actual transport often returns one-to-four-byte fragments.
        frame.toList().chunked(3).forEach { input.send(it.toByteArray()) }
    }
    override suspend fun receive() = input.receive()
    override fun discardBufferedInput() = emptyList<ByteArray>()
    override fun close() { input.close() }
}

class MeasurementsTest {
    @Test fun realColdStartMetranFrameKeepsStatusAndIdentity() {
        val frame = (HartDecoder().accept(hex("FF FF FF FF 06 80 00 18 00 20 FE 99 72 05 07 07 28 08 00 5F CA 7F 05 09 00 2C 00 00 99 00 99 01 45")).single() as DecodeEvent.Frame).value
        assertEquals(0x20, frame.payload[1].u8()); assertEquals("9972-5FCA7F", DeviceIdentity.parse(frame.payload.drop(2).toByteArray()).uniqueKey)
    }
    @Test fun realMetranPvFrameWithFfChecksumAndFragments() {
        val data = hex("FF FF FF FF 86 99 72 5F CA 7F 01 07 00 00 0C BC 59 F8 6F FF")
        for (split in 1 until data.size) {
            val decoder = HartDecoder(); val events = decoder.accept(data.take(split).toByteArray()) + decoder.accept(data.drop(split).toByteArray())
            val frame = (events.single() as DecodeEvent.Frame).value
            val pv = MeasurementDecoder.primary(HartReply(frame.payload.drop(2).toByteArray(), 0, stamp)).values.single()
            assertEquals(-0.01330386f, pv.value, 0.00000001f); assertEquals(12, pv.unitCode); assertEquals(stamp, pv.receivedAt)
        }
    }
    @Test fun command9CarriesRequestedCodesLengthAndXor() {
        val bytes = HartCodec.request(metran02.address, ReadCommand.VARIABLES_WITH_STATUS, 5, hex("F6 F7 F8 F9"))
        assertArrayEquals(hex("FF FF FF FF FF 82 99 72 5F CA 7F 09 04 F6 F7 F8 F9 8E"), bytes)
    }
    @Test fun emptyOrInvalidCommand9ArgumentsAndOtherPayloadsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { HartCodec.request(metran02.address, ReadCommand.VARIABLES_WITH_STATUS) }
        assertThrows(IllegalArgumentException::class.java) { HartCodec.request(metran02.address, ReadCommand.VARIABLES_WITH_STATUS, data = hex("FA")) }
        assertThrows(IllegalArgumentException::class.java) { HartCodec.request(metran02.address, ReadCommand.ADDITIONAL_STATUS, data = byteArrayOf(0)) }
    }
    @Test fun productionCommandAllowlistContainsOnlyApprovedReads() { assertEquals(setOf(0,1,3,8,9,13,15,20,48,50), ReadCommand.entries.map { it.number }.toSet()) }
    @Test fun command3DecodesDifferentUnitsAndLoopCurrentSeparately() {
        val batch = MeasurementDecoder.dynamic(reply("41 80 00 00 0C 41 20 00 00 20 41 A0 00 00 07 3F 80 00 00 FA 7F A0 00 00"))
        assertEquals(16f, batch.loopCurrent!!, 0f); assertEquals(listOf(10f,20f,1f), batch.values.take(3).map { it.value })
        assertEquals(listOf(12,32,7,250), batch.values.map { it.unitCode }); assertEquals(Quality.ABSENT, batch.values[3].quality)
        assertEquals(Quality.UNVERIFIED, batch.values[0].quality)
    }
    @Test fun command3AcceptsOneToFourWholeVariablesOnly() {
        assertEquals(1, MeasurementDecoder.dynamic(reply("40 80 00 00 0C 3F 80 00 00")).values.size)
        for (size in listOf(0,4,8,10,23,25)) assertThrows(IllegalArgumentException::class.java) { MeasurementDecoder.dynamic(HartReply(ByteArray(size),0,stamp)) }
    }
    @Test fun command9RetainsStatusesAndTimestampWithoutGuessingSensorRoles() {
        val batch = MeasurementDecoder.withStatus(reply("02 F6 41 0C 3F 80 00 00 C0 F7 40 20 41 A0 00 00 40 00 00 00 20"), listOf(246,247),7)
        assertEquals(listOf("PV","SV"), batch.values.map { it.slot }); assertEquals(Quality.GOOD,batch.values[0].quality)
        assertEquals(Quality.UNCERTAIN,batch.values[1].quality); assertEquals(32L,batch.deviceTicks); assertEquals(2,batch.extendedStatus)
        assertFalse(batch.values[1].plottable)
    }
    @Test fun command9RejectsWrongEchoOrIncompleteResponse() {
        assertThrows(IllegalArgumentException::class.java) { MeasurementDecoder.withStatus(reply("00 F7 41 0C 3F 80 00 00 C0 00 00 00 00"),listOf(246),7) }
        assertThrows(IllegalArgumentException::class.java) { MeasurementDecoder.withStatus(reply("00 F6 41 0C 3F 80"),listOf(246),7) }
    }
    @Test fun nonfiniteFixedAndFailedValuesAreNeverPlotted() {
        assertFalse(MeasurementDecoder.primary(reply("0C 7F 80 00 00")).values[0].plottable)
        assertFalse(MeasurementDecoder.primary(reply("0C 3F 80 00 00",0x80)).values[0].plottable)
        assertFalse(MeasurementDecoder.withStatus(reply("00 01 41 0C 3F 80 00 00 80"),listOf(1),6).values[0].plottable)
    }
    @Test fun unknownUnitPreservesDigitalValueAndCode() {
        val value = MeasurementDecoder.primary(reply("DE 41 20 00 00")).values.single()
        assertEquals(10f,value.value,0f); assertEquals("HART 222",value.unit)
    }
    @Test fun auxiliaryResponsesHaveCheckedLengthsAndRawManufacturerStatus() {
        assertEquals(listOf(1,2,3,250),MeasurementDecoder.fourCodes(reply("01 02 03 FA")))
        assertArrayEquals(hex("80 01 02 03 04 05 06"),MeasurementDecoder.additionalStatus(reply("80 01 02 03 04 05 06")))
        assertThrows(IllegalArgumentException::class.java) { MeasurementDecoder.fourCodes(reply("01 02")) }
        assertThrows(IllegalArgumentException::class.java) { MeasurementDecoder.additionalStatus(reply("00 00")) }
    }
    @Test fun unsupported9And3FallbackOnceAndStayDisabled() = runTest {
        val transport = ReadFixture(mapOf(1 to hex("0C 3F 80 00 00")))
        val reader = MeasurementReader(HartClient(transport),metran02)
        reader.discover(); repeat(3) { assertEquals(1,reader.sample().values.single().command) }
        assertEquals(1,transport.commands.count { it==9 }); assertEquals(1,transport.commands.count { it==3 })
        assertEquals(1,transport.commands.count { it==48 }); assertEquals(4,transport.commands.count { it==1 })
    }
    @Test fun supported9UsesSingleRequestPerCycleAndExplicitDynamicCodes() = runTest {
        val records = (246..249).map { byteArrayOf(it.toByte(),0x41,12,0x3f,0x80.toByte(),0,0,0xc0.toByte()) }.fold(byteArrayOf()) { a,b -> a+b }
        val transport = ReadFixture(mapOf(1 to hex("0C 3F 80 00 00"),9 to (byteArrayOf(0)+records+ByteArray(4))))
        val reader = MeasurementReader(HartClient(transport),metran02); reader.discover()
        val before = transport.commands.size; assertEquals(4,reader.sample().values.size)
        assertEquals(before+1,transport.commands.size)
        val request = (HartDecoder().accept(transport.requests.last()).single() as DecodeEvent.Frame).value
        assertArrayEquals(hex("F6 F7 F8 F9"),request.payload)
    }
    @Test fun hart5NeverProbes6Or7OnlyCommands() = runTest {
        val transport = ReadFixture(mapOf(1 to hex("0C 3F 80 00 00")))
        val identity = DeviceIdentity.parse((HartDecoder().accept(goldenIdentity).single() as DecodeEvent.Frame).value.payload.drop(2).toByteArray())
        val reader = MeasurementReader(HartClient(transport),identity); reader.discover(); reader.sample()
        assertFalse(transport.commands.any { it==8 || it==9 })
    }

    @Test fun recoveryRebindKeepsUnsupportedCommandsDisabledForEntireSession() = runTest {
        val first=ReadFixture(mapOf(1 to hex("0C 3F 80 00 00")))
        val reader=MeasurementReader(HartClient(first),metran02);reader.discover();reader.sample()
        val replacement=ReadFixture(mapOf(1 to hex("0C 40 00 00 00")))
        reader.rebind(HartClient(replacement));assertEquals(2f,reader.sample().values.single().value,0f)
        assertEquals(listOf(1),replacement.commands)
    }
    @Test fun hart6UsesReportedAssignmentsRatherThanHart7DynamicAliases() = runTest {
        val records=hex("00 01 41 0C 3F 80 00 00 C0 02 41 20 3F 80 00 00 C0 03 41 07 3F 80 00 00 C0 04 41 0C 3F 80 00 00 C0")
        val t=ReadFixture(mapOf(1 to hex("0C 3F 80 00 00"),50 to hex("01 02 03 04"),9 to records))
        val reader=MeasurementReader(HartClient(t),metran02.copy(hartRevision=6));reader.discover();reader.sample()
        val request=(HartDecoder().accept(t.requests.last()).single() as DecodeEvent.Frame).value
        assertArrayEquals(hex("01 02 03 04"),request.payload)
    }
    @Test fun invalidCodeSelectionFallsBackButBusyErrorDoesNotBecomeUnsupported() = runTest {
        val data=mapOf(1 to hex("0C 3F 80 00 00"),3 to hex("40 80 00 00 0C 3F 80 00 00"))
        val invalid=ReadFixture(data,mapOf(9 to 2));val reader=MeasurementReader(HartClient(invalid),metran02)
        reader.discover();repeat(2){assertEquals(3,reader.sample().values.single().command)}
        assertEquals(1,invalid.commands.count{it==9})
        val busy=ReadFixture(data,mapOf(9 to 32));val other=MeasurementReader(HartClient(busy),metran02);other.discover()
        repeat(2){try{other.sample();fail()}catch(e:HartResponseException){assertEquals(32,e.responseCode)}}
        assertEquals(2,busy.commands.count{it==9})
    }
    @Test fun hart7RequiresDeviceTimestampAndBadSlotDoesNotInvalidateGoodSlot() {
        assertThrows(IllegalArgumentException::class.java){MeasurementDecoder.withStatus(reply("00 F6 41 0C 3F 80 00 00 C0"),listOf(246),7)}
        val b=MeasurementDecoder.withStatus(reply("00 F6 41 0C 3F 80 00 00 C0 F7 40 20 7F A0 00 00 00 00 00 00 00"),listOf(246,247),7)
        assertTrue(b.values[0].plottable);assertFalse(b.values[1].plottable)
    }

    @Test fun twoCommand9TimeoutsUseWorkingCommand3ForOnlyThisSession() = runTest {
        val data=mapOf(1 to hex("0C 3F 80 00 00"),3 to hex("40 80 00 00 0C 3F 80 00 00"))
        val t=ReadFixture(data,silent=setOf(9));val reports=mutableListOf<String>()
        val reader=MeasurementReader(HartClient(t,timeoutMillis=50,readAttempts=1),metran02,reports::add);reader.discover()
        try{reader.sample();fail()}catch(e:HartTimeoutException){assertFalse(e.malformedFrames)}
        repeat(4){assertEquals(3,reader.sample().values.single().command)}
        assertEquals(2,t.commands.count{it==9});assertTrue(reports.any{it.contains("таймаута")&&it.contains("Это не ответ")})
        val another=MeasurementReader(HartClient(t,timeoutMillis=50,readAttempts=1),metran02);another.discover()
        try{another.sample();fail()}catch(_:HartTimeoutException){}
        assertEquals(3,t.commands.count{it==9})
    }
    @Test fun corruptRepliesAreNotClassifiedAsPureTimeoutsOrUnsupportedCommands() = runTest {
        val records=(246..249).map{byteArrayOf(it.toByte(),0x41,12,0x3f,0x80.toByte(),0,0,0xc0.toByte())}.fold(byteArrayOf()){a,b->a+b}
        val t=ReadFixture(mapOf(1 to hex("0C 3F 80 00 00"),9 to (byteArrayOf(0)+records+ByteArray(4))),corrupt=setOf(9))
        val reader=MeasurementReader(HartClient(t,timeoutMillis=50,readAttempts=1),metran02);reader.discover()
        repeat(3){try{reader.sample();fail()}catch(e:HartTimeoutException){assertTrue(e.malformedFrames)}}
        assertEquals(3,t.commands.count{it==9})
    }
}
