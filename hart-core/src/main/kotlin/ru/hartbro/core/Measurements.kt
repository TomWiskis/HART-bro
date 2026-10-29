package ru.hartbro.core

import java.nio.ByteBuffer
import java.time.Instant
import kotlinx.coroutines.CancellationException

/** Values retain the received float, even when NaN or bad quality. Nothing is derived from mA. */
enum class Quality { GOOD, UNVERIFIED, UNCERTAIN, FIXED, BAD, ABSENT }
data class Measurement(
    val slot: String, val value: Float, val unitCode: Int, val quality: Quality,
    val receivedAt: Instant, val command: Int, val variableCode: Int? = null,
    val classification: Int? = null, val rawStatus: Int? = null,
) {
    val unit: String get() = HartUnits.find(unitCode)?.symbol ?: "HART $unitCode"
    val plottable: Boolean get() = value.isFinite() && quality in setOf(Quality.GOOD, Quality.UNVERIFIED)
}
data class MeasurementBatch(
    val values: List<Measurement>, val deviceStatus: Int, val receivedAt: Instant,
    val loopCurrent: Float? = null, val extendedStatus: Int? = null, val deviceTicks: Long? = null,
    val loopCurrentAt: Instant? = if (loopCurrent != null) receivedAt else null,
)

object MeasurementDecoder {
    val slots = listOf("PV", "SV", "TV", "QV")
    private fun float(data: ByteArray, offset: Int) = ByteBuffer.wrap(data, offset, 4).float
    private fun measurement(slot: String, value: Float, unit: Int, reply: HartReply, command: Int,
                            code: Int? = null, classification: Int? = null, status: Int? = null): Measurement {
        val quality = when {
            unit == 250 || code == 250 -> Quality.ABSENT
            !value.isFinite() || reply.deviceStatus and 0x80 != 0 -> Quality.BAD
            status != null -> when (status and 0xC0) {
                0xC0 -> Quality.GOOD; 0x80 -> Quality.FIXED; 0x40 -> Quality.UNCERTAIN; else -> Quality.BAD
            }
            reply.deviceStatus and (if (slot == "PV") 1 else 2) != 0 -> Quality.UNCERTAIN
            else -> Quality.UNVERIFIED
        }
        return Measurement(slot, value, unit, quality, reply.receivedAt, command, code, classification, status)
    }
    fun primary(reply: HartReply): MeasurementBatch {
        require(reply.data.size == 5) { "Команда 1: длина ${reply.data.size}, требуется 5" }
        return MeasurementBatch(listOf(measurement("PV", float(reply.data, 1), reply.data[0].u8(), reply, 1)), reply.deviceStatus, reply.receivedAt)
    }
    fun dynamic(reply: HartReply): MeasurementBatch {
        val d = reply.data
        require(d.size in 9..24 && (d.size - 4) % 5 == 0) { "Команда 3: неверная длина ${d.size}" }
        return MeasurementBatch((0 until (d.size - 4) / 5).map { i ->
            measurement(slots[i], float(d, 5 + i * 5), d[4 + i * 5].u8(), reply, 3)
        }, reply.deviceStatus, reply.receivedAt, float(d, 0).takeIf { it.isFinite() })
    }
    fun withStatus(reply: HartReply, codes: List<Int>, revision: Int): MeasurementBatch {
        require(codes.size in 1..4 && revision in 6..7)
        val d = reply.data
        val base = 1 + codes.size * 8
        require(d.size == base + if (revision >= 7) 4 else 0) { "Команда 9: длина ${d.size}, слотов ${codes.size}" }
        val values = codes.mapIndexed { i, code ->
            val p = 1 + i * 8
            require(d[p].u8() == code) { "Команда 9: ответ другой переменной" }
            measurement(slots[i], float(d, p + 3), d[p + 2].u8(), reply, 9, code, d[p + 1].u8(), d[p + 7].u8())
        }
        val ticks = if (d.size == base + 4) ByteBuffer.wrap(d, base, 4).int.toLong() and 0xFFFFFFFFL else null
        return MeasurementBatch(values, reply.deviceStatus, reply.receivedAt, extendedStatus = d[0].u8(), deviceTicks = ticks)
    }
    fun fourCodes(reply: HartReply): List<Int> {
        require(reply.data.size == 4) { "Ожидаются 4 кода" }
        return reply.data.map { it.u8() }
    }
    fun additionalStatus(reply: HartReply): ByteArray {
        require(reply.data.size >= 6) { "Команда 48: требуется минимум 6 байтов" }
        return reply.data.copyOf()
    }
}

