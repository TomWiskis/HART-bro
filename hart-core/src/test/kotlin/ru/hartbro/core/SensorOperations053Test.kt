package ru.hartbro.core

import java.nio.ByteBuffer
import java.nio.file.Files
import java.time.LocalDate
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

private class SensorWire : HartTransport {
    var identity = metran02
    var tagDate = byteArrayOf(4,32,0xE0.toByte(),0x82.toByte(),8,32) + ByteArray(12){0x82.toByte()} + byteArrayOf(1,2,126)
    var longTag = "LONG-OLD".padEnd(32,'\u0000').toByteArray()
    var pv = 0.25f
    var protected = false
    var code = 0
    var loseAck = false
    var apply = true
    var zeroWorks = true
    val commands = mutableListOf<Int>()
    private val queue = Channel<ByteArray>(Channel.UNLIMITED)
    override suspend fun write(bytes: ByteArray) {
        val f = (HartDecoder().accept(bytes).single() as DecodeEvent.Frame).value
        commands += f.command
        if(f.command in setOf(18,22,43)) {
            if(apply && code==0) when(f.command) { 18->tagDate=f.payload.copyOf();22->longTag=f.payload.copyOf();43->if(zeroWorks)pv=0f }
            if(loseAck)return
        }
        val data = when(f.command) {
            0->identity.raw
            1->ByteBuffer.allocate(5).put(12).putFloat(pv).array()
            13->tagDate
            14->ByteBuffer.allocate(16).put(byteArrayOf(0,4,0xD2.toByte())).put(12).putFloat(40f).putFloat(-40f).putFloat(.267f).array()
            15->ByteBuffer.allocate(18).put(0).put(0).put(12).putFloat(16f).putFloat(0f).putFloat(.125f).put(if(protected)1 else 0).put(0).put(1).array()
            18->tagDate
            20,22->longTag
            else->byteArrayOf()
        }
        val payload=byteArrayOf(if(f.command in setOf(18,22,43))code.toByte() else 0,0x40)+data
        val body=byteArrayOf(if(f.address.size==1)6 else 0x86.toByte())+f.address+byteArrayOf(f.command.toByte(),payload.size.toByte())+payload
        var xor=0;body.forEach{xor=xor xor(it.toInt() and 255)}
        queue.send(ByteArray(5){0xFF.toByte()}+body+xor.toByte())
    }
    override suspend fun receive()=queue.receive()
    override fun discardBufferedInput()=buildList{while(true)add(queue.tryReceive().getOrNull() ?: break)}
    override fun close() {}
}
class SensorOperations053Test {
    private fun journal()=SensorJournal(Files.createTempDirectory("sensor-053").toFile().apply{deleteOnExit()})
    @Test fun dateHasStrictCalendarEncodingAndMissingValuesAreOmitted() {
        assertEquals(LocalDate.of(2026,2,1),HartDate.decode(byteArrayOf(1,2,126)))
        assertNull(HartDate.decode(byteArrayOf(0,0,0)));assertNull(HartDate.decode(byteArrayOf(-1,-1,-1)))
        assertNull(HartDate.decode(byteArrayOf(31,2,126)))
        assertArrayEquals(byteArrayOf(29,2,124),HartDate.encode(HartDate.parse("29.02.2024")))
        assertThrows(IllegalArgumentException::class.java){HartDate.parse("29.02.2025")}
        assertThrows(IllegalArgumentException::class.java){HartDate.parse("01.01.2200")}
    }
    @Test fun modelNeedsManufacturerAndFullTypeAndUnknownDeviceKeepsRawIds() {
        val known=metran02.copy(deviceType=0x9979)
        assertEquals("Метран-150",HartDeviceCatalog.model(known))
        assertNull(HartDeviceCatalog.model(known.copy(manufacturerCode=0x26)))
        assertNull(HartDeviceCatalog.model(metran02))
        assertTrue(sensorTitle(metran02).contains("9972"))
        assertEquals("6277759",metran02.deviceId.toString())
    }
    @Test fun shortTagWritePreservesDescriptorAndDateAndRequiresReadback() = runTest {
        val wire=SensorWire();val untouched=wire.tagDate.copyOfRange(6,21)
        val old=ConfigurationDecoder.tagDescription(wire.tagDate).getValue("tag").value
        val result=SensorOperations(HartClient(wire,timeoutMillis=40,readAttempts=3)).apply(SensorEdit(wire.identity,SensorField.TAG,old,"NEW-123"),journal())
        assertTrue(result.record.succeeded);assertEquals("NEW-123",result.record.actual)
        assertArrayEquals(untouched,wire.tagDate.copyOfRange(6,21));assertEquals(1,wire.commands.count{it==18});assertEquals(0,wire.commands.count{it==35})
        assertEquals(13,wire.commands.last())
    }
    @Test fun dateWritePreservesBothPackedFields() = runTest {
        val wire=SensorWire();val untouched=wire.tagDate.copyOfRange(0,18)
        val result=SensorOperations(HartClient(wire,timeoutMillis=40)).apply(SensorEdit(wire.identity,SensorField.DATE,"01.02.2026","30.11.2025"),journal())
        assertTrue(result.record.succeeded);assertArrayEquals(untouched,wire.tagDate.copyOfRange(0,18));assertArrayEquals(byteArrayOf(30,11,125),wire.tagDate.copyOfRange(18,21))
    }
    @Test fun lostLongTagAcknowledgementNeverResendsAndReadbackCanConfirmActualResult() = runTest {
        val wire=SensorWire().also{it.loseAck=true};val journal=journal()
        val result=SensorOperations(HartClient(wire,timeoutMillis=40,readAttempts=3)).apply(SensorEdit(wire.identity,SensorField.LONG_TAG,"LONG-OLD","NEW-LONG"),journal)
        assertTrue(result.record.succeeded);assertEquals(1,wire.commands.count{it==22});assertNull(result.record.responseCode)
        assertEquals(WriteStage.FINISHED,journal.list().single().stage);assertTrue(journal.list().single().succeeded)
    }
    @Test fun acknowledgementWithoutActualChangeIsNeverSuccess() = runTest {
        val wire=SensorWire().also{it.apply=false}
        val result=SensorOperations(HartClient(wire,timeoutMillis=40)).apply(SensorEdit(wire.identity,SensorField.LONG_TAG,"LONG-OLD","NEW"),journal())
        assertFalse(result.record.succeeded);assertEquals("LONG-OLD",result.record.actual);assertEquals(1,wire.commands.count{it==22})
    }
    @Test fun changedValueAndProtectionPreventAnyWrite() = runTest {
        val wire=SensorWire();val op=SensorOperations(HartClient(wire,timeoutMillis=40))
        try{op.apply(SensorEdit(wire.identity,SensorField.LONG_TAG,"STALE","NEW"),journal());fail()}catch(_:IllegalArgumentException){}
        wire.protected=true
        try{op.apply(SensorEdit(wire.identity,SensorField.LONG_TAG,"LONG-OLD","NEW"),journal());fail()}catch(_:IllegalArgumentException){}
        assertFalse(wire.commands.any{it in setOf(18,22,35,43)})
    }
    @Test fun replacedDeviceBeforeConfirmationReceivesNoWrite() = runTest {
        val wire=SensorWire()
        try { SensorOperations(HartClient(wire,timeoutMillis=40)).apply(SensorEdit(wire.identity.copy(deviceId=12),SensorField.LONG_TAG,"LONG-OLD","NEW"),journal());fail() }catch(_:RangeDeviceChanged){}
        assertFalse(wire.commands.any{it in setOf(18,22,35,43)})
    }
    @Test fun zeroWithoutExplicitConditionsConfirmationIsNotSent() = runTest {
        val wire=SensorWire()
        try { SensorOperations(HartClient(wire,timeoutMillis=40)).apply(SensorEdit(wire.identity,SensorField.ZERO,"","0"),journal());fail() }catch(_:IllegalArgumentException){}
        assertFalse(wire.commands.contains(43));assertFalse(wire.commands.contains(35))
    }
    private fun documented(wire:SensorWire)=ZeroProfiles(listOf(ZeroProfile(wire.identity.manufacturerCode!!,wire.identity.deviceType,setOf(wire.identity.hartRevision),setOf(wire.identity.deviceRevision),"Test fixture documented command 43")))
    @Test fun supportedPressureZeroWritesOnceAndChecksThreeRealPvReadings() = runTest {
        val wire=SensorWire()
        val result=SensorOperations(HartClient(wire,timeoutMillis=40,readAttempts=3),documented(wire)).apply(SensorEdit(wire.identity,SensorField.ZERO,"","0",ZeroOptions(conditionsConfirmed=true,pressureConfirmed=true,tolerance=.001f,accuracySource="Test fixture",unitCode=12)),journal())
        assertTrue(result.record.succeeded);assertEquals(1,wire.commands.count{it==43});assertEquals(4,wire.commands.count{it==1});assertFalse(wire.commands.contains(35))
    }
    @Test fun zeroAckWithoutZeroPvIsFailure() = runTest {
        val wire=SensorWire().also{it.zeroWorks=false}
        val result=SensorOperations(HartClient(wire,timeoutMillis=40),documented(wire)).apply(SensorEdit(wire.identity,SensorField.ZERO,"","0",ZeroOptions(conditionsConfirmed=true,pressureConfirmed=true,tolerance=.001f,accuracySource="Test fixture",unitCode=12)),journal())
        assertFalse(result.record.succeeded);assertEquals(1,wire.commands.count{it==43})
    }
    @Test fun interruptedSensorJournalNeverRestartsWritesAndIndividualDeletionPreservesOthers() {
        val j=journal();val a=SensorRecord(identity=metran02,field=SensorField.DATE,original="",requested="01.01.2026",stage=WriteStage.SENT)
        val b=a.copy(id=java.util.UUID.randomUUID().toString(),stage=WriteStage.FINISHED)
        j.save(a);j.save(b);assertThrows(IllegalStateException::class.java){j.delete(a.id)}
        j.recoverInterrupted();assertFalse(j.list().first{it.id==a.id}.succeeded);j.delete(a.id);assertEquals(b.id,j.list().single().id)
    }
}
