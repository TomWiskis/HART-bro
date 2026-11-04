package ru.hartbro.core

import java.time.Instant
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** Only these typed writes are available. No generic numeric write API. */
sealed class SensorWrite(val number: Int) {
    abstract fun bytes(): ByteArray
    class TagDate private constructor(private val payload: ByteArray) : SensorWrite(18) {
        override fun bytes() = payload.copyOf()
        companion object {
            fun changeTag(original: ByteArray, tag: String): TagDate {
                require(original.size == 21)
                return TagDate(original.copyOf().also { packedTag(tag).copyInto(it, 0) })
            }
            fun changeDate(original: ByteArray, date: String): TagDate {
                require(original.size == 21)
                return TagDate(original.copyOf().also { HartDate.encode(HartDate.parse(date)).copyInto(it, 18) })
            }
        }
    }
    class LongTag(val tag: String) : SensorWrite(22) {
        init { require(tag.length in 0..32 && tag.all { it.code in 32..126 || it.code in 160..255 }) { "Длинный TAG: до 32 символа Latin-1" } }
        override fun bytes() = tag.toByteArray(Charsets.ISO_8859_1).copyOf(32)
    }
    internal class Zero : SensorWrite(43) { override fun bytes() = byteArrayOf() }
    internal class ZeroMethod(private val method:ru.hartbro.core.ZeroMethod):SensorWrite(method.command) {
        override fun bytes()=when(val p=method.procedure){ZeroProcedure.Primary43->byteArrayOf();is ZeroProcedure.DeviceVariable52->byteArrayOf(p.variableCode.toByte());is ZeroProcedure.Manufacturer->p.data.toByteArray()}
    }
}
private fun packedTag(tag: String): ByteArray {
    require(tag.length in 0..8 && tag.all { it.code in 32..95 }) { "TAG: до 8 символов ASCII, заглавные буквы" }
    return tag.padEnd(8).chunked(4).flatMap { chars ->
        val bits = chars.fold(0) { n, c -> (n shl 6) or (c.code and 63) }
        listOf((bits shr 16).toByte(), (bits shr 8).toByte(), bits.toByte())
    }.toByteArray()
}
enum class SensorField(val label: String) { TAG("TAG"), LONG_TAG("Длинный TAG"), DATE("Дата производства"), ZERO("Установка нуля") }
data class SensorEdit(val identity: DeviceIdentity, val field: SensorField, val original: String, val requested: String, val zero:ZeroOptions=ZeroOptions(), val communicator:String="")
data class SensorEditResult(val record: SensorRecord, val configuration: ConfigurationSnapshot?, val identity: DeviceIdentity?)

/** Historical support metadata. Standard 43 is no longer gated by these profiles. */
data class ZeroProfile(val manufacturer: Int, val deviceType: Int, val hartRevisions: Set<Int>, val deviceRevisions: Set<Int>, val documentation: String) {
    init { require(documentation.isNotBlank() && hartRevisions.isNotEmpty() && deviceRevisions.isNotEmpty()) }
    fun matches(i: DeviceIdentity) = i.manufacturerCode == manufacturer && i.deviceType == deviceType && i.hartRevision in hartRevisions && i.deviceRevision in deviceRevisions
}
class ZeroProfiles(private val profiles: List<ZeroProfile> = documented) {
    fun find(i: DeviceIdentity) = profiles.firstOrNull { it.matches(i) }
    companion object { val documented = listOf(
        ZeroProfile(0x32,0x3201,setOf(5),setOf(2),"Viatran 970 · 98MANAL970 Rev.D · command table, p.27; FieldComm Group DEV_REV 2"),
    ) }
}

