package ru.hartbro.core

import java.nio.ByteBuffer
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth

enum class PassportField { TAG, SERIAL, DATE, MODEL, RANGE }
enum class FactSource { NONE, STANDARD_HART, CONFIRMED_FACTORY, PROFILE, USER }
enum class FactConfirmation { NONE, UNCONFIRMED, DOCUMENTED, USER_ASSERTED }
enum class DatePrecision { DAY, MONTH, YEAR }
data class FactoryDate(val year: Int, val month: Int? = null, val day: Int? = null) {
    init {
        require(year in 1..9999) { "Год должен содержать четыре цифры и быть больше нуля" }
        require(day == null || month != null)
        if (month != null) YearMonth.of(year, month)
        if (day != null) LocalDate.of(year, month!!, day)
    }
    val precision: DatePrecision get() = if (day != null) DatePrecision.DAY else if (month != null) DatePrecision.MONTH else DatePrecision.YEAR
    val label: String get() = when (precision) {
        DatePrecision.DAY -> "%02d.%02d.%04d".format(java.util.Locale.ROOT,day, month, year)
        DatePrecision.MONTH -> "%02d.%04d".format(java.util.Locale.ROOT,month, year)
        DatePrecision.YEAR -> "%04d".format(java.util.Locale.ROOT,year)
    }
    companion object {
        fun parse(text: String): FactoryDate {
            val value = text.trim()
            return try { when {
                value.matches(Regex("[0-9]{4}")) -> FactoryDate(value.toInt())
                value.matches(Regex("[0-9]{2}\\.[0-9]{4}")) -> FactoryDate(value.takeLast(4).toInt(), value.take(2).toInt())
                value.matches(Regex("[0-9]{2}\\.[0-9]{2}\\.[0-9]{4}")) -> FactoryDate(value.takeLast(4).toInt(), value.substring(3, 5).toInt(), value.take(2).toInt())
                else -> throw IllegalArgumentException("Введите ДД.ММ.ГГГГ, ММ.ГГГГ или ГГГГ; неизвестную дату оставьте пустой")
            } } catch(e: java.time.DateTimeException) { throw IllegalArgumentException("Несуществующая календарная дата: $value",e) }
        }
    }
}
data class PassportFact(val value: String? = null, val source: FactSource = FactSource.NONE, val command: Int? = null,
                        val raw: String = "", val readAt: Instant? = null, val manual: Boolean = false,
                        val confirmation: FactConfirmation = FactConfirmation.NONE, val documentation: String? = null,
                        val datePrecision: DatePrecision? = null) {
    val factoryConfirmed: Boolean get() = source == FactSource.USER && confirmation == FactConfirmation.USER_ASSERTED ||
        source in setOf(FactSource.CONFIRMED_FACTORY, FactSource.PROFILE) && confirmation == FactConfirmation.DOCUMENTED && !documentation.isNullOrBlank()
    companion object {
        fun manual(value: String, at: Instant, date: Boolean = false): PassportFact {
            val parsed = if (date && value.isNotBlank()) FactoryDate.parse(value) else null
            return PassportFact(parsed?.label ?: value.trim(), FactSource.USER, raw = value, readAt = at, manual = true,
                confirmation = FactConfirmation.USER_ASSERTED, datePrecision = parsed?.precision)
        }
    }
}
data class PassportReading(val identity: DeviceIdentity, val fields: Map<PassportField, PassportFact>,
                           val candidates: Map<String, PassportFact>, val range: ConfiguredRange?,
                           val readAt: Instant, val errors: Map<String, String> = emptyMap(), val transportLost: Boolean = false) {
    val key: String get() = passportKey(identity)
}
fun passportKey(identity: DeviceIdentity) = "${identity.manufacturerCode?.let { "%04X".format(it) } ?: "????"}/${identity.uniqueKey}"
fun reliablePassportKey(identity: DeviceIdentity) = identity.manufacturerCode != null && identity.manufacturerCode !in setOf(0, 0xFFFF) &&
    identity.deviceType !in setOf(0, 0xFFFF) && identity.deviceId !in setOf(0, 0xFFFFFF)

