package ru.hartbro.core

import java.io.*
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.UUID

data class SensorRecord(val id: String = UUID.randomUUID().toString(), val time: Instant = Instant.now(),
    val identity: DeviceIdentity, val field: SensorField, val original: String, val requested: String,
    val stage: WriteStage = WriteStage.PREPARED, val tx: String = "", val rx: String = "",
    val responseCode: Int? = null, val deviceStatus: Int? = null, val actual: String? = null,
    val verifiedAt: Instant? = null, val succeeded: Boolean = false, val error: String = "") {
    val change: String get() = "${this.field.label}: ${original.ifBlank { "не заполнено" }} → $requested"
}
/** Separate versioned storage preserves the existing command-35 journal unchanged. */
class SensorJournal(private val directory: File) {
    private fun file(id: String): File { require(UUID.fromString(id).toString() == id); return File(directory, "$id.sensor") }
    @Synchronized fun save(record: SensorRecord) {
        check(directory.isDirectory || directory.mkdirs()) { "Каталог журнала недоступен" }
        val target = file(record.id); val temp = File(directory, "${record.id}.tmp")
        try {
            FileOutputStream(temp).use { stream ->
                val out = DataOutputStream(BufferedOutputStream(stream))
                with(out) {
                    writeInt(0x48525333); writeInt(1); writeUTF(record.id); writeUTF(record.time.toString()); writeUTF(record.identity.raw.hex())
                    writeUTF(record.field.name); writeUTF(record.original); writeUTF(record.requested); writeUTF(record.stage.name)
                    writeUTF(record.tx); writeUTF(record.rx); writeInt(record.responseCode ?: -1); writeInt(record.deviceStatus ?: -1)
                    writeUTF(record.actual.orEmpty()); writeUTF(record.verifiedAt?.toString().orEmpty()); writeBoolean(record.succeeded); writeUTF(record.error.take(8000))
                    flush(); stream.fd.sync()
                }
            }
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { temp.delete() }
    }
    @Synchronized fun list(limit: Int = 100): List<SensorRecord> = directory.listFiles()?.filter { it.extension == "sensor" }
        ?.sortedByDescending { it.lastModified() }?.take(limit)?.map(::read).orEmpty().sortedByDescending { it.time }
    @Synchronized fun delete(id: String) { val f = file(id); if(!f.exists()) return; check(read(f).stage == WriteStage.FINISHED); check(f.delete()) }
    @Synchronized fun deleteAll() { val records = list(Int.MAX_VALUE); check(records.all { it.stage == WriteStage.FINISHED }); records.forEach { delete(it.id) } }
    @Synchronized fun recoverInterrupted() { list(Int.MAX_VALUE).filter { it.stage != WriteStage.FINISHED }.forEach { save(it.copy(stage = WriteStage.FINISHED, succeeded = false, error = "Процесс прерван. Результат не подтверждён; автоматическая повторная запись запрещена")) } }
    private fun read(file: File): SensorRecord {
        require(file.length() in 1..65536)
        return DataInputStream(file.inputStream().buffered()).use { input -> with(input) {
            require(readInt() == 0x48525333 && readInt() == 1)
            val id = readUTF(); file(id); val time = Instant.parse(readUTF()); val identity = DeviceIdentity.parse(hexBytes(readUTF()))
            val field = SensorField.valueOf(readUTF()); val original = readUTF(); val requested = readUTF(); val stage = WriteStage.valueOf(readUTF())
            val tx = readUTF(); val rx = readUTF(); val code = readInt().takeIf { it >= 0 }; val status = readInt().takeIf { it >= 0 }
            val actual = readUTF().ifEmpty { null }; val at = readUTF().ifEmpty { null }?.let(Instant::parse); val success = readBoolean(); val error = readUTF()
            require(read() == -1)
            SensorRecord(id,time,identity,field,original,requested,stage,tx,rx,code,status,actual,at,success,error)
        } }
    }
}
