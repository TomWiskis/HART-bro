package ru.hartbro.core

import java.io.*
import java.nio.ByteBuffer
import java.nio.file.Files
import java.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

/** Only an in-memory protocol simulator. There is no Bluetooth or device access in these tests. */
internal class RangeWire:HartTransport {
    var identity=identity040();var range=ConfiguredRange(0f,16f,12);var protection=0
    var sensor=SensorInformation(1234,12,40f,-40f,0.267f)
    var code=0;var apply=true;var rounded:Float?=null;var lostAck=false;var corruptAck=false;var wrongAck=false;var shortAck=false
    var fail15AfterWrite=false;var replaceAfterWrite=false;var optionalMissing=false;var ioAfterWrite=false;var status=0
    var exchangeDelay=0L;var open=true
    val requests=mutableListOf<HartFrame>();val rawRequests=mutableListOf<ByteArray>();val queue=Channel<ByteArray>(Channel.UNLIMITED)
    val writes get()=requests.count{it.command==35}
    override suspend fun write(bytes:ByteArray) {
        val frame=(HartDecoder().accept(bytes).single() as DecodeEvent.Frame).value
        requests+=frame;rawRequests+=bytes.copyOf();delay(exchangeDelay)
        if(!open)throw IOException("closed")
        if(frame.command==35) {
            val requested=ByteBuffer.wrap(frame.payload);val unit=requested.get().toInt() and 255;val upper=requested.float;val lower=requested.float
            if(apply && (code==0 || code in setOf(8,14)))range=ConfiguredRange(lower,rounded ?: upper,unit)
            if(replaceAfterWrite)identity=identity040(123)
            if(ioAfterWrite){open=false;throw IOException("RFCOMM lost after transmission")}
            if(lostAck)return
        }
        if(frame.command==15 && writes>0 && fail15AfterWrite)return
        if(frame.command in setOf(14,20,13) && optionalMissing)return
        val data=when(frame.command) {
            0->identity.raw
            15->ByteBuffer.allocate(18).put(1).put(0).put(range.unitCode.toByte()).putFloat(range.upper).putFloat(range.lower).putFloat(0.125f).put(protection.toByte()).put(0).put(1).array()
            14->ByteBuffer.allocate(16).put(byteArrayOf(0,4,0xD2.toByte())).put(sensor.unitCode.toByte()).putFloat(sensor.upperLimit ?: Float.NaN).putFloat(sensor.lowerLimit ?: Float.NaN).putFloat(sensor.minimumSpan ?: Float.NaN).array()
            20->"TEST-TAG".padEnd(32,'\u0000').toByteArray()
            13->hex("04 20 E0 82 08 20")+ByteArray(12){0x82.toByte()}+hex("01 02 7E")
            35->if(shortAck)byteArrayOf(12) else ByteBuffer.allocate(9).put(range.unitCode.toByte()).putFloat(range.upper).putFloat(range.lower).array()
            else->byteArrayOf()
        }
        val payload=byteArrayOf((if(frame.command==35)code else 0).toByte(),status.toByte())+data
        val address=frame.address.copyOf().also{if(wrongAck && frame.command==35)it[it.lastIndex]=(it.last()+1).toByte()}
        val body=byteArrayOf(if(address.size==1)6 else 0x86.toByte())+address+byteArrayOf(frame.command.toByte(),payload.size.toByte())+payload
        var xor=0;body.forEach{xor=xor xor (it.toInt() and 255)}
        val response=ByteArray(4){0xFF.toByte()}+body+xor.toByte()
        if(corruptAck && frame.command==35)response[response.lastIndex]=(response.last().toInt() xor 1).toByte()
        // Include the request echo and one-byte fragments: neither echo nor partial data confirms a write.
        if(frame.command==35)queue.send(bytes)
        response.forEach{queue.send(byteArrayOf(it))}
    }
    override suspend fun receive()=queue.receive()
    override fun discardBufferedInput()=buildList{while(true)add(queue.tryReceive().getOrNull() ?: break)}
    override fun close(){open=false}
}
internal class RangeTestConnection(val wire:RangeWire,val client:HartClient=HartClient(wire,timeoutMillis=20,readAttempts=3)):MonitorConnection {
    val operations=RangeOperations(client)
    override val isOpen get()=wire.open
    override suspend fun identify()=client.identify().first
    override suspend fun discover()=error("No measurements in configuration test")
    override suspend fun sample()=error("No measurements in configuration test")
    override suspend fun rangeSnapshot()=operations.prepare()
    override suspend fun applyRange(prepared:RangeSnapshot,requested:ConfiguredRange,beforeSend:(ByteArray)->Unit,onReply:(HartExchange)->Unit,onInvalidRx:(ByteArray)->Unit)=operations.apply(prepared,requested,beforeSend,onReply,onInvalidRx)
    override suspend fun verifyRange(expected:DeviceIdentity)=operations.verify(expected)
    override fun close(){wire.close()}
}
@OptIn(ExperimentalCoroutinesApi::class)
class RangeWriteTest {
    private fun snapshot(range:ConfiguredRange=ConfiguredRange(0f,16f,12),sensor:SensorInformation?=SensorInformation(1,12,40f,-40f,.267f),protected:Boolean?=false)=RangeSnapshot(identity040(),range,sensor,"PT5",protected,ConfigurationSnapshot())
    @Test fun command35GoldenFrameHasNineBigEndianBytesAndLongAddress() {
        // Independently calculated XOR for 82 99 72 5F CA 7F 23 09 0C 41 20 00 00 00 00 00 00 = C4.
        val bytes=HartCodec.writeRange(identity040().address,WriteRange(ConfiguredRange(0f,10f,12)),5)
        val expected=hex("FF FF FF FF FF 82 99 72 5F CA 7F 23 09 0C 41 20 00 00 00 00 00 00 C4")
        assertArrayEquals(expected,bytes);assertFalse(ReadCommand.entries.any{it.number==35})
        assertEquals(ConfiguredRange(0f,10f,12),rangeReply(hex("0C 41 20 00 00 00 00 00 00")))
        assertThrows(IllegalArgumentException::class.java){rangeReply(ByteArray(8))}
    }
    @Test fun metranBoundsDecimalCommaMinimumSpanAndFloat32OrderAreChecked() {
        for((lo,hi) in listOf("0" to "16","0" to "10","-10" to "10","0" to "0,267")) {
            val v=RangeRules.validate(snapshot(),lo,hi);assertTrue("$lo/$hi: $v",v.errors.isEmpty())
        }
        for((lo,hi) in listOf("-50" to "10","0" to "50","0" to "0.1","10" to "0","0" to "0","1" to "1.000000001","NaN" to "10","0" to "Infinity","" to "10","0" to "3.5e38","1e-500" to "1"))
            assertFalse("$lo/$hi",RangeRules.validate(snapshot(),lo,hi).allowed)
        assertTrue(RangeRules.validate(snapshot(),"0","16").unchanged)
        assertFalse(RangeRules.validate(snapshot(protected=true),"0","10").allowed)
        assertTrue(RangeRules.validate(snapshot(protected=null),"0","10").allowed)
    }
    @Test fun sensorAndRangeUnitsUseDocumentedConversionOrExplicitlySkipPhysicalCheck() {
        val mpas=SensorInformation(1,237,.04f,-.04f,.000267f)
        assertTrue(RangeRules.validate(snapshot(sensor=mpas),"-10","10").allowed)
        assertTrue(RangeRules.validate(snapshot(sensor=mpas),"0","0.267").allowed)
        assertFalse(RangeRules.validate(snapshot(sensor=mpas),"0","50").allowed)
        val unknown=RangeRules.validate(snapshot(sensor=mpas.copy(unitCode=240)),"0","10")
        assertTrue(unknown.allowed);assertTrue(unknown.warnings.any{it.contains("несопоставимы")})
        val missing=RangeRules.validate(snapshot(sensor=null),"0","10");assertTrue(missing.allowed);assertTrue(missing.warnings.size>=2)
        assertEquals(0.0,UnitConversion.between(33,32)!!.value(32f),1e-12)
        assertEquals(273.15,UnitConversion.between(32,35)!!.value(0f),1e-12)
        assertFalse(RangeRules.validate(snapshot(range=ConfiguredRange(0f,16f,250)),"0","10").allowed)
    }
    @Test fun oneWriteIsFollowedByIdentityAnd15WithinTheSameClientTransaction()=runTest {
        val wire=RangeWire();val c=RangeTestConnection(wire);val before=c.rangeSnapshot();wire.requests.clear()
        var tx="";val attempt=c.applyRange(before,ConfiguredRange(0f,10f,12),{tx=it.hex()},{})
        assertEquals(1,wire.writes);assertEquals(listOf(0,15,14,20,0,15,35,0,15),wire.requests.map{it.command})
        assertTrue(tx.contains("23 09 0C 41 20"));assertEquals(RangeOutcome.SUCCEEDED,rangeOutcome(ConfiguredRange(0f,10f,12),before.range,attempt))
    }
    @Test fun sharedQueueNeverInsertsACompetingReadBetween35AndVerification()=runTest {
        val wire=RangeWire().apply{exchangeDelay=1};val c=RangeTestConnection(wire);val before=c.rangeSnapshot();wire.requests.clear()
        val write=async{c.applyRange(before,ConfiguredRange(0f,10f,12),{},{})}
        runCurrent();val read=async{c.client.read(wire.identity.address,ReadCommand.DEVICE_INFORMATION)}
        write.await();read.await();assertEquals(listOf(0,15,14,20,0,15,35,0,15,15),wire.requests.map{it.command})
    }
    @Test fun warnings8and14RetainPayloadStatusAndActualRoundedValues()=runTest {
        for(code in listOf(8,14)) {
            val wire=RangeWire().apply{this.code=code;rounded=9.5f;status=0x40};val c=RangeTestConnection(wire);val before=c.rangeSnapshot()
            val a=c.applyRange(before,ConfiguredRange(0f,10f,12),{},{})
            assertEquals(ResponseClass.WARNING,RangeResponseCodes.classify(code));assertEquals(code,a.reply!!.responseCode);assertEquals(0x40,a.reply.deviceStatus)
            assertEquals(9.5f,rangeReply(a.reply.data).upper,0f);assertEquals(RangeOutcome.APPLIED_CHANGED,rangeOutcome(ConfiguredRange(0f,10f,12),before.range,a));assertEquals(1,wire.writes)
        }
    }
    @Test fun commandSpecificErrorsPreserveCodesAndVerifyOldConfigurationWithoutRetry()=runTest {
        for(code in listOf(2,5,6,7,9,10,11,12,13,16,18,29,32,64)) {
            val wire=RangeWire().apply{this.code=code};val c=RangeTestConnection(wire);val before=c.rangeSnapshot()
            val a=c.applyRange(before,ConfiguredRange(0f,10f,12),{},{})
            assertEquals("code $code",RangeOutcome.REJECTED,rangeOutcome(ConfiguredRange(0f,10f,12),before.range,a));assertEquals(code,a.reply!!.responseCode);assertEquals(1,wire.writes)
        }
    }
    @Test fun timeoutCorruptChecksumWrongAddressAndShortAckNeverRepeat35()=runTest {
        for(mode in 0..3) {
            val wire=RangeWire().apply{lostAck=mode==0;corruptAck=mode==1;wrongAck=mode==2;shortAck=mode==3};val c=RangeTestConnection(wire);val before=c.rangeSnapshot()
            val a=c.applyRange(before,ConfiguredRange(0f,10f,12),{},{})
            assertEquals(1,wire.writes);assertEquals(RangeOutcome.SUCCEEDED,rangeOutcome(ConfiguredRange(0f,10f,12),before.range,a));assertTrue(a.errors.isNotEmpty())
        }
    }
    @Test fun lostAckCanConfirmOldStateAndMissing15RemainsUnknown()=runTest {
        val wire=RangeWire().apply{lostAck=true;apply=false};val c=RangeTestConnection(wire);val before=c.rangeSnapshot()
        val old=c.applyRange(before,ConfiguredRange(0f,10f,12),{},{})
        assertEquals(RangeOutcome.UNCHANGED,rangeOutcome(ConfiguredRange(0f,10f,12),before.range,old));assertEquals(1,wire.writes)
        val wire2=RangeWire().apply{fail15AfterWrite=true};val c2=RangeTestConnection(wire2);val b=c2.rangeSnapshot()
        val unknown=c2.applyRange(b,ConfiguredRange(0f,10f,12),{},{})
        assertEquals(RangeOutcome.UNKNOWN,rangeOutcome(ConfiguredRange(0f,10f,12),b.range,unknown));assertEquals(1,wire2.writes)
    }
    @Test fun changedDeviceAndStaleCurrentRangeAreBlockedBeforeAnyWrite()=runTest {
        val wire=RangeWire();val c=RangeTestConnection(wire);val before=c.rangeSnapshot();wire.identity=identity040(99)
        try{c.applyRange(before,ConfiguredRange(0f,10f,12),{},{});fail()}catch(_:RangeDeviceChanged){}
        assertEquals(0,wire.writes);wire.identity=before.identity;wire.range=ConfiguredRange(0f,20f,12)
        assertTrue(c.applyRange(before,ConfiguredRange(0f,10f,12),{},{}).aborted);assertEquals(0,wire.writes)
    }
    @Test fun replacementAfterWriteIsNeverVerifiedAsOriginalAndNeverRetransmitted()=runTest {
        val wire=RangeWire().apply{replaceAfterWrite=true};val c=RangeTestConnection(wire);val before=c.rangeSnapshot()
        try{c.applyRange(before,ConfiguredRange(0f,10f,12),{},{});fail()}catch(_:RangeDeviceChanged){}
        assertEquals(1,wire.writes);assertEquals(0,wire.requests.dropWhile{it.command!=35}.drop(1).count{it.command==15})
    }
    @Test fun durableJournalFailureBeforeSendPreventsTransmission()=runTest {
        val wire=RangeWire();val c=RangeTestConnection(wire);val before=c.rangeSnapshot()
        val attempt=c.applyRange(before,ConfiguredRange(0f,10f,12),{throw IOException("disk full")},{})
        assertTrue(attempt.aborted);assertEquals(0,wire.writes)
    }
    @Test fun journalRestoresInterruptedIntentAsUnknownAndNeverActsOnTheTransport() {
        val dir=Files.createTempDirectory("range-journal").toFile()
        try {
            val journal=RangeJournal(dir);val record=RangeRecord(identity=identity040(),tag="PT5",original=ConfiguredRange(0f,16f,12),requested=ConfiguredRange(0f,10f,12),tx="FF 82",stage=WriteStage.SENT)
            journal.save(record);val recovered=RangeJournal(dir).recoverInterrupted().single()
            assertEquals(RangeOutcome.UNKNOWN,recovered.outcome);assertEquals(record.tx,recovered.tx);assertEquals(WriteStage.FINISHED,recovered.stage)
            assertTrue(recovered.text().contains("команда 35"));assertTrue(RangeJournal(dir).recoverInterrupted().isEmpty())
        } finally {dir.deleteRecursively()}
    }
    @Test fun controllerDoubleConfirmCreatesOneOperationAndVerificationOnlyNeverWrites()=runTest {
        val dir=Files.createTempDirectory("range-engine").toFile()
        try {
            val wire=RangeWire();val conn=RangeTestConnection(wire);val journal=RangeJournal(dir)
            val engine=RangeEngine(backgroundScope,conn,{conn},journal,clock={testScheduler.currentTime});engine.open();runCurrent()
            engine.select("0","10");val token=engine.state.value.token;engine.confirm(token);engine.confirm(token);runCurrent()
            assertEquals(1,wire.writes);assertEquals(RangeOutcome.SUCCEEDED,engine.state.value.record!!.outcome);assertEquals(1,journal.list().size)
            val verify=RangeEngine(backgroundScope,conn,{conn},journal,clock={testScheduler.currentTime});verify.verifyOnly(journal.list().single());runCurrent()
            assertEquals(1,wire.writes);assertEquals(RangeOutcome.SUCCEEDED,verify.state.value.record!!.outcome)
        } finally {dir.deleteRecursively()}
    }
    @Test fun explicitCancellationDuringPreflightSendsNo35AndKeepsADurableAbortedRecord()=runTest {
        val dir=Files.createTempDirectory("range-cancel").toFile()
        try {
            val wire=RangeWire();val conn=RangeTestConnection(wire);val journal=RangeJournal(dir)
            val engine=RangeEngine(backgroundScope,conn,{conn},journal);engine.open();runCurrent();engine.select("0","10")
            wire.exchangeDelay=100;engine.confirm(engine.state.value.token);runCurrent()
            assertEquals(RangePhase.PRECHECK,engine.state.value.phase);assertTrue(engine.cancelBeforeSend());engine.stop(closeConnection=false);runCurrent()
            assertEquals(0,wire.writes);assertTrue(wire.open);assertEquals(RangeOutcome.ABORTED,journal.list().single().outcome)
        } finally {dir.deleteRecursively()}
    }

