package ru.hartbro.core

import kotlin.math.abs

/** A single time-window model for live data, gestures, buttons and archive navigation. */
data class ChartViewport(val spanMillis: Long = 60_000, val endMillis: Long? = null, val following: Boolean = true) {
    init { require(spanMillis > 0) }
    fun bounds(start: Long, last: Long): LongRange {
        val hi = (if (following) last else endMillis ?: last).coerceIn(start, maxOf(start, last))
        return (if (spanMillis == Long.MAX_VALUE) start else hi - spanMillis)..hi
    }
    fun transform(start: Long, last: Long, zoom: Float, panFraction: Float): ChartViewport {
        require(zoom.isFinite() && zoom > 0 && panFraction.isFinite())
        val range = bounds(start, last)
        val width = if (spanMillis == Long.MAX_VALUE) (last - start).coerceAtLeast(1000) else spanMillis
        val pan = abs(panFraction) > 0.00001f
        val span = (width / zoom).toLong().coerceIn(1000, maxOf(86_400_000, (last - start).coerceAtLeast(1000) * 4))
        // Historical zoom stays centred on the viewed interval; live zoom keeps the right edge.
        val target = if (pan) range.last - (panFraction * width).toLong()
            else if (!following) range.last - width / 2 + span / 2 else range.last
        val hi = target.coerceIn(start, maxOf(start, last))
        val atLiveEdge = pan && last - hi <= 1000
        return copy(spanMillis = span, endMillis = if (following && !pan || atLiveEdge) null else hi,
            following = following && !pan || atLiveEdge)
    }
    fun follow() = copy(endMillis = null, following = true)
    fun all(live: Boolean) = ChartViewport(Long.MAX_VALUE, following = live)
    fun seek(start: Long, last: Long, fraction: Float, toleranceMillis: Long = 1000): ChartViewport {
        val end = start + ((last - start) * fraction.coerceIn(0f, 1f)).toLong()
        return if (last - end <= toleranceMillis.coerceAtLeast(0)) follow()
            else copy(endMillis = end, following = false)
    }
    fun select(points: List<ChartPoint>, hidden: Set<String>, start: Long, last: Long): List<ChartPoint> =
        points.filter { it.time in bounds(start, last) && it.measurement.slot !in hidden }
}
