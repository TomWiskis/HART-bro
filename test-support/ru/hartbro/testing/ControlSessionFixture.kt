package ru.hartbro.testing

import java.io.File
import java.io.InputStream
import java.time.Instant
import ru.hartbro.core.*

/** Test-only reconstruction from the user's exact CSV and PDF metadata; never a recorded HART wire response. */
object ControlSessionFixture {
    const val ID = "148d023e-8504-47ef-98a8-e3f5faba7954"
    data class Data(val history: SessionHistory, val id: String, val source: List<Measurement>)
    fun load(root: File, csv: InputStream, events: InputStream): Data {
        val lines = csv.bufferedReader(Charsets.UTF_8).use { it.readLines() }
        val header = lines.first().removePrefix("\uFEFF").split(';')
        val source = lines.drop(1).filter { it.isNotBlank() }.map { line ->
            val fields = line.split(';');require(fields.size == header.size)
            val values = header.zip(fields).toMap()
            Measurement(values.getValue("variable"), values.getValue("value").toFloat(), values.getValue("hart_unit_code").toInt(),
                Quality.valueOf(values.getValue("quality")), Instant.parse(values.getValue("timestamp_utc")), values.getValue("command").toInt(),
                values["variable_code"]?.toIntOrNull(), null, values["raw_status"]?.toIntOrNull())
        }
        require(source.size == 269 && source.map { it.receivedAt }.distinct().size == 68)
        val configuration = linkedMapOf<String, ConfigValue>()
        mapOf("hartRevision" to "5", "deviceType" to "2662", "deviceId" to "BF2C34", "manufacturer" to "0026", "deviceRevision" to "9", "softwareRevision" to "3")
            .forEach { (key, value) -> configuration[key] = ConfigValue(value, 0) }
        mapOf("tag" to "", "description" to "", "deviceDateRaw" to "16 06 7A").forEach { (key, value) -> configuration[key] = ConfigValue(value, 13) }
        mapOf("alarm" to "0", "transferFunction" to "0", "rangeUnit" to "12", "upperRange" to "10.0", "lowerRange" to "0.0", "damping" to "0.4", "writeProtect" to "0", "privateLabel" to "153")
            .forEach { (key, value) -> configuration[key] = ConfigValue(value, 15, 12.takeIf { key in setOf("rangeUnit", "lowerRange", "upperRange") }) }
        listOf(0, 1, 2, 1).forEachIndexed { i, code -> configuration["assignment${MeasurementDecoder.slots[i]}"] = ConfigValue(code.toString(), 50) }
        // Remaining wire-only header fields are placeholders, not claims about the real device.
        val identity = DeviceIdentity.parse("FE 26 62 05 05 09 03 00 00 BF 2C 34".split(' ').map { it.toInt(16).toByte() }.toByteArray())
        val history = SessionHistory(File(root, "working"), File(root, "saved"))
        history.begin(identity, "MACTekViator3B1F (00:06:66:BA:3B:1F)", Instant.parse("2026-10-31T02:24:41.670Z"), ConfigurationSnapshot(configuration))
        val actions = mutableListOf<Pair<Instant, () -> Unit>>()
        source.groupBy { it.receivedAt }.forEach { (at, values) -> actions += at to { history.append(MeasurementBatch(values, 0, at)) } }
        events.bufferedReader(Charsets.UTF_8).use { it.readLines() }.filter { it.isNotBlank() }.forEach { line ->
            val fields = line.split('\t', limit = 3);val at = Instant.parse(fields[0]);actions += at to { history.event(at, fields[2], fields[1].toBoolean()) }
        }
        actions.sortedBy { it.first }.forEach { it.second() }
        val generated = history.snapshot(Instant.parse("2026-10-31T02:27:56.760Z"));history.discard()
        check(File(root, "saved/$generated.hart").renameTo(File(root, "saved/$ID.hart")))
        return Data(history, ID, source)
    }
}
