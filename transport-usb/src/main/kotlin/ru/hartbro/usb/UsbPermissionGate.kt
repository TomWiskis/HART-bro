package ru.hartbro.usb

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred

/** One Android permission request per attachment/explicit selection. Late results cannot grant a new request. */
class UsbPermissionGate {
    data class Ticket(val path:String,val sequence:Long,val result:CompletableDeferred<Unit>)
    private var sequence=0L
    private val attempted=mutableSetOf<String>()
    private var pending:Ticket?=null
    @Synchronized fun resetSelection() { check(pending==null);attempted.clear() }
    @Synchronized fun begin(path:String):Ticket {
        check(pending==null){"Запрос USB уже выполняется"}
        if(!attempted.add(path))throw IOException("Доступ USB не получен. Для нового запроса выберите подключение вручную")
        return Ticket(path,++sequence,CompletableDeferred()).also{pending=it}
    }
    @Synchronized fun resolve(path:String,sequence:Long,present:Boolean,granted:Boolean):Boolean {
        val ticket=pending ?: return false
        if(ticket.path!=path || ticket.sequence!=sequence)return false
        if(present && granted)ticket.result.complete(Unit)
        else ticket.result.completeExceptionally(IOException(if(!present)"USB отключён до выдачи разрешения" else "Доступ USB отклонён"))
        return true
    }
    @Synchronized fun detached(path:String) {
        attempted.remove(path)
        pending?.takeIf{it.path==path}?.result?.completeExceptionally(IOException("USB отключён до выдачи разрешения"))
    }
    @Synchronized fun finish(ticket:Ticket) { if(pending===ticket)pending=null;ticket.result.cancel() }
}
