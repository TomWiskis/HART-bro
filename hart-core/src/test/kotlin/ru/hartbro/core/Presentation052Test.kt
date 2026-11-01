package ru.hartbro.core

import java.nio.file.Files
import java.time.Instant
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
    @Test fun measurementAgeAndSensorIdentityAreNotInferredFromBluetooth() {
        val now=Instant.ofEpochMilli(100_000)
        assertTrue(measurementFresh(now.minusSeconds(1),now,2000))
        assertFalse(measurementFresh(now.minusSeconds(20),now,2000))
        assertFalse(measurementFresh(null,now,2000))
        assertTrue(sensorTitle(metran02).startsWith("HART-датчик"))
        assertFalse(sensorTitle(metran02).contains("Метран-150"))
    }
    @Test fun historyDeletionIsLocalPreservesOtherRecordsAndRefusesInFlightIntent() {
        val directory=Files.createTempDirectory("history-052").toFile()
        try {
            val journal=RangeJournal(directory)
            val a=RangeRecord(identity=metran02,tag=null,original=ConfiguredRange(0f,16f,12),requested=ConfiguredRange(2f,20f,12),stage=WriteStage.FINISHED,outcome=RangeOutcome.SUCCEEDED)
            val b=a.copy(id=java.util.UUID.randomUUID().toString(),stage=WriteStage.SENT,outcome=RangeOutcome.PENDING)
            journal.save(a);journal.save(b)
            assertEquals(3,rangeChanges(a).size)
            journal.delete(a.id);assertEquals(b.id,journal.load(b.id).id)
            assertThrows(IllegalStateException::class.java){journal.deleteAll()}
            journal.recoverInterrupted();journal.deleteAll();assertTrue(journal.list().isEmpty())
        } finally {directory.deleteRecursively()}
    }
}
