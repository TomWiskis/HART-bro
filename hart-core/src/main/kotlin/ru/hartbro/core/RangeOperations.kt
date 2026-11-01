package ru.hartbro.core

import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.CancellationException

class RangeDeviceChanged(val expected:DeviceIdentity,val actual:DeviceIdentity):Exception("Обнаружен другой прибор: ${actual.uniqueKey}. Подготовленные значения не отправлены.")
class RangeStale(message:String):Exception(message)
enum class ResponseClass { SUCCESS, WARNING, ERROR, COMMUNICATION, UNKNOWN }
/** HCF_SPEC-151 rev.8, command 35, pp.29–30. These meanings are command-specific (8 and 14 are warnings). */
object RangeResponseCodes {
    private val codes=mapOf(
        0 to (ResponseClass.SUCCESS to "Нет ошибок команды"),2 to (ResponseClass.ERROR to "Недопустимый выбор"),
        5 to (ResponseClass.ERROR to "Недостаточно байтов запроса"),6 to (ResponseClass.ERROR to "Ошибка устройства"),
        7 to (ResponseClass.ERROR to "Защита записи"),8 to (ResponseClass.WARNING to "Установлено ближайшее допустимое значение"),
        9 to (ResponseClass.ERROR to "LRV выше допустимого"),10 to (ResponseClass.ERROR to "LRV ниже допустимого"),
        11 to (ResponseClass.ERROR to "URV выше допустимого"),12 to (ResponseClass.ERROR to "URV ниже допустимого"),
        13 to (ResponseClass.ERROR to "Обе границы вне пределов"),14 to (ResponseClass.WARNING to "Малая ширина; точность может ухудшиться"),
        16 to (ResponseClass.ERROR to "Доступ ограничен"),18 to (ResponseClass.ERROR to "Недопустимый код единицы"),
        29 to (ResponseClass.ERROR to "Недопустимая ширина диапазона"),32 to (ResponseClass.ERROR to "Прибор занят"),
        64 to (ResponseClass.ERROR to "Команда не реализована"))
    fun classify(code:Int)=if(code and 0x80!=0) ResponseClass.COMMUNICATION else codes[code]?.first ?: ResponseClass.UNKNOWN
    fun description(code:Int)=if(code and 0x80!=0) "Ошибка связи 0x%02X".format(code) else codes[code]?.second ?: "Код $code: назначение не определено для команды 35"
}

