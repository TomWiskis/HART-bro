package ru.hartbro.core

/** Time navigation is shared by the live and archive graph; it never transforms digital values. */
data class ChartViewport(val spanMillis: Long = 120_000, val endMillis: Long? = null, val following: Boolean = true) {
    fun bounds(start: Long, last: Long): LongRange {
        val hi = (if (following) last else endMillis ?: last).coerceIn(start, maxOf(start,last))
        val lo = if (spanMillis == Long.MAX_VALUE) start else (hi - spanMillis).coerceAtLeast(start)
        return lo..hi
    }
    fun transform(start: Long, last: Long, zoom: Float, panFraction: Float): ChartViewport {
        require(zoom.isFinite() && zoom > 0 && panFraction.isFinite())
        val range = bounds(start,last); val width = (range.last-range.first).coerceAtLeast(1)
        return ChartViewport((width / zoom).toLong().coerceIn(1000,(last-start).coerceAtLeast(1000)),
            (range.last-(panFraction*width).toLong()).coerceIn(start,maxOf(start,last)),false)
    }
    fun select(points: List<ChartPoint>, hidden: Set<String>, start: Long, last: Long): List<ChartPoint> {
        val range = bounds(start,last)
        return points.filter { it.time in range && it.measurement.slot !in hidden }
    }
}
