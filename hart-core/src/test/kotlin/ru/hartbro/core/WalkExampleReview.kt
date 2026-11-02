package ru.hartbro.core

import java.io.File
import java.time.Instant

/** Synthetic examples only; these rows are not captured industrial device passports. */
object WalkExampleReview {
    @JvmStatic fun main(args:Array<String>) {
        val directory=File(args.single()).apply{mkdirs()};val catalog=WalkCatalog(WalkBook(startedAt=Instant.parse("2026-10-31T00:00:00Z"),title="ТЕСТОВЫЙ обход — 100 искусственных приборов"))
        repeat(100) { index ->
            val range=when(index%3) {0->ConfiguredRange(-50f,150f,32);1->ConfiguredRange(0f,2.5f,237);else->ConfiguredRange(0f,40f,12)}
            val row=catalog.ingest(reading040(index+1,range)).rowId!!
            catalog.edit(row,mapOf(PassportField.TAG to if(index%10==0) "" else "ТЕСТ-%03d".format(index+1),
                PassportField.MODEL to "ТЕСТОВАЯ марка ${index%3+1}", PassportField.SERIAL to if(index%7==0) "" else "00%04d".format(index),
                PassportField.DATE to listOf("15.07.2025","07.2025","2025","")[index%4]))
        }
        val book=catalog.snapshot()
        File(directory,"TEST-ONLY-walk-100-devices.xlsx").outputStream().use{WalkXlsx.write(book,it)}
        File(directory,"TEST-ONLY-walk-100-devices.expected.tsv").bufferedWriter().use { writer ->
            writer.appendLine(WalkXlsx.headers.joinToString("\t"));book.rows.forEach { row ->writer.appendLine(PassportField.entries.joinToString("\t"){if(it==PassportField.SERIAL)row.observed.identity.deviceId.toString() else row.text(it).orEmpty()}) }
        }
        println("Synthetic walk workbook and 100-row comparison data written to ${directory.absolutePath}")
    }
}
