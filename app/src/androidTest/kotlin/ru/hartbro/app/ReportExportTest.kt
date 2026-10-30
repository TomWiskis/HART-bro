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

@RunWith(AndroidJUnit4::class)
class ReportExportTest {
    @Test fun pdfSingleVariableWithCyrillicMetadataRendersA4() = report(false, false, 20)
    @Test fun pdfDifferentUnitsAndRealGapsRenderA4WithoutInventedSamples() = report(true, true, 50)
    @Test fun longSessionPdfAndCsvKeepAllSourceMeasurements() = report(true, true, 10000)
    private fun report(multiple: Boolean, gaps: Boolean, count: Int) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "report-test-${System.nanoTime()}").apply { mkdirs() }
        try {
            val history = SessionHistory(File(root, "working"), File(root, "archive"))
            val identity = DeviceIdentity.parse("FE 99 72 05 07 07 28 08 00 5F CA 7F 05 09 00 2C 00 00 99 00 99 01".split(" ").map { it.toInt(16).toByte() }.toByteArray())
            history.begin(identity, "VIATOR", Instant.EPOCH)
            repeat(count) { i ->
                val now = Instant.ofEpochSecond(i.toLong())
                if (gaps && i == count / 2) history.event(now, "Потеря HART; проверочный разрыв", true)
                val values = listOf(Measurement("PV", -5.123456f + i, 12, if (i == count / 2 + 1) Quality.BAD else Quality.GOOD, now, 9)) +
                    if (multiple) listOf(Measurement("SV", 20f + i / 100f, 32, Quality.GOOD, now, 9)) else emptyList()
                history.append(MeasurementBatch(values, 0, now))
            }
            val id = history.snapshot(Instant.ofEpochSecond(count.toLong()))
            history.edit(id, "Метран — проверка цеха №1", "Кириллица и комментарий\nвторая строка")
            val file = File(root, "report.pdf")
            file.outputStream().use { SessionPdf.write(history, id, it) }
            assertTrue(file.length() > 3000)
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use { pdf ->
                    assertTrue(pdf.pageCount >= 2)
                    repeat(pdf.pageCount) { index -> pdf.openPage(index).use { page ->
                        assertEquals(595, page.width); assertEquals(842, page.height)
                        val bitmap = Bitmap.createBitmap(595, 842, Bitmap.Config.ARGB_8888)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                        val pixels = IntArray(595 * 842); bitmap.getPixels(pixels, 0, 595, 0, 0, 595, 842)
                        assertTrue("Rendered PDF page has no visible content", pixels.count { (it and 0x00FFFFFF) != 0x00FFFFFF && (it ushr 24) != 0 } > 100)
                        bitmap.recycle()
                    } }
                }
            }
            val csv = File(root, "report.csv"); csv.outputStream().use { SessionCsv.write(history, id, it) }
            assertEquals(count * (if (multiple) 2 else 1) + 1, csv.useLines { it.count() })
            assertEquals(count.toLong() - 1, sessionStatistics(history, id).variables.first().count)
        } finally { root.deleteRecursively() }
    }
}
