package ru.hartbro.core

import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class RangePhase { CLOSED, READING, FORM, CONFIRM, PRECHECK, WRITING, VERIFYING, RECOVERING, RESULT }
data class RangeState(val transportConnected:Boolean=false, val phase:RangePhase=RangePhase.CLOSED,val snapshot:RangeSnapshot?=null,val token:String=UUID.randomUUID().toString(),
                      val requested:ConfiguredRange?=null,val validation:RangeValidation?=null,val record:RangeRecord?=null,
                      val verification:RangeVerification?=null,val otherDevice:DeviceIdentity?=null,val message:String="",val secondsLeft:Int?=null) {
    val busy get()=phase in setOf(RangePhase.READING,RangePhase.PRECHECK,RangePhase.WRITING,RangePhase.VERIFYING,RangePhase.RECOVERING)
}
/** Owned by the application/service. Compose only issues explicit actions; it never launches a write. */
class RangeEngine(private val scope:CoroutineScope,initial:MonitorConnection?,private val connect:suspend()->MonitorConnection,
                  private val journal:RangeJournal,private val expected:DeviceIdentity?=null,
                  private val finished:(RangeRecord?,RangeVerification?,MonitorConnection?,Boolean)->Unit = {_,_,_,_->},
                  private val clock:()->Long = {System.nanoTime()/1_000_000}) {
    private val mutable=MutableStateFlow(RangeState());val state=mutable.asStateFlow()
    @Volatile var connection:MonitorConnection?=initial;private set
    private var job:Job?=null
    private val sendControl=Any()
    private var sentCommitted=false
    private var cancelRequested=false
    fun cancelBeforeSend():Boolean=synchronized(sendControl) {
        if(sentCommitted || state.value.phase !in setOf(RangePhase.READING,RangePhase.PRECHECK))return@synchronized false
        cancelRequested=true;job?.cancel();true
    }
    fun open() {
        check(job==null)
        mutable.update{it.copy(phase=RangePhase.READING,message="Чтение текущего диапазона и пределов сенсора…")}
        job=scope.launch {
            try {
                val snapshot=try { ensureConnection();connection!!.rangeSnapshot() } catch(e:java.io.IOException) {
                    connection?.close();connection=null;ensureConnection();connection!!.rangeSnapshot()
                }
                if(expected!=null && passportKey(expected)!=passportKey(snapshot.identity)) throw RangeDeviceChanged(expected,snapshot.identity)
                mutable.update{it.copy(phase=RangePhase.FORM,snapshot=snapshot,message="Данные прибора прочитаны")}
            } catch(e:CancellationException){throw e}catch(e:RangeDeviceChanged){mutable.update{it.copy(phase=RangePhase.RESULT,otherDevice=e.actual,message=e.message.orEmpty())}}
            catch(e:Exception){mutable.update{it.copy(phase=RangePhase.RESULT,message="Чтение не выполнено: ${e.message}")}}
        }
    }
    @Synchronized fun select(lower:String,upper:String,unitCode:Int=state.value.snapshot?.range?.unitCode ?: -1) {
        val before=state.value;if(before.phase!=RangePhase.FORM) return
        val validation=RangeRules.validate(before.snapshot!!,lower,upper,unitCode)
        mutable.update{it.copy(phase=if(validation.allowed)RangePhase.CONFIRM else RangePhase.FORM,requested=validation.range,validation=validation,
            message=if(validation.unchanged)"Новый диапазон совпадает с текущим. Запись не требуется." else validation.errors.joinToString("\n"))}
    }
    @Synchronized fun cancelConfirmation(){if(state.value.phase==RangePhase.CONFIRM)mutable.update{it.copy(phase=RangePhase.FORM)}}
    @Synchronized fun confirm(token:String) {
        val before=state.value
        if(before.phase!=RangePhase.CONFIRM || before.token!=token) return
        val snapshot=before.snapshot!!;val requested=before.requested!!
        mutable.update{it.copy(phase=RangePhase.PRECHECK,message="Повторная проверка прибора перед записью…")}
        job=scope.launch {
            var record=RangeRecord(identity=snapshot.identity,tag=snapshot.tag,original=snapshot.range,requested=requested)
            var verification:RangeVerification?=null
            try {
                journal.save(record);mutable.update{it.copy(record=record)}
                val attempt=connection!!.applyRange(snapshot,requested,beforeSend={tx ->
                    synchronized(sendControl) {
                        if(cancelRequested)throw CancellationException("Подготовка отменена до передачи")
                        val sent=record.copy(tx=tx.hex(),stage=WriteStage.SENT);journal.save(sent);record=sent;sentCommitted=true
                        mutable.update{it.copy(phase=RangePhase.WRITING,message="Запись диапазона…",record=record)}
                    }
                },onReply={reply ->
                    record=record.copy(rx=reply.rxFrame.hex(),responseCode=reply.responseCode,deviceStatus=reply.deviceStatus,
                        echoed=if(RangeResponseCodes.classify(reply.responseCode) in setOf(ResponseClass.SUCCESS,ResponseClass.WARNING))runCatching{rangeReply(reply.data)}.getOrNull() else null);journal.save(record)
                    mutable.update{it.copy(phase=RangePhase.VERIFYING,record=record,message="Проверочное чтение команды 15…")}
                },onInvalidRx={raw->
                    record=record.copy(rx="НЕПОДТВЕРЖДЁННЫЙ RX-поток: "+raw.hex());journal.save(record);mutable.update{it.copy(record=record)}
                })
                verification=attempt.verification
                val errors=attempt.errors.toMutableList()
                if(attempt.sent && verification==null && !attempt.aborted) {
                    try {verification=verifyWithRecovery(snapshot.identity)}
                    catch(e:RangeDeviceChanged){throw e}catch(e:CancellationException){throw e}catch(e:Exception){errors+="Проверка: ${e.message}"}
                }
                verification?.configuration?.responses?.firstOrNull{it.command==15}?.deviceStatus?.takeIf{it!=0}?.let{status->errors+="Контрольная 15: статус 0x%02X — ${HartStatus.deviceDescription(status)}".format(status)}
                val finalAttempt=attempt.copy(verification=verification)
                record=record.copy(actual=verification?.range,verifiedAt=verification?.readAt,outcome=rangeOutcome(requested,snapshot.range,finalAttempt),stage=WriteStage.FINISHED,errors=errors)
            } catch(e:RangeDeviceChanged){
                record=record.copy(outcome=RangeOutcome.DIFFERENT_DEVICE,stage=WriteStage.FINISHED,errors=record.errors+e.message.orEmpty())
                mutable.update{it.copy(otherDevice=e.actual)}
            } catch(e:CancellationException){
                record=record.copy(outcome=if(cancelRequested && !sentCommitted)RangeOutcome.ABORTED else RangeOutcome.UNKNOWN,stage=WriteStage.FINISHED,
                    errors=record.errors+if(cancelRequested && !sentCommitted)"Отменено до передачи команды 35" else "Операция прервана. Повторно передавать команду нельзя; требуется чтение.")
                withContext(NonCancellable){runCatching{journal.save(record)}};throw e
            } catch(e:Exception){record=record.copy(outcome=if(record.tx.isEmpty())RangeOutcome.ABORTED else RangeOutcome.UNKNOWN,stage=WriteStage.FINISHED,errors=record.errors+(e.message ?: e.javaClass.simpleName))}
            val persisted=runCatching{journal.save(record)}
            if(persisted.isFailure)record=record.copy(errors=record.errors+"Не удалось завершить журнал: ${persisted.exceptionOrNull()?.message}")
            mutable.update{it.copy(phase=RangePhase.RESULT,record=record,verification=verification,message=resultText(record),secondsLeft=null)}
            finished(record,verification,connection,record.outcome !in setOf(RangeOutcome.UNKNOWN,RangeOutcome.DIFFERENT_DEVICE))
        }
    }
    fun verifyOnly(record:RangeRecord) {
        check(job==null);mutable.update{it.copy(phase=RangePhase.VERIFYING,record=record,message="Проверка фактического диапазона; команда 35 не отправляется")}
        job=scope.launch {
            var result=record;var verification:RangeVerification?=null
            try {
                verification=verifyWithRecovery(record.identity)
                val outcome=if(sameRange(verification.range,record.requested))RangeOutcome.SUCCEEDED
                    else if(sameRange(verification.range,record.original))RangeOutcome.UNCHANGED else RangeOutcome.APPLIED_CHANGED
                val status=verification.configuration.responses.firstOrNull{it.command==15}?.deviceStatus
                result=record.copy(actual=verification.range,verifiedAt=verification.readAt,outcome=outcome,stage=WriteStage.FINISHED,
                    errors=record.errors+if(status!=null && status!=0)listOf("Контрольная 15: статус 0x%02X — ${HartStatus.deviceDescription(status)}".format(status))else emptyList())
            } catch(e:RangeDeviceChanged){result=record.copy(outcome=RangeOutcome.DIFFERENT_DEVICE,stage=WriteStage.FINISHED,errors=record.errors+e.message.orEmpty());mutable.update{it.copy(otherDevice=e.actual)}}
            catch(e:CancellationException){throw e}catch(e:Exception){result=record.copy(outcome=RangeOutcome.UNKNOWN,stage=WriteStage.FINISHED,errors=record.errors+"Проверка: ${e.message}")}
            runCatching{journal.save(result)}.onFailure{result=result.copy(errors=result.errors+"Ошибка журнала: ${it.message}")}
            mutable.update{it.copy(phase=RangePhase.RESULT,record=result,verification=verification,message=resultText(result),secondsLeft=null)}
            finished(result,verification,connection,result.outcome !in setOf(RangeOutcome.UNKNOWN,RangeOutcome.DIFFERENT_DEVICE))
        }
    }
    private suspend fun ensureConnection(){
        if(connection?.isOpen!=true){connection?.close();connection=connect()}
        connection?.onDisconnect { mutable.update { it.copy(transportConnected=false) } }
        mutable.update { it.copy(transportConnected=connection?.isOpen==true) }
    }
    private suspend fun verifyWithRecovery(identity:DeviceIdentity):RangeVerification {
        val deadline=clock()+60_000
        return withTimeout(60_000) {
            var hartAttempts=0
            while(true) {
                try {ensureConnection();return@withTimeout connection!!.verifyRange(identity)}
                catch(e:RangeDeviceChanged){throw e}catch(e:TimeoutCancellationException){currentCoroutineContext().ensureActive();connection?.close();connection=null}
                catch(e:CancellationException){throw e}catch(e:java.io.IOException){connection?.close();connection=null}
                catch(e:Exception){hartAttempts++;if(hartAttempts>=3)throw e}
                mutable.update{it.copy(phase=RangePhase.RECOVERING,message="Проверка диапазона после сбоя; запись не повторяется",secondsLeft=((deadline-clock()+999)/1000).toInt().coerceAtLeast(0))}
                delay(1000)
            }
            @Suppress("UNREACHABLE_CODE") error("unreachable")
        }
    }
    suspend fun stop(closeConnection:Boolean=true){
        val wasBusy=state.value.busy
        connection?.takeIf{wasBusy && closeConnection}?.close();job?.cancelAndJoin();job=null
        if(wasBusy) {
            val record=state.value.record?.let{runCatching{journal.load(it.id)}.getOrNull()}
            mutable.update{it.copy(phase=RangePhase.RESULT,record=record,message=record?.let(::resultText) ?: "Чтение прервано")}
        }
    }
    companion object {
        fun resultText(r:RangeRecord)=when(r.outcome){
            RangeOutcome.SUCCEEDED->"Успешно: новый диапазон подтверждён командой 15"
            RangeOutcome.APPLIED_CHANGED->"Применено с изменением: фактический диапазон ${r.actual?.label}"
            RangeOutcome.REJECTED->"Отклонено прибором: прежний диапазон подтверждён чтением"
            RangeOutcome.UNCHANGED->"Контрольное чтение подтверждает прежний диапазон; новое значение не подтверждено"
            RangeOutcome.DIFFERENT_DEVICE->"Обнаружен другой прибор. Проверка исходного устройства не завершена."
            RangeOutcome.ABORTED->"Запись не отправлена: ${r.errors.joinToString("; ")}"
            else->"Состояние диапазона не подтверждено. Требуется повторное чтение прибора."
        }
    }
}
