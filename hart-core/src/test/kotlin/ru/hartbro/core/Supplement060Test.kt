package ru.hartbro.core

import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

internal fun field060(name:String)=Supplement060Test::class.java.getResource("/field060/$name.txt")!!.readText()
internal fun field060Bytes(name:String)=hexBytes(field060(name).lineSequence().first{it.matches(Regex("[0-9A-F]{2}( [0-9A-F]{2})+"))})
internal fun field060Identity()=DeviceIdentity.parse(((HartDecoder().accept(field060Bytes("success-four-ff-043449")).single())as DecodeEvent.Frame).value.payload.drop(2).toByteArray())
internal fun reply060(request:HartFrame,data:ByteArray=byteArrayOf(),code:Int=0,status:Int=0):ByteArray {
    val payload=byteArrayOf(code.toByte(),status.toByte())+data
    val body=byteArrayOf(if(request.address.size==1)6 else 0x86.toByte())+request.address+byteArrayOf(request.command.toByte(),payload.size.toByte())+payload
    return ByteArray(4){0xFF.toByte()}+body+HartCodec.checksum(body)
}
internal class ZeroWire060:HartTransport {
    var identity=field060Identity();var value=.25f;var after=0f;var unit=12;var ack=0;var omitAck=false;var corruptAck=false;var protect=false;var postPvMissing=false
    var sent=false;var open=true;val requests=mutableListOf<HartFrame>();private val rx=Channel<ByteArray>(Channel.UNLIMITED)
    override val isOpen get()=open
    override suspend fun write(bytes:ByteArray) {
        check(open);val f=(HartDecoder().accept(bytes).single()as DecodeEvent.Frame).value;requests+=f
        val data=when(f.command){0->identity.raw;15->range030(0f,16f,12).also{if(protect)it[14]=1};1->{if(sent&&postPvMissing)return;byteArrayOf(unit.toByte())+ByteBuffer.allocate(4).putFloat(value).array()};43,52,200->{sent=true;if(ack==0)value=after;if(omitAck)return;byteArrayOf()};else->byteArrayOf()}
        val reply=reply060(f,data,if(f.command in setOf(43,52,200))ack else 0)
        if(corruptAck&&f.command==43)reply[reply.lastIndex]=(reply.last().toInt() xor 1).toByte()
        rx.send(reply)
    }
    override suspend fun receive()=rx.receive()
    override fun discardBufferedInput()=buildList{while(true)add(rx.tryReceive().getOrNull()?:break)}
    override fun close(){open=false;rx.close()}
}
private fun confirmedZero(w:ZeroWire060,tolerance:Float?=.001f,resolution:Float?=null,method:String="hart43")=SensorEdit(w.identity,SensorField.ZERO,"","0",
    ZeroOptions(method,PressureKind.UNKNOWN,true,true,tolerance,resolution,if(tolerance!=null||resolution!=null)"Test fixture accuracy specification" else "",12),"Scripted VIATOR/USB")
