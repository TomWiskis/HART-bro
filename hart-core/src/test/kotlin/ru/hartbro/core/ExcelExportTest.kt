package ru.hartbro.core

import java.io.*
import java.nio.file.Files
import java.time.Instant
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test
import org.w3c.dom.Element
import ru.hartbro.testing.ControlSessionFixture

class ExcelExportTest {
    private val ns = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
    private fun archive(bytes: ByteArray): Map<String, ByteArray> = buildMap {
        ZipInputStream(ByteArrayInputStream(bytes)).use { input -> while (true) { val entry = input.nextEntry ?: break;put(entry.name, input.readBytes()) } }
    }
    private fun xml(bytes: ByteArray) = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true;setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    }.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
    private fun cells(bytes: ByteArray): Map<String, Element> {
        val list = xml(bytes).getElementsByTagNameNS(ns, "c")
        return (0 until list.length).map { list.item(it) as Element }.associateBy { it.getAttribute("r") }
    }
    private fun Element.value(): String = textContent
    private fun fixture(test: (ControlSessionFixture.Data, File) -> Unit) {
        val root = Files.createTempDirectory("hart031-real-control").toFile()
        try { test(ControlSessionFixture.load(root, javaClass.getResourceAsStream("/export/session-148d023e.csv")!!,
            javaClass.getResourceAsStream("/export/session-148d023e-events.tsv")!!), root) } finally { root.deleteRecursively() }
    }
    @Test fun realControl269MeasurementsBecome68ExactTimestampRowsWithoutLosingAnySlot() = fixture { data, _ ->
        val bytes = ByteArrayOutputStream().also { SessionXlsx.write(data.history, data.id, it) }.toByteArray()
        val parts = archive(bytes);val sheet = cells(parts.getValue("xl/worksheets/sheet1.xml"))
        assertEquals(1, xml(parts.getValue("xl/workbook.xml")).getElementsByTagNameNS(ns, "sheet").length)
        val table = xml(parts.getValue("xl/tables/table2.xml")).documentElement
        val ref = table.getAttribute("ref");val start = ref.substringBefore(':').drop(1).toInt();val end = ref.substringAfter(':').dropWhile { it.isLetter() }.toInt()
        assertEquals(68, end - start)
        var count = 0
        data.source.groupBy { it.receivedAt }.entries.forEachIndexed { i, (at, values) ->
            val row = start + 1 + i
            val timestamp = sheet.getValue("A$row");assertEquals("n", timestamp.getAttribute("t"));assertEquals("6", timestamp.getAttribute("s"))
            assertEquals(at.toEpochMilli(), Math.round((timestamp.value().toDouble() - 25569.0) * 86400000))
            MeasurementDecoder.slots.forEachIndexed { slotIndex, slot ->
                val cell = sheet["${('B'.code + slotIndex).toChar()}$row"];val source = values.firstOrNull { it.slot == slot }
                if (source == null) assertNull("Missing $slot must stay empty", cell)
                else {
                    assertNotNull(cell);assertEquals("n", cell!!.getAttribute("t"))
                    assertEquals(source.value.toRawBits(), cell.value().toFloat().toRawBits())
                    assertEquals(source.quality.name, sheet.getValue("${('H'.code + slotIndex).toChar()}$row").value())
                    assertEquals(source.command.toString(), sheet.getValue("${('L'.code + slotIndex).toChar()}$row").value())
                    assertEquals(source.unitCode.toString(), sheet.getValue("${('P'.code + slotIndex).toChar()}$row").value())
                    val variableCell = sheet["${('T'.code + slotIndex).toChar()}$row"]
                    if (source.variableCode == null) assertNull(variableCell) else assertEquals(source.variableCode.toString(), variableCell!!.value())
                    assertEquals("0", sheet.getValue("A${('B'.code + slotIndex).toChar()}$row").value());count++
                }
            }
        }
        assertEquals(269, count)
        assertEquals("PV (kPa)", sheet.getValue("B$start").value());assertEquals("TV (HART 240)", sheet.getValue("D$start").value())
        assertEquals("UNVERIFIED", sheet.getValue("H${start + 1}").value())
        assertEquals("1", sheet.getValue("L${start + 1}").value());assertEquals("3", sheet.getValue("L${start + 2}").value())
        val profile = exportProfile(data.history, data.id);assertEquals(269L, profile.sourceValues);assertEquals(269L, profile.exportedValues);assertEquals(68L, profile.rows)
    }
    @Test fun factoryFieldsAreNotFilledFromHartIdentityOrEditableHartDateAndBothTablesHaveFilters() = fixture { data, _ ->
        val bytes = ByteArrayOutputStream().also { SessionXlsx.write(data.history, data.id, it) }.toByteArray();val parts = archive(bytes)
        val sheet = cells(parts.getValue("xl/worksheets/sheet1.xml"))
        assertEquals("Нет данных", sheet.getValue("B6").value());assertEquals("Нет данных", sheet.getValue("C6").value())
        assertEquals("2662-BF2C34", sheet.getValue("F6").value());assertTrue(sheet.getValue("D6").value().contains("Rosemount"))
        assertEquals("0…10 кПа", sheet.getValue("E6").value())
        assertEquals("16 06 7A", data.history.info(data.id).configuration.parameters.getValue("deviceDateRaw").value)
        val register = deviceRegister(listOf(data.history.info(data.id))).single();assertNull(register.factorySerial);assertNull(register.manufacturedOn)
        assertEquals(1, xml(parts.getValue("xl/tables/table1.xml")).getElementsByTagNameNS(ns, "autoFilter").length)
        assertEquals(1, xml(parts.getValue("xl/tables/table2.xml")).getElementsByTagNameNS(ns, "autoFilter").length)
        assertEquals("frozen", (xml(parts.getValue("xl/worksheets/sheet1.xml")).getElementsByTagNameNS(ns, "pane").item(0) as Element).getAttribute("state"))
    }
    @Test fun sourceStatsKeepAllUnverifiedValuesWithoutCallingThemGood() = fixture { data, _ ->
        val stats = sessionStatistics(data.history, data.id)
        assertEquals(4, stats.variables.size);assertEquals(269L, stats.variables.sumOf { it.count })
        assertEquals(0L, stats.variables.sumOf { it.good });assertEquals(269L, stats.variables.sumOf { it.unverified })
        assertEquals(18L, stats.eventCount);assertEquals(3L, stats.gapCount)
    }
    @Test fun equalSvAndQvRemainSeparateAndConflictingSameTimestampValuesAreNeverOverwritten() = fixture { data, root ->
        val rows = mutableListOf<MeasurementRow>();measurementRows(data.history, data.id, rows::add)
        assertNull(rows.first().values["SV"]);assertEquals(68, rows.size)
        rows.drop(1).forEach { assertEquals(it.values.getValue("SV").value, it.values.getValue("QV").value, 0f);assertEquals(4, it.values.size) }
        val other = SessionHistory(File(root, "work2"), File(root, "saved2"))
        other.begin(metran02, "V", Instant.EPOCH);other.append(sample02(1, 1f));other.append(sample02(1, 2f));val id = other.snapshot()
        assertThrows(IllegalArgumentException::class.java) { SessionXlsx.write(other, id, ByteArrayOutputStream()) }
    }
    @Test fun badNumbersAndAbsentVariablesStayEmptyWithRawValueAndQualityPreserved() {
        val root = Files.createTempDirectory("hart031-numbers").toFile()
        try {
            val history = SessionHistory(File(root, "work"), File(root, "saved"))
            history.begin(metran02, "V", Instant.EPOCH)
            history.append(sample02(1, Float.NaN, Quality.BAD));history.append(sample02(2, Float.NaN, Quality.ABSENT));history.append(sample02(3, -12.345678f))
            val id = history.snapshot();val parts = archive(ByteArrayOutputStream().also { SessionXlsx.write(history, id, it) }.toByteArray())
            val table = xml(parts.getValue("xl/tables/table2.xml")).documentElement;val first = table.getAttribute("ref").substringBefore(':').drop(1).toInt() + 1
            val sheet = cells(parts.getValue("xl/worksheets/sheet1.xml"))
            assertNull(sheet["B$first"]);assertEquals("NaN", sheet.getValue("AF$first").value());assertEquals("BAD", sheet.getValue("H$first").value())
            assertNull(sheet["B${first + 1}"]);assertEquals("ABSENT", sheet.getValue("H${first + 1}").value())
            assertEquals(-12.345678f, sheet.getValue("B${first + 2}").value().toFloat(), 0f)
        } finally { root.deleteRecursively() }
    }
    @Test fun verySmallNumericValuesStayNumericWithGeneralFormatAndExactDuplicatesAreMerged() {
        val root = Files.createTempDirectory("hart031-small").toFile()
        try {
            val history = SessionHistory(File(root, "work"), File(root, "saved"))
            history.begin(metran02, "V", Instant.EPOCH)
            val batch = sample02(1, Float.MIN_VALUE);history.append(batch);history.append(batch)
            val id = history.snapshot();val parts = archive(ByteArrayOutputStream().also { SessionXlsx.write(history, id, it) }.toByteArray())
            val profile = exportProfile(history, id);assertEquals(2L, profile.sourceValues);assertEquals(1L, profile.rows);assertEquals(1L, profile.exportedValues)
            val styles = xml(parts.getValue("xl/styles.xml")).getElementsByTagNameNS(ns, "cellXfs").item(0) as Element
            val numberStyle = styles.getElementsByTagNameNS(ns, "xf").item(5) as Element
            assertEquals("0", numberStyle.getAttribute("numFmtId"))
            val table = xml(parts.getValue("xl/tables/table2.xml")).documentElement
            val first = table.getAttribute("ref").substringBefore(':').drop(1).toInt() + 1
            assertEquals(Float.MIN_VALUE.toRawBits(), cells(parts.getValue("xl/worksheets/sheet1.xml")).getValue("B$first").value().toFloat().toRawBits())
        } finally { root.deleteRecursively() }
    }
    @Test fun multipleDevicesGetSeparateRegistryRowsAndTextIsEscapedAsTextNotFormulas() {
        val root = Files.createTempDirectory("hart031-devices").toFile()
        try {
            val history = SessionHistory(File(root, "work"), File(root, "saved"))
            val ids = (1..2).map { device ->
                history.begin(metran02.copy(deviceId = device), "V", Instant.EPOCH,
                    ConfigurationSnapshot(mapOf("tag" to ConfigValue("=1+1 & <цех> \"А\"", 13))))
                history.append(sample02(1000));val id = history.snapshot(Instant.ofEpochMilli(2000));history.discard();id
            }
            val parts = archive(ByteArrayOutputStream().also { SessionXlsx.write(history, ids, it) }.toByteArray())
            val registry = xml(parts.getValue("xl/tables/table1.xml")).documentElement
            assertEquals("A5:F7", registry.getAttribute("ref"));val sheet = xml(parts.getValue("xl/worksheets/sheet1.xml"))
            assertEquals(0, sheet.getElementsByTagNameNS(ns, "f").length)
            assertTrue(sheet.documentElement.textContent.contains("=1+1 & <цех> \"А\""))
        } finally { root.deleteRecursively() }
    }
}
