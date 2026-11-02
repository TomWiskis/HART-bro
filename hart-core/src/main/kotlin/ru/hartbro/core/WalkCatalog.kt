package ru.hartbro.core

import java.time.Instant
import java.util.UUID

data class WalkRow(val id: String, val observed: PassportReading, val fields: Map<PassportField, PassportFact>,
                   val acceptedRange: ConfiguredRange?, val pendingRange: ConfiguredRange? = null,
                   val pendingRangeFact: PassportFact? = null, val manuallySeparated: Boolean = false) {
    val key: String get() = observed.key
    val requiresIdentityCheck: Boolean get() = !reliablePassportKey(observed.identity) || manuallySeparated
    fun text(field: PassportField): String? {
        val fact = fields[field] ?: return null
        if (field in setOf(PassportField.SERIAL, PassportField.DATE) && !fact.factoryConfirmed) return null
        return fact.value?.takeIf { it.isNotBlank() }
    }
    val completedFields: Int get() = PassportField.entries.count { text(it) != null }
}
data class PendingPassport(val id: String, val reading: PassportReading, val explanation: String)
data class WalkBook(val id: String = UUID.randomUUID().toString(), val startedAt: Instant = Instant.now(),
                    val endedAt: Instant? = null, val title: String = "Обход датчиков", val rows: List<WalkRow> = emptyList(),
                    val pending: List<PendingPassport> = emptyList())
data class WalkMerge(val rowId: String?, val pendingId: String?)

