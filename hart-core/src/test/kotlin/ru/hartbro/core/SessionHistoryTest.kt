package ru.hartbro.core

import java.io.File
import java.nio.file.Files
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class SessionHistoryTest {
    private fun withHistory(test: (SessionHistory,File)->Unit) {
        val root=Files.createTempDirectory("hart-history-test").toFile()
        try { test(SessionHistory(File(root,"temp"),File(root,"archive")),root) } finally { root.deleteRecursively() }
    }
    @Test fun archivesAppearOnlyOnManualSnapshotAndRepeatSaveCreatesDistinctSnapshots() = withHistory { h,_ ->
        h.begin(metran02,"VIATOR",Instant.EPOCH);h.append(sample02(1000));assertTrue(h.list().isEmpty())
        val first=h.snapshot();h.append(sample02(2000,2f));val second=h.snapshot();assertNotEquals(first,second)
        assertEquals(1,h.view(first)!!.points.size);assertEquals(2,h.view(second)!!.points.size)
        h.discard();assertEquals(2,h.list().size);assertNull(h.view())
    }
    @Test fun discardDeletesTemporaryHistoryButPreservesPreviousArchive() = withHistory { h,root ->
        h.begin(metran02,"VIATOR",Instant.EPOCH);h.append(sample02());val id=h.snapshot();h.discard()
        assertEquals(0,File(root,"temp").listFiles()!!.size);assertNotNull(h.view(id))
        h.begin(metran02.copy(deviceId=1),"VIATOR",Instant.EPOCH);h.append(sample02(1));h.discard();assertEquals(1,h.list().size)
    }
    @Test fun gapsQualityAndUnitChangesRemainDistinctSegmentsAfterReopening() = withHistory { h,_ ->
        h.begin(metran02,"VIATOR",Instant.EPOCH);h.append(sample02(1));h.event(Instant.ofEpochMilli(2),"Пауза")
        h.append(sample02(3));h.append(sample02(4,quality=Quality.BAD));h.append(sample02(5))
        h.append(sample02(6).let{it.copy(values=it.values.map{v->v.copy(unitCode=32)})})
        val id=h.snapshot();h.discard();val view=h.view(id)!!
        assertEquals(listOf(1L,3L,5L,6L),view.points.map{it.time});assertEquals(4,view.points.map{it.segment}.distinct().size)
        assertEquals("Пауза",view.events.single().second)
    }
    @Test fun missingSlotDoesNotBridgeItsPreviousAndNextValues() = withHistory { h,_ ->
        h.begin(metran02,"V",Instant.EPOCH);h.append(sample02(1));h.append(sample02(2).let{it.copy(values=it.values.map{v->v.copy(slot="SV")})});h.append(sample02(3))
        assertNotEquals(h.view()!!.points.first().segment,h.view()!!.points.last().segment)
    }
    @Test fun longHistoryHasBoundedChartMemoryAndKeepsSpikesAndMultipleSeries() = withHistory { h,_ ->
        h.begin(metran02,"V",Instant.EPOCH)
        repeat(5000){i->h.append(sample02(i.toLong(),if(i==3333)1000f else 1f).let{it.copy(values=it.values+it.values[0].copy(slot="SV",unitCode=32))})}
        val view=h.view(buckets=20)!!;assertTrue(view.points.size<=160);assertEquals(1000f,view.points.maxOf{it.measurement.value},0f)
        assertEquals(setOf("PV","SV"),view.points.map{it.measurement.slot}.toSet());assertEquals(4999L,view.end)
    }
    @Test fun timeWindowAndPointValuesArePreservedWithoutNormalization() = withHistory { h,_ ->
        h.begin(metran02,"V",Instant.EPOCH);repeat(10){h.append(sample02(it*1000L,it.toFloat()))}
        val view=h.view(from=3000,to=5000)!!;assertEquals(listOf(3f,4f,5f),view.points.map{it.measurement.value})
    }
    @Test fun deletionRequiresValidLocalIdAndOnlyRemovesSelectedSnapshot() = withHistory { h,_ ->
        h.begin(metran02,"V",Instant.EPOCH);val a=h.snapshot();val b=h.snapshot();h.delete(a)
        assertEquals(listOf(b),h.list().map{it.first});assertThrows(IllegalArgumentException::class.java){h.delete("../archive")}
    }
    @Test fun temporaryStorageIsBoundedAndDoesNotSilentlyDropMeasurements() {
        val root=Files.createTempDirectory("hart-cap").toFile()
        try {val h=SessionHistory(File(root,"tmp"),File(root,"archive"),1);h.begin(metran02,"V",Instant.EPOCH)
            assertThrows(HistoryLimitException::class.java){h.append(sample02())};assertTrue(h.list().isEmpty())
        }finally{root.deleteRecursively()}
    }
    @Test fun processRestartDoesNotTurnUnrequestedTemporaryDataIntoAnArchive() = withHistory { h,root ->
        h.begin(metran02,"V",Instant.EPOCH);h.append(sample02());val id=h.snapshot()
        val restarted=SessionHistory(File(root,"temp"),File(root,"archive"));restarted.cleanOrphans()
        assertEquals(0,File(root,"temp").listFiles()!!.size);assertEquals(id,restarted.list().single().first)
    }

    @Test fun snapshotBoundaryIncludesAnOpenPauseUntilTheActualSaveTime() = withHistory { h,_ ->
        h.begin(metran02,"V",Instant.EPOCH);h.append(sample02(1000));h.event(Instant.ofEpochMilli(2000),"Пауза")
        val id=h.snapshot(Instant.ofEpochMilli(15000));val view=h.view(id)!!
        assertEquals(15000L,view.end);assertEquals(1,view.points.size);assertEquals("Пауза",view.events.single().second)
    }
}