data class SensorInformation(val number: Int, val unitCode: Int, val upperLimit: Float?, val lowerLimit: Float?, val minimumSpan: Float?)
object PassportDecoder {
    fun sensor(data: ByteArray): SensorInformation {
        require(data.size >= 16) { "Команда 14: ожидается не менее 16 байтов, получено ${data.size}" }
        fun number(at: Int) = ByteBuffer.wrap(data, at, 4).float.takeIf { it.isFinite() }
        return SensorInformation(uint24(data), data[3].u8(), number(4), number(8), number(12)?.takeIf { it >= 0 })
    }
    fun assembly(data: ByteArray): Int {
        require(data.size == 3) { "Команда 16: ожидается 3 байта, получено ${data.size}" };return uint24(data)
    }
    private fun uint24(data: ByteArray) = (data[0].u8() shl 16) or (data[1].u8() shl 8) or data[2].u8()
}

/** Profiles must name documentation for every factory interpretation. No speculative vendor commands are generated. */
interface PassportProfile {
    val documentation: String
    fun matches(identity: DeviceIdentity): Boolean
    fun model(identity: DeviceIdentity): String? = null
    val additionalReads: List<ReadCommand> get() = emptyList()
    fun factoryFields(identity: DeviceIdentity, replies: Map<ReadCommand, HartReply>): Map<PassportField, PassportFact> = emptyMap()
}
class PassportProfiles(private val profiles: List<PassportProfile> = emptyList()) {
    fun find(identity: DeviceIdentity) = profiles.firstOrNull { it.documentation.isNotBlank() && it.matches(identity) }
    fun model(identity: DeviceIdentity): String = find(identity)?.model(identity)?.takeIf { it.isNotBlank() }
        ?: "${identity.manufacturerName ?: identity.manufacturerDescription} · тип 0x%04X".format(identity.deviceType)
}

