package ru.hartbro.core

import java.io.OutputStream
import java.io.OutputStreamWriter
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** A streaming, macro-free OOXML workbook. No Android/Office dependency and no sheet-sized XML buffers. */
object SessionXlsx {
    const val MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    fun write(history: SessionHistory, id: String, output: OutputStream) = write(history, listOf(id), output)
    fun write(history: SessionHistory, ids: List<String>, output: OutputStream) {
        require(ids.isNotEmpty()) { "Выберите сохранённую проверку" }
        val sources = ids.distinct().map { id -> Triple(id, history.info(id), exportProfile(history, id)) }
        // Multiple-device export is ready for a future walk mode. Overlapping snapshots must never duplicate readings.
        sources.groupBy { it.second.manufacturer to it.second.deviceKey }.values.forEach { group ->
            group.sortedBy { it.second.startedAt }.zipWithNext().forEach { (a, b) ->
                require(a.second.endedAt < b.second.startedAt) { "Выбраны перекрывающиеся снимки одного прибора. Экспортируйте один полный снимок." }
            }
        }
        val devices = deviceRegister(sources.map { it.second })
        val units = linkedMapOf<String, Set<Int>>()
        MeasurementDecoder.slots.forEach { slot -> units[slot] = sources.flatMap { it.third.units[slot].orEmpty() }.toSortedSet() }
        val registryHeader = 5; val registryEnd = registryHeader + devices.size
        val historyHeader = registryEnd + 5
        val rowCount = sources.sumOf { it.third.rows }
        val historyEnd = historyHeader + rowCount.coerceAtLeast(1).toInt()
        require(rowCount <= 1_048_576L - historyHeader) { "Превышен предел строк Excel. Экспортируйте проверки отдельно или сохраните технический CSV." }
        val headers = listOf("Время (UTC)") + MeasurementDecoder.slots.map { slot ->
            val labels = units.getValue(slot).joinToString(" / ") { HartUnits.find(it)?.symbol ?: "HART $it" }
            "$slot (${labels.ifEmpty { "единица не получена" }})"
        } + listOf("HART Device ID", "ID проверки") +
            MeasurementDecoder.slots.map { "$it — качество" } + MeasurementDecoder.slots.map { "$it — команда HART" } +
            MeasurementDecoder.slots.map { "$it — код единицы HART" } + MeasurementDecoder.slots.map { "$it — код переменной HART" } + MeasurementDecoder.slots.map { "$it — исходный статус переменной" } +
            MeasurementDecoder.slots.map { "$it — статус прибора" } + MeasurementDecoder.slots.map { "$it — исходное нечисловое значение" }
        val lastColumn = column(headers.lastIndex)
        val zip = ZipOutputStream(object : java.io.FilterOutputStream(output) { override fun close() { flush() } })
        try {
        fun part(name: String, action: (java.io.Writer) -> Unit) {
            zip.putNextEntry(ZipEntry(name).apply { time = 0 })
            val writer = OutputStreamWriter(zip, Charsets.UTF_8); action(writer); writer.flush(); zip.closeEntry()
        }
        fun xml(name: String, value: String) = part(name) { it.write(value) }
        xml("[Content_Types].xml", """<?xml version="1.0" encoding="UTF-8"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/><Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/><Override PartName="/xl/tables/table1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.table+xml"/><Override PartName="/xl/tables/table2.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.table+xml"/></Types>""")
        xml("_rels/.rels", """<?xml version="1.0"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>""")
        xml("xl/workbook.xml", """<?xml version="1.0" encoding="UTF-8"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><workbookPr date1904="0"/><bookViews><workbookView/></bookViews><sheets><sheet name="Проверка" sheetId="1" r:id="rId1"/></sheets></workbook>""")
        xml("xl/_rels/workbook.xml.rels", """<?xml version="1.0"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/><Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>""")
        xml("xl/styles.xml", STYLES)
        part("xl/worksheets/sheet1.xml") { writer ->
            fun textCell(c: Int, row: Int, text: String, style: Int = 4) {
                writer.write("<c r=\"${column(c)}$row\" s=\"$style\" t=\"inlineStr\"><is><t xml:space=\"preserve\">${escape(text)}</t></is></c>")
            }
            fun numberCell(c: Int, row: Int, number: String, style: Int = 5) {
                writer.write("<c r=\"${column(c)}$row\" s=\"$style\" t=\"n\"><v>$number</v></c>")
            }
            fun label(row: Int, text: String, style: Int = 2, height: Int = 25) { writer.write("<row r=\"$row\" ht=\"$height\" customHeight=\"1\">"); textCell(0, row, text, style); writer.write("</row>") }
            val frozenRows = if (devices.size <= 2) historyHeader else 3
            writer.write("""<?xml version="1.0" encoding="UTF-8"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><dimension ref="A1:$lastColumn$historyEnd"/><sheetViews><sheetView workbookViewId="0"><pane ySplit="$frozenRows" topLeftCell="A${frozenRows + 1}" activePane="bottomLeft" state="frozen"/><selection pane="bottomLeft" activeCell="A${historyHeader + 1}" sqref="A${historyHeader + 1}"/></sheetView></sheetViews><sheetFormatPr defaultRowHeight="20"/><cols><col min="1" max="1" width="27" customWidth="1"/><col min="2" max="5" width="20" customWidth="1"/><col min="6" max="7" width="27" customWidth="1"/><col min="8" max="11" width="21" customWidth="1"/><col min="12" max="35" width="18" customWidth="1"/></cols><sheetData>""")
            label(1, "HART-бро — ведомость и история измерений", 1, 30)
            label(2, sources.map { it.second.title.ifBlank { "Проверка ${it.second.deviceKey}" } }.distinct().joinToString("; "), 0, 25)
            label(3, "Время — UTC; числа и даты — ячейки Excel. Статусы качества и команды приведены отдельно.", 0, 30)
            label(4, "1. Сводная ведомость приборов")
            val registryColumns = listOf("TAG", "Заводской серийный номер", "Дата выпуска", "Тип / марка прибора", "Настроенный диапазон", "HART Device ID")
            writer.write("<row r=\"$registryHeader\" ht=\"36\" customHeight=\"1\">");registryColumns.forEachIndexed { c, name -> textCell(c, registryHeader, name, 3) };writer.write("</row>")
            devices.forEachIndexed { i, device ->
                val row = registryHeader + i + 1; writer.write("<row r=\"$row\" ht=\"42\" customHeight=\"1\">")
                listOf(device.tag ?: "Нет данных", device.factorySerial ?: "Нет данных", device.manufacturedOn?.toString() ?: "Нет данных", device.model,
                    device.configuredRange ?: "Диапазон неизвестен", device.hartDeviceId).forEachIndexed { c, value -> textCell(c, row, value) };writer.write("</row>")
            }
            label(registryEnd + 2, "Заводской номер и дата изготовления не получены. HART Device ID и редактируемая HART Date не заменяют эти сведения.", 0, 42)
            label(historyHeader - 1, "2. История измерений — $rowCount временных строк, ${sources.sumOf { it.third.exportedValues }} исходных значений")
            writer.write("<row r=\"$historyHeader\" ht=\"48\" customHeight=\"1\">");headers.forEachIndexed { c, value -> textCell(c, historyHeader, value, 3) };writer.write("</row>")
            var rowNumber = historyHeader
            sources.forEach { (id, info, _) -> measurementRows(history, id) { row ->
                rowNumber++; writer.write("<row r=\"$rowNumber\">")
                numberCell(0, rowNumber, excelTime(row.at).toString(), 6)
                MeasurementDecoder.slots.forEachIndexed { i, slot -> row.values[slot]?.let { v ->
                    if (v.value.isFinite() && v.quality != Quality.ABSENT) numberCell(i + 1, rowNumber, v.value.toString())
                } }
                textCell(5, rowNumber, info.deviceKey);textCell(6, rowNumber, id)
                MeasurementDecoder.slots.forEachIndexed { i, slot -> row.values[slot]?.let { textCell(i + 7, rowNumber, it.quality.name) } }
                MeasurementDecoder.slots.forEachIndexed { i, slot -> row.values[slot]?.let { numberCell(i + 11, rowNumber, it.command.toString(), 7) } }
                MeasurementDecoder.slots.forEachIndexed { i, slot -> row.values[slot]?.let { numberCell(i + 15, rowNumber, it.unitCode.toString(), 7) } }
                MeasurementDecoder.slots.forEachIndexed { i, slot -> row.values[slot]?.variableCode?.let { numberCell(i + 19, rowNumber, it.toString(), 7) } }
                MeasurementDecoder.slots.forEachIndexed { i, slot -> row.values[slot]?.rawStatus?.let { numberCell(i + 23, rowNumber, it.toString(), 7) } }
                MeasurementDecoder.slots.forEachIndexed { i, slot -> row.deviceStatuses[slot]?.let { numberCell(i + 27, rowNumber, it.toString(), 7) } }
                MeasurementDecoder.slots.forEachIndexed { i, slot -> row.values[slot]?.let { v ->
                    if (!v.value.isFinite() || v.quality == Quality.ABSENT) textCell(i + 31, rowNumber, v.value.toString())
                } }
                writer.write("</row>")
            } }
            if (rowCount == 0L) writer.write("<row r=\"${historyHeader + 1}\"/>")
            writer.write("</sheetData><mergeCells count=\"5\"><mergeCell ref=\"A1:F1\"/><mergeCell ref=\"A2:F2\"/><mergeCell ref=\"A3:F3\"/><mergeCell ref=\"A4:F4\"/><mergeCell ref=\"A${registryEnd + 2}:F${registryEnd + 2}\"/></mergeCells><pageMargins left=\"0.25\" right=\"0.25\" top=\"0.5\" bottom=\"0.5\" header=\"0.2\" footer=\"0.2\"/><tableParts count=\"2\"><tablePart r:id=\"rId1\"/><tablePart r:id=\"rId2\"/></tableParts></worksheet>")
        }
        xml("xl/worksheets/_rels/sheet1.xml.rels", """<?xml version="1.0"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/table" Target="../tables/table1.xml"/><Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/table" Target="../tables/table2.xml"/></Relationships>""")
        fun table(id: Int, name: String, ref: String, columns: List<String>) = xml("xl/tables/table$id.xml",
            """<?xml version="1.0" encoding="UTF-8"?><table xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" id="$id" name="$name" displayName="$name" ref="$ref" totalsRowShown="0"><autoFilter ref="$ref"/><tableColumns count="${columns.size}">${columns.mapIndexed { i, value -> "<tableColumn id=\"${i + 1}\" name=\"${escape(value)}\"/>" }.joinToString("")}</tableColumns><tableStyleInfo name="TableStyleMedium2" showFirstColumn="0" showLastColumn="0" showRowStripes="1" showColumnStripes="0"/></table>""")
        table(1, "Devices", "A$registryHeader:F$registryEnd", listOf("TAG", "Заводской серийный номер", "Дата выпуска", "Тип / марка прибора", "Настроенный диапазон", "HART Device ID"))
        table(2, "Measurements", "A$historyHeader:$lastColumn$historyEnd", headers)
        zip.finish(); zip.flush() // Caller owns the selected document stream.
        } finally { zip.close() }
    }
    fun excelTime(instant: Instant): Double = instant.toEpochMilli() / 86_400_000.0 + 25569.0
    private fun column(index: Int): String { var n = index + 1; var result = ""; while (n > 0) { n--; result = ('A'.code + n % 26).toChar() + result; n /= 26 };return result }
    private fun escape(value: String): String = buildString { value.forEach { c -> when(c) {
        '&' -> append("&amp;"); '<' -> append("&lt;"); '>' -> append("&gt;"); '"' -> append("&quot;"); '\'' -> append("&apos;")
        else -> if (c == '\t' || c == '\n' || c == '\r' || c >= ' ') append(c)
    } } }
    private val STYLES = """<?xml version="1.0" encoding="UTF-8"?><styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><numFmts count="2"><numFmt numFmtId="164" formatCode="0.###############"/><numFmt numFmtId="165" formatCode="dd.mm.yyyy hh:mm:ss.000"/></numFmts><fonts count="3"><font><sz val="11"/><name val="Calibri"/></font><font><b/><sz val="18"/><name val="Calibri"/></font><font><b/><color rgb="FFFFFFFF"/><sz val="11"/><name val="Calibri"/></font></fonts><fills count="4"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill><fill><patternFill patternType="solid"><fgColor rgb="FF17365D"/><bgColor indexed="64"/></patternFill></fill><fill><patternFill patternType="solid"><fgColor rgb="FFDCE6F1"/><bgColor indexed="64"/></patternFill></fill></fills><borders count="2"><border/><border><left style="thin"><color rgb="FFD0D8E0"/></left><right style="thin"><color rgb="FFD0D8E0"/></right><top style="thin"><color rgb="FFD0D8E0"/></top><bottom style="thin"><color rgb="FFD0D8E0"/></bottom></border></borders><cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs><cellXfs count="8"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"><alignment vertical="center" wrapText="1"/></xf><xf numFmtId="0" fontId="1" fillId="0" borderId="0"/><xf numFmtId="0" fontId="0" fillId="3" borderId="0"><alignment vertical="center"/></xf><xf numFmtId="0" fontId="2" fillId="2" borderId="1"><alignment vertical="center" wrapText="1"/></xf><xf numFmtId="0" fontId="0" fillId="0" borderId="1"><alignment vertical="top" wrapText="1"/></xf><xf numFmtId="0" fontId="0" fillId="0" borderId="1" applyNumberFormat="1"/><xf numFmtId="165" fontId="0" fillId="0" borderId="1" applyNumberFormat="1"/><xf numFmtId="0" fontId="0" fillId="0" borderId="1"/></cellXfs><cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles></styleSheet>"""
}
