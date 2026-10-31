package ru.hartbro.core

import java.io.*
import java.nio.file.Files
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class JournalExportTest {
    private fun history(test: (SessionHistory, File) -> Unit) {
        val root = Files.createTempDirectory("hart030-journal").toFile()
        try { test(SessionHistory(File(root, "working"), File(root, "archives")), root) } finally { root.deleteRecursively() }
    }
    @Test fun metadataAndConfigurationSurviveReopenWithoutRewritingMeasurements() = history { h, root ->
        val config = ConfigurationSnapshot(ConfigurationDecoder.deviceInformation(range030(), 7))
        h.begin(metran02, "VIATOR", Instant.EPOCH, config); h.append(sample02(1000))
        val id = h.snapshot(Instant.ofEpochMilli(2000)); val before = File(root, "archives/$id.hart").readBytes()
        h.edit(id, "Проверка; \"цех 1\"", "Комментарий\nвторая строка")
        val reopened = SessionHistory(File(root, "working2"), File(root, "archives"))
        val info = reopened.info(id)
        assertEquals(id, info.id); assertEquals(2000L, info.durationMillis)
        assertEquals("Проверка; \"цех 1\"", info.title); assertTrue(info.comment.contains('\n')); assertEquals(config, info.configuration)
        assertArrayEquals(before, File(root, "archives/$id.hart").readBytes())
        assertEquals(1, reopened.view(id)!!.points.size)
    }
    @Test fun deletingMultipleSelectedRecordsPreservesOthersAndRemovesTheirMetadata() = history { h, root ->
        h.begin(metran02, "V", Instant.EPOCH); val ids = List(3) { h.snapshot() }
        ids.forEach { h.edit(it, "Название", "Комментарий") }
        ids.take(2).forEach(h::delete)
        assertEquals(listOf(ids.last()), h.list().map { it.first })
        assertEquals(1, File(root, "archives").listFiles()!!.count { it.extension == "meta" })
    }
    @Test fun csvContainsEveryOriginalPointWithNegativeValuesQualityUtcAndNoZeroSubstitution() = history { h, _ ->
        h.begin(metran02, "VIATOR", Instant.EPOCH)
        h.append(sample02(1234, -0.01330386f)); h.append(sample02(2345, Float.NaN, Quality.BAD)); h.append(sample02(3456, Float.NaN, Quality.ABSENT))
        val id = h.snapshot(Instant.ofEpochMilli(4000))
        val output = ByteArrayOutputStream(); SessionCsv.write(h, id, output)
        val text = output.toString("UTF-8")
        assertTrue(text.startsWith("\uFEFFsep=;\r\ntimestamp_utc;")); assertTrue(text.contains("1970-01-01T00:00:01.234Z;9972-5FCA7F;PV;-0.01330386;"))
        assertTrue(text.contains(";kPa;12;")); assertTrue(text.contains("килопаскаль")); assertTrue(text.contains(";NaN;")); assertTrue(text.contains(";BAD;9;")); assertTrue(text.contains(";PV;;")); assertEquals(5, text.trim().lines().size)
    }
    @Test fun csvUtf8EscapingAndBothSeparatorsPreserveUnknownUnitLabels() = history { h, _ ->
        h.begin(metran02, "Кириллица", Instant.EPOCH)
        h.append(sample02(100, -1.2345678f).let { it.copy(values = it.values.map { v -> v.copy(unitCode = 999) }) })
        val id = h.snapshot()
        for (sep in listOf(';', ',')) {
            val out = ByteArrayOutputStream(); SessionCsv.write(h, id, out, sep)
            assertTrue(out.toString("UTF-8").contains("-1.2345678${sep}HART 999${sep}999"))
        }
    }
    @Test fun longSessionCsvAndStatisticsAreNotReducedToChartBuckets() = history { h, _ ->
        h.begin(metran02, "V", Instant.EPOCH)
        repeat(10000) { h.append(sample02(it.toLong(), it.toFloat())) }
        h.event(Instant.ofEpochMilli(10000), "Пауза", gap = true)
        val id = h.snapshot(Instant.ofEpochMilli(11000))
        val out = ByteArrayOutputStream(); SessionCsv.write(h, id, out)
        assertEquals(10002, out.toString("UTF-8").trim().lines().size)
        val stats = sessionStatistics(h, id)
        assertEquals(10000L, stats.variables.single().count); assertEquals(4999.5, stats.variables.single().mean, 0.0)
        assertEquals(1000L, stats.noDataMillis); assertEquals(1L, stats.gapCount)
        assertTrue(h.savedView(id, 10).points.size <= 40)
    }
    @Test fun gapsAndBadValuesAreExcludedFromStatisticsWithoutInventingSamples() = history { h, _ ->
        h.begin(metran02, "V", Instant.EPOCH); h.append(sample02(1000, -2f)); h.event(Instant.ofEpochMilli(2000), "Bluetooth потерян", true)
        h.append(sample02(3000, 1000f, Quality.BAD)); h.append(sample02(4000, 2f, Quality.UNVERIFIED))
        val id = h.snapshot(Instant.ofEpochMilli(5000)); val stats = sessionStatistics(h, id)
        assertEquals(2L, stats.variables.single().count); assertEquals(1L, stats.variables.single().rejected)
        assertEquals(0.0, stats.variables.single().mean, 0.0); assertEquals(2000L, stats.noDataMillis)
        assertEquals(1L, stats.variables.single().unverified)
    }
}
