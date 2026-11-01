package ru.hartbro.core

import java.time.Instant

/** Freshness is independent of Bluetooth availability and device quality bits. */
fun measurementFresh(at: Instant?, now: Instant, periodMillis: Long): Boolean = at != null &&
    java.time.Duration.between(at, now).toMillis() in 0..maxOf(5000, periodMillis * 3)
fun sensorTitle(identity: DeviceIdentity?): String = identity?.let {
    PassportProfiles().find(it)?.model(it) ?: "HART-датчик · ${it.manufacturerName ?: "производитель неизвестен"} · ${it.uniqueKey}"
} ?: "HART-датчик"
fun primaryParameter(unit: Int?): String = when(unit) {
    in 1..14, 237 -> "Давление"
    in 32..35 -> "Температура"
    else -> "Основной параметр"
}
fun rangeChanges(record: RangeRecord): List<String> = buildList {
    val a = record.original; val b = record.requested
    fun number(v: Float) = v.toString().removeSuffix(".0").replace('.', ',')
    if (a.lower != b.lower || a.unitCode != b.unitCode) add("Нижний предел: ${number(a.lower)} ${HartUnits.find(a.unitCode)?.symbol ?: a.unitCode} → ${number(b.lower)} ${HartUnits.find(b.unitCode)?.symbol ?: b.unitCode}")
    if (a.upper != b.upper || a.unitCode != b.unitCode) add("Верхний предел: ${number(a.upper)} ${HartUnits.find(a.unitCode)?.symbol ?: a.unitCode} → ${number(b.upper)} ${HartUnits.find(b.unitCode)?.symbol ?: b.unitCode}")
    if (a.unitCode != b.unitCode) add("Единица: ${HartUnits.find(a.unitCode)?.symbol ?: a.unitCode} → ${HartUnits.find(b.unitCode)?.symbol ?: b.unitCode}")

}

/** An expected measurement that failed must turn the HART indicator off immediately. */
fun MonitorState.hartExchangeCurrent(now: Instant): Boolean = bluetoothConnected && identity != null &&
    (phase == MonitorPhase.READY || phase == MonitorPhase.MONITORING && latest != null) &&
    measurementFresh(lastHartSuccess, now, periodMillis)
