package ru.hartbro.core

import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.*
import org.junit.Test
import org.junit.Assert.*

private class WalkWire060(val identity:DeviceIdentity,val waitMs:Long=150):HartTransport {
    val commands=mutableListOf<Int>();val queue=Channel<ByteArray>(Channel.UNLIMITED)
    var missing=setOf<Int>();var lose:Int?=null
    override suspend fun write(bytes:ByteArray) {
        val f=(HartDecoder().accept(bytes).single() as DecodeEvent.Frame).value
        commands+=f.command
        if(f.command==lose)throw java.io.IOException("USB detached")
        if(f.command in missing)return
        delay(waitMs)
        val data=when(f.command){0->identity.raw;13->byteArrayOf(4,32,0xE0.toByte(),0x82.toByte(),8,32)+ByteArray(12){0x82.toByte()}+byteArrayOf(1,2,126);15->range030(0f,10f,12);20->"USED-LONG-TAG".padEnd(32,'\u0000').toByteArray();else->null}
        val payload=if(data==null)byteArrayOf(64,0) else byteArrayOf(0,0)+data
        val body=byteArrayOf(if(f.address.size==1)6 else 0x86.toByte())+f.address+byteArrayOf(f.command.toByte(),payload.size.toByte())+payload
        queue.send(ByteArray(4){0xFF.toByte()}+body+body.fold(0){a,b->a xor(b.toInt() and 255)}.toByte())
    }
    override suspend fun receive()=queue.receive()
    override fun discardBufferedInput()=buildList{while(true)add(queue.tryReceive().getOrNull()?:break)}
    override fun close(){}
}
class Walk060Test {
    @Test fun basicWalkRouteHasOnlyUsedFieldsAndOneBoundedRetryForMissing13And15()=runTest {
        for(missing in listOf(emptySet(),setOf(13),setOf(15))) {
            val wire=WalkWire060(identity040(revision=5)).apply{this.missing=missing}
            val metrics=mutableListOf<HartTransactionMetric>()
            val client=HartClient(wire,timeoutMillis=1500,readAttempts=1,observed={metrics+=it},clockMillis={testScheduler.currentTime})
            val reading=PassportReader(client).read(client.identify().first)
            assertFalse(reading.transportLost);assertFalse(wire.commands.any{it in setOf(14,16)})
            assertEquals(if(13 in missing)2 else 1,wire.commands.count{it==13})
            assertEquals(if(15 in missing)2 else 1,wire.commands.count{it==15})
            if(15 in missing){assertNull(reading.range);assertTrue(reading.errors.containsKey("15"));assertNotNull(reading.fields[PassportField.TAG]?.value)}
            if(13 in missing){assertNotNull(reading.range);assertTrue(reading.errors.containsKey("13"))}
            assertEquals(missing.size*2,metrics.count{it.outcome=="TIMEOUT"})
        }
    }
    @Test fun USBUnplugMidConfigurationKeepsReceivedTagAndMarksUnreadRange()=runTest {
        val wire=WalkWire060(identity040(revision=5)).apply{lose=15};val client=HartClient(wire,readAttempts=1)
        val reading=PassportReader(client).read(client.identify().first)
        assertTrue(reading.transportLost);assertEquals("ABC",reading.fields[PassportField.TAG]?.value);assertNull(reading.range)
        assertEquals(listOf(0,13,15),wire.commands)
    }
    @Test fun oneMissingDiscoveryDoesNotCreateRowButConfirmedAbsenceRequiresDuplicateDecision()=runTest {
        var present=true;var passportReads=0;var probes=0
        val conn=object:MonitorConnection {
            override suspend fun identify():DeviceIdentity {probes++;if(!present)throw HartTimeoutException("no sensor");return identity040(1)}
            override suspend fun passport(identity:DeviceIdentity)=reading040(1).also{passportReads++}
            override suspend fun discover():MeasurementBatch=error("walk must not measure")
            override suspend fun sample():MeasurementBatch=error("walk must not measure")
            override fun close(){}
        }
        val engine=WalkEngine(backgroundScope,{conn},clock={testScheduler.currentTime});engine.start();runCurrent()
        present=false;advanceTimeBy(1000);runCurrent();present=true;advanceTimeBy(400);runCurrent()
        assertEquals(1,passportReads);assertEquals(1,engine.state.value.book!!.rows.size);assertTrue(engine.state.value.book!!.pending.isEmpty())
        present=false;advanceTimeBy(1000);runCurrent();advanceTimeBy(400);runCurrent()
        present=true;advanceTimeBy(400);runCurrent()
        val book=engine.state.value.book!!;assertEquals(1,book.rows.size);assertEquals(1,book.pending.size)
        val pending=book.pending.single().id;engine.catalog.resolvePending(pending);engine.documentChanged()
        assertEquals(2,engine.state.value.book!!.rows.size);assertEquals(2,engine.state.value.book!!.rows.map{it.id}.distinct().size)
        assertTrue(engine.state.value.book!!.rows.all{it.observed.identity.deviceId==1});engine.stop()
    }
    @Test fun workbookExportsUnsignedDecimalDeviceIdForOldManualAndDuplicateRows() {
        val catalog=WalkCatalog();val row=catalog.ingest(reading040(1)).rowId!!;catalog.edit(row,mapOf(PassportField.SERIAL to "legacy manual serial"));catalog.separate(row)
        catalog.ingest(reading040(0xBF2C34));val out=ByteArrayOutputStream();WalkXlsx.write(catalog.snapshot(),out)
        var xml="";ZipInputStream(out.toByteArray().inputStream()).use{zip->while(true){val e=zip.nextEntry?:break;if(e.name=="xl/worksheets/sheet1.xml")xml=zip.readBytes().toString(Charsets.UTF_8)}}
        assertTrue(xml.contains("12528692"));assertEquals(2,Regex("<c r=\"B[23]\"[^>]*><is><t xml:space=\"preserve\">1</t>").findAll(xml).count())
        assertFalse(xml.contains("legacy manual serial"));assertEquals("legacy manual serial",catalog.snapshot().rows.first().fields[PassportField.SERIAL]?.value)
        assertFalse(xml.contains("BF2C34"))
    }
    @Test fun normalLogOmitsChunksWhileDetailedModeRetainsThemAndMetricsRemain()=runTest {
        for(detailed in listOf(false,true)) {
            val wire=WalkWire060(identity040(revision=5));val entries=mutableListOf<DiagnosticEntry>();val metrics=mutableListOf<HartTransactionMetric>()
            HartClient(wire,{entries+=it},readAttempts=1,detailed={detailed},observed={metrics+=it},clockMillis={testScheduler.currentTime}).identify()
            assertEquals(detailed,entries.any{it.kind=="RX-CHUNK"});assertTrue(entries.any{it.kind=="RX-FRAME"});assertEquals(150L,metrics.single().millis)
        }
    }
}
