package ru.hartbro.core

import java.io.File
import java.io.InputStream
import java.io.ByteArrayOutputStream
import java.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class HartFragment(val bytes:ByteArray,val receivedAt:Instant=Instant.now(),val receivedNanos:Long=System.nanoTime())
enum class CommandEffect { READ, WRITE, UNKNOWN }
data class CapturedHart(val receivedAt:Instant?,val firstFragmentAt:Instant?,val frame:HartFrame?,val raw:ByteArray,
    val error:String?=null,val failure:HartFailureKind?=null) {
    val valid get()=frame!=null
    val response get()=frame?.let{it.isResponse||it.isBurst} ?: false
    val command:Int? get()=frame?.let { f ->
        if(f.command!=31)f.command else {
            val offset=if(response)2 else 0
            if(f.payload.size>=offset+2)((f.payload[offset].toInt() and 255) shl 8) or (f.payload[offset+1].toInt() and 255) else null
        }
    }
    val data:ByteArray get()=frame?.let{f->f.payload.drop((if(response)2 else 0)+(if(f.command==31)2 else 0)).toByteArray()} ?: byteArrayOf()
    val effect get()=when(command){in ReadCommand.entries.map{it.number}->CommandEffect.READ;6,17,18,19,22,35,36,37,38,40,42,43,44,45,46,52->CommandEffect.WRITE;else->CommandEffect.UNKNOWN}
    val responseCode get()=frame?.payload?.firstOrNull()?.toInt()?.and(255)?.takeIf{response}
    val deviceStatus get()=frame?.payload?.getOrNull(1)?.toInt()?.and(255)?.takeIf{response}
    val address get()=frame?.address?.hex().orEmpty()
    val summary get()=if(!valid)"ОТКЛОНЁН: $error" else "${if(frame!!.isBurst)"BURST" else if(response)"Ответ" else "Запрос"} · команда ${command ?: "31 (неполная расширенная)"} · ${effect.name} · адрес $address · данные ${data.hex()}"+
        (responseCode?.let{" · код $it (${HartStatus.responseDescription(it)}), статус 0x%02X".format(deviceStatus)} ?: "")+
        " · XOR 0x%02X проверена".format(raw.last().toInt() and 255)
    fun text()="${receivedAt?.toString() ?: "TIME-UNKNOWN"} [CAPTURE-${if(valid)"VALID" else "INVALID"}] $summary\n${raw.hex()}\n"
}
class CaptureDecoder {
    private val decoder=HartDecoder();private var first:Instant?=null;private var last:Instant?=null
    fun accept(bytes:ByteArray,at:Instant?):List<CapturedHart> {
        if(bytes.isNotEmpty())last=at
        if(!decoder.hasPartialFrame)first=at
        val start=first
        val events=decoder.accept(bytes).map { e ->when(e){is DecodeEvent.Frame->CapturedHart(at,start,e.value,e.value.raw);is DecodeEvent.Rejected->CapturedHart(at,start,null,e.raw,e.reason,e.failure)} }
        if(!decoder.hasPartialFrame)first=null else if(events.isNotEmpty())first=at
        return events
    }
    fun finish(at:Instant?=null):List<CapturedHart> = if(!decoder.hasPartialFrame)emptyList() else listOf(CapturedHart(last ?: at,first,null,decoder.reset(),"Неполный кадр: поток/запись завершены",HartFailureKind.INCOMPLETE_FRAME)).also{first=null}
}
data class HartExchangePair(val request:CapturedHart,val response:CapturedHart,val ambiguous:Boolean)
data class HartAnalysis(val source:String,val records:List<CapturedHart>,val notes:List<String> = emptyList()) {
    val valid get()=records.count{it.valid}
    val writes get()=records.filter{it.valid&&!it.response&&it.effect==CommandEffect.WRITE}
    fun pairs():List<HartExchangePair> {
        val pending=mutableMapOf<String,MutableList<CapturedHart>>();val result=mutableListOf<HartExchangePair>()
        records.filter{it.valid&&!it.frame!!.isBurst}.forEach{r->val key="${r.address}/${r.command}";if(!r.response)pending.getOrPut(key){mutableListOf()}+=r
            else pending.remove(key)?.let{result+=HartExchangePair(it.last(),r,it.size>1)} }
        return result
    }
    fun text()=buildString {
        appendLine("HART-бро · анализ обмена · источник: $source")
        appendLine("Корректных кадров: $valid; отклонений декодера: ${records.size-valid}; наблюдаемых известных команд записи: ${writes.size}")
        appendLine("Импорт/приём не подтверждает исполнение операции. Неизвестная команда не объявляется установкой нуля. Автоматического повтора пакетов нет.")
        notes.forEach{appendLine(it)};records.forEach{append(it.text())}
    }
    fun compare(before:HartAnalysis):List<String> {
        fun group(a:HartAnalysis)=a.records.filter{it.valid&&!it.response}.groupBy{"${it.address}/${it.command}"}
        val a=group(before);val b=group(this)
        return (a.keys+b.keys).sorted().map{key->val x=a[key].orEmpty();val y=b[key].orEmpty()
            "$key: запросов ${x.size} → ${y.size}; параметры до [${x.map{it.data.hex()}.distinct().joinToString(" | ")}], после [${y.map{it.data.hex()}.distinct().joinToString(" | ")}]"
        }
    }
}
object HartLogImport {
    const val MAX_BYTES=16*1024*1024
    const val MAX_RECORDS=20000
    /** Parses complete HEX lines, not arbitrary numbers in prose/timestamps. Never opens a transport. */
    private fun parseHexLine(line:String):ByteArray? {
        if(line.isEmpty())return null
        val out=ByteArrayOutputStream();var i=0
        while(i<line.length){
            while(i<line.length&&line[i].isWhitespace())i++
            if(i==line.length)break
            if(i+1>=line.length)return null
            val hi=line[i].digitToIntOrNull(16) ?: return null;val lo=line[i+1].digitToIntOrNull(16) ?: return null
            i+=2;if(i<line.length&&!line[i].isWhitespace())return null
            out.write((hi shl 4)or lo)
        }
        return out.toByteArray().takeIf{it.isNotEmpty()}
    }
    fun readText(input:InputStream):String {
        val out=ByteArrayOutputStream();val buffer=ByteArray(8192)
        while(true){val n=input.read(buffer);if(n<0)break;require(out.size()+n<=MAX_BYTES){"Журнал превышает 16 МиБ"};out.write(buffer,0,n)}
        return out.toString(Charsets.UTF_8.name())
    }
    fun parse(text:String,source:String="Импорт HEX"):HartAnalysis {
        require(text.toByteArray().size<=MAX_BYTES){"Журнал превышает 16 МиБ"}
        val lines=text.removePrefix("\uFEFF").lineSequence().toList()
        val assembledRx=lines.any{it.contains("[RX-FRAME]")||it.contains("[FRAME]")||it.contains("[HART-FAULT]")||it.contains("[CAPTURE-")}
        val records=mutableListOf<CapturedHart>();val stream=CaptureDecoder()
        var block:CaptureDecoder?=null;var at:Instant?=null;var accept=true
        fun add(values:List<CapturedHart>){require(records.size+values.size<=MAX_RECORDS){"Более 20000 событий: разделите журнал"};records+=values}
        lines.forEach{rawLine->
            val line=rawLine.trim()
            val header=Regex("^(\\S+)\\s+\\[([^]]+)]").find(line)
            if(header!=null) {
                block?.let{add(it.finish(at))};block=null
                at=runCatching{Instant.parse(header.groupValues[1])}.getOrNull()
                val tag=header.groupValues[2]
                accept=tag in setOf("TX-FRAME","RX-FRAME","FRAME","HART-FAULT","CAPTURE-VALID","CAPTURE-INVALID") || tag=="RX-CHUNK"&&!assembledRx
                if(accept&&tag!="RX-CHUNK")block=CaptureDecoder()
            } else {
                val plain=line.removePrefix("TX:").removePrefix("RX:").trim()
                if(accept)parseHexLine(plain)?.let{bytes->
                    var offset=0
                    while(offset<bytes.size){val end=minOf(offset+4096,bytes.size);add((block ?: stream).accept(bytes.copyOfRange(offset,end),at));offset=end}
                }
            }
        }
        block?.let{add(it.finish(at))};add(stream.finish(at))
        require(records.isNotEmpty()){ "Полных HEX-строк с HART-кадрами не найдено. Нужен сырой HEX, журнал HART-бро или экспорт совместимого анализатора." }
        return HartAnalysis(source,records, listOf("Время отсутствующих меток не выдумывается; пары сопоставлены по адресу и команде, HART не содержит transaction ID."))
    }
}
/** Hard TX guard: receive-only mode has no HART client and cannot send bytes. */
class ReceiveOnlyTransport(private val raw:HartTransport):HartTransport {
    override val isOpen get()=raw.isOpen
    override fun onDisconnect(listener:()->Unit)=raw.onDisconnect(listener)
    override suspend fun write(bytes:ByteArray):Unit=throw UnsupportedOperationException("Передача запрещена в режиме прослушивания")
    override suspend fun receive()=raw.receive()
    override suspend fun receiveStamped()=raw.receiveStamped()
    override fun discardBufferedInput()=raw.discardBufferedInput()
    override fun close()=raw.close()
}
enum class CapturePhase { IDLE, OPENING, LISTENING, STOPPED, ERROR }
data class CaptureState(val phase:CapturePhase=CapturePhase.IDLE,val transportConnected:Boolean=false,val records:List<CapturedHart> = emptyList(),
    val total:Long=0,val valid:Long=0,val requestFrames:Long=0,val responseFrames:Long=0,val error:String?=null)
