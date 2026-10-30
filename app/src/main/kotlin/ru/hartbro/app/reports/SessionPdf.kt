package ru.hartbro.app.reports

import android.graphics.*
import android.graphics.pdf.PdfDocument
import java.io.OutputStream
import java.time.Instant
import java.time.ZoneOffset
import ru.hartbro.core.*

/** Local A4 report. Statistics use every source record; graph uses bounded extrema-preserving reduction. */
object SessionPdf {
    private val colors = intArrayOf(Color.rgb(25, 90, 175), Color.rgb(180, 65, 15), Color.rgb(0, 110, 90), Color.rgb(120, 35, 150))
    fun write(history: SessionHistory, id: String, output: OutputStream) {
        val info = history.info(id); val statistics = sessionStatistics(history, id)
        val pdf = PdfDocument()
        val report = Pages(pdf)
        try {
            report.heading("HART-бро · отчёт о проверке")
            report.line(info.title.ifBlank { "Проверка ${info.deviceKey}" })
            report.line("ID записи: $id")
            report.line("Начало: ${timestamp(info.startedAt)}")
            report.line("Окончание: ${timestamp(info.endedAt)} · длительность ${info.durationMillis / 1000} с")
            report.heading("Прибор и связь")
            report.line("Производитель: ${info.manufacturer}")
            report.line("Тип / Device ID: ${info.deviceKey}")
            val identity = runCatching { DeviceIdentity.parse(info.identityHex.split(' ').filter { it.isNotEmpty() }.map { it.toInt(16).toByte() }.toByteArray()) }.getOrNull()
            report.line("HART: ${identity?.hartRevision ?: "Нет данных"}")
            report.line("TAG: ${info.configuration.tag ?: "Нет данных"}")
            report.line("Настроенный диапазон: ${info.configuration.range?.label ?: "Диапазон неизвестен"}")
            report.line("Коммуникатор: ${info.communicator}")
            report.heading("Исходные измерения")
            report.line("GOOD и UNVERIFIED — конечные принятые значения; для UNVERIFIED прибор не передал индивидуальное качество. BAD/UNCERTAIN/FIXED/ABSENT исключены из статистики и графика.")
            if (statistics.variables.isEmpty()) report.line("Нет измерений")
            statistics.variables.forEach { s ->
                report.line("${s.slot}, ${s.unit} (HART ${s.unitCode}): ${s.count} принятых; GOOD ${s.good}, UNVERIFIED ${s.unverified}; исключено ${s.rejected}")
                if (s.count > 0) report.line("min ${s.min}; max ${s.max}; среднее ${s.mean}")
            }
            report.line("Разрывов: ${statistics.gapCount}; отсутствие достоверных данных ${statistics.noDataMillis / 1000.0} с. Событий: ${statistics.eventCount}")
            report.newPage()
            report.heading("Единый график измерений")
            report.line("UTC. Каждая единица имеет собственную шкалу; одинаковые единицы используют общую. Пропуски не интерполируются. График сокращён с сохранением экстремумов; CSV содержит все исходные строки.")
            graph(report, history.savedView(id, 300))
            report.heading("Доступная конфигурация (неполный снимок)")
            if (info.configuration.parameters.isEmpty()) report.line("Нет данных: архив создан до сохранения конфигурации")
            info.configuration.parameters.forEach { (key, value) ->
                report.line("${configurationNames[key] ?: key}: ${value.value}${value.unitCode?.let { " (HART $it)" } ?: ""}; команда ${value.command}")
            }
            info.configuration.responses.filter { it.error != null }.forEach { report.line("Команда ${it.command}: ${it.error}") }
            report.heading("Комментарий")
            report.line(info.comment.ifBlank { "Нет комментария" })
            report.heading("События и ошибки")
            if (statistics.eventCount == 0L) report.line("События отсутствуют")
            history.records(id, sample = { batch -> batch.values.filter { !it.plottable }.forEach { v ->
                report.line("${v.receivedAt} ${v.slot}: ${v.quality}, исходное значение ${v.value}, HART ${v.unitCode}, команда ${v.command}")
            } }, event = { at, text, gap -> report.line("${timestamp(at)} ${if (gap) "[РАЗРЫВ] " else ""}$text") })
            report.finish(); pdf.writeTo(output)
        } finally { report.finish(); pdf.close() }
    }
    private fun graph(report: Pages, view: HistoryView) {
        if (view.points.isEmpty()) { report.line("Нет достоверных точек для графика"); return }
        val groups = view.points.groupBy { it.measurement.unitCode }.toSortedMap()
        // Legend with numeric scales below a shared plot avoids overlapping multiple right-side axes.
        report.ensure(330f)
        val canvas = report.canvas; val left = (36f + groups.size.coerceAtMost(4) * 44f); val right = 550f; val top = report.y + 16f; val bottom = top + 220f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 8f; strokeWidth = 0.7f }
        val lo = view.info.startedAt; val hi = view.end.coerceAtLeast(lo + 1)
        val axes = groups.mapValues { ChartAxes.numeric(it.value.map { p -> p.measurement.value }) }
        fun x(at: Long) = left + ((at - lo).toDouble() / (hi - lo) * (right - left)).toFloat()
        axes.entries.take(4).forEachIndexed { column, (unit, axis) ->
            paint.color = Color.DKGRAY
            canvas.drawText(HartUnits.find(unit)?.symbol ?: "HART $unit", 36f + column * 44f, top - 5f, paint)
            axis.ticks.forEach { tick ->
                val y = bottom - ((tick - axis.low) / (axis.high - axis.low) * (bottom - top)).toFloat()
                canvas.drawText(axis.label(tick), 36f + column * 44f, y, paint)
            }
        }
        paint.color = Color.LTGRAY
        axes.values.first().let { axis -> axis.ticks.forEach { tick ->
            val y = bottom - ((tick - axis.low) / (axis.high - axis.low) * (bottom - top)).toFloat()
            canvas.drawLine(left, y, right, y, paint)
        } }
        ChartAxes.timeTicks(lo, hi, 5).forEach { at ->
            paint.color = Color.LTGRAY; canvas.drawLine(x(at), top, x(at), bottom, paint)
            paint.color = Color.DKGRAY; val label = ChartAxes.timeLabel(at, hi - lo, ZoneOffset.UTC)
            canvas.drawText(label, (x(at) - paint.measureText(label) / 2).coerceIn(left, right - paint.measureText(label)), bottom + 14, paint)
        }
        view.points.groupBy { it.measurement.slot }.forEach { (slot, points) ->
            paint.color = colors[MeasurementDecoder.slots.indexOf(slot).coerceIn(0, 3)]; paint.strokeWidth = 1.3f
            var previous: ChartPoint? = null
            points.forEach { point ->
                val axis = axes.getValue(point.measurement.unitCode)
                fun y(p: ChartPoint) = bottom - ((p.measurement.value - axis.low) / (axis.high - axis.low) * (bottom - top)).toFloat()
                val prev = previous
                if (prev != null && prev.segment == point.segment && prev.measurement.unitCode == point.measurement.unitCode)
                    canvas.drawLine(x(prev.time), y(prev), x(point.time), y(point), paint)
                else canvas.drawCircle(x(point.time), y(point), 1.6f, paint)
                previous = point
            }
        }
        report.y = bottom + 28f
        if (axes.size > 4) report.line("Числовые деления дополнительных единиц приведены в таблице шкал ниже.", 9f)
        axes.forEach { (unit, axis) ->
            val slots = groups.getValue(unit).map { it.measurement.slot }.distinct().joinToString(", ")
            report.line("$slots · ${HartUnits.find(unit)?.symbol ?: "HART $unit"}: шкала снизу вверх ${axis.ticks.joinToString(" / ") { axis.label(it) }}", 9f)
        }
        MeasurementDecoder.slots.filter { slot -> view.points.any { it.measurement.slot == slot } }.forEach { slot ->
            report.line("━ $slot", 9f, colors[MeasurementDecoder.slots.indexOf(slot)])
        }
    }
    private fun timestamp(millis: Long) = Instant.ofEpochMilli(millis).toString()
    private class Pages(private val pdf: PdfDocument) {
        private var page: PdfDocument.Page? = null
        private var number = 0
        var y = 40f
        val canvas get() = page!!.canvas
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create("sans-serif", Typeface.NORMAL) }
        init { newPage() }
        fun ensure(height: Float) { if (y + height > 800f) newPage() }
        fun newPage() { finish(); number++; page = pdf.startPage(PdfDocument.PageInfo.Builder(595, 842, number).create()); y = 40f }
        fun heading(text: String) { ensure(50f); y += 8f; line(text, 14f); y += 4f }
        fun line(text: String, size: Float = 10f, color: Int = Color.BLACK) {
            paint.textSize = size; paint.color = color
            text.replace('\t', ' ').split('\n').forEach { paragraph ->
                var rest = paragraph
                if (rest.isEmpty()) { ensure(size + 5); y += size + 5 }
                while (rest.isNotEmpty()) {
                    paint.textSize = size; paint.color = color
                    var count = paint.breakText(rest, true, 523f, null).coerceAtLeast(1)
                    if (count < rest.length) { val space = rest.lastIndexOf(' ', count - 1); if (space > count / 2) count = space }
                    ensure(size + 5); paint.textSize = size; paint.color = color; canvas.drawText(rest.substring(0, count), 36f, y, paint); y += size + 5
                    rest = rest.substring(count).trimStart()
                }
            }
        }
        fun finish() { page?.let { p ->
            paint.color = Color.GRAY; paint.textSize = 8f
            p.canvas.drawText("HART-бро · Только чтение · $number", 36f, 820f, paint)
            pdf.finishPage(p); page = null
        } }
    }
}