    @Test fun requestedUnitMustRemainTheExactUnitReadFromCommand15()=runTest {
        val wire=RangeWire();val c=RangeTestConnection(wire);val snapshot=c.rangeSnapshot()
        val attempt=c.applyRange(snapshot,ConfiguredRange(0f,10f,11),{},{})
        assertTrue(attempt.aborted);assertEquals(0,wire.writes)
    }

    @Test fun hart5UsesTheSameTypedWriteAndNeverRequiresLongTag20()=runTest {
        val wire=RangeWire().apply{identity=identity040(revision=5)};val c=RangeTestConnection(wire);val before=c.rangeSnapshot()
        val attempt=c.applyRange(before,ConfiguredRange(0f,10f,12),{},{})
        assertEquals(RangeOutcome.SUCCEEDED,rangeOutcome(ConfiguredRange(0f,10f,12),before.range,attempt))
        assertEquals(1,wire.writes);assertFalse(wire.requests.any{it.command==20});assertEquals(5,wire.requests.first{it.command==35}.address.size)
    }
    @Test fun unexpectedDeviceStatusIsPreservedAndNeverClearedByCommand38()=runTest {
        val wire=RangeWire().apply{status=0x80};val c=RangeTestConnection(wire);val before=c.rangeSnapshot()
        assertTrue(before.warnings.any{it.contains("неисправность")})
        val attempt=c.applyRange(before,ConfiguredRange(0f,10f,12),{},{})
        assertEquals(0x80,attempt.reply!!.deviceStatus);assertTrue(attempt.errors.any{it.contains("неисправность")})
        assertFalse(wire.requests.any{it.command in setOf(36,37,38)})
    }

}
