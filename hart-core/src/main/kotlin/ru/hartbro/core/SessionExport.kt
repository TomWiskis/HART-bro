package ru.hartbro.core

import java.io.OutputStream
import java.io.OutputStreamWriter

/** Streaming UTF-8 export of source records, not the reduced chart. Float.toString round-trips received floats. */
object SessionCsv {
    fun write(history: SessionHistory, id: String, output: OutputStream, separator: Char = ';') {
        require(separator == ';' || separator == ',')
        val info = history.info(id)
        val writer = OutputStreamWriter(output, Charsets.UTF_8)
        writer.write("\uFEFF") // Excel UTF-8 detection
        fun row(fields: List<String>) {
            writer.write(fields.joinToString(separator.toString()) { value ->
                if (value.any { it == separator || it == '"' || it == '\r' || it == '\n' }) "\"${value.replace("\"", "\"\"")}\"" else value
            }); writer.write("\r\n")
        }
        row(listOf("timestamp_utc", "device_id", "variable", "value", "unit", "hart_unit_code", "quality", "command", "variable_code", "raw_status", "device_status", "unit_name"))
        history.records(id, sample = { batch -> batch.values.forEach { v ->
            row(listOf(v.receivedAt.toString(), info.deviceKey, v.slot,
                if (v.quality == Quality.ABSENT) "" else v.value.toString(), v.unit, v.unitCode.toString(), v.quality.name,
                v.command.toString(), v.variableCode?.toString() ?: "", v.rawStatus?.toString() ?: "", batch.deviceStatus.toString(), HartUnits.find(v.unitCode)?.name ?: "неизвестная единица HART ${v.unitCode}"))
        } })
        writer.flush()
    }
}

data class VariableStatistics(val slot: String, val unitCode: Int, var count: Long = 0, var good: Long = 0, var unverified: Long = 0,
                              var rejected: Long = 0, var min: Float = Float.POSITIVE_INFINITY, var max: Float = Float.NEGATIVE_INFINITY,
                              var mean: Double = 0.0) {
    val unit: String get() = HartUnits.find(unitCode)?.symbol ?: "HART $unitCode"
    fun accept(value: Measurement) {
        if (!value.plottable) { rejected++; return }
        count++; if (value.quality == Quality.GOOD) good++ else unverified++
        min = minOf(min, value.value); max = maxOf(max, value.value)
        mean += (value.value.toDouble() - mean) / count
    }
}
data class SessionStatistics(val variables: List<VariableStatistics>, val gapCount: Long, val eventCount: Long,
                             val noDataMillis: Long, val firstAt: Long?, val lastAt: Long?)
fun sessionStatistics(history: SessionHistory, id: String): SessionStatistics {
    val stats = linkedMapOf<Pair<String, Int>, VariableStatistics>()
    var gaps = 0L; var events = 0L; var gapStart: Long? = null; var gapMillis = 0L; var first: Long? = null; var last: Long? = null
    history.records(id, sample = { batch ->
        batch.values.forEach { stats.getOrPut(it.slot to it.unitCode) { VariableStatistics(it.slot, it.unitCode) }.accept(it) }
        val at = batch.receivedAt.toEpochMilli()
        if (batch.values.any { it.plottable }) {
            if (first == null) first = at; last = at
            gapStart?.let { gapMillis += (at - it).coerceAtLeast(0) }; gapStart = null
        } else if (gapStart == null) { gapStart = at; gaps++ }
    }, event = { time, _, gap -> events++; if (gap && gapStart == null) { gapStart = time; gaps++ } })
    gapStart?.let { gapMillis += (history.info(id).endedAt - it).coerceAtLeast(0) }
    return SessionStatistics(stats.values.toList(), gaps, events, gapMillis, first, last)
}
