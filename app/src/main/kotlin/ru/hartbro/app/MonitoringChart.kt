package ru.hartbro.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.hartbro.core.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs

internal fun timeLabel(millis: Long): String = DateTimeFormatter.ofPattern("dd.MM HH:mm:ss.SSS").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(millis))
private val seriesColors = listOf(Color(0xFF1565C0), Color(0xFFD84315), Color(0xFF008577), Color(0xFF9C27B0))

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MonitoringChart(history: SessionHistory, archiveId: String?, revision: Long) {
    var view by remember(archiveId) { mutableStateOf<HistoryView?>(null) }
    var error by remember(archiveId) { mutableStateOf<String?>(null) }
    var viewport by remember(archiveId) { mutableStateOf(if (archiveId == null) ChartViewport() else ChartViewport(Long.MAX_VALUE, following = false)) }
    var hidden by remember(archiveId) { mutableStateOf(setOf<String>()) }
    var selected by remember(archiveId) { mutableStateOf<ChartPoint?>(null) }
    LaunchedEffect(archiveId, revision, viewport) {
        try {
            val overview = withContext(Dispatchers.IO) { history.view(archiveId, buckets = 1) }
            view = if (overview == null) null else {
                val range = viewport.bounds(overview.info.startedAt, overview.end)
                val hi = range.last; val lo = range.first
                withContext(Dispatchers.IO) { history.view(archiveId, lo, hi) }
            }
            error = null
        } catch (e: Exception) { error = "Не удалось прочитать историю: ${e.message}" }
    }
    val data = view
    Text("Общий график", style = MaterialTheme.typography.titleLarge)
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    if (data == null) { Text("Измерений пока нет"); return }
    if (archiveId != null) Text("${data.info.deviceKey} · ${data.info.manufacturer}\n${data.info.communicator}\nНачало: ${timeLabel(data.info.startedAt)}")
    val range = viewport.bounds(data.info.startedAt, data.end)
    val hi = range.last; val lo = range.first
    val widthMillis = (hi - lo).coerceAtLeast(1)
    val visible = viewport.select(data.points, hidden, data.info.startedAt, data.end)
    val groups = visible.groupBy { it.measurement.slot to it.measurement.unitCode }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        MeasurementDecoder.slots.forEachIndexed { i, slot ->
            FilterChip(selected = slot !in hidden, onClick = { hidden = if (slot in hidden) hidden - slot else hidden + slot },
                label = { Text(slot, color = seriesColors[i]) })
        }
    }
    Text("Независимые шкалы Y для каждой серии; значения и единицы не пересчитываются.", style = MaterialTheme.typography.bodySmall)
    groups.forEach { (key, points) ->
        Text("${key.first}: ${points.minOf { it.measurement.value }} … ${points.maxOf { it.measurement.value }} ${points.first().measurement.unit}",
            color = seriesColors[MeasurementDecoder.slots.indexOf(key.first)], style = MaterialTheme.typography.bodySmall)
    }
    val grid = MaterialTheme.colorScheme.outlineVariant
    val pan: (Float, Float) -> Unit = { zoom, dx ->
        viewport = viewport.transform(data.info.startedAt, data.end, zoom, dx)
    }
    Canvas(Modifier.fillMaxWidth().height(260.dp)
        .pointerInput(hi, lo) { detectTransformGestures { _, offset, zoom, _ -> pan(zoom, offset.x / size.width.coerceAtLeast(1)) } }
        .pointerInput(visible, lo, hi) { detectTapGestures { offset ->
            val target = lo + (offset.x / size.width * widthMillis).toLong()
            selected = visible.minByOrNull { abs(it.time - target) }
        } }) {
        repeat(5) { n -> val y = size.height * n / 4; drawLine(grid, Offset(0f, y), Offset(size.width, y)) }
        groups.forEach { (key, points) ->
            val min = points.minOf { it.measurement.value }.toDouble()
            val max = points.maxOf { it.measurement.value }.toDouble()
            fun position(p: ChartPoint) = Offset(((p.time - lo).toDouble() / widthMillis * size.width).toFloat(),
                (size.height * (0.95 - 0.9 * if (max == min) 0.5 else (p.measurement.value - min) / (max - min))).toFloat())
            val color = seriesColors[MeasurementDecoder.slots.indexOf(key.first)]
            var previous: ChartPoint? = null
            points.forEach { p ->
                val last = previous
                if (last != null && last.segment == p.segment) drawLine(color, position(last), position(p), 2.dp.toPx())
                drawCircle(color, 2.dp.toPx(), position(p)); previous = p
            }
        }
    }
    Text("${timeLabel(lo)} — ${timeLabel(hi)}", style = MaterialTheme.typography.bodySmall)
    selected?.let { p -> Text("${timeLabel(p.time)} · ${p.measurement.slot}: ${p.measurement.value} ${p.measurement.unit} · ${p.measurement.quality} · команда ${p.measurement.command}") }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { pan(2f, 0f) }) { Text("Масштаб +") }
        OutlinedButton(onClick = { pan(0.5f, 0f) }) { Text("Масштаб −") }
        OutlinedButton(onClick = { viewport = ChartViewport(Long.MAX_VALUE, following = false) }) { Text("Вся сессия") }
        FilterChip(selected = viewport.following, onClick = { viewport = ChartViewport(following = !viewport.following) }, label = { Text("Следовать") })
    }
    if (data.end > data.info.startedAt) {
        Text("Положение во времени", style = MaterialTheme.typography.bodySmall)
        Slider(value = ((hi - data.info.startedAt).toDouble() / (data.end - data.info.startedAt)).toFloat().coerceIn(0f, 1f), onValueChange = {
            viewport = ChartViewport(if (viewport.spanMillis == Long.MAX_VALUE) 120_000 else viewport.spanMillis,
                data.info.startedAt + ((data.end - data.info.startedAt) * it).toLong(), false)
        })
    }
    Text("Разрывы означают паузу, отсутствие или недостоверность данных. Плотная история показана с сохранением экстремумов; нажмите на точку для значения.", style = MaterialTheme.typography.bodySmall)
    data.events.takeLast(12).forEach { (at, text) -> Text("${timeLabel(at)} · $text", style = MaterialTheme.typography.bodySmall) }
}