@OptIn(ExperimentalCoroutinesApi::class)
class Supplement060Test {
    @Test fun realMetranFrameSelectsRegisteredRevisionNineWithoutChangingRosemountCode() {
        val i=field060Identity();assertEquals(0x26,i.manufacturerCode);assertEquals(0x2662,i.deviceType);assertEquals(9,i.deviceRevision)
        assertEquals("Метран-150R",sensorTitle(i));assertTrue(i.manufacturerDescription.contains("Rosemount"));assertEquals("Метран-150R",PassportProfiles().model(i))
        assertNull(HartDeviceCatalog.model(i.copy(deviceRevision=8)));assertTrue(sensorTitle(i.copy(deviceRevision=8)).contains("2662"))
        assertNull(HartDeviceCatalog.model(i.copy(manufacturerCode=0xFA,deviceType=0xFAEC)));assertEquals(0x26,i.raw[1].toInt())
        assertTrue(HartDeviceCatalog.entry(i)!!.sourceUrl.contains("4a540f08"))
    }
    @Test fun realCorruptXorAndTruncationAreRejectedWhileFourFfSuccessRemainsValid() {
        val bad=HartLogImport.parse(field060("bad-xor-043442"));assertEquals(0,bad.valid);assertTrue(bad.records.any{it.failure==HartFailureKind.CHECKSUM})
        val partial=HartLogImport.parse(field060("partial-013358"));assertEquals(0,partial.valid);assertTrue(partial.records.any{it.failure==HartFailureKind.INCOMPLETE_FRAME})
        val good=HartLogImport.parse(field060("success-four-ff-043449"));assertEquals(1,good.valid);assertEquals(4,good.records.single().frame!!.preambleCount)
    }
    @Test fun firstIdentificationRetriesOnSameHealthyConnectionAndStopsAtFirstSuccess()=runTest {
        for(successAt in listOf(2,3)) {
            var ids=0;var connects=0;var configs=0
            val c=object:MonitorConnection {
                override suspend fun identify():DeviceIdentity{ids++;if(ids<successAt)throw HartTimeoutException("first packet absent");return field060Identity()}
                override suspend fun configuration()=ConfigurationSnapshot().also{configs++}
                override suspend fun discover():MeasurementBatch=error("no session")
                override suspend fun sample():MeasurementBatch=error("no session")
                override fun close(){}
            }
            val e=MonitoringEngine(backgroundScope,{connects++;c},object:SessionSink {override fun begin(i:DeviceIdentity,t:Instant)=error("no session");override fun sample(b:MeasurementBatch)=error("no session");override fun event(t:Instant,s:String){}},monotonicMillis={testScheduler.currentTime})
            e.start(false);runCurrent();assertEquals(MonitorPhase.CONNECTING,e.state.value.phase)
            advanceTimeBy(701);runCurrent();assertEquals(MonitorPhase.READY,e.state.value.phase);assertEquals(successAt,ids);assertEquals(1,connects);assertEquals(1,configs);e.stop()
        }
    }
    @Test fun threeFailedInitialAttemptsRetainTransportAndDoNotLoopForever()=runTest {
        var ids=0;var closes=0
        val c=object:MonitorConnection {override suspend fun identify():DeviceIdentity{ids++;throw HartTimeoutException("no data")};override suspend fun discover():MeasurementBatch=error("no session");override suspend fun sample():MeasurementBatch=error("no session");override fun close(){closes++}}
        val e=MonitoringEngine(backgroundScope,{c},object:SessionSink{override fun begin(i:DeviceIdentity,t:Instant){};override fun sample(b:MeasurementBatch){};override fun event(t:Instant,s:String){}},monotonicMillis={testScheduler.currentTime})
        e.start(false);advanceTimeBy(701);runCurrent();assertEquals(3,ids);assertEquals(MonitorPhase.ERROR,e.state.value.phase);assertTrue(e.state.value.transportConnected);assertEquals(0,closes)
        advanceTimeBy(10000);runCurrent();assertEquals(3,ids);e.stop()
    }
    @Test fun timeoutCausesDistinguishNoDataPartialXorAddressCommandAndLength()=runTest {
        for(kind in listOf(HartFailureKind.NO_DATA,HartFailureKind.INCOMPLETE_FRAME,HartFailureKind.CHECKSUM,HartFailureKind.ADDRESS,HartFailureKind.COMMAND,HartFailureKind.LENGTH)) {
            val rx=Channel<ByteArray>(Channel.UNLIMITED)
            val t=object:HartTransport {
                override suspend fun write(b:ByteArray){val q=(HartDecoder().accept(b).single()as DecodeEvent.Frame).value
                    val bytes=when(kind){HartFailureKind.NO_DATA->null;HartFailureKind.INCOMPLETE_FRAME->field060Bytes("partial-013358");HartFailureKind.CHECKSUM->field060Bytes("bad-xor-043442");HartFailureKind.ADDRESS->reply060(q.copy(address=byteArrayOf(0)),field060Identity().raw);HartFailureKind.COMMAND->reply060(q.copy(command=1),field060Identity().raw);else->{val body=byteArrayOf(6,0x80.toByte(),0,1,0);ByteArray(4){0xFF.toByte()}+body+HartCodec.checksum(body)}}
                    if(bytes!=null)rx.send(bytes)}
                override suspend fun receive()=rx.receive();override fun discardBufferedInput()=emptyList<ByteArray>();override fun close(){}
            }
            val failure=runCatching{HartClient(t,timeoutMillis=20,readAttempts=1).identify()}.exceptionOrNull()as HartTimeoutException
            assertTrue("${kind}: ${failure.failures}",kind in failure.failures)
        }
    }
    @Test fun zeroIsAvailableForUnknownModelAndWrites43OnceWithEmptyPayloadAndFreshIdentity()=runTest {
        val w=ZeroWire060().apply{identity=identity.copy(manufacturerCode=0xFA,deviceType=0xFAEC,raw=hex("FE FA EC 05 05 09 03 08 00 00 00 01"),deviceId=1)}
        val dir=Files.createTempDirectory("zero060").toFile();try {
            val result=ZeroOperations(HartClient(w,timeoutMillis=30,readAttempts=3)).apply(confirmedZero(w),SensorJournal(dir))
            assertTrue(result.record.succeeded);val tx=w.requests.single{it.command==43};assertEquals(0,tx.payload.size);assertArrayEquals(w.identity.address.bytes,tx.address)
            assertFalse(w.requests.any{it.command==35});assertEquals(SensorOutcome.SUCCEEDED,result.record.outcome)
            val saved=SensorJournal(dir).list().single();assertEquals(43,saved.command);assertEquals("Scripted VIATOR/USB",saved.communicator);assertNotNull(saved.pvBefore);assertEquals(3,saved.pvAfter.size)
        }finally{dir.deleteRecursively()}
    }
    @Test fun zeroActualRepliesClassifyUnsupportedProtectConditionsAndBusyWithoutManufacturerBlacklist()=runTest {
        for((code,outcome)in listOf(64 to SensorOutcome.NOT_SUPPORTED,7 to SensorOutcome.WRITE_PROTECTED,16 to SensorOutcome.WRITE_PROTECTED,9 to SensorOutcome.CONDITIONS_REJECTED,32 to SensorOutcome.BUSY,6 to SensorOutcome.DEVICE_ERROR)) {
            val w=ZeroWire060().apply{ack=code};val dir=Files.createTempDirectory("zero060").toFile();try {
                val r=ZeroOperations(HartClient(w,timeoutMillis=30,readAttempts=3)).apply(confirmedZero(w),SensorJournal(dir)).record
                assertEquals(outcome,r.outcome);assertEquals(code,r.responseCode);assertFalse(r.succeeded);assertEquals(1,w.requests.count{it.command==43})
                assertTrue(ZeroMethods().available(w.identity).any{it.command==43})
            }finally{dir.deleteRecursively()}
        }
    }
    @Test fun zeroLostOrDamagedAckNeverResendsOrClaimsSuccessEvenWhenPvIsZero()=runTest {
        for(corrupt in listOf(false,true)) {
            val w=ZeroWire060().apply{omitAck=!corrupt;corruptAck=corrupt};val dir=Files.createTempDirectory("zero060").toFile();try {
                val r=ZeroOperations(HartClient(w,timeoutMillis=30,readAttempts=3)).apply(confirmedZero(w),SensorJournal(dir)).record
                assertEquals(1,w.requests.count{it.command==43});assertFalse(r.succeeded);assertEquals(if(corrupt)SensorOutcome.INVALID_RESPONSE else SensorOutcome.NO_ACK,r.outcome)
                assertEquals(3,r.pvAfter.size);assertTrue(r.pvAfter.all{it.value==0f})
            }finally{dir.deleteRecursively()}
        }
    }
    @Test fun acceptedZeroWithoutAccuracyOrWithoutControlPvIsExplicitlyUnverified()=runTest {
        for(noPv in listOf(false,true)) {
            val w=ZeroWire060().apply{postPvMissing=noPv};val dir=Files.createTempDirectory("zero060").toFile();try {
                val r=ZeroOperations(HartClient(w,timeoutMillis=30,readAttempts=1)).apply(confirmedZero(w,tolerance=if(noPv).001f else null),SensorJournal(dir)).record
                assertEquals(SensorOutcome.ACCEPTED_UNVERIFIED,r.outcome);assertFalse(r.succeeded);assertEquals(0,r.responseCode)
            }finally{dir.deleteRecursively()}
        }
    }
    @Test fun zeroUsesSuppliedResolutionAndAccuracyAndCannotRunWithoutConfirmation()=runTest {
        val dir=Files.createTempDirectory("zero060").toFile();try {
            val unconfirmed=ZeroWire060();assertTrue(runCatching{ZeroOperations(HartClient(unconfirmed)).apply(SensorEdit(unconfirmed.identity,SensorField.ZERO,"","0"),SensorJournal(dir))}.isFailure);assertTrue(unconfirmed.requests.isEmpty())
            for((after,verified)in listOf(.004f to true,.007f to false)) {
                val w=ZeroWire060().apply{this.after=after}
                val r=ZeroOperations(HartClient(w,readAttempts=1)).apply(confirmedZero(w,.001f,.01f),SensorJournal(dir)).record
                assertEquals(verified,r.succeeded)
            }
        }finally{dir.deleteRecursively()}
    }
    @Test fun alternate52RequiresExactConfirmedProfileAndKnownVariableCode()=runTest {
        val w=ZeroWire060();val profile=ZeroMethodProfile("test52",0x26,0x2662,setOf(9),ZeroProcedure.DeviceVariable52(17),"Test-only documented variable",setOf(0),"Prepared test sensor","Synthetic test mapping PV→17",ZeroHardwareStatus.DOCUMENTED)
        val methods=ZeroMethods(listOf(profile));assertFalse(methods.available(w.identity.copy(deviceRevision=8)).any{it.id=="test52"})
        val dir=Files.createTempDirectory("zero060").toFile();try {
            val r=ZeroOperations(HartClient(w,readAttempts=1),methods).apply(confirmedZero(w,method="test52"),SensorJournal(dir)).record
            assertTrue(r.succeeded);assertArrayEquals(byteArrayOf(17),w.requests.single{it.command==52}.payload);assertFalse(w.requests.any{it.command==43})
        }finally{dir.deleteRecursively()}
    }
    @Test fun legacySensorJournalVersionOneRemainsReadable() {
        val dir=Files.createTempDirectory("sensor-v1").toFile();try {
            val id=UUID.randomUUID().toString();val time=Instant.now().toString()
            DataOutputStream(File(dir,"$id.sensor").outputStream()).use {o->with(o){writeInt(0x48525333);writeInt(1);writeUTF(id);writeUTF(time);writeUTF(field060Identity().raw.hex());writeUTF("ZERO");writeUTF("0.2 kPa");writeUTF("0");writeUTF("FINISHED");writeUTF("");writeUTF("");writeInt(0);writeInt(0);writeUTF("0 kPa");writeUTF(time);writeBoolean(true);writeUTF("")}}
            val record=SensorJournal(dir).list().single();assertTrue(record.succeeded);assertEquals(43,record.command);assertEquals("",record.communicator)
        }finally{dir.deleteRecursively()}
    }
}
