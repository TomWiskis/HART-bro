package ru.hartbro.core

import java.time.Instant

/** Presentation only: one exact receive timestamp, at most one source value for each HART slot. */
data class MeasurementRow(val at: Instant, val values: Map<String, Measurement>, val deviceStatuses: Map<String, Int>)
data class ExportProfile(val rows: Long, val sourceValues: Long, val exportedValues: Long,
                         val units: Map<String, Set<Int>>)

fun measurementRows(history: SessionHistory, id: String, emit: (MeasurementRow) -> Unit) {
    var at: Instant? = null
    val values = linkedMapOf<String, Measurement>()
    val statuses = linkedMapOf<String, Int>()
    fun flush() { at?.let { emit(MeasurementRow(it, values.toMap(), statuses.toMap())) }; values.clear();statuses.clear() }
    history.records(id, sample = { batch ->
        batch.values.sortedBy { it.receivedAt }.forEach { value ->
            require(value.slot in MeasurementDecoder.slots)
            val previous = at
            require(previous == null || value.receivedAt >= previous) { "Временные метки истории идут назад. Для исходного порядка сохраните технический CSV." }
            if (previous != value.receivedAt) { flush(); at = value.receivedAt }
            val existing = values[value.slot]
            require(existing == null || existing == value && statuses[value.slot] == batch.deviceStatus) {
                "В одной временной метке ${value.receivedAt} обнаружены разные значения ${value.slot}; экспорт не будет перезаписывать измерения. Сохраните технический CSV."
            }
            values[value.slot] = value
            statuses[value.slot] = batch.deviceStatus
        }
    })
    flush()
}

fun exportProfile(history: SessionHistory, id: String): ExportProfile {
    var rows = 0L; var source = 0L; var exported = 0L
    val units = linkedMapOf<String, MutableSet<Int>>()
    history.records(id, sample = { source += it.values.size })
    measurementRows(history, id) { row ->
        rows++; exported += row.values.size
        row.values.values.forEach { units.getOrPut(it.slot) { linkedSetOf() } += it.unitCode }
    }
    return ExportProfile(rows, source, exported, units)
}

/** Manufacturing facts must have their own confirmed source; cmd 0 ID and editable cmd 13 date are not substitutes. */
data class DeviceRegisterRow(val tag: String?, val factorySerial: String?, val manufacturedOn: java.time.LocalDate?,
                             val model: String, val configuredRange: String?, val hartDeviceId: String)
fun deviceRegister(infos: List<SessionInfo>): List<DeviceRegisterRow> = infos.groupBy { it.manufacturer to it.deviceKey }.values.map { records ->
    val info = records.first()
    val type = info.configuration.parameters["deviceType"]?.value ?: info.deviceKey.substringBefore('-')
    DeviceRegisterRow(records.mapNotNull { it.configuration.tag }.distinct().joinToString(" / ").ifEmpty { null },
        factorySerial = null, manufacturedOn = null,
        model = runCatching { HartDeviceCatalog.title(DeviceIdentity.parse(hexBytes(info.identityHex))) }.getOrDefault("${info.manufacturer}; тип HART 0x$type"),
        configuredRange = records.mapNotNull { it.configuration.range?.label }.distinct().joinToString(" / ").ifEmpty { null },
        hartDeviceId = info.deviceKey)
}