/** Thread-safe document model: identity collisions never overwrite a row; manual facts survive automatic refresh. */
class WalkCatalog(initial: WalkBook = WalkBook()) {
    private var book = initial
    private val resolutions=mutableMapOf<String,String>()
    @Synchronized fun takeResolvedRow(pendingId:String):String? = resolutions.remove(pendingId)
    @Synchronized fun refreshConfirmed(rowId:String,reading:PassportReading):WalkMerge {
        val row=book.rows.first { it.id==rowId };require(row.key==reading.key)
        if(numbersChanged(row,reading)) return ingest(reading)
        book=book.copy(rows=book.rows.map { if(it.id==rowId) refresh(it,reading) else it });return WalkMerge(rowId,null)
    }
    @Synchronized fun snapshot() = book.copy(rows = book.rows.toList(), pending = book.pending.toList())
    private fun newRow(reading: PassportReading, separate: Boolean = false) = WalkRow(UUID.randomUUID().toString(), reading, reading.fields.toMap(), reading.range, manuallySeparated = separate)
    @Synchronized fun ingest(reading: PassportReading, currentPending: String? = null, reappeared: Boolean = false): WalkMerge {
        val targets = book.rows.filter { it.key == reading.key }
        if (targets.isEmpty()) {
            check(book.rows.size < 10000) { "Достигнут предел 10000 приборов: сохраните ведомость и начните следующий обход" }
            val row = newRow(reading);book = book.copy(rows = book.rows + row);return WalkMerge(row.id, null)
        }
        val target = targets.singleOrNull()
        val changedNumbers = target?.let { numbersChanged(it,reading) } ?: false
        if (target == null || !reliablePassportKey(reading.identity) || changedNumbers || reappeared || book.pending.any { it.id==currentPending }) {
            val pending = PendingPassport(currentPending?.takeIf { id -> book.pending.any { it.id == id } } ?: UUID.randomUUID().toString(), reading,
                if(reappeared) "Прибор обнаружен повторно после отсутствия. Тот же HART ID может принадлежать другому датчику: подтвердите строку или создайте новую."
                else if (changedNumbers) "Совпадает HART ID, но изменились номера сенсора/сборки. Проверьте прибор и разделите записи при необходимости."
                else "Идентификатор не позволяет однозначно выбрать строку. Требуется ручная проверка.")
            check(book.pending.size<10000 || book.pending.any { it.id==pending.id }) { "Достигнут предел несопоставленных чтений" }
            book = book.copy(pending = book.pending.filterNot { it.id == pending.id } + pending)
            return WalkMerge(null, pending.id)
        }
        val updated = refresh(target, reading)
        book = book.copy(rows = book.rows.map { if (it.id == target.id) updated else it })
        return WalkMerge(target.id, null)
    }
    private fun numbersChanged(before:WalkRow,reading:PassportReading) = listOf("sensorNumber","assemblyNumber").any { key ->
        val a=before.observed.candidates[key]?.value;val b=reading.candidates[key]?.value
        a!=null && b!=null && a!=b
    }
    private fun refresh(previous: WalkRow, reading: PassportReading): WalkRow {
        val fields = previous.fields.toMutableMap()
        reading.fields.filterKeys { it != PassportField.RANGE }.forEach { (key, value) ->
            if (fields[key]?.manual != true && value.value != null) fields[key] = value
        }
        var accepted = previous.acceptedRange;var pending = previous.pendingRange;var pendingFact = previous.pendingRangeFact
        val range = reading.range
        if (range != null) {
            if (accepted != null && range != accepted || accepted == null && fields[PassportField.RANGE]?.manual == true) {
                pending = range;pendingFact = reading.fields[PassportField.RANGE]
            } else {
                pending = null;pendingFact = null
                if (fields[PassportField.RANGE]?.manual != true) { fields[PassportField.RANGE] = reading.fields.getValue(PassportField.RANGE);accepted = range }
            }
        }
        return previous.copy(observed = reading.copy(candidates=previous.observed.candidates + reading.candidates), fields = fields, acceptedRange = accepted, pendingRange = pending, pendingRangeFact = pendingFact)
    }
    @Synchronized fun edit(rowId: String, values: Map<PassportField, String>, at: Instant = Instant.now()) {
        val row = book.rows.first { it.id == rowId };val fields = row.fields.toMutableMap()
        values.forEach { (key, value) ->
            require(value.length <= 200) { "Поле должно содержать не более 200 символов" }
            // Unchanged editor fields retain their original provenance; intentionally clearing a field is manual too.
            if (value != fields[key]?.value.orEmpty()) fields[key] = PassportFact.manual(value, at, key == PassportField.DATE)
        }
        book = book.copy(rows = book.rows.map { if (it.id == rowId) row.copy(fields = fields.toMap()) else it })
    }
    @Synchronized fun acceptRange(rowId: String) {
        book = book.copy(rows = book.rows.map { row -> if (row.id != rowId || row.pendingRange == null) row else
            row.copy(fields = row.fields + (PassportField.RANGE to row.pendingRangeFact!!), acceptedRange = row.pendingRange,
                pendingRange = null, pendingRangeFact = null) })
    }
    @Synchronized fun resolvePending(pendingId: String, existingRowId: String? = null) {
        val pending = book.pending.first { it.id == pendingId }
        check(book.rows.size < 10000 || existingRowId!=null)
        val new = if(existingRowId==null) newRow(pending.reading,true) else null
        val rows = if (existingRowId == null) book.rows + new!! else {
            val target = book.rows.first { it.id == existingRowId };require(target.key == pending.reading.key)
            book.rows.map { if (it.id == existingRowId) refresh(it, pending.reading) else it }
        }
        resolutions[pendingId]=existingRowId ?: new!!.id
        book = book.copy(rows = rows, pending = book.pending.filterNot { it.id == pendingId })
    }
    @Synchronized fun separate(rowId: String) {
        check(book.rows.size<10000)
        val original = book.rows.first { it.id == rowId }
        book = book.copy(rows = book.rows + newRow(original.observed, true))
    }
    @Synchronized fun finish(at: Instant = Instant.now()) { book = book.copy(endedAt = at) }
    @Synchronized fun rename(title: String) { require(title.length <= 200);book = book.copy(title = title.trim().ifEmpty { "Обход датчиков" }) }
}
