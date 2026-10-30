package ru.hartbro.core

import java.io.*
import java.nio.ByteBuffer
import java.time.Instant

/** Read-only, deliberately partial configuration. Raw reply bytes preserve IEEE-754 bits and HART codes. */
data class ConfigValue(val value: String, val command: Int, val unitCode: Int? = null)
data class ConfigResponse(val command: Int, val rawHex: String, val receivedAt: String, val deviceStatus: Int?, val error: String? = null, val responseCode: Int? = null)
data class ConfiguredRange(val lower: Float, val upper: Float, val unitCode: Int) {
    val label: String get() = "${number(lower)}…${number(upper)} ${when (unitCode) { 11 -> "Па"; 12 -> "кПа"; 237 -> "МПа"; else -> HartUnits.find(unitCode)?.symbol ?: "HART $unitCode" }}"
    private fun number(value: Float) = value.toString().removeSuffix(".0").replace('-', '−').replace('.', ',')
}
data class ConfigurationSnapshot(val parameters: Map<String, ConfigValue> = emptyMap(), val responses: List<ConfigResponse> = emptyList()) {
    val tag: String? get() = parameters["longTag"]?.value?.takeIf { it.isNotBlank() } ?: parameters["tag"]?.value?.takeIf { it.isNotBlank() }
    val range: ConfiguredRange? get() {
        val lo = parameters["lowerRange"]?.value?.toFloatOrNull() ?: return null
        val hi = parameters["upperRange"]?.value?.toFloatOrNull() ?: return null
        val unit = parameters["rangeUnit"]?.value?.toIntOrNull() ?: return null
        return if (lo.isFinite() && hi.isFinite() && unit !in setOf(250, 251)) ConfiguredRange(lo, hi, unit) else null
    }
    fun write(out: DataOutput) {
        out.writeInt(2); out.writeInt(parameters.size)
        parameters.forEach { (key, v) -> out.writeUTF(key); out.writeUTF(v.value); out.writeInt(v.command); out.writeInt(v.unitCode ?: -1) }
        out.writeInt(responses.size)
        responses.forEach { r -> out.writeInt(r.command); out.writeUTF(r.rawHex); out.writeUTF(r.receivedAt); out.writeInt(r.deviceStatus ?: -1); out.writeUTF(r.error ?: ""); out.writeInt(r.responseCode ?: -1) }
    }
    companion object {
        fun read(input: DataInput): ConfigurationSnapshot {
            val version = input.readInt(); require(version in 1..2) { "Неизвестная версия снимка конфигурации" }
            val n = input.readInt(); require(n in 0..100)
            val params = linkedMapOf<String, ConfigValue>()
            repeat(n) { val key = input.readUTF(); params[key] = ConfigValue(input.readUTF(), input.readInt(), input.readInt().takeIf { it >= 0 }) }
            val count = input.readInt(); require(count in 0..50)
            val replies = List(count) { ConfigResponse(input.readInt(), input.readUTF(), input.readUTF(), input.readInt().takeIf { it >= 0 }, input.readUTF().ifEmpty { null }, if (version >= 2) input.readInt().takeIf { it >= 0 } else null) }
            return ConfigurationSnapshot(params, replies)
        }
    }
}

data class ConfigurationDifference(val key: String, val a: ConfigValue?, val b: ConfigValue?) {
    val different: Boolean get() = a != null && b != null && (a.value != b.value || a.unitCode != b.unitCode)
}
fun compareConfigurations(a: ConfigurationSnapshot, b: ConfigurationSnapshot): List<ConfigurationDifference> =
    (a.parameters.keys + b.parameters.keys).sorted().map { ConfigurationDifference(it, a.parameters[it], b.parameters[it]) }

