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
        h.begin(metran02,"VIATOR",Instant.EPOCH);h.append(sample02(1));h.event(Instant.ofEpochMilli(2),"Пауза",gap=true)
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
        h.begin(metran02,"V",Instant.EPOCH);h.append(sample02(1000));h.event(Instant.ofEpochMilli(2000),"Пауза",gap=true)
        val id=h.snapshot(Instant.ofEpochMilli(15000));val view=h.view(id)!!
        assertEquals(15000L,view.end);assertEquals(1,view.points.size);assertEquals("Пауза",view.events.single().second)
    }

    @Test fun informationalEventsAndSnapshotsDoNotIntroduceFalseGaps() = withHistory { h,_ ->
        h.begin(metran02,"V",Instant.EPOCH);h.append(sample02(1))
        h.event(Instant.ofEpochMilli(2),"Activity в фоне");h.event(Instant.ofEpochMilli(3),"Снимок сохранён: id")
        h.append(sample02(4));assertEquals(1,h.view()!!.points.map{it.segment}.distinct().size)
    }
    @Test fun liveUpdatesUseIncrementalIndexAndOnlyTheRequestedTail() = withHistory { h,_ ->
        h.begin(metran02,"V",Instant.EPOCH);repeat(5000){h.append(sample02(it.toLong()))}
        h.view(from=4900);val before=h.decodedRecords
        h.append(sample02(5000));val view=h.view(from=4900)!!
        assertTrue(h.decodedRecords-before<300);assertEquals(5000L,view.lastMeasurementAt)
        assertEquals(101,view.points.size)
    }
    @Test fun windowDomainIgnoresInformationalWallTimeAndAgreesWithVisiblePoints() = withHistory { h,_ ->
        h.begin(metran02,"V",Instant.EPOCH);h.append(sample02(1000));h.event(Instant.ofEpochMilli(100000),"Диагностика")
        val window=h.window(null,ChartViewport())!!;assertEquals(1000L,window.range.last)
        assertTrue(window.view.points.all{it.time in window.range})
    }
    @Test fun readsLegacyVersion1EventsAndMeasurementsWithoutModifyingArchives() = withHistory { h,root ->
        val name="00000000-0000-0000-0000-000000000001"
        val file=File(root,"archive/$name.hart")
        java.io.DataOutputStream(file.outputStream()).use { out ->
            out.writeInt(0x48425232);out.writeInt(1);out.writeUTF("old-session");out.writeLong(0)
            listOf("VIATOR","9972-5FCA7F","Метран","FE 99").forEach(out::writeUTF)
            fun event(at:Long,text:String){
                val buffer=java.io.ByteArrayOutputStream();java.io.DataOutputStream(buffer).use{it.writeLong(at);it.writeUTF(text)}
                out.writeInt(buffer.size()+1);out.writeByte(2);out.write(buffer.toByteArray())
            }
            fun measurement(at:Long,value:Float){
                val buffer=java.io.ByteArrayOutputStream();java.io.DataOutputStream(buffer).use{
                    it.writeLong(at);it.writeInt(0);it.writeFloat(Float.NaN);it.writeInt(-1);it.writeLong(-1);it.writeLong(-1);it.writeInt(1)
                    it.writeUTF("PV");it.writeFloat(value);it.writeInt(12);it.writeInt(0);it.writeLong(at);it.writeInt(9);it.writeInt(246);it.writeInt(65);it.writeInt(192)
                }
                out.writeInt(buffer.size()+1);out.writeByte(1);out.write(buffer.toByteArray())
            }
            measurement(10,12.5f);event(20,"Пауза");event(30,"Связь восстановлена; идентификатор проверен");measurement(40,13.5f)
        }
        val before=file.readBytes();val view=h.view(name)!!
        assertEquals("old-session",view.info.id);assertEquals(2,view.events.size);assertEquals(listOf(12.5f,13.5f),view.points.map{it.measurement.value})
        assertNotEquals(view.points[0].segment,view.points[1].segment);assertArrayEquals(before,file.readBytes())
    }
}
