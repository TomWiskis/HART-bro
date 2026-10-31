package ru.hartbro.app

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import ru.hartbro.app.reports.SessionPdf
import ru.hartbro.core.*
import ru.hartbro.testing.ControlSessionFixture
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

@RunWith(AndroidJUnit4::class)
class ReportExportTest {
    @Test fun pdfSingleVariableWithCyrillicMetadataRendersA4() = report(false, false, 20)
    @Test fun pdfDifferentUnitsAndRealGapsRenderA4WithoutInventedSamples() = report(true, true, 50)
    @Test fun longSessionPdfAndCsvKeepAllSourceMeasurements() = report(true, true, 10000)
    @Test fun realControlExcelHas68TimestampRowsAnd269NumericValues() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val root = File(context.cacheDir, "excel-control-test").apply { deleteRecursively();mkdirs() }
        try {
            val data = ControlSessionFixture.load(root, assets.open("session-148d023e.csv"), assets.open("session-148d023e-events.tsv"))
            val review = context.getExternalFilesDir("export-review")!!.apply { mkdirs() }
            val xlsx = File(review, "hart-${data.id}.xlsx");xlsx.outputStream().use { SessionXlsx.write(data.history, data.id, it) }
            val ns = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
            ZipFile(xlsx).use { zip ->
                val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
                val sheet = zip.getInputStream(zip.getEntry("xl/worksheets/sheet1.xml")).use { factory.newDocumentBuilder().parse(it) }
                val nodes = sheet.getElementsByTagNameNS(ns, "c")
                var numericValues = 0;var dates = 0
                repeat(nodes.length) { i ->
                    val cell = nodes.item(i) as org.w3c.dom.Element
                    if (cell.getAttribute("s") == "6") dates++
                    if (cell.getAttribute("s") == "5" && cell.getAttribute("r").first() in 'B'..'E') numericValues++
                }
                assertEquals(68, dates);assertEquals(269, numericValues)
            }
            assertEquals(269L, exportProfile(data.history, data.id).exportedValues)
        } finally { root.deleteRecursively() }
    }
    @Test fun realControlPdfGroupsAllSectionsIntoTablesWithReadableGraphAndNoInventedFactoryData() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val root = File(context.cacheDir, "pdf-control-test").apply { deleteRecursively();mkdirs() }
        try {
            val data = ControlSessionFixture.load(root, assets.open("session-148d023e.csv"), assets.open("session-148d023e-events.tsv"))
            val review = context.getExternalFilesDir("export-review")!!.apply { mkdirs() }
            val file = File(review, "hart-${data.id}.pdf");file.outputStream().use { SessionPdf.write(data.history, data.id, it) }
            val text = StringBuilder()
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor -> PdfRenderer(descriptor).use { pdf ->
                repeat(pdf.pageCount) { index -> pdf.openPage(index).use { page ->
                    text.append(page.textContents.joinToString(" ") { it.text }).append('\n')
                    val bitmap = Bitmap.createBitmap(1190, 1684, Bitmap.Config.ARGB_8888)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                    File(review, "control-page-${index + 1}.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 90, it) };bitmap.recycle()
                    assertEquals(595, page.width);assertEquals(842, page.height)
                } }
            } }
            listOf("Прибор", "Параметр", "Значение", "Измерения", "PV", "SV", "TV", "QV", "Конфигурация", "Единица", "Команда HART", "События", "Категория", "Описание", "Заводской серийный номер", "Нет данных", "UNVERIFIED", "HART Device ID", "2662-BF2C34", "Y1", "Y2", "Y3").forEach {
                assertTrue("PDF is missing expected table/label: $it", text.contains(it))
            }
            val stats = sessionStatistics(data.history, data.id)
            assertEquals(269L, stats.variables.sumOf { it.count });assertEquals(0L, stats.variables.sumOf { it.good })
        } finally { root.deleteRecursively() }
    }
    private fun report(multiple: Boolean, gaps: Boolean, count: Int) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "report-test-${System.nanoTime()}").apply { mkdirs() }
        try {
            val history = SessionHistory(File(root, "working"), File(root, "archive"))
            val identity = DeviceIdentity.parse("FE 99 72 05 07 07 28 08 00 5F CA 7F 05 09 00 2C 00 00 99 00 99 01".split(" ").map { it.toInt(16).toByte() }.toByteArray())
            history.begin(identity, "VIATOR", Instant.EPOCH)
            val stepSeconds = if (count >= 10000) 10L else 1L // long case spans more than one UTC day
            repeat(count) { i ->
                val now = Instant.ofEpochSecond(i * stepSeconds)
                if (gaps && i == count / 2) history.event(now, "Потеря HART; проверочный разрыв", true)
                val values = listOf(Measurement("PV", -5.123456f + i, 12, if (i == count / 2 + 1) Quality.BAD else Quality.GOOD, now, 9)) +
                    if (multiple) listOf(Measurement("SV", 20f + i / 100f, 32, Quality.GOOD, now, 9)) else emptyList()
                history.append(MeasurementBatch(values, 0, now))
            }
            val id = history.snapshot(Instant.ofEpochSecond(count * stepSeconds))
            history.edit(id, "Метран — проверка цеха №1", "Кириллица и комментарий\nвторая строка")
            val file = File(root, "report.pdf")
            file.outputStream().use { SessionPdf.write(history, id, it) }
            assertTrue(file.length() > 3000)
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use { pdf ->
                    assertTrue(pdf.pageCount >= 2)
                    val documentText = StringBuilder()
                    repeat(pdf.pageCount) { index -> pdf.openPage(index).use { page ->
                        assertEquals(595, page.width); assertEquals(842, page.height)
                        val text = page.textContents.joinToString(" ") { it.text };documentText.append(text)
                        if (index == 0) assertTrue("PDF Cyrillic title must remain readable/extractable", text.contains("Метран"))
                        val bitmap = Bitmap.createBitmap(595, 842, Bitmap.Config.ARGB_8888)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                        val pixels = IntArray(595 * 842); bitmap.getPixels(pixels, 0, 595, 0, 0, 595, 842)
                        assertTrue("Rendered PDF page has no visible content", pixels.count { (it and 0x00FFFFFF) != 0x00FFFFFF && (it ushr 24) != 0 } > 100)
                        bitmap.recycle()
                    } }
                    if (count >= 10000) assertTrue("Multi-day chart needs dates on its UTC axis", documentText.contains("01.01") || documentText.contains("02.01"))
                }
            }
            val csv = File(root, "report.csv"); csv.outputStream().use { SessionCsv.write(history, id, it) }
            assertEquals(count * (if (multiple) 2 else 1) + 2, csv.useLines { it.count() })
            assertEquals(count.toLong() - 1, sessionStatistics(history, id).variables.first().count)
        } finally { root.deleteRecursively() }
    }
}