object ConfigurationDecoder {
    fun deviceInformation(data: ByteArray, revision: Int): Map<String, ConfigValue> {
        require(revision in 5..7 && data.size >= 17) { "Команда 15: неполный ответ (${data.size} байт)" }
        fun float(offset: Int) = ByteBuffer.wrap(data, offset, 4).float
        val upper = float(3); val lower = float(7); val damping = float(11); val unit = data[2].u8()
        require(upper.isFinite() && lower.isFinite() && damping.isFinite() && damping >= 0 && unit !in setOf(250, 251)) { "Команда 15: недостоверные границы/единица/демпфирование" }
        return linkedMapOf(
            "alarm" to ConfigValue(data[0].u8().toString(), 15), "transferFunction" to ConfigValue(data[1].u8().toString(), 15),
            "rangeUnit" to ConfigValue(unit.toString(), 15, unit), "upperRange" to ConfigValue(upper.toString(), 15, unit),
            "lowerRange" to ConfigValue(lower.toString(), 15, unit), "damping" to ConfigValue(damping.toString(), 15),
            "writeProtect" to ConfigValue(data[15].u8().toString(), 15), "privateLabel" to ConfigValue(data[16].u8().toString(), 15),
        ).also { if (revision >= 6 && data.size >= 18) it["analogChannelFlags"] = ConfigValue(data[17].u8().toString(), 15) }
    }
    fun packedAscii(bytes: ByteArray): String {
        require(bytes.size % 3 == 0)
        return bytes.toList().chunked(3).flatMap { b ->
            val bits = (b[0].u8() shl 16) or (b[1].u8() shl 8) or b[2].u8()
            listOf(18, 12, 6, 0).map { shift -> val c = (bits shr shift) and 63; (if (c and 32 == 0) c or 64 else c).toChar() }
        }.joinToString("").trimEnd()
    }
    fun tagDescription(data: ByteArray): Map<String, ConfigValue> {
        require(data.size == 21) { "Команда 13: ожидается 21 байт" }
        return mapOf("tag" to ConfigValue(packedAscii(data.copyOfRange(0, 6)), 13),
            "description" to ConfigValue(packedAscii(data.copyOfRange(6, 18)), 13),
            "deviceDateRaw" to ConfigValue(data.copyOfRange(18, 21).hex(), 13))
    }
    fun longTag(data: ByteArray): Map<String, ConfigValue> {
        require(data.size == 32 && data.all { it.u8() == 0 || it.u8() in 32..126 || it.u8() in 160..255 }) { "Команда 20: неверный длинный TAG" }
        return mapOf("longTag" to ConfigValue(data.toString(Charsets.ISO_8859_1).trimEnd('\u0000', ' '), 20))
    }
}

suspend fun readConfiguration(client: HartClient, identity: DeviceIdentity, report: (String) -> Unit = {}): ConfigurationSnapshot {
    val params = linkedMapOf<String, ConfigValue>(); val replies = mutableListOf<ConfigResponse>()
    params["hartRevision"] = ConfigValue(identity.hartRevision.toString(), 0)
    params["deviceType"] = ConfigValue("%04X".format(identity.deviceType), 0)
    params["deviceId"] = ConfigValue("%06X".format(identity.deviceId), 0)
    identity.manufacturerCode?.let { params["manufacturer"] = ConfigValue("%04X".format(it), 0) }
    params["deviceRevision"] = ConfigValue(identity.deviceRevision.toString(), 0)
    params["softwareRevision"] = ConfigValue(identity.softwareRevision.toString(), 0)
    replies += ConfigResponse(0, identity.raw.hex(), Instant.now().toString(), null)
    val commands = listOf(ReadCommand.TAG_DESCRIPTION, ReadCommand.DEVICE_INFORMATION) +
        (if (identity.hartRevision >= 6) listOf(ReadCommand.LONG_TAG) else emptyList()) + listOf(ReadCommand.ADDITIONAL_STATUS, ReadCommand.ASSIGNMENTS)
    for (command in commands) {
        var reply: HartReply? = null
        try {
            reply = client.read(identity.address, command)
            val decoded = when (command) {
                ReadCommand.TAG_DESCRIPTION -> ConfigurationDecoder.tagDescription(reply.data)
                ReadCommand.DEVICE_INFORMATION -> ConfigurationDecoder.deviceInformation(reply.data, identity.hartRevision)
                ReadCommand.LONG_TAG -> ConfigurationDecoder.longTag(reply.data)
                ReadCommand.ASSIGNMENTS -> MeasurementDecoder.fourCodes(reply).mapIndexed { i, code -> "assignment${MeasurementDecoder.slots[i]}" to ConfigValue(code.toString(), 50) }.toMap()
                ReadCommand.ADDITIONAL_STATUS -> { MeasurementDecoder.additionalStatus(reply); emptyMap() } // status is diagnostic, not a configuration setting
                else -> emptyMap()
            }
            params.putAll(decoded)
            replies += ConfigResponse(command.number, reply.data.hex(), reply.receivedAt.toString(), reply.deviceStatus, responseCode = 0)
        } catch (e: kotlinx.coroutines.CancellationException) { throw e
        } catch (e: IOException) { throw e
        } catch (e: Exception) {
            replies += ConfigResponse(command.number, reply?.data?.hex() ?: "", reply?.receivedAt?.toString() ?: Instant.now().toString(), reply?.deviceStatus ?: (e as? HartResponseException)?.deviceStatus, e.message ?: e.javaClass.simpleName, (e as? HartResponseException)?.responseCode)
            report("Снимок конфигурации: команда ${command.number}: ${e.message}; параметр недоступен")
        }
    }
    return ConfigurationSnapshot(params, replies)
}

val configurationNames = mapOf("tag" to "TAG", "longTag" to "Длинный TAG", "description" to "Описание", "lowerRange" to "Нижняя граница", "upperRange" to "Верхняя граница", "rangeUnit" to "Единица HART", "damping" to "Демпфирование, с", "transferFunction" to "Передаточная функция", "alarm" to "Сигнализация", "writeProtect" to "Защита записи", "privateLabel" to "Марка OEM", "analogChannelFlags" to "Флаги аналогового канала", "deviceDateRaw" to "Дата прибора (HART)")
