package ru.hartbro.core

import java.nio.file.Files
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class Presentation052Test {
    @Test fun unitsConvertPressureAndTemperatureWithoutChangingPhysicalSpan() {
        val converted=convertRange(ConfiguredRange(0f,16f,12),237)
        assertEquals(0.016f,converted.upper,0.00000001f)
        val fahrenheit=convertRange(ConfiguredRange(-40f,100f,32),33)
        assertEquals(-40f,fahrenheit.lower,0.0001f);assertEquals(212f,fahrenheit.upper,0.0001f)
        val kelvin=convertRange(ConfiguredRange(0f,100f,32),35)
        assertEquals(273.15f,kelvin.lower,0.0001f)
    }
    @Test fun unknownCapabilityNeverOffersOrValidatesAnAlternativeUnit() {
        val snapshot=RangeSnapshot(metran02,ConfiguredRange(0f,16f,12),null,null,false,ConfigurationSnapshot())
        assertEquals(setOf(12),snapshot.writableUnits)
        assertFalse(RangeRules.validate(snapshot,"0","0.016",237).allowed)
        assertTrue(RangeRules.validate(snapshot.copy(writableUnits=setOf(12,237)),"0","0.016",237).allowed)
    }
    @Test fun documentedCapabilityCombinesUnitsAndBoundsInOneVerifiedWrite() = runTest {
        val wire=RangeWire()
        val operations=RangeOperations(HartClient(wire,timeoutMillis=50,readAttempts=3)) { _, _ -> setOf(12,237) }
        val before=operations.prepare();val requested=convertRange(before.range,237)
        val attempt=operations.apply(before,requested,{},{})
        assertEquals(RangeOutcome.SUCCEEDED,rangeOutcome(requested,before.range,attempt))
        assertEquals(1,wire.writes);assertEquals(237,attempt.verification!!.range.unitCode)
        assertEquals(0.016f,attempt.verification!!.range.upper,0.00000001f)
    }
    @Test fun measurementAgeAndSensorIdentityAreNotInferredFromBluetooth() {
        val now=Instant.ofEpochMilli(100_000)
        assertTrue(measurementFresh(now.minusSeconds(1),now,2000))
        assertFalse(measurementFresh(now.minusSeconds(20),now,2000))
        assertFalse(measurementFresh(null,now,2000))
        assertTrue(sensorTitle(metran02).contains("Метран"))
        assertFalse(sensorTitle(metran02).contains("Метран-150"))
    }
    @Test fun failedExpectedReadTurnsHartIndicatorOffEvenWithHealthyBluetoothAndRecentSuccess() {
        val now=Instant.ofEpochMilli(100_000)
        val state=MonitorState(phase=MonitorPhase.MONITORING,identity=metran02,latest=sample02(100_000),lastHartSuccess=now,transportConnected=true)
        assertTrue(state.hartExchangeCurrent(now))
        assertFalse(state.copy(latest=null).hartExchangeCurrent(now.plusMillis(1)))
        assertFalse(state.copy(transportConnected=false).hartExchangeCurrent(now))
        assertFalse(state.copy(phase=MonitorPhase.RECOVERING).hartExchangeCurrent(now))
        assertFalse(state.hartExchangeCurrent(now.plusSeconds(20)))
        assertTrue(state.copy(phase=MonitorPhase.READY,latest=null,identificationResponding=true).hartExchangeCurrent(now))
    }
    @Test fun historyDeletionIsLocalPreservesOtherRecordsAndRefusesInFlightIntent() {
        val directory=Files.createTempDirectory("history-052").toFile()
        try {
            val journal=RangeJournal(directory)
            val a=RangeRecord(identity=metran02,tag=null,original=ConfiguredRange(0f,16f,12),requested=ConfiguredRange(2f,20f,12),stage=WriteStage.FINISHED,outcome=RangeOutcome.SUCCEEDED)
            val b=a.copy(id=java.util.UUID.randomUUID().toString(),stage=WriteStage.SENT,outcome=RangeOutcome.PENDING)
            journal.save(a);journal.save(b)
            assertEquals(2,rangeChanges(a).size)
            journal.delete(a.id);assertEquals(b.id,journal.load(b.id).id)
            assertThrows(IllegalStateException::class.java){journal.deleteAll()}
            journal.recoverInterrupted();journal.deleteAll();assertTrue(journal.list().isEmpty())
        } finally {directory.deleteRecursively()}
    }
}
