package ru.hartbro.core

import java.io.*
import java.util.zip.*

/** Common OOXML container for session reports and the independent five-column walk register. */
internal class XlsxPackage(output: OutputStream, sheetName: String, tableCount: Int) : AutoCloseable {
    private val zip = ZipOutputStream(object : FilterOutputStream(output) { override fun close() { flush() } })
    init {
        xml("[Content_Types].xml", """<?xml version="1.0" encoding="UTF-8"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/><Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>${(1..tableCount).joinToString("") { "<Override PartName=\"/xl/tables/table$it.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.table+xml\"/>" }}</Types>""")
        xml("_rels/.rels", """<?xml version="1.0"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>""")
        xml("xl/workbook.xml", """<?xml version="1.0" encoding="UTF-8"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><workbookPr date1904="0"/><bookViews><workbookView/></bookViews><sheets><sheet name="${escape(sheetName)}" sheetId="1" r:id="rId1"/></sheets></workbook>""")
        xml("xl/_rels/workbook.xml.rels", """<?xml version="1.0"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/><Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>""")
        xml("xl/styles.xml", STYLES)
    }
    fun part(name: String, action: (Writer) -> Unit) {
        zip.putNextEntry(ZipEntry(name).apply { time = 0 })
        val writer = OutputStreamWriter(zip, Charsets.UTF_8); action(writer);writer.flush();zip.closeEntry()
    }
    fun xml(name: String, value: String) = part(name) { it.write(value) }
    fun tables(tables: List<Triple<String, String, List<String>>>) {
        xml("xl/worksheets/_rels/sheet1.xml.rels", """<?xml version="1.0"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">${tables.indices.joinToString("") { "<Relationship Id=\"rId${it+1}\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/table\" Target=\"../tables/table${it+1}.xml\"/>" }}</Relationships>""")
        tables.forEachIndexed { index, (name, ref, columns) ->
            val id = index + 1
            xml("xl/tables/table$id.xml", """<?xml version="1.0" encoding="UTF-8"?><table xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" id="$id" name="$name" displayName="$name" ref="$ref" totalsRowShown="0"><autoFilter ref="$ref"/><tableColumns count="${columns.size}">${columns.mapIndexed { i, value -> "<tableColumn id=\"${i+1}\" name=\"${escape(value)}\"/>" }.joinToString("")}</tableColumns><tableStyleInfo name="TableStyleMedium2" showFirstColumn="0" showLastColumn="0" showRowStripes="1" showColumnStripes="0"/></table>""")
        }
    }
    override fun close() { try { zip.finish();zip.flush() } finally { zip.close() } }
    companion object {
        fun column(index: Int): String { var n = index+1;var result = "";while(n>0){n--;result=('A'.code+n%26).toChar()+result;n/=26};return result }
        fun escape(value: String): String = buildString { value.forEach { c -> when(c) {
            '&' -> append("&amp;");'<' -> append("&lt;");'>' -> append("&gt;");'"' -> append("&quot;");'\'' -> append("&apos;")
            else -> if(c=='\t'||c=='\n'||c=='\r'||c>=' ') append(c)
        } } }
    private val STYLES = """<?xml version="1.0" encoding="UTF-8"?><styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><numFmts count="2"><numFmt numFmtId="164" formatCode="0.###############"/><numFmt numFmtId="165" formatCode="dd.mm.yyyy hh:mm:ss.000"/></numFmts><fonts count="3"><font><sz val="11"/><name val="Calibri"/></font><font><b/><sz val="18"/><name val="Calibri"/></font><font><b/><color rgb="FFFFFFFF"/><sz val="11"/><name val="Calibri"/></font></fonts><fills count="4"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill><fill><patternFill patternType="solid"><fgColor rgb="FF17365D"/><bgColor indexed="64"/></patternFill></fill><fill><patternFill patternType="solid"><fgColor rgb="FFDCE6F1"/><bgColor indexed="64"/></patternFill></fill></fills><borders count="2"><border/><border><left style="thin"><color rgb="FFD0D8E0"/></left><right style="thin"><color rgb="FFD0D8E0"/></right><top style="thin"><color rgb="FFD0D8E0"/></top><bottom style="thin"><color rgb="FFD0D8E0"/></bottom></border></borders><cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs><cellXfs count="8"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"><alignment vertical="center" wrapText="1"/></xf><xf numFmtId="0" fontId="1" fillId="0" borderId="0"/><xf numFmtId="0" fontId="0" fillId="3" borderId="0"><alignment vertical="center"/></xf><xf numFmtId="0" fontId="2" fillId="2" borderId="1"><alignment vertical="center" wrapText="1"/></xf><xf numFmtId="0" fontId="0" fillId="0" borderId="1"><alignment vertical="top" wrapText="1"/></xf><xf numFmtId="0" fontId="0" fillId="0" borderId="1" applyNumberFormat="1"/><xf numFmtId="165" fontId="0" fillId="0" borderId="1" applyNumberFormat="1"/><xf numFmtId="0" fontId="0" fillId="0" borderId="1"/></cellXfs><cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles></styleSheet>"""
    }
}
