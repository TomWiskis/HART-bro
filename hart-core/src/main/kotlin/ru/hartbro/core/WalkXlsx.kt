package ru.hartbro.core

import java.io.OutputStream
import ru.hartbro.core.XlsxPackage.Companion.escape
import ru.hartbro.core.XlsxPackage.Companion.column

/** Local passport register only; measurement session export remains SessionXlsx. */
object WalkXlsx {
    val headers = listOf("TAG", "Серийный номер", "Дата выпуска", "Тип / марка датчика", "Диапазон")
    fun write(book: WalkBook, output: OutputStream) {
        require(book.rows.size <= 10000)
        XlsxPackage(output, "Приборы", 1).use { workbook ->
            val end = book.rows.size + 1
            workbook.part("xl/worksheets/sheet1.xml") { writer ->
                writer.write("""<?xml version="1.0" encoding="UTF-8"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><dimension ref="A1:E$end"/><sheetViews><sheetView workbookViewId="0"><pane ySplit="1" topLeftCell="A2" activePane="bottomLeft" state="frozen"/></sheetView></sheetViews><sheetFormatPr defaultRowHeight="30"/><cols><col min="1" max="1" width="24" customWidth="1"/><col min="2" max="2" width="24" customWidth="1"/><col min="3" max="3" width="18" customWidth="1"/><col min="4" max="4" width="40" customWidth="1"/><col min="5" max="5" width="25" customWidth="1"/></cols><sheetData>""")
                fun row(number: Int, values: List<String>, style: Int) {
                    writer.write("<row r=\"$number\" ht=\"30\" customHeight=\"1\">")
                    values.forEachIndexed { i, text -> if (text.isNotEmpty()) writer.write("<c r=\"${column(i)}$number\" s=\"$style\" t=\"inlineStr\"><is><t xml:space=\"preserve\">${escape(text)}</t></is></c>") }
                    writer.write("</row>")
                }
                row(1, headers, 3)
                book.rows.forEachIndexed { i, device -> row(i + 2, PassportField.entries.map { if(it==PassportField.SERIAL) (device.observed.identity.deviceId.toLong() and 0xFFFFFF).toString() else device.text(it).orEmpty() }, 4) }
                writer.write("""</sheetData><pageMargins left="0.25" right="0.25" top="0.5" bottom="0.5" header="0.2" footer="0.2"/><tableParts count="1"><tablePart r:id="rId1"/></tableParts></worksheet>""")
            }
            workbook.tables(listOf(Triple("WalkDevices", "A1:E$end", headers)))
        }
    }
}
