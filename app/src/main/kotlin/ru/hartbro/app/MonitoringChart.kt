package ru.hartbro.app

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.conflate
import ru.hartbro.core.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs

internal fun timeLabel(millis: Long): String = DateTimeFormatter.ofPattern("dd.MM HH:mm:ss.SSS").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(millis))
private val seriesColors = listOf(Color(0xFF1976D2), Color(0xFFD84315), Color(0xFF008577), Color(0xFF9C27B0))

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MonitoringChart(history: SessionHistory, archiveId: String?, revision: Long, latest: MeasurementBatch? = null) {
    var loaded by remember(archiveId) { mutableStateOf<HistoryWindow?>(null) }
    var error by remember(archiveId) { mutableStateOf<String?>(null) }
    var viewport by remember(archiveId) { mutableStateOf(if (archiveId == null) ChartViewport() else ChartViewport(Long.MAX_VALUE, following = false)) }
    var hidden by remember(archiveId) { mutableStateOf(setOf<String>()) }
    var highlighted by remember(archiveId) { mutableStateOf("PV") }
    var selectedTime by remember(archiveId) { mutableStateOf<Long?>(null) }
    var eventsExpanded by remember { mutableStateOf(false) }
    val currentRevision by rememberUpdatedState(revision)
    LaunchedEffect(history, archiveId, viewport) {
        snapshotFlow { currentRevision }.conflate().collect {
            try {
                loaded = withContext(Dispatchers.IO) { history.window(archiveId, viewport) }
                error = null
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { error = "Ошибка чтения истории: ${e.message}" }
            delay(250) // Bound UI reads; every received sample is still written by the engine.
        }
    }
    Text("Общий график", style = MaterialTheme.typography.titleMedium)
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    val model = loaded
    if (model == null) { Text("Измерений пока нет"); return }
    val data = model.view; val lo = model.range.first; val hi = model.range.last
    val start = model.domain.first; val end = model.domain.last
    val widthMillis = (hi - lo).coerceAtLeast(1)
    if (archiveId != null) Text("${data.info.deviceKey} · ${data.info.manufacturer}\n${timeLabel(data.info.startedAt)}", style = MaterialTheme.typography.bodySmall)
    val visible = data.points.filter { it.measurement.slot !in hidden }
    val groups = visible.groupBy { it.measurement.unitCode }.toSortedMap()
    val axes = groups.mapValues { ChartAxes.numeric(it.value.map { p -> p.measurement.value }) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        MeasurementDecoder.slots.forEachIndexed { i, slot ->
            val value = if (archiveId == null) latest?.values?.find { it.slot == slot } else data.points.lastOrNull { it.measurement.slot == slot }?.measurement
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Checkbox(checked = slot !in hidden, onCheckedChange = { hidden = if (it) hidden - slot else hidden + slot })
                Text("$slot · ${value?.takeIf { it.plottable }?.let { "${it.value} ${it.unit}" } ?: "—"}${if (highlighted == slot) " ●" else ""}",
                    color = seriesColors[i], style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.clickable { highlighted = slot }.testTag("series-$slot"))
            }
        }
    }
    if (groups.size > 1) FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        groups.keys.forEachIndexed { i, unit -> Text("Y${i + 1}: ${HartUnits.find(unit)?.symbol ?: "HART $unit"}", style = MaterialTheme.typography.bodySmall) }
    } else groups.keys.firstOrNull()?.let { Text("Y: ${HartUnits.find(it)?.symbol ?: "HART $it"}", style = MaterialTheme.typography.bodySmall) }
    val grid = MaterialTheme.colorScheme.outlineVariant; val foreground = MaterialTheme.colorScheme.onSurface
    Canvas(Modifier.fillMaxWidth().height(230.dp).testTag("measurementChart")
        .pointerInput(lo, hi, start, end) { detectTransformGestures { _, offset, zoom, _ ->
            val pan = if (abs(zoom - 1f) > 0.001f) 0f else offset.x / size.width.coerceAtLeast(1)
            viewport = viewport.transform(start, end, zoom, pan)
        } }
        .pointerInput(visible, lo, hi) { detectTapGestures { offset ->
            val left = ((groups.size + 1) / 2 * 49 + 6).dp.toPx()
            val right = (groups.size / 2 * 49 + 6).dp.toPx()
            val target = lo + ((offset.x - left) / (size.width - left - right).coerceAtLeast(1f) * widthMillis).toLong()
            selectedTime = visible.minByOrNull { abs(it.time - target) }?.time
        } }) {
        val left = ((groups.size + 1) / 2 * 49 + 6).dp.toPx(); val right = (groups.size / 2 * 49 + 6).dp.toPx()
        val plotWidth = (size.width - left - right).coerceAtLeast(1f)
        val top = 14.dp.toPx(); val bottom = size.height - 26.dp.toPx(); val plotHeight = bottom - top
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 10.sp.toPx(); color = foreground.toArgb() }
        fun x(time: Long) = left + ((time - lo).toDouble() / widthMillis * plotWidth).toFloat()
        repeat(5) { n -> val y = top + plotHeight * n / 4; drawLine(grid, Offset(left, y), Offset(left + plotWidth, y)) }
        ChartAxes.timeTicks(lo, hi, (plotWidth / 75.dp.toPx()).toInt()).forEach { t ->
            val label = ChartAxes.timeLabel(t, widthMillis)
            val half = paint.measureText(label) / 2
            paint.textAlign = Paint.Align.CENTER
            drawContext.canvas.nativeCanvas.drawText(label, x(t).coerceIn(left + half, (left + plotWidth - half).coerceAtLeast(left + half)), size.height - 7.dp.toPx(), paint)
            drawLine(grid, Offset(x(t), top), Offset(x(t), bottom))
        }
        groups.keys.forEachIndexed { i, unit ->
            val axis = axes.getValue(unit)
            val isLeft = i % 2 == 0
            val column = i / 2
            val axisX = if (isLeft) left - 5.dp.toPx() - column * 49.dp.toPx() else left + plotWidth + 5.dp.toPx() + column * 49.dp.toPx()
            paint.textAlign = if (isLeft) Paint.Align.RIGHT else Paint.Align.LEFT
            drawContext.canvas.nativeCanvas.drawText("Y${i + 1}", axisX, 10.dp.toPx(), paint)
            axis.ticks.forEach { value ->
                val y = bottom - ((value - axis.low) / (axis.high - axis.low) * plotHeight).toFloat()
                drawContext.canvas.nativeCanvas.drawText(axis.label(value), axisX, y + 3.dp.toPx(), paint)
            }
        }
        val series = visible.groupBy { it.measurement.slot }.entries.sortedBy { if (it.key == highlighted) 1 else 0 }
        series.forEach { (slot, points) ->
            val i = MeasurementDecoder.slots.indexOf(slot); val color = seriesColors[i]
            var previous: ChartPoint? = null
            fun position(p: ChartPoint): Offset {
                val axis = axes.getValue(p.measurement.unitCode)
                return Offset(x(p.time), bottom - ((p.measurement.value - axis.low) / (axis.high - axis.low) * plotHeight).toFloat())
            }
            points.forEach { p ->
                val last = previous
                if (last != null && last.segment == p.segment && last.measurement.unitCode == p.measurement.unitCode)
                    drawLine(color, position(last), position(p), (if (slot == highlighted) 3f else 1.5f).dp.toPx(),
                        pathEffect = if (slot == highlighted) null else PathEffect.dashPathEffect(floatArrayOf((i + 2) * 3.dp.toPx(), 3.dp.toPx())))
                drawCircle(color, (if (slot == highlighted) 2.5 else 1.5).dp.toPx(), position(p)); previous = p
            }
        }
    }
    selectedTime?.let { at ->
        visible.filter { it.time == at }.distinctBy { it.measurement.slot }.forEach { p ->
            Text("${timeLabel(p.time)} · ${p.measurement.slot}: ${p.measurement.value} ${p.measurement.unit} · ${qualityText(p.measurement.quality)}", style = MaterialTheme.typography.bodySmall)
        }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedButton(onClick = { viewport = viewport.transform(start, end, 2f, 0f) }) { Text("Масштаб +") }
        OutlinedButton(onClick = { viewport = viewport.transform(start, end, 0.5f, 0f) }) { Text("Масштаб −") }
        FilterChip(selected = viewport.following, onClick = { viewport = if (viewport.following) viewport.copy(following = false, endMillis = hi) else viewport.follow() }, label = { Text("Следовать") })
        OutlinedButton(onClick = { viewport = viewport.all(archiveId == null) }) { Text("Вся сессия") }
    }
    Slider(value = if (end > start) ((hi - start).toDouble() / (end - start)).toFloat().coerceIn(0f, 1f) else 1f,
        enabled = end > start && viewport.spanMillis < end - start,
        onValueChange = { viewport = viewport.seek(start, end, it) })
    Text("${ChartAxes.timeLabel(lo, widthMillis)} — ${ChartAxes.timeLabel(hi, widthMillis)}", style = MaterialTheme.typography.bodySmall)
    TextButton(onClick = { eventsExpanded = !eventsExpanded }) { Text(if (eventsExpanded) "Скрыть события" else "События сессии") }
    if (eventsExpanded) data.events.forEach { (at, text) -> Text("${timeLabel(at)} · $text", style = MaterialTheme.typography.bodySmall) }
}
