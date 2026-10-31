package ru.hartbro.core

import java.io.IOException
import java.nio.ByteBuffer
import java.time.Instant
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

internal fun identity040(id:Int=0x5FCA7F,revision:Int=7):DeviceIdentity {
    val raw=hex(if(revision==5) "FE 26 62 05 05 07 28 08 00 BF 2C 34" else "FE 99 72 05 07 07 28 08 00 5F CA 7F 05 09 00 2C 00 00 99 00 99 01")
    raw[9]=(id shr 16).toByte();raw[10]=(id shr 8).toByte();raw[11]=id.toByte();return DeviceIdentity.parse(raw)
}
internal fun reading040(id:Int=0x5FCA7F,range:ConfiguredRange?=ConfiguredRange(-50f,150f,32),sensor:String="1234"):PassportReading {
    val at=Instant.parse("2026-10-31T00:00:00Z")
    return PassportReading(identity040(id),mapOf(
        PassportField.TAG to PassportFact("ТЕСТ-$id",FactSource.STANDARD_HART,20,"54 45",at),
        PassportField.MODEL to PassportFact("Тестовый датчик",FactSource.STANDARD_HART,0,"FE",at),
        PassportField.RANGE to PassportFact(range?.label,FactSource.STANDARD_HART,15,"00",at)),
        mapOf("sensorNumber" to PassportFact(sensor,FactSource.STANDARD_HART,14,"00 04 D2",at)),range,at)
}
private val sensor040 = hex("00 04 D2 0C 43 48 00 00 C2 C8 00 00 3F 80 00 00") // #1234, kPa, physical limits 200/-100, span 1.
private class PassportWire(val identity:DeviceIdentity,val answer:(Int)->ByteArray? = { command -> when(command) {
    0->identity.raw;13->hex("04 20 E0 82 08 20")+ByteArray(12){0x82.toByte()}+hex("01 02 7E")
    20->"LONG-TAG".padEnd(32,'\u0000').toByteArray();14->sensor040;15->range030(0f,10f,12);16->hex("00 01 2C");else->null
} }) : HartTransport {
    val commands=mutableListOf<Int>();val addresses=mutableListOf<ByteArray>();val queue=Channel<ByteArray>(Channel.UNLIMITED)
    var corruptCommand:Int?=null;var truncatedCommand:Int?=null;var loseAt:Int?=null;var missing:Set<Int> = emptySet()
    override suspend fun write(bytes:ByteArray) {
        val frame=(HartDecoder().accept(bytes).single() as DecodeEvent.Frame).value;val cmd=frame.command
        commands+=cmd;addresses+=frame.address
        if(cmd==loseAt) throw IOException("physical RFCOMM loss")
        if(cmd in missing) return
        val data=answer(cmd);val payload=if(data==null) byteArrayOf(64,0) else byteArrayOf(0,0)+data
        val body=byteArrayOf(if(frame.address.size==1) 6 else 0x86.toByte())+frame.address+byteArrayOf(cmd.toByte(),payload.size.toByte())+payload
        var xor=0;body.forEach { xor=xor xor (it.toInt() and 255) };var reply=ByteArray(5){0xFF.toByte()}+body+xor.toByte()
        if(cmd==corruptCommand) reply[reply.lastIndex]=(reply.last().toInt() xor 1).toByte()
        if(cmd==truncatedCommand) reply=reply.dropLast(2).toByteArray()
        reply.forEach { queue.send(byteArrayOf(it)) }
    }
    override suspend fun receive()=queue.receive()
    override fun discardBufferedInput()=buildList { while(true) add(queue.tryReceive().getOrNull() ?: break) }
    override fun close() {}
}
class PassportTest {
    @Test fun sensorAndAssemblyDecodeUnsignedNumbersWithoutCallingThemFactorySerial() {
        val sensor=PassportDecoder.sensor(sensor040);assertEquals(1234,sensor.number);assertEquals(12,sensor.unitCode)
        assertEquals(200f,sensor.upperLimit!!,0f);assertEquals(-100f,sensor.lowerLimit!!,0f);assertEquals(1f,sensor.minimumSpan!!,0f)
        assertEquals(16777215,PassportDecoder.assembly(hex("FF FF FF")));assertEquals(0,PassportDecoder.assembly(hex("00 00 00")))
        assertThrows(IllegalArgumentException::class.java){PassportDecoder.sensor(sensor040.copyOf(15))}
        for(size in listOf(0,2,4)) assertThrows(IllegalArgumentException::class.java){PassportDecoder.assembly(ByteArray(size))}
    }
    @Test fun hart5UsesShortTagAndOnlyNecessaryLongAddressedReads()=runTest {
        val wire=PassportWire(identity040(revision=5));val client=HartClient(wire,readAttempts=1)
        val identity=client.identify().first;val result=PassportReader(client).read(identity)
        assertEquals(listOf(0,13,15,14,16),wire.commands);assertTrue(wire.addresses.drop(1).all{it.size==5})
        assertEquals("ABC",result.fields[PassportField.TAG]!!.value);assertEquals("0…10 кПа",result.range!!.label)
        assertEquals("1234",result.candidates["sensorNumber"]!!.value);assertEquals("300",result.candidates["assemblyNumber"]!!.value)
        assertNull(result.fields[PassportField.SERIAL]!!.value);assertNull(result.fields[PassportField.DATE]!!.value)
        assertEquals("01 02 7E",result.candidates["hartDate"]!!.value)
    }
    @Test fun hart7PrefersLongTagAndKeepsIdentitySeparateFromFactoryFacts()=runTest {
        val wire=PassportWire(identity040());val client=HartClient(wire,readAttempts=1)
        val identity=client.identify().first;val result=PassportReader(client).read(identity)
        assertEquals(listOf(0,20,15,14,16),wire.commands);assertEquals("LONG-TAG",result.fields[PassportField.TAG]!!.value)
        assertNull(result.fields[PassportField.SERIAL]!!.value);assertNull(result.fields[PassportField.DATE]!!.value)
        assertEquals("Метран · тип 0x9972",result.fields[PassportField.MODEL]!!.value)
        assertTrue(result.fields.values.filter{it.source!=FactSource.NONE}.all{it.readAt!=null && it.raw.isNotEmpty()})
    }
    @Test fun unknownManufacturerNeverInventsAModelOrFactoryFields() {
        val d=identity040().copy(manufacturerCode=0x1234,deviceType=0x8765)
        val label=PassportProfiles().model(d);assertTrue(label.contains("1234"));assertTrue(label.contains("8765"))
        assertFalse(label.contains("150"));assertFalse(reliablePassportKey(d.copy(deviceId=0)))
    }
    @Test fun unsupportedOptionalCommandsAreCachedPerIdentityAndDoNotAbort()=runTest {
        val wire=PassportWire(identity040()){cmd->if(cmd==0)identity040().raw else null};val client=HartClient(wire,readAttempts=1)
        val identity=client.identify().first;val reader=PassportReader(client);val first=reader.read(identity)
        assertEquals(listOf(0,20,13,15,14,16),wire.commands);assertEquals(5,first.errors.size)
        reader.read(identity);assertEquals(6,wire.commands.size);assertNull(first.range)
    }
    @Test fun silentOptional14or15or16RetainsOtherFieldsAndDoesNotDeclareBluetoothLoss()=runTest {
        for(cmd in listOf(14,15,16)) {
            val wire=PassportWire(identity040()).apply{missing=setOf(cmd)};val client=HartClient(wire,timeoutMillis=20,readAttempts=1)
            val result=PassportReader(client).read(client.identify().first)
            assertTrue(result.errors.containsKey(cmd.toString()));assertFalse(result.transportLost)
            assertEquals("LONG-TAG",result.fields[PassportField.TAG]!!.value)
            if(cmd==15) assertNull(result.range) else assertNotNull(result.range)
        }
    }
    @Test fun corruptXorAndIncomplete14FramesDoNotBecomePassportNumbers()=runTest {
        for(corrupt in listOf(true,false)) {
            val wire=PassportWire(identity040()).apply{if(corrupt)corruptCommand=14 else truncatedCommand=14}
            val client=HartClient(wire,timeoutMillis=20,readAttempts=1);val result=PassportReader(client).read(client.identify().first)
            assertFalse(result.candidates.containsKey("sensorNumber"));assertTrue(result.errors.containsKey("14"))
            assertEquals("300",result.candidates["assemblyNumber"]!!.value)
        }
    }
    @Test fun shortSuccessful14and16AreReportedAsPartialFields()=runTest {
        val wire=PassportWire(identity040()){cmd->when(cmd){0->identity040().raw;14->byteArrayOf(1);16->byteArrayOf(1,2);else->null}}
        val client=HartClient(wire,readAttempts=1);val result=PassportReader(client).read(client.identify().first)
        assertTrue(result.errors.keys.containsAll(listOf("14","16")));assertTrue(result.candidates.isEmpty())
    }
    @Test fun physicalLossAfterTagReturnsPartialPassportWithoutDiscardingIt()=runTest {
        val wire=PassportWire(identity040()).apply{loseAt=14};val client=HartClient(wire,readAttempts=1)
        val result=PassportReader(client).read(client.identify().first)
        assertTrue(result.transportLost);assertEquals("LONG-TAG",result.fields[PassportField.TAG]!!.value);assertNotNull(result.range)
        assertFalse(wire.commands.contains(16));assertTrue(result.errors.containsKey("16"))
    }
    @Test fun documentedProfileIsRequiredBeforeAssemblyCanBeFactorySerialOrDate()=runTest {
        fun profile(doc:String)=object:PassportProfile {
            override val documentation=doc
            override fun matches(identity:DeviceIdentity)=true
            override val additionalReads=listOf(ReadCommand.TAG_DESCRIPTION)
            override fun model(identity:DeviceIdentity)="ТЕСТ документированный профиль"
            override fun factoryFields(identity:DeviceIdentity,replies:Map<ReadCommand,HartReply>)=mapOf(
                PassportField.SERIAL to PassportFact("00300",FactSource.PROFILE,16,"00 01 2C",Instant.now(),confirmation=FactConfirmation.DOCUMENTED,documentation=doc),
                PassportField.DATE to PassportFact("07.2025",FactSource.PROFILE,13,replies.getValue(ReadCommand.TAG_DESCRIPTION).data.hex(),Instant.now(),confirmation=FactConfirmation.DOCUMENTED,documentation=doc,datePrecision=DatePrecision.MONTH))
        }
        for(doc in listOf("","Test-only documented mapping, not a production manufacturer claim")) {
            val wire=PassportWire(identity040());val client=HartClient(wire,readAttempts=1)
            val result=PassportReader(client,PassportProfiles(listOf(profile(doc)))).read(client.identify().first)
            assertEquals(doc.isNotEmpty(),result.fields[PassportField.SERIAL]!!.factoryConfirmed)
            if(doc.isNotEmpty()) { assertEquals("00300",result.fields[PassportField.SERIAL]!!.value);assertEquals(DatePrecision.MONTH,result.fields[PassportField.DATE]!!.datePrecision) }
        }
    }
    @Test fun partialDatesHaveNoInventedDayOrMonthAndRejectImpossibleDates() {
        for((text,precision) in listOf("15.07.2025" to DatePrecision.DAY,"07.2025" to DatePrecision.MONTH,"2025" to DatePrecision.YEAR,"29.02.2024" to DatePrecision.DAY)) {
            val date=FactoryDate.parse(text);assertEquals(text,date.label);assertEquals(precision,date.precision)
        }
        assertNull(FactoryDate.parse("2025").month);assertNull(FactoryDate.parse("07.2025").day)
        for(text in listOf("29.02.2025","31.04.2025","00.2025","2025-01-01","0000","15.7.2025",""))
            assertThrows(IllegalArgumentException::class.java){FactoryDate.parse(text)}
    }
}