class PassiveCaptureEngine(private val scope:CoroutineScope,private val open:suspend()->HartTransport,private val file:File,
    private val diagnostic:(DiagnosticEntry)->Unit = {}) {
    private val mutable=MutableStateFlow(CaptureState());val state=mutable.asStateFlow()
    private var job:Job?=null;private var transport:ReceiveOnlyTransport?=null
    private val decoder=CaptureDecoder()
    private fun record(item:CapturedHart) {
        val text=item.text();synchronized(file){check(file.length()+text.toByteArray().size<=HartLogImport.MAX_BYTES){"Достигнут предел журнала 16 МиБ; приём остановлен, файл сохранён"}
        file.appendText(text)}
        mutable.update{it.copy(records=(it.records+item).takeLast(2000),total=it.total+1,valid=it.valid+if(item.valid)1 else 0,
            requestFrames=it.requestFrames+if(item.valid&&!item.response)1 else 0,responseFrames=it.responseFrames+if(item.valid&&item.response)1 else 0)}
        diagnostic(DiagnosticEntry(item.receivedAt ?: Instant.now(),"LISTEN",item.summary,item.raw))
    }
    fun snapshotText():String=synchronized(file){file.readText()}
    fun start() {
        check(job==null);file.parentFile?.mkdirs();file.writeText("HART-бро · приём без собственных HART-запросов · аппаратная видимость чужого обмена требует проверки\n")
        mutable.value=CaptureState(phase=CapturePhase.OPENING)
        job=scope.launch {
            try {
                val t=ReceiveOnlyTransport(open());transport=t
                t.onDisconnect{mutable.update{it.copy(transportConnected=false)}}
                check(t.isOpen){"Транспорт закрыт"};mutable.update{it.copy(phase=CapturePhase.LISTENING,transportConnected=true)}
                while(isActive){val part=t.receiveStamped();decoder.accept(part.bytes,part.receivedAt).forEach(::record)}
            }catch(e:CancellationException){throw e}catch(e:Exception){mutable.update{it.copy(phase=CapturePhase.ERROR,error=e.message)}}
            finally {
                runCatching{decoder.finish(Instant.now()).forEach(::record)}
                transport?.close();transport=null
                mutable.update{it.copy(transportConnected=false,phase=if(it.phase==CapturePhase.ERROR)it.phase else CapturePhase.STOPPED)}
            }
        }
    }
    suspend fun stop(){job?.cancelAndJoin();job=null}
}
