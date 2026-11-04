package ru.hartbro.core

import java.io.IOException
import java.nio.file.Files
import java.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HartCapture060Test {
    private fun request(command:SensorWrite=SensorWrite.Zero())=HartCodec.writeSensor(field060Identity().address,command)
    private fun frame(bytes:ByteArray)=(HartDecoder().accept(bytes).single()as DecodeEvent.Frame).value
    @Test fun importingMixedRequestsResponsesPreservesParametersStatusTimestampAndXorWithoutSending() {
        val tx=request();val rx=reply060(frame(tx),code=64)
        val time="2026-11-03T04:34:49.927393Z"
        val a=HartLogImport.parse("$time [TX-FRAME] request\n${tx.hex()}\n$time [RX-FRAME] response\n${rx.hex()}\n")
        assertEquals(2,a.valid);assertEquals(1,a.writes.size);assertEquals(43,a.writes.single().command)
        assertTrue(a.writes.single().data.isEmpty());assertEquals(64,a.records.last().responseCode)
        assertEquals(Instant.parse(time),a.records.first().receivedAt);assertEquals(1,a.pairs().size)
        assertFalse(a.pairs().single().ambiguous)
        assertTrue(a.text().contains(tx.hex()));assertTrue(a.text().contains(rx.hex()))
    }
    @Test fun importedUnknownProcedureNeverBecomesAZeroMethodOrAutomaticReplay() {
        val body=byteArrayOf(0x82.toByte())+field060Identity().address.bytes+byteArrayOf(200.toByte(),2,0x12,0x34)
        val raw=ByteArray(4){0xFF.toByte()}+body+HartCodec.checksum(body)
        val a=HartLogImport.parse(raw.hex())
        assertEquals(200,a.records.single().command);assertEquals(CommandEffect.UNKNOWN,a.records.single().effect)
        assertArrayEquals(byteArrayOf(0x12,0x34),a.records.single().data);assertNull(a.records.single().receivedAt)
        assertTrue(ZeroMethods().available(field060Identity()).all{it.command==43})
    }
    @Test fun comparisonsShowCommandsAndChangedParametersAndRetriedPairsAreMarkedAmbiguous() {
        val t=request();val r=reply060(frame(t))
        val before=HartLogImport.parse(t.hex()+"\n"+r.hex())
        val after=HartLogImport.parse(t.hex()+"\n"+t.hex()+"\n"+r.hex())
        assertTrue(after.pairs().single().ambiguous);assertTrue(after.compare(before).single().contains("1 → 2"))
    }
    @Test fun duplicateChunkAndAssembledFrameLogIsNotCountedTwiceAndBomIsAccepted() {
        val b=field060Bytes("success-four-ff-043449")
        val s="\uFEFF2026-11-03T04:34:49Z [RX-CHUNK] raw\n${b.hex()}\n2026-11-03T04:34:49Z [RX-FRAME] complete\n${b.hex()}\n"
        assertEquals(1,HartLogImport.parse(s).valid)
        assertEquals(1,HartLogImport.parse("\uFEFF"+b.hex()).valid)
    }
    @Test fun fragmentedChunkImportRetainsFirstAndLastReceiveTimesAndNeverUsesNumbersInProse() {
        val b=field060Bytes("success-four-ff-043449");val x=b.size/2
        val a=HartLogImport.parse("2026-11-03T04:34:49Z [RX-CHUNK] 11 bytes\n${b.take(x).toByteArray().hex()}\n2026-11-03T04:34:50Z [RX-CHUNK] tail\n${b.drop(x).toByteArray().hex()}\n")
        assertEquals(1,a.valid);assertEquals(Instant.parse("2026-11-03T04:34:49Z"),a.records.single().firstFragmentAt);assertEquals(Instant.parse("2026-11-03T04:34:50Z"),a.records.single().receivedAt)
        assertThrows(IllegalArgumentException::class.java){HartLogImport.parse("PV=43\nId=00 FF 01\nNo HART frame here")}
    }
    @Test fun passiveReceiverHasHardTxGuardPreservesNativeTimesAndStopsWithoutNewQueries()=runTest {
        val input=Channel<HartFragment>(Channel.UNLIMITED);var writes=0;var closes=0;var open=true
        val t=object:HartTransport {
            override val isOpen get()=open
            override suspend fun write(bytes:ByteArray){writes++}
            override suspend fun receive()=input.receive().bytes
            override suspend fun receiveStamped()=input.receive()
            override fun discardBufferedInput()=emptyList<ByteArray>()
            override fun close(){if(open){open=false;closes++;input.close(IOException("unplug"))}}
        }
        val dir=Files.createTempDirectory("capture060").toFile();try {
            val file=java.io.File(dir,"capture.txt");val engine=PassiveCaptureEngine(backgroundScope,{t},file)
            engine.start();runCurrent();assertEquals(CapturePhase.LISTENING,engine.state.value.phase)
            val instant=Instant.parse("2026-11-03T04:34:49.927393Z")
            val tx=request();input.send(HartFragment(tx,instant,123));input.send(HartFragment(reply060(frame(tx)),instant,124));runCurrent()
            assertEquals(0,writes);assertEquals(2L,engine.state.value.valid);assertEquals(instant,engine.state.value.records.first().receivedAt)
            assertTrue(runCatching{ReceiveOnlyTransport(t).write(tx)}.exceptionOrNull()is UnsupportedOperationException)
            assertEquals(0,writes);engine.stop();assertEquals(1,closes);assertFalse(engine.state.value.transportConnected)
            assertEquals(2,HartLogImport.parse(file.readText()).valid)
        }finally{dir.deleteRecursively()}
    }
    @Test fun unplugAndIncompleteFrameKeepCaptureWithNoInventedSuccessOrWrites()=runTest {
        val input=Channel<ByteArray>(Channel.UNLIMITED);var writes=0
        val t=object:HartTransport {override suspend fun write(b:ByteArray){writes++};override suspend fun receive()=input.receive();override fun discardBufferedInput()=emptyList<ByteArray>();override fun close(){}}
        val dir=Files.createTempDirectory("capture060").toFile();try {
            val file=java.io.File(dir,"log.txt");val engine=PassiveCaptureEngine(backgroundScope,{t},file);engine.start();runCurrent()
            input.send(field060Bytes("partial-013358"));input.close(IOException("USB detached"));runCurrent()
            assertEquals(CapturePhase.ERROR,engine.state.value.phase);assertEquals(0L,engine.state.value.valid);assertEquals(0,writes)
            assertTrue(engine.state.value.records.any{it.failure==HartFailureKind.INCOMPLETE_FRAME && !it.valid})
            assertTrue(file.readText().contains(field060Bytes("partial-013358").hex()));engine.stop()
        }finally{dir.deleteRecursively()}
    }
}
