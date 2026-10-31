package ru.hartbro.core

import java.io.*
import java.nio.file.Files
import java.time.Instant
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WalkTest {
    @Test fun aThenNoneThenBThenAKeepsTwoRowsAndOneBluetoothConnection()=runTest {
        var present:Int?=1;var connects=0;var passports=0;var closes=0;var measurements=0
        val connection=object:MonitorConnection {
            override suspend fun identify()=present?.let{identity040(it)} ?: throw HartTimeoutException("probes removed")
            override suspend fun passport(identity:DeviceIdentity)=reading040(identity.deviceId).also{passports++}
            override suspend fun discover():MeasurementBatch { measurements++;error("walk must not measure") }
            override suspend fun sample():MeasurementBatch { measurements++;error("walk must not measure") }
            override fun close(){closes++}
        }
        val engine=WalkEngine(backgroundScope,{connects++;connection},clock={testScheduler.currentTime})
        engine.start();runCurrent();assertEquals(1,engine.state.value.book!!.rows.size)
        present=null;advanceTimeBy(5000);runCurrent();assertEquals(WalkPhase.WAITING,engine.state.value.phase);assertNull(engine.state.value.recovery)
        assertTrue(engine.state.value.bluetoothConnected);assertEquals(0,closes)
        present=2;advanceTimeBy(1001);runCurrent();present=1;advanceTimeBy(1001);runCurrent()
        assertEquals(2,engine.state.value.book!!.rows.size);assertEquals(3,passports);assertEquals(1,connects);assertEquals(0,measurements)
        engine.stop();assertEquals(1,closes)
    }
    @Test fun partialPassportAndRepeatedActiveExportsDoNotStopPollingOrCreateDuplicates()=runTest {
        var probes=0;var reads=0
        val conn=object:MonitorConnection {
            override suspend fun identify()=identity040().also{probes++}
            override suspend fun passport(identity:DeviceIdentity)=reading040(range=null).copy(errors=mapOf("14" to "timeout","15" to "unsupported","16" to "short reply")).also{reads++}
            override suspend fun discover():MeasurementBatch=error("unused")
            override suspend fun sample():MeasurementBatch=error("unused")
            override fun close(){}
        }
        val engine=WalkEngine(backgroundScope,{conn},clock={testScheduler.currentTime});engine.start();runCurrent()
        repeat(2) { WalkXlsx.write(engine.state.value.book!!,ByteArrayOutputStream()) }
        advanceTimeBy(2001);runCurrent();assertTrue(probes>=3);assertEquals(1,reads);assertEquals(1,engine.state.value.book!!.rows.size)
        engine.repeatCurrent();advanceTimeBy(1001);runCurrent();assertEquals(2,reads);assertNull(engine.state.value.recovery);engine.stop()
    }
    @Test fun physicalBluetoothLossReconnectsWithoutDemandingThePreviousDevice()=runTest {
        var connects=0;var phase=0;var closes=0
        fun connection()=object:MonitorConnection {
            override suspend fun identify():DeviceIdentity {
                if(phase==1){phase=2;throw IOException("RFCOMM gone")}
                if(phase==2)throw HartTimeoutException("no next sensor yet")
                return identity040(if(phase==3)2 else 1)
            }
            override suspend fun passport(identity:DeviceIdentity)=reading040(identity.deviceId)
            override suspend fun discover():MeasurementBatch=error("unused")
            override suspend fun sample():MeasurementBatch=error("unused")
            override fun close(){closes++}
        }
        val engine=WalkEngine(backgroundScope,{connects++;connection()},clock={testScheduler.currentTime});engine.start();runCurrent()
        phase=1;advanceTimeBy(2001);runCurrent();assertEquals(2,connects);assertEquals(WalkPhase.WAITING,engine.state.value.phase)
        assertEquals(1,engine.state.value.book!!.rows.size);phase=3;advanceTimeBy(1001);runCurrent()
        assertEquals(2,engine.state.value.book!!.rows.size);assertEquals(1,closes);engine.stop()
    }
    @Test fun bluetoothRecoveryHasOne60SecondDeadlineAndNoHiddenRetriesAfterDecision()=runTest {
        var connects=0;var ids=0
        val conn=object:MonitorConnection {
            override suspend fun identify():DeviceIdentity { if(ids++>0)throw IOException("lost");return identity040() }
            override suspend fun passport(identity:DeviceIdentity)=reading040()
            override suspend fun discover():MeasurementBatch=error("unused")
            override suspend fun sample():MeasurementBatch=error("unused")
            override fun close(){}
        }
        val engine=WalkEngine(backgroundScope,{if(connects++==0)conn else withTimeout(15000){delay(30000);conn}},clock={testScheduler.currentTime})
        engine.start();runCurrent();advanceTimeBy(1000);runCurrent();assertEquals(WalkPhase.RECOVERING,engine.state.value.phase)
        advanceTimeBy(59999);runCurrent();assertEquals(WalkPhase.RECOVERING,engine.state.value.phase)
        advanceTimeBy(1);runCurrent();assertEquals(WalkPhase.DECISION,engine.state.value.phase);assertEquals(1,engine.state.value.book!!.rows.size)
        val attempts=connects;advanceTimeBy(120000);runCurrent();assertEquals(attempts,connects);engine.stop()
    }
    @Test fun failedDraftWriteStopsSafelyWithBookInMemoryInsteadOfPretendingSensorIsAbsent()=runTest {
        var closes=0
        val conn=object:MonitorConnection {
            override suspend fun identify()=identity040()
            override suspend fun passport(identity:DeviceIdentity)=reading040()
            override suspend fun discover():MeasurementBatch=error("unused")
            override suspend fun sample():MeasurementBatch=error("unused")
            override fun close(){closes++}
        }
        val engine=WalkEngine(backgroundScope,{conn},changed={throw IOException("disk full")})
        engine.start();runCurrent();assertEquals(WalkPhase.DECISION,engine.state.value.phase);assertEquals(1,engine.state.value.book!!.rows.size);assertEquals(1,closes);engine.stop()
    }
    @Test fun initialConnectTimeoutProducesUserDecisionInsteadOfStuckConnecting()=runTest {
        val engine=WalkEngine(backgroundScope,{withTimeout(100){delay(1000);error("unreachable")}})
        engine.start();advanceTimeBy(101);runCurrent();assertEquals(WalkPhase.DECISION,engine.state.value.phase);engine.stop()
    }
    @Test fun manualPassportFieldsSurviveRepeatedReadAndRangeChangesNeedAcceptance() {
        val catalog=WalkCatalog();val row=catalog.ingest(reading040()).rowId!!
        catalog.edit(row,mapOf(PassportField.SERIAL to "0000123",PassportField.DATE to "07.2025",PassportField.TAG to "ШИЛЬДИК",PassportField.MODEL to "Уточнённая марка",PassportField.RANGE to "локальный диапазон"))
        catalog.ingest(reading040(range=ConfiguredRange(0f,40f,12)))
        var current=catalog.snapshot().rows.single();assertEquals("0000123",current.text(PassportField.SERIAL));assertEquals("07.2025",current.text(PassportField.DATE))
        assertEquals("ШИЛЬДИК",current.text(PassportField.TAG));assertEquals("локальный диапазон",current.text(PassportField.RANGE));assertNotNull(current.pendingRange)
        assertEquals(-50f,current.acceptedRange!!.lower,0f);catalog.acceptRange(row)
        current=catalog.snapshot().rows.single();assertEquals("0…40 кПа",current.text(PassportField.RANGE));assertNull(current.pendingRange)
    }
    @Test fun suspiciousAndCollidingIdsNeverSilentlyMergeAndCanBeSplitOrConfirmed() {
        val catalog=WalkCatalog();catalog.ingest(reading040(id=0));val uncertain=catalog.ingest(reading040(id=0))
        assertNull(uncertain.rowId);assertNotNull(uncertain.pendingId);assertTrue(catalog.snapshot().rows.single().requiresIdentityCheck)
        catalog.resolvePending(uncertain.pendingId!!);assertEquals(2,catalog.snapshot().rows.size);assertTrue(catalog.snapshot().pending.isEmpty())
        val known=WalkCatalog();val row=known.ingest(reading040()).rowId!!;known.edit(row,mapOf(PassportField.SERIAL to "007"))
        val different=known.ingest(reading040(sensor="5678"));assertNotNull(different.pendingId);assertEquals("1234",known.snapshot().rows.single().observed.candidates["sensorNumber"]!!.value)
        known.resolvePending(different.pendingId!!,row);assertEquals(row,known.takeResolvedRow(different.pendingId));assertEquals("007",known.snapshot().rows.single().text(PassportField.SERIAL))
        known.separate(row);val again=known.ingest(reading040());assertNotNull(again.pendingId);known.resolvePending(again.pendingId!!)
        assertEquals(3,known.snapshot().rows.size)
    }
    @Test fun unknownSerialDateTagAndRangeStayBlankRatherThanCopyingTechnicalIds() {
        val reading=reading040().copy(fields=mapOf(PassportField.TAG to PassportFact("")),range=null)
        val catalog=WalkCatalog();catalog.ingest(reading);val row=catalog.snapshot().rows.single()
        assertNull(row.text(PassportField.TAG));assertNull(row.text(PassportField.SERIAL));assertNull(row.text(PassportField.DATE));assertNull(row.text(PassportField.RANGE))
    }
    @Test fun draftAndManualSaveAreDistinctAndRoundTripPreservesProvenancePrecisionAndPendingRange() {
        val directory=Files.createTempDirectory("walk-store-test").toFile()
        try {
            val store=WalkStore(directory);val catalog=WalkCatalog();val id=catalog.ingest(reading040()).rowId!!
            catalog.edit(id,mapOf(PassportField.SERIAL to "000123",PassportField.DATE to "2025"));catalog.ingest(reading040(range=ConfiguredRange(0f,10f,12)))
            val before=catalog.snapshot();store.writeDraft(before);assertTrue(store.hasDraft());assertTrue(store.list().isEmpty())
            val restored=store.restoreDraft();val row=restored.rows.single();assertEquals("000123",row.text(PassportField.SERIAL));assertEquals(DatePrecision.YEAR,row.fields[PassportField.DATE]!!.datePrecision)
            assertEquals(before.rows.single().fields,row.fields);assertEquals(before.rows.single().observed.candidates,row.observed.candidates);assertNotNull(row.pendingRange)
            store.save(restored);assertEquals(1,store.list().size);store.clearDraft();assertFalse(store.hasDraft());assertEquals(restored.id,store.load(restored.id).id)
            store.delete(restored.id);assertTrue(store.list().isEmpty());assertThrows(IllegalArgumentException::class.java){store.load("../escape")}
            File(directory,"current.draft").writeBytes(byteArrayOf(1,2));assertThrows(EOFException::class.java){store.restoreDraft()}
        } finally { directory.deleteRecursively() }
    }
    @Test fun workbookHasOneSheetExactlyFiveTextColumnsAnd100DevicesWithNoExtraData() {
        val catalog=WalkCatalog();repeat(100) { i -> val id=catalog.ingest(reading040(i+1,if(i%2==0)ConfiguredRange(-50f,150f,32) else ConfiguredRange(0f,2.5f,237))).rowId!!
            catalog.edit(id,mapOf(PassportField.SERIAL to "00%04d".format(i),PassportField.DATE to listOf("15.07.2025","07.2025","2025","")[i%4])) }
        val bytes=ByteArrayOutputStream().also{WalkXlsx.write(catalog.snapshot(),it)}.toByteArray()
        val parts=mutableMapOf<String,ByteArray>();ZipInputStream(bytes.inputStream()).use { zip -> while(true) { val e=zip.nextEntry ?: break;parts[e.name]=zip.readBytes() } }
        fun xml(name:String)=DocumentBuilderFactory.newInstance().apply{isNamespaceAware=true}.newDocumentBuilder().parse(parts.getValue(name).inputStream())
        val workbook=xml("xl/workbook.xml");assertEquals(1,workbook.getElementsByTagName("sheet").length);assertEquals("Приборы",workbook.getElementsByTagName("sheet").item(0).attributes.getNamedItem("name").nodeValue)
        val sheet=xml("xl/worksheets/sheet1.xml");assertEquals(101,sheet.getElementsByTagName("row").length)
        assertEquals("A2",sheet.getElementsByTagName("pane").item(0).attributes.getNamedItem("topLeftCell").nodeValue)
        val cells=sheet.getElementsByTagName("c");for(i in 0 until cells.length) { val a=cells.item(i).attributes;assertEquals("inlineStr",a.getNamedItem("t").nodeValue);assertTrue(a.getNamedItem("r").nodeValue.first() in 'A'..'E') }
        assertEquals("A1:E101",xml("xl/tables/table1.xml").getElementsByTagName("autoFilter").item(0).attributes.getNamedItem("ref").nodeValue)
        val text=String(parts.getValue("xl/worksheets/sheet1.xml"));assertTrue(text.contains("000000"));assertTrue(text.contains("−50…150 °C"));assertTrue(text.contains("0…2,5 МПа"));assertFalse(text.contains("HART Device ID"));assertFalse(text.contains("PV"))
    }
    @Test fun readyConnectionCanBeTransferredBetweenActorsWithoutConcurrentReadsOrReconnect()=runTest {
        var measurements=0;var closes=0;var prepares=0
        val conn=object:MonitorConnection {
            override suspend fun identify()=identity040()
            override suspend fun passport(identity:DeviceIdentity)=reading040()
            override suspend fun discover()=sample02().also{measurements++}
            override suspend fun sample()=sample02().also{measurements++}
            override fun prepareWalk(){prepares++}
            override fun close(){closes++}
        }
        val sink=object:SessionSink {
            override fun begin(identity:DeviceIdentity,at:Instant){}
            override fun sample(batch:MeasurementBatch){}
            override fun event(at:Instant,text:String){}
        }
        val monitor=MonitoringEngine(backgroundScope,{conn},sink);monitor.start(false);runCurrent()
        assertEquals(MonitorPhase.READY,monitor.state.value.phase);assertEquals(0,measurements)
        val handed=monitor.detachReadyConnection();assertSame(conn,handed)
        val walk=WalkEngine(backgroundScope,{error("must reuse")},clock={testScheduler.currentTime})
        walk.start(handed);runCurrent();advanceTimeBy(3001);runCurrent();assertEquals(0,measurements);assertEquals(1,prepares)
        assertSame(conn,walk.stop(keepConnection=true));assertEquals(0,closes)
        val resumed=MonitoringEngine(backgroundScope,{conn},sink);resumed.start(false);runCurrent();resumed.startSession();runCurrent()
        assertEquals(MonitorPhase.MONITORING,resumed.state.value.phase);assertTrue(measurements>0);resumed.stop();assertEquals(1,closes)
    }

    @Test fun queuedMeasurementSessionStartCannotBeSilentlyCancelledByWalkHandoff()=runTest {
        val conn=object:MonitorConnection {
            override suspend fun identify()=identity040()
            override suspend fun discover()=sample02()
            override suspend fun sample()=sample02()
            override fun close(){}
        }
        val sink=object:SessionSink {
            override fun begin(identity:DeviceIdentity,at:Instant){}
            override fun sample(batch:MeasurementBatch){}
            override fun event(at:Instant,text:String){}
        }
        val monitor=MonitoringEngine(backgroundScope,{conn},sink);monitor.start(false);runCurrent();monitor.startSession()
        try { monitor.detachReadyConnection();fail("A pending explicit session start must require a user decision") } catch(_:IllegalStateException) {}
        runCurrent();assertEquals(MonitorPhase.MONITORING,monitor.state.value.phase);monitor.finishSession()
        assertSame(conn,monitor.detachReadyConnection());conn.close()
    }

    @Test fun partialReadsRetainIdentityEvidenceAndConfirmedRowsStillDetectDifferentNumbers() {
        val catalog=WalkCatalog();val id=catalog.ingest(reading040(sensor="1234")).rowId!!
        catalog.ingest(reading040().copy(candidates=emptyMap(),errors=mapOf("14" to "timeout")))
        assertEquals("1234",catalog.snapshot().rows.single().observed.candidates["sensorNumber"]!!.value)
        val collision=catalog.ingest(reading040(sensor="5678"));assertNotNull(collision.pendingId)
        catalog.resolvePending(collision.pendingId!!,id)
        assertEquals(id,catalog.refreshConfirmed(id,reading040(sensor="5678")).rowId)
        assertNotNull(catalog.refreshConfirmed(id,reading040(sensor="9999")).pendingId)
        assertEquals("5678",catalog.snapshot().rows.single().observed.candidates["sensorNumber"]!!.value)
    }
    @Test fun delayedDraftCallbacksReadLatestManualFieldsAfterAcquiringStorageLock() {
        val directory=Files.createTempDirectory("walk-draft-order").toFile()
        try {
            val store=WalkStore(directory);val catalog=WalkCatalog();val id=catalog.ingest(reading040()).rowId!!
            val ready=java.util.concurrent.CountDownLatch(1)
            val writer=Thread { ready.countDown();store.writeDraft(catalog::snapshot) }
            synchronized(store) {
                writer.start();assertTrue(ready.await(2,java.util.concurrent.TimeUnit.SECONDS))
                catalog.edit(id,mapOf(PassportField.SERIAL to "00042"))
            }
            writer.join(2000);assertFalse(writer.isAlive)
            assertEquals("00042",store.restoreDraft().rows.single().text(PassportField.SERIAL))
        } finally { directory.deleteRecursively() }
    }

}