/** Session-scoped capability negotiation. Unsupported commands are never retried in a polling loop. */
class MeasurementReader(private var client: HartClient, private val identity: DeviceIdentity,
                        private val report: (String) -> Unit = {}) {
    private val disabled = mutableSetOf<ReadCommand>()
    private var assignments: List<Int>? = null
    private var classifications: List<Int>? = null
    private var codes: List<Int>? = null
    private var lastLoopCurrent: Pair<Float, Instant>? = null
    var discovered: Boolean = false
        private set
    fun rebind(newClient: HartClient) { client = newClient }
    suspend fun discover(): MeasurementBatch {
        val pv = MeasurementDecoder.primary(client.read(identity.address, ReadCommand.PRIMARY_VARIABLE))
        if (identity.hartRevision >= 6) optional(ReadCommand.CLASSIFICATIONS) { classifications = MeasurementDecoder.fourCodes(it) }
        optional(ReadCommand.ASSIGNMENTS) { assignments = MeasurementDecoder.fourCodes(it) }
        optional(ReadCommand.ADDITIONAL_STATUS) { report("Дополнительный статус: ${MeasurementDecoder.additionalStatus(it).hex()}") }
        optional(ReadCommand.DYNAMIC_VARIABLES) {
            val batch = MeasurementDecoder.dynamic(it)
            lastLoopCurrent = batch.loopCurrent?.let { value -> value to batch.receivedAt }
            report("Первичное чтение команды 3: ${batch.values}; токовый выход по данным датчика ${batch.loopCurrent} мА; ${batch.receivedAt}")
        }
        codes = when {
            identity.hartRevision >= 7 -> listOf(246, 247, 248, 249)
            identity.hartRevision == 6 -> assignments?.takeIf { a -> a.all { it < 250 } }
            else -> null
        }
        report("Назначения: ${assignments ?: "не сообщены"}; классификации: ${classifications ?: "не сообщены"}")
        discovered = true
        return pv
    }
    private suspend fun optional(command: ReadCommand, use: (HartReply) -> Unit) {
        if (command in disabled) return
        try { use(client.read(identity.address, command)) }
        catch (e: CancellationException) { throw e }
        catch (e: HartResponseException) { disabled += command; report("Команда ${command.number}: ${e.message}") }
        catch (e: HartTimeoutException) { disabled += command; report("Команда ${command.number}: ${e.message}") }
        catch (e: IllegalArgumentException) { disabled += command; report("Команда ${command.number}: ${e.message}") }
    }
    suspend fun sample(): MeasurementBatch {
        val requested = codes
        if (requested != null && ReadCommand.VARIABLES_WITH_STATUS !in disabled) {
            try {
                val batch = MeasurementDecoder.withStatus(client.read(identity.address, ReadCommand.VARIABLES_WITH_STATUS,
                    requested.map { it.toByte() }.toByteArray()), requested, identity.hartRevision)
                return batch.copy(loopCurrent = lastLoopCurrent?.first, loopCurrentAt = lastLoopCurrent?.second)
            } catch (e: HartResponseException) {
                if (e.responseCode !in setOf(2, 64)) throw e
                disabled += ReadCommand.VARIABLES_WITH_STATUS
                report("Команда 9 отключена для сессии: ${e.message}; переход к 3")
            }
        }
        if (ReadCommand.DYNAMIC_VARIABLES !in disabled) {
            try {
                val batch = MeasurementDecoder.dynamic(client.read(identity.address, ReadCommand.DYNAMIC_VARIABLES))
                return batch.copy(values = batch.values.mapIndexed { i, v -> v.copy(variableCode = assignments?.get(i), classification = classifications?.get(i)) })
            } catch (e: HartResponseException) {
                if (e.responseCode != 64) throw e
                disabled += ReadCommand.DYNAMIC_VARIABLES
                report("Команда 3 не поддерживается; переход к 1")
            }
        }
        return MeasurementDecoder.primary(client.read(identity.address, ReadCommand.PRIMARY_VARIABLE))
    }
}
