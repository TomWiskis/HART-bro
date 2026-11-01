package ru.hartbro.core

import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionLifecycle030Test {
    @Test fun connectingDoesNotCreateHistoryOrPollUntilExplicitStart() = runTest {
        var begins = 0; var samples = 0; var identifies = 0
        val connection = object : MonitorConnection {
            override suspend fun identify() = metran02.also { identifies++ }
            override suspend fun discover() = sample02().also { samples++ }
            override suspend fun sample() = sample02().also { samples++ }
            override fun close() {}
        }
        val sink = object : SessionSink {
            override fun begin(identity: DeviceIdentity, at: Instant) { begins++ }
            override fun sample(batch: MeasurementBatch) {}
            override fun event(at: Instant, text: String) {}
        }
        val engine = MonitoringEngine(backgroundScope, { connection }, sink)
        engine.start(automaticSession = false); runCurrent(); advanceTimeBy(60000); runCurrent()
        assertEquals(MonitorPhase.READY, engine.state.value.phase); assertTrue(engine.state.value.bluetoothConnected)
        assertNull(engine.state.value.startedAt); assertEquals(0, begins); assertEquals(0, samples)
        val beforeStart=identifies
        engine.startSession(); engine.startSession(); runCurrent()
        assertEquals(1, begins); assertTrue(samples > 0); assertEquals(beforeStart+1, identifies)
        assertNotNull(engine.state.value.startedAt); engine.stop()
    }
    @Test fun newSessionReusesBluetoothButRechecksIdentityAndStartsNewHistory() = runTest {
        var id = 1; var connects = 0; var closes = 0; var resets = 0
        val devices = mutableListOf<Int>()
        val connection = object : MonitorConnection {
            override suspend fun identify() = metran02.copy(deviceId = id)
            override suspend fun discover() = sample02()
            override suspend fun sample() = sample02()
            override fun resetSession() { resets++ }
            override fun close() { closes++ }
        }
        val sink = object : SessionSink {
            override fun begin(identity: DeviceIdentity, at: Instant) { devices += identity.deviceId }
            override fun sample(batch: MeasurementBatch) {}
            override fun event(at: Instant, text: String) {}
        }
        val engine = MonitoringEngine(backgroundScope, { connects++; connection }, sink)
        engine.start(false); runCurrent(); engine.startSession(); runCurrent()
        engine.pause(true); assertEquals(MonitorPhase.PAUSED, engine.state.value.phase)
        engine.pause(false); assertEquals(MonitorPhase.MONITORING, engine.state.value.phase)
        engine.finishSession(); runCurrent(); assertEquals(MonitorPhase.READY, engine.state.value.phase); assertNull(engine.state.value.startedAt)
        id = 2; engine.startSession(); runCurrent()
        assertEquals(listOf(1, 2), devices); assertEquals(1, connects); assertEquals(0, closes); assertEquals(2, resets)
        engine.stop(); assertEquals(1, closes)
    }
    @Test fun failedInitialIdentificationLeavesNoSessionAndReportsError() = runTest {
        var begins = 0; var closes = 0
        val connection = object : MonitorConnection {
            override suspend fun identify(): DeviceIdentity { delay(5000); throw HartTimeoutException("No initial HART reply") }
            override suspend fun discover() = sample02()
            override suspend fun sample() = sample02()
            override fun close() { closes++ }
        }
        val sink = object : SessionSink {
            override fun begin(identity: DeviceIdentity, at: Instant) { begins++ }
            override fun sample(batch: MeasurementBatch) {}
            override fun event(at: Instant, text: String) {}
        }
        val engine = MonitoringEngine(backgroundScope, { connection }, sink)
        engine.start(false); advanceTimeBy(5001); runCurrent()
        assertEquals(MonitorPhase.ERROR, engine.state.value.phase); assertEquals(0, begins); assertNull(engine.state.value.startedAt)
        assertEquals(0, closes); assertTrue(engine.state.value.bluetoothConnected)
        assertTrue(engine.state.value.message.contains("No initial HART reply")); engine.stop(); assertEquals(1, closes)
    }
}
