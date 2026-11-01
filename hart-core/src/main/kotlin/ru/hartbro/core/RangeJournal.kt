package ru.hartbro.core

import java.io.*
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.UUID

enum class WriteStage { PREPARED, SENT, FINISHED }
data class RangeRecord(val id:String=UUID.randomUUID().toString(),val time:Instant=Instant.now(),val identity:DeviceIdentity,
                       val tag:String?,val original:ConfiguredRange,val requested:ConfiguredRange,
                       val tx:String="",val rx:String="",val responseCode:Int?=null,val deviceStatus:Int?=null,
                       val echoed:ConfiguredRange?=null,val actual:ConfiguredRange?=null,val verifiedAt:Instant?=null,
                       val outcome:RangeOutcome=RangeOutcome.PENDING,val stage:WriteStage=WriteStage.PREPARED,val errors:List<String> = emptyList()) {
    fun text():String=buildString {
        appendLine("HART-бро — операция конфигурирования $id");appendLine("Время UTC: $time")
        appendLine("Прибор: ${identity.manufacturerDescription}; тип 0x%04X; Device ID %06X; HART ${identity.hartRevision}".format(identity.deviceType,identity.deviceId))
        appendLine("TAG: ${tag ?: "не получен"}; команда 35")
        appendLine("Исходный: ${original.label}; код единицы ${original.unitCode}");appendLine("Запрошен: ${requested.label}; код единицы ${requested.unitCode}")
        appendLine("TX: ${tx.ifBlank{"не передан / не зафиксирован"}}");appendLine("RX: ${rx.ifBlank{"не получен"}}")
        appendLine("Код ответа: ${responseCode?.let { "$it — ${RangeResponseCodes.description(it)}" } ?: "нет ответа"}")
        appendLine("Статус: ${deviceStatus?.let{ "0x%02X — ${HartStatus.deviceDescription(it)}".format(it)} ?: "не получен"}")
        appendLine("Ответ 35: ${echoed?.label ?: "не декодирован"}");appendLine("Фактически прочитано 15: ${actual?.label ?: "не подтверждено"}; UTC $verifiedAt")
        appendLine("Результат: $outcome; стадия $stage");errors.forEach{appendLine(it)}
        appendLine("Этот журнал не выполняет повторную запись и не восстанавливает прежние настройки автоматически.")
    }
}
/** Durable intent is committed before the transport is allowed to send command 35. */
class RangeJournal(private val directory:File) {
    private fun file(id:String):File { require(UUID.fromString(id).toString()==id);return File(directory,"$id.range") }
    @Synchronized fun save(record:RangeRecord) {
        check(directory.isDirectory || directory.mkdirs()){"Каталог журнала недоступен"}
        val target=file(record.id);val temp=File(directory,"${record.id}.tmp")
        try {
            FileOutputStream(temp).use { stream ->
                val out=DataOutputStream(BufferedOutputStream(stream));with(out) {
                    writeInt(0x48523531);writeInt(1);writeUTF(record.id);writeUTF(record.time.toString());writeUTF(record.identity.raw.hex());writeUTF(record.tag.orEmpty())
                    range(record.original);range(record.requested);writeUTF(record.tx);writeUTF(record.rx);writeInt(record.responseCode ?: -1);writeInt(record.deviceStatus ?: -1)
                    optionalRange(record.echoed);optionalRange(record.actual);writeUTF(record.verifiedAt?.toString().orEmpty());writeUTF(record.outcome.name);writeUTF(record.stage.name)
                    require(record.errors.size<=100);writeInt(record.errors.size);record.errors.forEach{writeUTF(it.take(4000))};flush();stream.fd.sync()
                }
            }
            require(temp.length()<=1024*1024)
            Files.move(temp.toPath(),target.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
        } finally {temp.delete()}
    }
    @Synchronized fun delete(id: String) {
        val target = file(id)
        if (!target.exists()) return
        val record = read(target)
        check(record.stage == WriteStage.FINISHED) { "Нельзя удалить выполняемую операцию" }
        check(target.delete()) { "Не удалось удалить запись" }
    }
    @Synchronized fun deleteAll() {
        val files = directory.listFiles()?.filter { it.extension == "range" }.orEmpty()
        check(files.all { read(it).stage == WriteStage.FINISHED }) { "Дождитесь завершения операции" }
        files.forEach { check(it.delete()) { "Не удалось удалить запись" } }
    }
    @Synchronized fun load(id:String)=read(file(id))
    @Synchronized fun list(limit:Int=100):List<RangeRecord> = directory.listFiles()?.filter{it.extension=="range"}?.sortedByDescending{it.lastModified()}?.take(limit)?.map(::read).orEmpty().sortedByDescending{it.time}
    @Synchronized fun recoverInterrupted():List<RangeRecord> {
        val interrupted=directory.listFiles()?.filter{it.extension=="range"}?.map(::read)?.filter{it.stage!=WriteStage.FINISHED}.orEmpty()
        return interrupted.map { r -> r.copy(outcome=RangeOutcome.UNKNOWN,stage=WriteStage.FINISHED,errors=r.errors+"Процесс завершился во время операции. Требуется проверочное чтение; повторная передача запрещена.").also(::save) }
    }
    private fun read(file:File):RangeRecord {
        require(file.length() in 1..1024*1024)
        return DataInputStream(file.inputStream().buffered()).use { input ->with(input) {
            require(readInt()==0x48523531 && readInt()==1)
            val id=readUTF();file(id);val time=Instant.parse(readUTF());val identity=DeviceIdentity.parse(readUTF().split(' ').map{it.toInt(16).toByte()}.toByteArray());val tag=readUTF().ifEmpty{null}
            val original=range();val requested=range();val tx=readUTF();val rx=readUTF();val code=readInt().takeIf{it>=0};val status=readInt().takeIf{it>=0}
            val echoed=optionalRange();val actual=optionalRange();val at=readUTF().ifEmpty{null}?.let(Instant::parse);val result=RangeOutcome.valueOf(readUTF());val stage=WriteStage.valueOf(readUTF())
            val count=readInt();require(count in 0..100);val errors=List(count){readUTF()};require(read()==-1)
            RangeRecord(id,time,identity,tag,original,requested,tx,rx,code,status,echoed,actual,at,result,stage,errors)
        } }
    }
    private fun DataOutputStream.range(r:ConfiguredRange){writeFloat(r.lower);writeFloat(r.upper);writeInt(r.unitCode)}
    private fun DataInputStream.range()=ConfiguredRange(readFloat(),readFloat(),readInt()).also{require(it.lower.isFinite() && it.upper.isFinite() && it.unitCode in 0..255)}
    private fun DataOutputStream.optionalRange(r:ConfiguredRange?){writeBoolean(r!=null);r?.let{range(it)}}
    private fun DataInputStream.optionalRange()=if(readBoolean())range()else null
}