class RangeOperations(private val client:HartClient,
    // Only a documented device capability provider may enable alternatives. Universal reads do not enumerate writable units.
    private val supportedUnits: (DeviceIdentity, ConfiguredRange) -> Set<Int> = { _, range -> setOf(range.unitCode) }) {
    suspend fun prepare():RangeSnapshot = client.transaction { snapshot() }
    private suspend fun HartClient.Transaction.snapshot():RangeSnapshot {
        val identity=identify();val configReply=read(identity.address,ReadCommand.DEVICE_INFORMATION)
        val configuration=ConfigurationSnapshot(ConfigurationDecoder.deviceInformation(configReply.data,identity.hartRevision),
            listOf(ConfigResponse(15,configReply.data.hex(),configReply.receivedAt.toString(),configReply.deviceStatus,responseCode=0)))
        val range=configuration.range ?: throw IllegalArgumentException("Не получен достоверный диапазон команды 15")
        val warnings=mutableListOf<String>()
        if(configReply.deviceStatus!=0)warnings+="Статус прибора: ${HartStatus.deviceDescription(configReply.deviceStatus)}"
        val sensor=try { PassportDecoder.sensor(read(identity.address,ReadCommand.SENSOR_INFORMATION).data) }
            catch(e:CancellationException){throw e}catch(e:IOException){throw e}catch(e:Exception){warnings+="Команда 14: ${e.message}";null}
        var tag:String?=null
        if(identity.hartRevision>=6) try { tag=ConfigurationDecoder.longTag(read(identity.address,ReadCommand.LONG_TAG).data)["longTag"]?.value?.takeIf{it.isNotBlank()} }
            catch(e:CancellationException){throw e}catch(e:IOException){throw e}catch(_:Exception){}
        if(tag==null) try { tag=ConfigurationDecoder.tagDescription(read(identity.address,ReadCommand.TAG_DESCRIPTION).data)["tag"]?.value?.takeIf{it.isNotBlank()} }
            catch(e:CancellationException){throw e}catch(e:IOException){throw e}catch(_:Exception){}
        val protection=when(configuration.parameters["writeProtect"]?.value?.toIntOrNull()){0->false;1->true;else->null}
        return RangeSnapshot(identity,range,sensor,tag,protection,configuration,Instant.now(),warnings,supportedUnits(identity,range).filter { UnitConversion.between(range.unitCode,it)!=null }.toSet()+range.unitCode)
    }
    suspend fun verify(expected:DeviceIdentity):RangeVerification=client.transaction { verification(expected) }
    private suspend fun HartClient.Transaction.verification(expected:DeviceIdentity):RangeVerification {
        val actual=identify();if(passportKey(actual)!=passportKey(expected)) throw RangeDeviceChanged(expected,actual)
        val reply=read(actual.address,ReadCommand.DEVICE_INFORMATION)
        val config=ConfigurationSnapshot(ConfigurationDecoder.deviceInformation(reply.data,actual.hartRevision),
            listOf(ConfigResponse(15,reply.data.hex(),reply.receivedAt.toString(),reply.deviceStatus,responseCode=0)))
        require(config.range!=null){"Недостоверная команда 15 после записи"}
        return RangeVerification(actual,config)
    }
    suspend fun apply(prepared:RangeSnapshot,requested:ConfiguredRange,beforeSend:(ByteArray)->Unit,onReply:(HartExchange)->Unit,onInvalidRx:(ByteArray)->Unit = {}):RangeAttempt = client.transaction {
        val errors=mutableListOf<String>();var sent=false;var reply:HartExchange?=null;var preflight:RangeSnapshot?=null
        // This complete block owns the same mutex as read(): no measurement can enter the 35 → 0 → 15 sequence.
        try {
            val fresh=snapshot();preflight=fresh
            if(passportKey(fresh.identity)!=passportKey(prepared.identity)) throw RangeDeviceChanged(prepared.identity,fresh.identity)
            if(!sameRange(fresh.range,prepared.range) || fresh.sensor!=prepared.sensor || fresh.tag!=prepared.tag || fresh.writeProtected!=prepared.writeProtected)
                throw RangeStale("Настройки или условия проверки изменились. Повторно считайте прибор и подтвердите новые данные.")
            require(requested.unitCode in fresh.writableUnits && requested.unitCode in prepared.writableUnits){"Поддержка записи выбранной единицы не подтверждена"}
            val validation=RangeRules.validate(fresh,requested.lower.toString(),requested.upper.toString(),requested.unitCode)
            require(validation.allowed){(validation.errors+if(validation.unchanged)listOf("Новый диапазон совпадает с текущим")else emptyList()).joinToString("; ")}
            val finalIdentity=identify()
            if(passportKey(finalIdentity)!=passportKey(prepared.identity) || finalIdentity.hartRevision!=prepared.identity.hartRevision)throw RangeDeviceChanged(prepared.identity,finalIdentity)
            val finalReply=read(finalIdentity.address,ReadCommand.DEVICE_INFORMATION)
            val finalConfig=ConfigurationSnapshot(ConfigurationDecoder.deviceInformation(finalReply.data,finalIdentity.hartRevision),listOf(ConfigResponse(15,finalReply.data.hex(),finalReply.receivedAt.toString(),finalReply.deviceStatus,responseCode=0)))
            val finalRange=finalConfig.range ?: throw RangeStale("Команда 15 перед записью недостоверна")
            preflight=fresh.copy(range=finalRange,configuration=finalConfig)
            if(!sameRange(finalRange,prepared.range) || finalConfig.parameters["writeProtect"]!=fresh.configuration.parameters["writeProtect"])throw RangeStale("Диапазон или защита изменились непосредственно перед записью. Повторно считайте настройки.")
            reply=writeRange(fresh.identity.address,WriteRange(requested)) { tx -> beforeSend(tx);sent=true }
            runCatching { onReply(reply) }.onFailure { errors+="Журнал ответа: ${it.message}" }
            if(reply.responseCode!=0) errors+="35: ${RangeResponseCodes.description(reply.responseCode)} (код ${reply.responseCode})"
            if(reply.deviceStatus!=0) errors+="Статус прибора: ${HartStatus.deviceDescription(reply.deviceStatus)}"
            runCatching{rangeReply(reply.data)}.onFailure{errors+="Ответ 35: ${it.message}"}
        } catch(e:CancellationException){throw e
        } catch(e:RangeDeviceChanged){throw e
        } catch(e:Exception){
            errors+=e.message ?: e.javaClass.simpleName
            val raw=when(e){is HartTimeoutException->e.receivedRaw;is HartWriteIOException->e.receivedRaw;else->byteArrayOf()}
            if(raw.isNotEmpty())runCatching{onInvalidRx(raw)}.onFailure{errors+="Журнал RX: ${it.message}"}
            if(e is HartTimeoutException && e.malformedFrames)errors+="Получен повреждённый или неполный кадр; ответ 35 не подтверждён"
        }
        if(!sent) return@transaction RangeAttempt(verification=preflight?.let{RangeVerification(it.identity,it.configuration)},errors=errors,aborted=true)
        val actual=try { verification(prepared.identity) }
            catch(e:CancellationException){throw e}catch(e:RangeDeviceChanged){throw e}
            catch(e:Exception){errors+="Проверка 15: ${e.message}";null}
        RangeAttempt(reply,actual,errors,sent)
    }
}
