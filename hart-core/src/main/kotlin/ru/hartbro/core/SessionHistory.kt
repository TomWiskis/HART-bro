package ru.hartbro.core

import java.io.*
import java.time.Instant
import java.util.UUID

class HistoryLimitException : IOException("Достигнут предел временной истории (64 МиБ). Сохраните или завершите сессию.")
data class SessionInfo(val id: String, val startedAt: Long, val communicator: String, val deviceKey: String,
                       val manufacturer: String, val identityHex: String)
data class ChartPoint(val time: Long, val measurement: Measurement, val segment: Long)
data class HistoryView(val info: SessionInfo, val end: Long, val points: List<ChartPoint>, val events: List<Pair<Long, String>>,
                       val lastMeasurementAt: Long = end)

/** v1 remains readable. Record 2 is legacy; 4 marks a real gap, 5 an informational event. */
class SessionHistory(private val working: File, private val archives: File, private val maxBytes: Long = 64L * 1024 * 1024) {
    private var active: File? = null
    private val indexes = linkedMapOf<File, Index>()
    internal var decodedRecords: Long = 0
        private set
    init { working.mkdirs(); archives.mkdirs() }
    @Synchronized fun cleanOrphans() { working.listFiles()?.filter { it.extension == "tmp" }?.forEach { it.delete() } }
    @Synchronized fun begin(identity: DeviceIdentity, communicator: String, at: Instant) {
        check(active == null)
        val id = UUID.randomUUID().toString(); val file = File(working, "$id.tmp")
        DataOutputStream(BufferedOutputStream(FileOutputStream(file))).use { out ->
            out.writeInt(MAGIC); out.writeInt(1); out.writeUTF(id); out.writeLong(at.toEpochMilli())
            out.writeUTF(communicator); out.writeUTF(identity.uniqueKey); out.writeUTF(identity.manufacturerDescription); out.writeUTF(identity.raw.hex())
        }
        active = file
    }
    @Synchronized fun append(batch: MeasurementBatch) = record(1) { out ->
        out.writeLong(batch.receivedAt.toEpochMilli()); out.writeInt(batch.deviceStatus)
        out.writeFloat(batch.loopCurrent ?: Float.NaN); out.writeInt(batch.extendedStatus ?: -1)
        out.writeLong(batch.deviceTicks ?: -1); out.writeLong(batch.loopCurrentAt?.toEpochMilli() ?: -1); out.writeInt(batch.values.size)
        batch.values.forEach { v ->
            out.writeUTF(v.slot); out.writeFloat(v.value); out.writeInt(v.unitCode); out.writeInt(v.quality.ordinal)
            out.writeLong(v.receivedAt.toEpochMilli()); out.writeInt(v.command); out.writeInt(v.variableCode ?: -1)
            out.writeInt(v.classification ?: -1); out.writeInt(v.rawStatus ?: -1)
        }
    }
    @Synchronized fun event(at: Instant, text: String, gap: Boolean = false) {
        if (active != null) record(if (gap) 4 else 5) { it.writeLong(at.toEpochMilli()); it.writeUTF(text.take(1000)) }
    }
    private fun record(type: Int, block: (DataOutputStream) -> Unit) {
        val file = active ?: error("Нет активной сессии")
        val bytes = ByteArrayOutputStream().also { b -> DataOutputStream(b).use(block) }.toByteArray()
        val before = file.length()
        if (before + bytes.size + 5 > maxBytes) throw HistoryLimitException()
        try {
            DataOutputStream(BufferedOutputStream(FileOutputStream(file, true))).use { out ->
                out.writeInt(bytes.size + 1); out.writeByte(type); out.write(bytes)
            }
        } catch (e: IOException) { RandomAccessFile(file, "rw").use { it.setLength(before) }; throw e }
    }
    @Synchronized fun snapshot(at: Instant = Instant.now()): String {
        val file = active ?: error("Сначала начните сессию"); val id = UUID.randomUUID().toString()
        val staging = File(archives, "$id.partial")
        try {
            FileInputStream(file).use { input -> FileOutputStream(staging).use { input.copyTo(it); it.fd.sync() } }
            FileOutputStream(staging, true).use { fileOutput ->
                val output = DataOutputStream(fileOutput)
                output.writeInt(9); output.writeByte(3); output.writeLong(at.toEpochMilli()); output.flush(); fileOutput.fd.sync()
            }
            check(staging.renameTo(File(archives, "$id.hart"))) { "Не удалось завершить сохранение" }
        } finally { staging.delete() }
        return id
    }
    @Synchronized fun discard() { active?.let { indexes.remove(it); check(!it.exists() || it.delete()) }; active = null }
    @Synchronized fun list(): List<Pair<String, SessionInfo>> = archives.listFiles()?.filter { it.extension == "hart" }?.map {
        it.nameWithoutExtension to DataInputStream(BufferedInputStream(FileInputStream(it))).use(::readHeader)
    }?.sortedByDescending { it.second.startedAt } ?: emptyList()
    @Synchronized fun delete(id: String) { val f = archive(id); indexes.remove(f); check(!f.exists() || f.delete()) }
    private fun archive(id: String): File {
        require(id.matches(Regex("[a-f0-9-]{36}"))); return File(archives, "$id.hart")
    }
    private data class Cursor(var end: Long, var lastMeasurement: Long, var gap: Long = 0,
                              val segments: MutableMap<String, Long> = mutableMapOf(), val units: MutableMap<String, Int> = mutableMapOf()) {
        fun fork() = copy(segments = segments.toMutableMap(), units = units.toMutableMap())
    }
    private data class Checkpoint(val offset: Long, val cursor: Cursor)
    private class Index(val info: SessionInfo, var offset: Long, val cursor: Cursor) {
        val checkpoints = mutableListOf(Checkpoint(offset, cursor.fork()))
        var records = 0
        var ordered = true
        var lastTime = Long.MIN_VALUE
    }
    private fun index(file: File): Index {
        var index = indexes[file]
        RandomAccessFile(file, "r").use { input ->
            if (index == null || file.length() < index!!.offset) {
                val info = readHeader(input)
                index = Index(info, input.filePointer, Cursor(info.startedAt, info.startedAt))
                indexes[file] = index!!
                while (indexes.size > 3) indexes.remove(indexes.keys.first())
            }
            val current = index!!
            input.seek(current.offset)
            while (input.filePointer < input.length()) {
                if (current.records % 128 == 0 && input.filePointer != current.checkpoints.last().offset)
                    current.checkpoints += Checkpoint(input.filePointer, current.cursor.fork())
                readRecord(input) { type, data ->
                    val time = consume(type, data, current.cursor, {}, { _, _ -> })
                    if (time < current.lastTime) current.ordered = false
                    current.lastTime = time
                }
                current.records++; current.offset = input.filePointer
            }
        }
        return index!!
    }
    /** Sparse, incremental index: live updates scan only appended records, then the requested window. */
    @Synchronized fun view(id: String? = null, from: Long = Long.MIN_VALUE, to: Long = Long.MAX_VALUE, buckets: Int = 300): HistoryView? {
        val file = if (id == null) active ?: return null else archive(id)
        require(buckets in 1..1200)
        val index = index(file)
        val lo = maxOf(from, index.info.startedAt); val hi = minOf(to, index.cursor.end).coerceAtLeast(lo + 1)
        val checkpoint = if (index.ordered) index.checkpoints.lastOrNull { it.cursor.end < lo } ?: index.checkpoints.first() else index.checkpoints.first()
        val cursor = checkpoint.cursor.fork()
        val bins = mutableMapOf<String, Array<MutableList<ChartPoint>?>>()
        val events = ArrayDeque<Pair<Long, String>>()
        RandomAccessFile(file, "r").use { input ->
            input.seek(checkpoint.offset)
            while (input.filePointer < index.offset) {
                var time = 0L
                readRecord(input) { type, data -> time = consume(type, data, cursor, { point ->
                    if (point.time in lo..hi) {
                        val b = (((point.time - lo).toDouble() / (hi - lo)) * buckets).toInt().coerceIn(0, buckets - 1)
                        val array = bins.getOrPut(point.measurement.slot) { arrayOfNulls(buckets) }
                        val bin = array[b] ?: mutableListOf<ChartPoint>().also { array[b] = it }
                        bin += point
                        if (bin.size > 4) {
                            val keep = listOf(bin.first(), bin.minBy { it.measurement.value }, bin.maxBy { it.measurement.value }, bin.last()).distinct()
                            bin.clear(); bin.addAll(keep.sortedBy { it.time })
                        }
                    }
                }, { at, text -> if (at in lo..hi) { events.addLast(at to text); if (events.size > 100) events.removeFirst() } }) }
                if (index.ordered && time > hi) break
            }
        }
        return HistoryView(index.info, index.cursor.end, bins.values.flatMap { it.filterNotNull().flatten() }.sortedBy { it.time }, events.toList(), index.cursor.lastMeasurement)
    }
    @Synchronized fun bounds(id: String? = null): Pair<Long, Long>? {
        val file = if (id == null) active ?: return null else archive(id)
        val i = index(file)
        return i.info.startedAt to if (id == null) i.cursor.lastMeasurement else i.cursor.end
    }
    @Synchronized fun window(id: String?, viewport: ChartViewport): HistoryWindow? {
        val domain = bounds(id) ?: return null
        val range = viewport.bounds(domain.first, domain.second)
        val view = view(id, range.first, range.last) ?: return null
        return HistoryWindow(view, range, domain.first..domain.second)
    }
    private fun consume(type: Int, data: DataInputStream, cursor: Cursor, point: (ChartPoint) -> Unit, event: (Long, String) -> Unit): Long {
        val time = data.readLong(); cursor.end = maxOf(cursor.end, time)
        if (type in setOf(2, 4, 5)) {
            val text = data.readUTF()
            if (type == 4 || (type == 2 && legacyGap(text))) cursor.gap++
            event(time, text)
        } else if (type == 1) {
            cursor.lastMeasurement = maxOf(cursor.lastMeasurement, time)
            data.readInt(); data.readFloat(); data.readInt(); data.readLong(); data.readLong()
            val count = data.readInt(); require(count in 1..4)
            val present = mutableSetOf<String>()
            repeat(count) {
                val slot = data.readUTF(); require(slot in MeasurementDecoder.slots); present += slot
                val v = Measurement(slot, data.readFloat(), data.readInt(), Quality.entries[data.readInt()], Instant.ofEpochMilli(data.readLong()),
                    data.readInt(), data.readInt().takeIf { it >= 0 }, data.readInt().takeIf { it >= 0 }, data.readInt().takeIf { it >= 0 })
                if (!v.plottable || cursor.units[slot]?.let { it != v.unitCode } == true) cursor.segments[slot] = (cursor.segments[slot] ?: 0) + 1
                cursor.units[slot] = v.unitCode
                if (v.plottable) point(ChartPoint(time, v, cursor.gap * 1_000_000_000 + (cursor.segments[slot] ?: 0)))
            }
            MeasurementDecoder.slots.filter { it !in present }.forEach { cursor.segments[it] = (cursor.segments[it] ?: 0) + 1 }
        } else require(type == 3) { "Неизвестная запись истории $type" }
        return time
    }
    private fun legacyGap(text: String) = text == "Пауза" || listOf("Ошибка обмена", "Связь потеряна", "Остановка:", "Первоначальное чтение").any(text::startsWith)
    private fun readHeader(input: DataInput): SessionInfo {
        require(input.readInt() == MAGIC && input.readInt() == 1) { "Неизвестный формат архива" }
        return SessionInfo(input.readUTF(), input.readLong(), input.readUTF(), input.readUTF(), input.readUTF(), input.readUTF())
    }
    private fun readRecord(input: RandomAccessFile, consume: (Int, DataInputStream) -> Unit) {
        val length = input.readInt(); require(length in 1..4096) { "Повреждённая запись истории" }
        val bytes = ByteArray(length); input.readFully(bytes); decodedRecords++
        DataInputStream(ByteArrayInputStream(bytes)).use { consume(it.readUnsignedByte(), it) }
    }
    companion object { private const val MAGIC = 0x48425232 }
}

data class HistoryWindow(val view: HistoryView, val range: LongRange, val domain: LongRange)
