package ru.hartbro.core

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.*

data class NumericAxis(val low: Double, val high: Double, val ticks: List<Double>, val step: Double) {
    fun label(value: Double): String {
        if (abs(value) >= 1e8 || (value != 0.0 && abs(value) < 1e-5)) return String.format(Locale.US, "%.2e", value)
        val decimals = (-floor(log10(step))).toInt().coerceIn(0, 8)
        return String.format(Locale.US, "%.${decimals}f", if (value == 0.0) 0.0 else value)
    }
}
object ChartAxes {
    fun numeric(values: List<Float>): NumericAxis {
        require(values.isNotEmpty() && values.all { it.isFinite() })
        var low = values.min().toDouble(); var high = values.max().toDouble()
        val pad = if (high == low) max(1e-6, abs(low) * 0.02) else (high - low) * 0.05
        low -= pad; high += pad
        val raw = (high - low) / 4
        val power = 10.0.pow(floor(log10(raw)))
        val ratio = raw / power
        val step = (when { ratio <= 1 -> 1; ratio <= 2 -> 2; ratio <= 5 -> 5; else -> 10 }) * power
        val first = floor(low / step) * step; val last = ceil(high / step) * step
        val count = ((last - first) / step).roundToInt().coerceIn(1, 10)
        return NumericAxis(first, first + count * step, (0..count).map { first + it * step }, step)
    }
    fun timeTicks(start: Long, end: Long, maxCount: Int): List<Long> {
        if (end <= start) return listOf(start)
        val target = (end - start) / maxCount.coerceIn(2, 10)
        val steps = listOf(1000L, 2000, 5000, 10000, 15000, 30000, 60000, 120000, 300000, 600000, 1800000, 3600000, 21600000, 86400000)
        val step = steps.firstOrNull { it >= target } ?: ((target / 86400000 + 1) * 86400000)
        val first = Math.floorDiv(start, step) * step
        return generateSequence(first) { it + step }.dropWhile { it < start }.takeWhile { it <= end }.take(12).toList()
    }
    fun timeLabel(at: Long, range: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        DateTimeFormatter.ofPattern(if (range < 600_000) "HH:mm:ss" else if (range < 86_400_000) "HH:mm" else "dd.MM HH:mm")
            .withZone(zone).format(Instant.ofEpochMilli(at))
}
