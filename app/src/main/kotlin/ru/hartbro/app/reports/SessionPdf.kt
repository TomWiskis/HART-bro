package ru.hartbro.app.reports

import android.graphics.*
import android.graphics.pdf.PdfDocument
import java.io.OutputStream
import java.time.Instant
import java.time.ZoneOffset
import ru.hartbro.core.*

/** Local A4 tables and a shared chart. Source history, statuses and polling are never modified. */
object SessionPdf {
    private val colors = intArrayOf(Color.rgb(25, 90, 175), Color.rgb(180, 65, 15), Color.rgb(0, 110, 90), Color.rgb(120, 35, 150))
    fun write(history: SessionHistory, id: String, output: OutputStream) {
        val info = history.info(id); val stats = sessionStatistics(history, id)
        val pdf = PdfDocument(); val report = Pages(pdf)
        try {
            report.heading("HART-бро — отчёт о проверке")
            report.line(info.title.ifBlank { "Проверка ${info.deviceKey}" }, 12f)
            report.line("ID проверки: $id", 9f)
            report.line("${timestamp(info.startedAt)} — ${timestamp(info.endedAt)} · ${info.durationMillis / 1000} с · UTC", 9f)
            report.heading("Прибор")
            val register = deviceRegister(listOf(info)).single()
            val hart = info.configuration.parameters["hartRevision"]?.value ?: runCatching {
                DeviceIdentity.parse(info.identityHex.split(' ').filter { it.isNotEmpty() }.map { it.toInt(16).toByte() }.toByteArray()).hartRevision.toString()
            }.getOrNull()
            report.table(listOf("Параметр", "Значение"), listOf(155f, 368f), listOf(
                listOf("TAG", register.tag ?: "Нет данных"), listOf("Заводской серийный номер", register.factorySerial ?: "Нет данных"),
                listOf("Дата выпуска", register.manufacturedOn?.toString() ?: "Нет данных"), listOf("Тип / марка", register.model),
                listOf("Прибор", info.deviceTitle), listOf("Производитель по HART", info.manufacturer), listOf("HART Device ID", info.deviceKey), listOf("Версия HART", hart ?: "Нет данных"),
                listOf("Настроенный диапазон", register.configuredRange ?: "Диапазон неизвестен"), listOf("Коммуникатор", info.communicator)
            ).asSequence())
            report.line("HART Device ID не является заводским номером; редактируемая HART Date не подтверждает дату изготовления.", 9f)
            report.heading("Измерения")
            val bySlot = stats.variables.groupBy { it.slot }
            fun cells(select: (VariableStatistics) -> String): List<String> = MeasurementDecoder.slots.map { slot ->
                val values = bySlot[slot].orEmpty()
                if (values.isEmpty()) "Нет данных" else values.joinToString("\n") { s ->
                    (if (values.size > 1) "${s.unit}: " else "") + select(s)
                }
            }
            report.table(listOf("Показатель", "PV", "SV", "TV", "QV"), listOf(155f, 92f, 92f, 92f, 92f), listOf(
                listOf("Единица (код HART)") + cells { "${it.unit} (${it.unitCode})" },
                listOf("Минимум принятых*") + cells { if (it.count > 0) it.min.toString() else "Нет данных" },
                listOf("Максимум принятых*") + cells { if (it.count > 0) it.max.toString() else "Нет данных" },
                listOf("Среднее принятых*") + cells { if (it.count > 0) String.format(java.util.Locale.US, "%.9g", it.mean) else "Нет данных" },
                listOf("Количество принятых*") + cells { it.count.toString() },
                listOf("Подтверждённое качество GOOD") + cells { it.good.toString() },
                listOf("Индивидуальное качество не передано: UNVERIFIED") + cells { it.unverified.toString() },
                listOf("Исключено из статистики") + cells { it.rejected.toString() }
            ).asSequence(), 8.5f)
            report.line("* Статистика исходных конечных GOOD и UNVERIFIED сохранена как в 0.3.0. Среднее показано с 9 значащими цифрами. UNVERIFIED не подтверждает индивидуальное качество. BAD/UNCERTAIN/FIXED/ABSENT исключены; их исходные значения и статусы доступны в Excel/CSV.", 9f)
            report.line("Зарегистрировано разрывов: ${stats.gapCount}; без принятых данных ${stats.noDataMillis / 1000.0} с. Событий: ${stats.eventCount}.", 9f)
            report.newPage(); report.heading("Единый график измерений")
            report.line("UTC. Одинаковые единицы используют одну шкалу, разные — независимые шкалы Y. Реальные разрывы сохранены. На графике сокращение с сохранением экстремумов; статистика и Excel/CSV используют все исходные измерения.", 9f)
            graph(report, history.savedView(id, 300))
            report.heading("Конфигурация")
            report.line("Частичный снимок доступных параметров, только чтение.", 9f)
            val configRows = info.configuration.parameters.map { (key, value) ->
                val unit = value.unitCode?.let { "${HartUnits.find(it)?.symbol ?: "HART $it"} (код $it)" } ?: if (key == "damping") "с" else "—"
                listOf(parameterName(key), value.value.ifBlank { "Нет данных" }, unit, value.command.toString())
            } + info.configuration.responses.filter { it.error != null }.map { listOf("Чтение параметров", it.error!!, "—", it.command.toString()) }
            report.table(listOf("Параметр", "Значение", "Единица", "Команда HART"), listOf(170f, 170f, 105f, 78f),
                (if (configRows.isEmpty()) listOf(listOf("Снимок", "Нет данных", "—", "—")) else configRows).asSequence())
            report.heading("Комментарий"); report.line(info.comment.ifBlank { "Нет комментария" })
            report.heading("События")
            report.beginTable(listOf("Время UTC", "Категория", "Описание"), listOf(105f, 95f, 323f))
            var eventRows = 0
            history.records(id, sample = { batch -> batch.values.filter { !it.plottable }.forEach { value ->
                report.tableRow(listOf(timestamp(value.receivedAt.toEpochMilli()), "Качество", "${value.slot}: ${value.quality}; исходное ${value.value} ${value.unit}; команда ${value.command}")); eventRows++
            } }, event = { at, text, gap ->
                val category = when {
                    text == "Пауза" -> "Пауза"
                    text.startsWith("Связь восстановлена") -> "Восстановление"
                    text.startsWith("Ошибка") -> "Ошибка обмена"
                    gap -> "Разрыв"
                    text.startsWith("Остановка") -> "Остановка"
                    text.startsWith("Попытка") || text.startsWith("Восстановление:") -> "Восстановление"
                    else -> "Событие"
                }
                report.tableRow(listOf(timestamp(at), category, text)); eventRows++
            })
            if (eventRows == 0) report.tableRow(listOf("—", "—", "События отсутствуют"))
            report.endTable(); report.finish(); pdf.writeTo(output)
        } finally { report.finish(); pdf.close() }
    }
    private fun parameterName(key: String): String = when {
        key == "damping" -> "Демпфирование"
        key.startsWith("assignment") -> "Назначение ${key.removePrefix("assignment")}"
        else -> mapOf("hartRevision" to "Версия HART", "deviceType" to "Тип устройства HART", "deviceId" to "HART Device ID",
            "manufacturer" to "Код производителя", "deviceRevision" to "Ревизия устройства", "softwareRevision" to "Ревизия ПО",
            "deviceDateRaw" to "Редактируемая дата HART (исходный код)").get(key) ?: configurationNames[key] ?: key
    }
    private fun graph(report: Pages, view: HistoryView) {
        if (view.points.isEmpty()) { report.line("Нет принятых точек для графика"); return }
        val groups = view.points.groupBy { it.measurement.unitCode }.toSortedMap()
        val axes = groups.mapValues { ChartAxes.numeric(it.value.map { p -> p.measurement.value }) }
        report.ensure(285f)
        val canvas = report.canvas; val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 8f; strokeWidth = 0.7f }
        val widths = axes.values.take(4).map { axis -> (axis.ticks.maxOf { paint.measureText(axis.label(it)) } + 12f).coerceIn(38f, 64f) }
        val left = 36f + widths.sum(); val right = 559f; val top = report.y + 20f; val bottom = top + 220f
        val lo = view.info.startedAt; val hi = view.end.coerceAtLeast(lo + 1)
        fun x(at: Long) = left + ((at - lo).toDouble() / (hi - lo) * (right - left)).toFloat()
        var labelRight = 36f
        axes.entries.take(4).forEachIndexed { i, (_, axis) ->
            labelRight += widths[i]; paint.color = Color.DKGRAY; paint.textAlign = Paint.Align.RIGHT
            canvas.drawText("Y${i + 1}", labelRight - 8f, top - 9f, paint)
            axis.ticks.forEach { tick ->
                val y = bottom - ((tick - axis.low) / (axis.high - axis.low) * (bottom - top)).toFloat()
                canvas.drawText(axis.label(tick), labelRight - 8f, y + 3f, paint)
            }
        }
        paint.textAlign = Paint.Align.LEFT; paint.color = Color.LTGRAY
        axes.values.first().let { axis -> axis.ticks.forEach { tick ->
            val y = bottom - ((tick - axis.low) / (axis.high - axis.low) * (bottom - top)).toFloat();canvas.drawLine(left, y, right, y, paint)
        } }
        ChartAxes.timeTicks(lo, hi, 5).forEach { at ->
            paint.color = Color.LTGRAY;canvas.drawLine(x(at), top, x(at), bottom, paint)
            paint.color = Color.DKGRAY;val label = ChartAxes.timeLabel(at, hi - lo, ZoneOffset.UTC)
            canvas.drawText(label, (x(at) - paint.measureText(label) / 2).coerceIn(left, right - paint.measureText(label)), bottom + 16, paint)
        }
        view.points.groupBy { it.measurement.slot }.forEach { (slot, points) ->
            paint.color = colors[MeasurementDecoder.slots.indexOf(slot).coerceIn(0, 3)];paint.strokeWidth = 1.3f
            var previous: ChartPoint? = null
            points.forEach { point ->
                val axis = axes.getValue(point.measurement.unitCode)
                fun y(p: ChartPoint) = bottom - ((p.measurement.value - axis.low) / (axis.high - axis.low) * (bottom - top)).toFloat()
                val before = previous
                if (before != null && before.segment == point.segment && before.measurement.unitCode == point.measurement.unitCode)
                    canvas.drawLine(x(before.time), y(before), x(point.time), y(point), paint)
                else canvas.drawCircle(x(point.time), y(point), 1.6f, paint)
                previous = point
            }
        }
        report.y = bottom + 32f
        val legend = view.points.groupBy { it.measurement.slot to it.measurement.unitCode }.keys.map { (slot, unit) ->
            val axis = axes.getValue(unit);val number = groups.keys.indexOf(unit) + 1
            listOf(slot, HartUnits.find(unit)?.symbol ?: "HART $unit", "Y$number: ${axis.label(axis.low)} … ${axis.label(axis.high)}")
        }
        report.table(listOf("Серия", "Единица", "Шкала"), listOf(90f, 130f, 303f), legend.asSequence(), 9f,
            legend.map { colors[MeasurementDecoder.slots.indexOf(it[0])] })
        if (axes.size > 4) report.line("Шкалы Y5 и далее заданы численно в таблице легенды.", 9f)
    }
    private fun timestamp(millis: Long) = Instant.ofEpochMilli(millis).toString()
    private class Pages(private val pdf: PdfDocument) {
        private var page: PdfDocument.Page? = null;private var number = 0
        var y = 40f
        val canvas get() = page!!.canvas
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var tableHeaders = emptyList<String>();private var tableWidths = emptyList<Float>();private var tableSize = 9f
        init { newPage() }
        fun ensure(height: Float) { if (y + height > 795f) newPage() }
        fun newPage() { finish();number++;page = pdf.startPage(PdfDocument.PageInfo.Builder(595, 842, number).create());y = 40f }
        private fun font(size: Float, bold: Boolean = false, color: Int = Color.BLACK) {
            paint.textSize = size;paint.color = color;paint.style = Paint.Style.FILL
            paint.typeface = Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
        }
        private fun wrap(text: String, width: Float, size: Float, bold: Boolean = false): List<String> {
            font(size, bold)
            return text.replace('\t', ' ').split('\n').flatMap { paragraph ->
                val result = mutableListOf<String>();var rest = paragraph
                if (rest.isEmpty()) result += ""
                while (rest.isNotEmpty()) {
                    var count = paint.breakText(rest, true, width, null).coerceAtLeast(1)
                    if (count < rest.length) { val space = rest.lastIndexOf(' ', count - 1);if (space > count / 2) count = space }
                    result += rest.substring(0, count);rest = rest.substring(count).trimStart()
                };result
            }
        }
        fun heading(text: String) { ensure(55f);y += 8;line(text, 14f, true);y += 5 }
        fun line(text: String, size: Float = 10f, bold: Boolean = false) {
            wrap(text, 523f, size, bold).forEach { line -> ensure(size + 5);font(size, bold);canvas.drawText(line, 36f, y + size, paint);y += size + 5 }
        }
        fun beginTable(headers: List<String>, widths: List<Float>, size: Float = 9f) {
            require(headers.size == widths.size && kotlin.math.abs(widths.sum() - 523f) < 0.1f)
            tableHeaders = headers;tableWidths = widths;tableSize = size
            ensure(70f);drawRow(headers, true)
        }
        fun tableRow(cells: List<String>, color: Int? = null) {
            val height = rowHeight(cells, false)
            if (y + height > 795f) { newPage();drawRow(tableHeaders, true) }
            require(y + height <= 795f) { "Слишком длинная строка таблицы PDF" }
            drawRow(cells, false, color)
        }
        private fun rowHeight(cells: List<String>, header: Boolean): Float =
            cells.indices.maxOf { wrap(cells[it], tableWidths[it] - 12, tableSize, header).size } * (tableSize + 3) + 12
        private fun drawRow(cells: List<String>, header: Boolean, color: Int? = null) {
            require(cells.size == tableWidths.size)
            val lines = cells.indices.map { wrap(cells[it], tableWidths[it] - 12, tableSize, header) }
            val height = lines.maxOf { it.size } * (tableSize + 3) + 12
            var x = 36f
            cells.indices.forEach { i ->
                font(tableSize, header, if (header) Color.rgb(23, 54, 93) else Color.WHITE)
                canvas.drawRect(x, y, x + tableWidths[i], y + height, paint)
                paint.color = Color.rgb(205, 214, 224);paint.style = Paint.Style.STROKE;paint.strokeWidth = 0.5f
                canvas.drawRect(x, y, x + tableWidths[i], y + height, paint)
                font(tableSize, header, if (header) Color.WHITE else color ?: Color.BLACK)
                lines[i].forEachIndexed { line, text -> canvas.drawText(text, x + 6, y + 6 + tableSize + line * (tableSize + 3), paint) }
                x += tableWidths[i]
            };y += height
        }
        fun endTable() { y += 9;tableHeaders = emptyList();tableWidths = emptyList() }
        fun table(headers: List<String>, widths: List<Float>, rows: Sequence<List<String>>, size: Float = 9f, colors: List<Int>? = null) {
            beginTable(headers, widths, size);rows.forEachIndexed { i, row -> tableRow(row, colors?.getOrNull(i)) };endTable()
        }
        fun finish() { page?.let { p ->
            font(8f, color = Color.GRAY);p.canvas.drawText("HART-бро · Только чтение · $number", 36f, 820f, paint)
            pdf.finishPage(p);page = null
        } }
    }
}
