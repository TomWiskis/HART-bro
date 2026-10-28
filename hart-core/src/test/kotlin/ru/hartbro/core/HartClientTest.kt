package ru.hartbro.core

import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

/** Explicitly test-only simulator: never linked into the Android app. */
private class SimulatedTransport(val onWrite: (ByteArray, Int) -> List<ByteArray>) : HartTransport {
    val writes = mutableListOf<ByteArray>()
    val queue = Channel<ByteArray>(Channel.UNLIMITED)
    override suspend fun write(bytes: ByteArray) {
        writes += bytes
        onWrite(bytes, writes.size).forEach { queue.send(it) }
    }
    override suspend fun receive() = queue.receive()
    override fun discardBufferedInput(): List<ByteArray> = buildList {
        while (true) add(queue.tryReceive().getOrNull() ?: break)
    }
    override fun close() { queue.close() }
}

private fun changedFrame(source: ByteArray, change: (ByteArray) -> Unit): ByteArray = source.copyOf().also {
    change(it)
    // Fixtures have exactly five preambles. Independent checksum calculation for modified replies.
    var xor = 0
    for (index in 5 until it.lastIndex) xor = xor xor (it[index].toInt() and 255)
    it[it.lastIndex] = xor.toByte()
}

@OptIn(ExperimentalCoroutinesApi::class)
class HartClientTest {
    @Test fun identifiesThenReadsPvUsingLongAddress() = runTest {
        val transport = SimulatedTransport { _, n -> listOf(if (n == 1) goldenIdentity else goldenPv) }
        val client = HartClient(transport)
        val (identity, status) = client.identify()
        val (pv, _) = client.primaryVariable(identity)
        assertEquals(0, status)
        assertEquals(12.5f, pv.value, 0f)
        assertEquals(2, transport.writes.size)
        assertArrayEquals(HartCodec.request(identity.address, ReadCommand.PRIMARY_VARIABLE), transport.writes[1])
    }
    @Test fun readsRetryOnceAfterTimeout() = runTest {
        val transport = SimulatedTransport { _, n -> if (n == 1) emptyList() else listOf(goldenIdentity) }
        val events = mutableListOf<DiagnosticEntry>()
        HartClient(transport, events::add, timeoutMillis = 100).identify()
        assertEquals(2, transport.writes.size)
        assertEquals(1, events.count { it.kind == "TIMEOUT" })
    }
    @Test fun noResponseFailsAfterBoundedAttempts() = runTest {
        val transport = SimulatedTransport { _, _ -> emptyList() }
        try { HartClient(transport, timeoutMillis = 100).identify(); fail("Must time out")
        } catch (_: HartTimeoutException) { assertEquals(2, transport.writes.size) }
    }
    @Test fun deviceErrorIsNotRetriedOrDecodedAsData() = runTest {
        val errorFrame = changedFrame(goldenIdentity) { it[9] = 64; it[10] = 0x80.toByte() }
        val transport = SimulatedTransport { _, _ -> listOf(errorFrame) }
        try { HartClient(transport).identify(); fail("Must report device error")
        } catch (e: HartResponseException) {
            assertEquals(64, e.responseCode)
            assertEquals(128, e.deviceStatus)
            assertEquals(1, transport.writes.size)
        }
    }
    @Test fun communicationErrorBitIsPreserved() = runTest {
        val errorFrame = changedFrame(goldenIdentity) { it[9] = 0x88.toByte() }
        val transport = SimulatedTransport { _, _ -> listOf(errorFrame) }
        try { HartClient(transport).identify(); fail("Must report communication error")
        } catch (e: HartResponseException) { assertTrue(e.message!!.contains("ошибка связи")) }
    }
    @Test fun echoBurstWrongAddressAndWrongCommandAreIgnored() = runTest {
        val transport = SimulatedTransport { request, _ -> listOf(
            request,
            changedFrame(goldenIdentity) { it[5] = 1 },
            changedFrame(goldenIdentity) { it[6] = 0x81.toByte() },
            changedFrame(goldenIdentity) { it[7] = 2 },
            goldenIdentity,
        ) }
        val events = mutableListOf<DiagnosticEntry>()
        HartClient(transport, events::add).identify()
        assertEquals(4, events.count { it.kind == "IGNORE" })
    }
    @Test fun fragmentedResponseAndCorruptFrameAreHandled() = runTest {
        val corrupt = goldenIdentity.copyOf().also { it[it.lastIndex] = 0 }
        val transport = SimulatedTransport { _, _ -> listOf(corrupt) + goldenIdentity.map { byteArrayOf(it) } }
        HartClient(transport).identify()
        assertEquals(1, transport.writes.size)
    }
    @Test fun incompleteFrameIsDiagnosedAtTimeout() = runTest {
        val transport = SimulatedTransport { _, _ -> listOf(goldenIdentity.dropLast(1).toByteArray()) }
        val events = mutableListOf<DiagnosticEntry>()
        try { HartClient(transport, events::add, timeoutMillis = 50, readAttempts = 1).identify(); fail()
        } catch (_: HartTimeoutException) { assertTrue(events.any { it.message.contains("Неполный") }) }
    }
    @Test fun parallelCallsAreSerialized() = runTest {
        var outstanding = 0
        var maximumOutstanding = 0
        var writes = 0
        val replies = Channel<ByteArray>(Channel.UNLIMITED)
        val transport = object : HartTransport {
            override suspend fun write(bytes: ByteArray) {
                writes++
                outstanding++
                maximumOutstanding = maxOf(maximumOutstanding, outstanding)
                delay(10) // Force competing callers to reach the request boundary.
                replies.send(goldenIdentity)
            }
            override suspend fun receive(): ByteArray = replies.receive().also { outstanding-- }
            override fun discardBufferedInput() = emptyList<ByteArray>()
            override fun close() { replies.close() }
        }
        val client = HartClient(transport)
        val values = List(5) { async { client.identify().first.uniqueKey } }.awaitAll()
        assertEquals(List(5) { "26AB-123456" }, values)
        assertEquals(5, writes)
        assertEquals(1, maximumOutstanding)
    }
    @Test fun outerCancellationDoesNotTriggerRetry() = runTest {
        val transport = SimulatedTransport { _, _ -> emptyList() }
        try { withTimeout(10) { HartClient(transport, timeoutMillis = 100).identify() }; fail()
        } catch (_: kotlinx.coroutines.TimeoutCancellationException) { assertEquals(1, transport.writes.size) }
    }
    @Test fun bluetoothIoFailureIsNotRetriedAsHartTimeout() = runTest {
        val transport = SimulatedTransport { _, _ -> throw IOException("Bluetooth lost") }
        try { HartClient(transport).identify(); fail()
        } catch (e: IOException) { assertEquals("Bluetooth lost", e.message); assertEquals(1, transport.writes.size) }
    }
    @Test fun deviceRequestedPreamblesAreHonored() = runTest {
        val reply = changedFrame(goldenIdentity) { it[14] = 30 }
        val transport = SimulatedTransport { _, n -> listOf(if (n == 1) reply else goldenPv) }
        val client = HartClient(transport)
        val identity = client.identify().first
        client.primaryVariable(identity)
        assertEquals(30, transport.writes[1].takeWhile { it.u8() == 255 }.size)
    }
    @Test fun previouslyBufferedRepliesAreLoggedAndNeverAcceptedForNewRequest() = runTest {
        val transport = SimulatedTransport { _, _ -> emptyList() }
        transport.queue.send(goldenIdentity)
        val events = mutableListOf<DiagnosticEntry>()
        try { HartClient(transport, events::add, timeoutMillis = 50, readAttempts = 1).identify(); fail()
        } catch (_: HartTimeoutException) {
            assertEquals(1, events.count { it.kind == "STALE" })
            assertArrayEquals(goldenIdentity, events.single { it.kind == "STALE" }.bytes)
        }
    }
}
