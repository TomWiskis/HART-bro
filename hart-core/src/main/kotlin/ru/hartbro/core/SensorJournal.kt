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
    val verifiedAt: Instant? = null, val succeeded: Boolean = false, val error: String = "",
    val communicator:String="",val command:Int=when(field){SensorField.ZERO->43;SensorField.LONG_TAG->22;else->18},
    val methodId:String="",val methodSource:String="",val tolerance:Float?=null,val resolution:Float?=null,val accuracySource:String="",
    val pressureKind:PressureKind=PressureKind.UNKNOWN,val outcome:SensorOutcome?=null,val pvBefore:ZeroValue?=null,val pvAfter:List<ZeroValue> = emptyList()) {
    val resultText get()=outcome?.label ?: if(succeeded)"Подтверждено чтением" else "Результат не подтверждён"
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
                    writeInt(0x48525333); writeInt(2); writeUTF(record.id); writeUTF(record.time.toString()); writeUTF(record.identity.raw.hex())
                    writeUTF(record.field.name); writeUTF(record.original); writeUTF(record.requested); writeUTF(record.stage.name)
                    writeUTF(record.tx); writeUTF(record.rx); writeInt(record.responseCode ?: -1); writeInt(record.deviceStatus ?: -1)
                    writeUTF(record.actual.orEmpty()); writeUTF(record.verifiedAt?.toString().orEmpty()); writeBoolean(record.succeeded); writeUTF(record.error.take(8000))
                    writeUTF(record.communicator.take(2000));writeInt(record.command);writeUTF(record.methodId);writeUTF(record.methodSource.take(4000))
                    writeBoolean(record.tolerance!=null);record.tolerance?.let{writeFloat(it)}
                    writeBoolean(record.resolution!=null);record.resolution?.let{writeFloat(it)}
                    writeUTF(record.accuracySource.take(4000));writeUTF(record.pressureKind.name);writeUTF(record.outcome?.name.orEmpty())
                    fun pv(value:ZeroValue){writeFloat(value.value);writeInt(value.unitCode);writeUTF(value.at.toString())}
                    writeBoolean(record.pvBefore!=null);record.pvBefore?.let(::pv)
                    require(record.pvAfter.size<=3);writeInt(record.pvAfter.size);record.pvAfter.forEach(::pv)
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
    @Synchronized fun recoverInterrupted() { list(Int.MAX_VALUE).filter { it.stage != WriteStage.FINISHED }.forEach { save(it.copy(stage = WriteStage.FINISHED, succeeded = false, outcome=if(it.stage==WriteStage.SENT)SensorOutcome.UNKNOWN else SensorOutcome.ABORTED, error = "Процесс прерван. Результат не подтверждён; автоматическая повторная запись запрещена")) } }
    private fun read(file: File): SensorRecord {
        require(file.length() in 1..65536)
        return DataInputStream(file.inputStream().buffered()).use { input -> with(input) {
            require(readInt() == 0x48525333);val version=readInt();require(version in 1..2)
            val id = readUTF(); file(id); val time = Instant.parse(readUTF()); val identity = DeviceIdentity.parse(hexBytes(readUTF()))
            val field = SensorField.valueOf(readUTF()); val original = readUTF(); val requested = readUTF(); val stage = WriteStage.valueOf(readUTF())
            val tx = readUTF(); val rx = readUTF(); val code = readInt().takeIf { it >= 0 }; val status = readInt().takeIf { it >= 0 }
            val actual = readUTF().ifEmpty { null }; val at = readUTF().ifEmpty { null }?.let(Instant::parse); val success = readBoolean(); val error = readUTF()
            var result=SensorRecord(id,time,identity,field,original,requested,stage,tx,rx,code,status,actual,at,success,error)
            if(version==2) {
                val communicator=readUTF();val command=readInt();val method=readUTF();val source=readUTF()
                val tolerance=if(readBoolean())readFloat()else null;val resolution=if(readBoolean())readFloat()else null
                val accuracy=readUTF();val kind=PressureKind.valueOf(readUTF());val outcome=readUTF().ifEmpty{null}?.let(SensorOutcome::valueOf)
                fun pv()=ZeroValue(readFloat(),readInt(),Instant.parse(readUTF()))
                val before=if(readBoolean())pv()else null;val count=readInt();require(count in 0..3)
                result=result.copy(communicator=communicator,command=command,methodId=method,methodSource=source,tolerance=tolerance,resolution=resolution,
                    accuracySource=accuracy,pressureKind=kind,outcome=outcome,pvBefore=before,pvAfter=List(count){pv()})
            }
            require(read() == -1);result
        } }
    }
}
