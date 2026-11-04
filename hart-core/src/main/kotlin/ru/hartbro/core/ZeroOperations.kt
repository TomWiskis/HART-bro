package ru.hartbro.core

import java.io.IOException
import java.time.Instant
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** One confirmed write; every recovery action afterwards is a read. Never replays captured packets. */
class ZeroOperations(private val client:HartClient,private val methods:ZeroMethods=ZeroMethods()) {
    suspend fun prepare(expected:DeviceIdentity):ZeroPreparation = client.transaction {
        val id=identify();if(passportKey(id)!=passportKey(expected))throw RangeDeviceChanged(expected,id)
        val errors=mutableListOf<String>()
        val info=try{ConfigurationDecoder.deviceInformation(read(id.address,ReadCommand.DEVICE_INFORMATION).data,id.hartRevision)}
            catch(e:CancellationException){throw e}catch(e:IOException){throw e}catch(e:Exception){errors+="Состояние прибора: ${e.message}";emptyMap()}
        val pv=try{val r=read(id.address,ReadCommand.PRIMARY_VARIABLE);require(r.deviceStatus and 0x81==0){"PV имеет недостоверный статус"};PrimaryVariable.parse(r.data,r.receivedAt)}
            catch(e:CancellationException){throw e}catch(e:IOException){throw e}catch(e:Exception){errors+="PV до операции: ${e.message}";null}
        val unit=pv?.unitCode ?: info["rangeUnit"]?.value?.toIntOrNull()
        require(!knownNonPressureUnit(unit)){"Основная переменная прибора не является давлением"}
        ZeroPreparation(id,pv?.let{ZeroValue(it.value,it.unitCode,it.receivedAt)},unit,
            when(info["writeProtect"]?.value){"1"->true;"0"->false;else->null},methods.available(id),errors)
    }
    suspend fun apply(edit:SensorEdit,journal:SensorJournal):SensorEditResult = client.transaction {
        val options=edit.zero
        require(options.conditionsConfirmed && options.pressureConfirmed){"Установка нуля требует отдельного подтверждения условий и типа прибора"}
        val method=methods.select(edit.identity,options.methodId)
        var record=SensorRecord(identity=edit.identity,field=SensorField.ZERO,original="",requested="0",communicator=edit.communicator,
            command=method.command,methodId=method.id,methodSource=method.source,accuracySource=options.accuracySource,tolerance=options.tolerance,resolution=options.resolution,
            pressureKind=options.pressureKind,outcome=SensorOutcome.ABORTED)
        journal.save(record)
        var found:DeviceIdentity?=null
        var exchange:HartExchange?=null
        var writeFailure:Exception?=null
        var originalUnit:Int?=null
        val readings=mutableListOf<ZeroValue>()
        try {
            val id=identify();found=id
            if(passportKey(id)!=passportKey(edit.identity))throw RangeDeviceChanged(edit.identity,id)
            val info=try{read(id.address,ReadCommand.DEVICE_INFORMATION)}catch(e:CancellationException){throw e}catch(e:IOException){throw e}catch(_:Exception){null}
            val config=info?.let{runCatching{ConfigurationDecoder.deviceInformation(it.data,id.hartRevision)}.getOrNull()}.orEmpty()
            if(config["writeProtect"]?.value=="1") {
                record=record.copy(outcome=SensorOutcome.WRITE_PROTECTED,error="Защита записи включена; команда не отправлена")
                return@transaction SensorEditResult(record.copy(stage=WriteStage.FINISHED),null,id)
            }
            require(info==null || info.deviceStatus and 0x80==0){"Прибор сообщает неисправность; команда не отправлена"}
            val before=try {val r=read(id.address,ReadCommand.PRIMARY_VARIABLE);require(r.deviceStatus and 0x81==0){"PV недостоверна"};PrimaryVariable.parse(r.data,r.receivedAt)}
                catch(e:CancellationException){throw e}catch(e:IOException){throw e}catch(e:Exception){record=record.copy(error="PV до операции недоступна: ${e.message}");null}
            originalUnit=before?.unitCode ?: config["rangeUnit"]?.value?.toIntOrNull()
            require(!knownNonPressureUnit(originalUnit)){"PV не является давлением; команда не отправлена"}
            require(options.unitCode==null || originalUnit==options.unitCode){"Единица PV изменилась после подтверждения; перечитайте прибор"}
            record=record.copy(original=before?.let{"${it.value} ${it.unit}"}.orEmpty(),pvBefore=before?.let{ZeroValue(it.value,it.unitCode,it.receivedAt)})
            journal.save(record)
            val write=SensorWrite.ZeroMethod(method)
            try {
                exchange=writeSensor(id.address,write){tx->record=record.copy(stage=WriteStage.SENT,tx=tx.hex(),outcome=SensorOutcome.UNKNOWN);journal.save(record)}
                record=record.copy(rx=exchange!!.rxFrame.hex(),responseCode=exchange!!.responseCode,deviceStatus=exchange!!.deviceStatus)
            }catch(e:CancellationException){throw e}catch(e:Exception){
                writeFailure=e
                record=record.copy(rx=(when(e){is HartTimeoutException->e.receivedRaw;is HartWriteIOException->e.receivedRaw;else->byteArrayOf()}).hex(),error=listOf(record.error,"Ответ записи: ${e.message}").filter{it.isNotBlank()}.joinToString("; "))
            }
            val code=exchange?.responseCode
            if(code!=null && code!=0) {
                val outcome=when(code){64->SensorOutcome.NOT_SUPPORTED;7,16->SensorOutcome.WRITE_PROTECTED;32->SensorOutcome.BUSY;2,3,4,9,10->SensorOutcome.CONDITIONS_REJECTED;else->SensorOutcome.DEVICE_ERROR}
                record=record.copy(outcome=outcome,error="${outcome.label}; HART $code: ${ZeroResponses.description(method.command,code)}")
            } else {
                // A lost reply does not authorise a second write, even if PV later happens to read zero.
                val check=identify();found=check
                if(passportKey(check)!=passportKey(id))throw RangeDeviceChanged(id,check)
                repeat(3) {
                    delay(500)
                    try {
                        val reply=read(id.address,ReadCommand.PRIMARY_VARIABLE)
                        require(reply.deviceStatus and 0x81==0){"Недостоверный статус PV"}
                        val pv=PrimaryVariable.parse(reply.data,reply.receivedAt)
                        require(!knownNonPressureUnit(pv.unitCode)){"PV не является давлением"}
                        readings+=ZeroValue(pv.value,pv.unitCode,pv.receivedAt)
                    }catch(e:CancellationException){throw e}catch(e:IOException){throw e}catch(e:Exception){record=record.copy(error=listOf(record.error,"Проверка PV: ${e.message}").filter{it.isNotBlank()}.joinToString("; "))}
                }
                val limit=options.tolerance?.plus((options.resolution ?: 0f)/2)
                val canVerify=limit!=null && limit.isFinite() && options.unitCode!=null && originalUnit==options.unitCode && exchange?.deviceStatus?.and(0x81)==0 && readings.size==3 && readings.all{it.unitCode==originalUnit}
                val verified=canVerify && readings.all{abs(it.value)<=limit!!}
                val outcome=when {
                    record.stage!=WriteStage.SENT->SensorOutcome.ABORTED
                    exchange==null->if(writeFailure is HartTimeoutException && (writeFailure as HartTimeoutException).malformedFrames)SensorOutcome.INVALID_RESPONSE else SensorOutcome.NO_ACK
                    !canVerify->SensorOutcome.ACCEPTED_UNVERIFIED
                    verified->SensorOutcome.SUCCEEDED
                    else->SensorOutcome.NOT_VERIFIED
                }
                record=record.copy(outcome=outcome,succeeded=outcome==SensorOutcome.SUCCEEDED,
                    actual=readings.joinToString{it.text}.ifEmpty{null},pvAfter=readings.toList(),verifiedAt=Instant.now())
            }
        }catch(e:CancellationException){record=record.copy(outcome=if(record.stage==WriteStage.SENT)SensorOutcome.UNKNOWN else SensorOutcome.ABORTED,error="Операция прервана. Повторная запись запрещена");throw e}
        catch(e:Exception){record=record.copy(outcome=when{exchange?.responseCode==0->SensorOutcome.ACCEPTED_UNVERIFIED;record.stage==WriteStage.SENT->SensorOutcome.UNKNOWN;else->SensorOutcome.ABORTED},error=listOf(record.error,e.message).filterNot{it.isNullOrBlank()}.joinToString("; "))}
        finally {record=record.copy(stage=WriteStage.FINISHED,pvAfter=readings.toList());journal.save(record)}
        SensorEditResult(record,null,found)
    }
}