class PassportReader(private val client: HartClient, private val profiles: PassportProfiles = PassportProfiles(),
                     private val report: (String) -> Unit = {}) {
    private val unsupported = mutableMapOf<String, MutableSet<ReadCommand>>()
    suspend fun read(identity: DeviceIdentity): PassportReading {
        val replies = linkedMapOf<ReadCommand, HartReply>();val errors = linkedMapOf<String, String>()
        val fields = linkedMapOf<PassportField, PassportFact>();val candidates = linkedMapOf<String, PassportFact>()
        val profile = profiles.find(identity)
        var transportLost = false
        suspend fun optional(command: ReadCommand): HartReply? {
            if (transportLost) { errors[command.number.toString()] = "Bluetooth потерян до чтения поля";return null }
            if (command in unsupported[passportKey(identity)].orEmpty()) { errors[command.number.toString()] = "Команда ${command.number} не поддерживается";return null }
            return try { client.read(identity.address, command).also { replies[command] = it }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (e: java.io.IOException) {
                transportLost = true;errors[command.number.toString()] = e.message ?: "Bluetooth потерян";null
            } catch (e: Exception) {
                if (e is HartResponseException && e.responseCode == 64) unsupported.getOrPut(passportKey(identity)) { mutableSetOf() } += command
                errors[command.number.toString()] = (e.message ?: e.javaClass.simpleName).take(1500)
                report("Паспорт: команда ${command.number}: ${e.message}; обход продолжается");null
            }
        }
        fun fact(value: String, command: ReadCommand): PassportFact {
            val reply = replies.getValue(command)
            return PassportFact(value, FactSource.STANDARD_HART, command.number, reply.data.hex(), reply.receivedAt,
                confirmation = FactConfirmation.UNCONFIRMED)
        }
        fields[PassportField.MODEL] = PassportFact(profiles.model(identity), if (profile == null) FactSource.STANDARD_HART else FactSource.PROFILE,
            0, identity.raw.hex(), Instant.now(), confirmation = if (profile == null) FactConfirmation.UNCONFIRMED else FactConfirmation.DOCUMENTED,
            documentation = profile?.documentation)
        if (identity.hartRevision >= 6) optional(ReadCommand.LONG_TAG)?.let { reply ->
            runCatching { ConfigurationDecoder.longTag(reply.data).getValue("longTag").value }.onSuccess { if (it.isNotBlank()) fields[PassportField.TAG] = fact(it, ReadCommand.LONG_TAG) }
                .onFailure { errors["20"] = it.message ?: "Некорректный Long TAG" }
        }
        if (PassportField.TAG !in fields || ReadCommand.TAG_DESCRIPTION in profile?.additionalReads.orEmpty()) {
            optional(ReadCommand.TAG_DESCRIPTION)?.let { reply ->
                runCatching { ConfigurationDecoder.tagDescription(reply.data) }.onSuccess { values ->
                    if (PassportField.TAG !in fields) fields[PassportField.TAG] = fact(values.getValue("tag").value, ReadCommand.TAG_DESCRIPTION)
                    candidates["hartDate"] = fact(values.getValue("deviceDateRaw").value, ReadCommand.TAG_DESCRIPTION)
                }.onFailure { errors["13"] = it.message ?: "Некорректный TAG" }
            }
        }
        var range: ConfiguredRange? = null
        optional(ReadCommand.DEVICE_INFORMATION)?.let { reply ->
            runCatching { ConfigurationSnapshot(ConfigurationDecoder.deviceInformation(reply.data, identity.hartRevision)).range!! }
                .onSuccess { range = it;fields[PassportField.RANGE] = fact(it.label, ReadCommand.DEVICE_INFORMATION) }
                .onFailure { errors["15"] = it.message ?: "Диапазон неизвестен" }
        }
        optional(ReadCommand.SENSOR_INFORMATION)?.let { reply ->
            runCatching { PassportDecoder.sensor(reply.data) }.onSuccess { candidates["sensorNumber"] = fact(it.number.toString(), ReadCommand.SENSOR_INFORMATION) }
                .onFailure { errors["14"] = it.message ?: "Номер сенсора неизвестен" }
        }
        optional(ReadCommand.FINAL_ASSEMBLY)?.let { reply ->
            runCatching { PassportDecoder.assembly(reply.data) }.onSuccess { candidates["assemblyNumber"] = fact(it.toString(), ReadCommand.FINAL_ASSEMBLY) }
                .onFailure { errors["16"] = it.message ?: "Номер сборки неизвестен" }
        }
        profile?.additionalReads?.distinct()?.filter { it !in replies && it != ReadCommand.IDENTITY }?.forEach { optional(it) }
        if (profile != null) runCatching {
            profile.factoryFields(identity, replies).forEach { (key, value) ->
                if (key in setOf(PassportField.SERIAL, PassportField.DATE) && value.factoryConfirmed &&
                    value.source in setOf(FactSource.PROFILE,FactSource.CONFIRMED_FACTORY) && value.documentation==profile.documentation) {
                    val reply=replies.entries.firstOrNull { it.key.number==value.command }?.value
                    require(reply!=null && value.raw==reply.data.hex()) { "Заводское поле профиля не связано с исходным ответом" }
                    if (key == PassportField.DATE && !value.value.isNullOrBlank()) {
                        val parsed = FactoryDate.parse(value.value);require(parsed.precision == value.datePrecision)
                    }
                    fields[key] = value
                }
            }
        }.onFailure { errors["profile"] = it.message ?: "Профиль не подтвердил заводские сведения" }
        PassportField.entries.forEach { fields.putIfAbsent(it, PassportFact()) }
        return PassportReading(identity, fields.toMap(), candidates.toMap(), range, Instant.now(), errors, transportLost)
    }
}
