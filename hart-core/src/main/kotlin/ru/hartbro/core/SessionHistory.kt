package ru.hartbro.core

import java.io.*
import java.time.Instant
import java.util.UUID

class HistoryLimitException : IOException("Достигнут предел временной истории (64 МиБ). Сохраните или завершите сессию.")
data class SessionInfo(val id: String, val startedAt: Long, val communicator: String, val deviceKey: String,
                       val manufacturer: String, val identityHex: String)
data class ChartPoint(val time: Long, val measurement: Measurement, val segment: Long)
data class HistoryView(val info: SessionInfo, val end: Long, val points: List<ChartPoint>, val events: List<Pair<Long, String>>)

/** Bounded disk journal, not Compose state. Archives are immutable explicit snapshots, never autosaves. */
class SessionHistory(private val working: File, private val archives: File, private val maxBytes: Long = 64L * 1024 * 1024) {
    private var active: File? = null
    init { working.mkdirs(); archives.mkdirs() }
    @Synchronized fun cleanOrphans() { working.listFiles()?.filter { it.extension == "tmp" }?.forEach { it.delete() } }
    @Synchronized fun begin(identity: DeviceIdentity, communicator: String, at: Instant) {
        check(active == null)
        val id = UUID.randomUUID().toString()
        val file = File(working, "$id.tmp")
        DataOutputStream(BufferedOutputStream(FileOutputStream(file))).use { out ->
            out.writeInt(MAGIC); out.writeInt(1)
            out.writeUTF(id); out.writeLong(at.toEpochMilli()); out.writeUTF(communicator)
            out.writeUTF(identity.uniqueKey); out.writeUTF(identity.manufacturerDescription); out.writeUTF(identity.raw.hex())
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
    @Synchronized fun event(at: Instant, text: String) {
        if (active != null) record(2) { it.writeLong(at.toEpochMilli()); it.writeUTF(text.take(4000)) }
    }
    private fun record(type: Int, block: (DataOutputStream) -> Unit) {
        val file = active ?: error("Нет активной сессии")
        if (file.length() >= maxBytes) throw HistoryLimitException()
        val bytes = ByteArrayOutputStream().also { b -> DataOutputStream(b).use { block(it) } }.toByteArray()
        DataOutputStream(BufferedOutputStream(FileOutputStream(file, true))).use { out ->
            out.writeInt(bytes.size + 1); out.writeByte(type); out.write(bytes)
        }
    }
    @Synchronized fun snapshot(at: Instant = Instant.now()): String {
        val file = active ?: error("Сначала начните сессию")
        val id = UUID.randomUUID().toString()
        val staging = File(archives, "$id.partial")
        try {
            FileInputStream(file).use { input -> FileOutputStream(staging).use { output ->
                input.copyTo(output); output.fd.sync()
            } }
            // Snapshot boundary is explicit, including the open pause/loss interval up to this instant.
            // This small metadata record is permitted even when the active history has reached its cap.
            FileOutputStream(staging, true).use { fileOutput ->
                val output = DataOutputStream(fileOutput)
                output.writeInt(9); output.writeByte(3); output.writeLong(at.toEpochMilli())
                output.flush(); fileOutput.fd.sync()
            }
            check(staging.renameTo(File(archives, "$id.hart"))) { "Не удалось завершить сохранение" }
        } finally { staging.delete() }
        return id
    }
    @Synchronized fun discard() { active?.let { check(!it.exists() || it.delete()) }; active = null }
    @Synchronized fun list(): List<Pair<String, SessionInfo>> = archives.listFiles()?.filter { it.extension == "hart" }?.map {
        it.nameWithoutExtension to DataInputStream(BufferedInputStream(FileInputStream(it))).use(::readHeader)
    }?.sortedByDescending { it.second.startedAt } ?: emptyList()
    @Synchronized fun delete(id: String) { val f = archive(id); check(!f.exists() || f.delete()) }
    private fun archive(id: String): File {
        require(id.matches(Regex("[a-f0-9-]{36}")))
        return File(archives, "$id.hart")
    }
    /** Whole-history scan uses O(buckets * variables) memory and preserves gap/unit/quality boundaries. */
    @Synchronized fun view(id: String? = null, from: Long = Long.MIN_VALUE, to: Long = Long.MAX_VALUE, buckets: Int = 300): HistoryView? {
        val file = if (id == null) active ?: return null else archive(id)
        require(buckets in 1..1200)
        DataInputStream(BufferedInputStream(FileInputStream(file))).use { input ->
            val info = readHeader(input)
            var end = info.startedAt
            // First pass determines domain without holding the complete session in memory.
            scan(input) { type, data -> if (type in 1..3) end = maxOf(end, data.readLong()) }
            val lo = maxOf(from, info.startedAt); val hi = minOf(to, end).coerceAtLeast(lo + 1)
            val bins = mutableMapOf<String, Array<MutableList<ChartPoint>?>>()
            val segments = mutableMapOf<String, Long>()
            val units = mutableMapOf<String, Int>()
            var gap = 0L
            val events = ArrayDeque<Pair<Long, String>>()
            DataInputStream(BufferedInputStream(FileInputStream(file))).use { stream ->
                readHeader(stream)
                scan(stream) { type, data ->
                    if (type == 2) {
                        val time = data.readLong(); val text = data.readUTF()
                        // Save events do not interrupt valid series; all other session events do.
                        if (!text.startsWith("Снимок сохранён")) gap++
                        if (time in lo..hi) { events.addLast(time to text); if (events.size > 100) events.removeFirst() }
                    } else if (type == 1) {
                        val time = data.readLong(); data.readInt(); data.readFloat(); data.readInt(); data.readLong(); data.readLong()
                        val count = data.readInt(); require(count in 1..4)
                        val present = mutableSetOf<String>()
                        repeat(count) {
                            val slot = data.readUTF(); require(slot in MeasurementDecoder.slots); present += slot
                            val v = Measurement(slot, data.readFloat(), data.readInt(), Quality.entries[data.readInt()],
                                Instant.ofEpochMilli(data.readLong()), data.readInt(), data.readInt().takeIf { it >= 0 },
                                data.readInt().takeIf { it >= 0 }, data.readInt().takeIf { it >= 0 })
                            if (!v.plottable || units[slot]?.let { it != v.unitCode } == true) segments[slot] = (segments[slot] ?: 0) + 1
                            units[slot] = v.unitCode
                            if (time in lo..hi && v.plottable) {
                                val point = ChartPoint(time, v, gap * 1_000_000_000 + (segments[slot] ?: 0))
                                val index = (((time - lo).toDouble() / (hi - lo)) * buckets).toInt().coerceIn(0, buckets - 1)
                                val array = bins.getOrPut(slot) { arrayOfNulls(buckets) }
                                val bin = array[index] ?: mutableListOf<ChartPoint>().also { array[index] = it }
                                bin += point
                                // First, extrema and last retain spikes with bounded memory. Different segments never connect.
                                if (bin.size > 4) {
                                    val keep = listOf(bin.first(), bin.minBy { it.measurement.value }, bin.maxBy { it.measurement.value }, bin.last()).distinct()
                                    bin.clear(); bin.addAll(keep.sortedBy { it.time })
                                }
                            }
                        }
                        MeasurementDecoder.slots.filter { it !in present }.forEach { segments[it] = (segments[it] ?: 0) + 1 }
                    }
                }
            }
            return HistoryView(info, end, bins.values.flatMap { array -> array.filterNotNull().flatten() }.sortedBy { it.time }, events.toList())
        }
    }
    private fun readHeader(input: DataInputStream): SessionInfo {
        require(input.readInt() == MAGIC && input.readInt() == 1) { "Неизвестный формат архива" }
        return SessionInfo(input.readUTF(), input.readLong(), input.readUTF(), input.readUTF(), input.readUTF(), input.readUTF())
    }
    private fun scan(input: DataInputStream, consume: (Int, DataInputStream) -> Unit) {
        while (true) {
            val first = input.read(); if (first == -1) return
            val length = (first shl 24) or (input.readUnsignedByte() shl 16) or (input.readUnsignedByte() shl 8) or input.readUnsignedByte()
            require(length in 1..4096) { "Повреждённая запись истории" }
            val bytes = ByteArray(length); input.readFully(bytes)
            DataInputStream(ByteArrayInputStream(bytes)).use { consume(it.readUnsignedByte(), it) }
        }
    }
    companion object { private const val MAGIC = 0x48425232 }
}