/** Identity, protection, untouched fields and readback all belong to one serialized transaction. */
class SensorOperations(private val client: HartClient, private val zeroProfiles: ZeroProfiles = ZeroProfiles(),private val zeroMethods:ZeroMethods=ZeroMethods()) {
    suspend fun prepareZero(identity:DeviceIdentity)=ZeroOperations(client,zeroMethods).prepare(identity)
    suspend fun apply(edit: SensorEdit, journal: SensorJournal): SensorEditResult {
        if(edit.field==SensorField.ZERO)return ZeroOperations(client,zeroMethods).apply(edit,journal)
        return client.transaction {
        val identity = identify()
        if(passportKey(identity) != passportKey(edit.identity)) throw RangeDeviceChanged(edit.identity,identity)
        val info = read(identity.address, ReadCommand.DEVICE_INFORMATION)
        val config15 = ConfigurationDecoder.deviceInformation(info.data, identity.hartRevision)
        require(config15["writeProtect"]?.value == "0") { "Запись защищена или состояние защиты неизвестно" }
        require(info.deviceStatus and 0x80 == 0) { "Прибор сообщает неисправность: ${HartStatus.deviceDescription(info.deviceStatus)}" }
        var original = edit.original
        val write: SensorWrite = when (edit.field) {
            SensorField.TAG, SensorField.DATE -> {
                val raw = read(identity.address, ReadCommand.TAG_DESCRIPTION).data
                val config = ConfigurationSnapshot(ConfigurationDecoder.tagDescription(raw))
                original = if (edit.field == SensorField.TAG) config.parameters.getValue("tag").value else config.productionDate?.let(HartDate::format).orEmpty()
                require(original == edit.original) { "Значение изменилось в приборе. Перечитайте его перед записью" }
                if (edit.field == SensorField.TAG) SensorWrite.TagDate.changeTag(raw, edit.requested) else SensorWrite.TagDate.changeDate(raw, edit.requested)
            }
            SensorField.LONG_TAG -> {
                require(identity.hartRevision >= 6)
                original = ConfigurationDecoder.longTag(read(identity.address, ReadCommand.LONG_TAG).data).getValue("longTag").value
                require(original == edit.original) { "TAG изменился в приборе. Перечитайте его перед записью" }
                SensorWrite.LongTag(edit.requested)
            }
            SensorField.ZERO -> error("Zero uses its dedicated confirmed transaction")
        }
        require(edit.field == SensorField.ZERO || original != edit.requested) { "Значение не изменено" }
        var record = SensorRecord(identity = identity, field = edit.field, original = original, requested = if(edit.field == SensorField.ZERO) "0" else edit.requested)
        journal.save(record)
        var config: ConfigurationSnapshot? = null
        var readbackIdentity: DeviceIdentity? = null
        try {
            var exchange: HartExchange? = null
            try {
                exchange = writeSensor(identity.address, write) { tx ->
                    val sent = record.copy(stage = WriteStage.SENT, tx = tx.hex()); journal.save(sent); record = sent
                }
                record = record.copy(rx = exchange.rxFrame.hex(), responseCode = exchange.responseCode, deviceStatus = exchange.deviceStatus)
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                record = record.copy(rx = (when(e) { is HartTimeoutException -> e.receivedRaw; is HartWriteIOException -> e.receivedRaw; else -> byteArrayOf() }).hex(), error = "Ответ записи: ${e.message}")
            }
            // Even after a lost acknowledgement only reads are allowed. Never resend a write.
            val found = identify()
            readbackIdentity = found
            if(passportKey(found) != passportKey(identity)) throw RangeDeviceChanged(identity,found)
            val actual: String
            val verified: Boolean

                val command = if(edit.field == SensorField.LONG_TAG) ReadCommand.LONG_TAG else ReadCommand.TAG_DESCRIPTION
                val reply = read(identity.address, command)
                val parameters = if(command == ReadCommand.LONG_TAG) ConfigurationDecoder.longTag(reply.data) else ConfigurationDecoder.tagDescription(reply.data)
                config = ConfigurationSnapshot(parameters, listOf(ConfigResponse(command.number, reply.data.hex(), reply.receivedAt.toString(), reply.deviceStatus, responseCode = 0)))
                actual = when(edit.field) { SensorField.TAG -> parameters.getValue("tag").value; SensorField.LONG_TAG -> parameters.getValue("longTag").value; else -> config!!.productionDate?.let(HartDate::format).orEmpty() }
                verified = actual == edit.requested

            val accepted = exchange == null || exchange.responseCode == 0
            record = record.copy(actual = actual, verifiedAt = Instant.now(), succeeded = verified && accepted && record.stage == WriteStage.SENT,
                error = listOfNotNull(record.error, if(!accepted) "HART ${exchange!!.responseCode}: ${HartStatus.responseDescription(exchange.responseCode)}" else null,
                    if(!verified) "Повторное чтение не подтвердило результат" else null).joinToString("; "))
        } catch (e: CancellationException) { record = record.copy(error = "Операция прервана. Результат не подтверждён"); throw e
        } catch (e: Exception) { record = record.copy(succeeded = false, error = listOf(record.error, e.message).filterNot { it.isNullOrBlank() }.joinToString("; ")) }
        finally { record = record.copy(stage = WriteStage.FINISHED); journal.save(record) }
        SensorEditResult(record, config, readbackIdentity)
        }
    }
}
