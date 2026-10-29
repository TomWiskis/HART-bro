package ru.hartbro.core

import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

private class MemorySink : SessionSink {
    val batches = mutableListOf<MeasurementBatch>()
    val events = mutableListOf<Pair<Instant,String>>()
    var begins = 0
    override fun begin(identity: DeviceIdentity, at: Instant) { begins++ }
    override fun sample(batch: MeasurementBatch) { batches += batch }
    override fun event(at: Instant, text: String) { events += at to text }
}
private class TestConnection(
    val onIdentify: suspend () -> DeviceIdentity = { metran02 },
    val onSample: suspend () -> MeasurementBatch = { sample02() },
) : MonitorConnection {
    var closes = 0
    override suspend fun identify() = onIdentify()
    override suspend fun discover() = sample02()
    override suspend fun sample() = onSample()
    override fun close() { closes++ }
}
@OptIn(ExperimentalCoroutinesApi::class)
class MonitoringEngineTest {
    @Test fun pollingIsSerializedAndSlowerExchangesDoNotBuildABacklog() = runTest {
        val starts = mutableListOf<Long>(); var outstanding = 0; var peak = 0
        val conn = TestConnection(onSample = {
            starts += testScheduler.currentTime; outstanding++; peak = maxOf(peak,outstanding)
            delay(3000); outstanding--; sample02(testScheduler.currentTime)
        })
        val sink = MemorySink()
        val engine = MonitoringEngine(backgroundScope,{conn},sink,monotonicMillis={testScheduler.currentTime})
        engine.setPeriod(1000); engine.start(); runCurrent(); advanceTimeBy(10_000); runCurrent()
        assertEquals(1,peak); assertTrue(starts.zipWithNext().all { (a,b) -> b-a>=3000 }); assertEquals(4,starts.size)
        assertEquals(3,sink.batches.drop(1).size); engine.stop(); assertEquals(1,conn.closes)
    }
    @Test fun pauseDropsInFlightMeasurementAndResumeDoesNotReplayIt() = runTest {
        val sink = MemorySink(); val conn = TestConnection(onSample={delay(100);sample02(testScheduler.currentTime)})
        val engine = MonitoringEngine(backgroundScope,{conn},sink,monotonicMillis={testScheduler.currentTime})
        engine.start(); runCurrent(); engine.pause(true); advanceTimeBy(5000); runCurrent()
        assertEquals(1,sink.batches.size); assertNull(engine.state.value.latest); assertEquals(MonitorPhase.PAUSED,engine.state.value.phase)
        engine.pause(false); advanceTimeBy(2500); runCurrent(); assertTrue(sink.batches.size>1)
        assertTrue(sink.events.any { it.second=="Пауза" }); engine.stop()
    }
    @Test fun periodCanChangeWithoutCreatingAnotherPollingJob() = runTest {
        val times = mutableListOf<Long>()
        val conn = TestConnection(onSample={times+=testScheduler.currentTime;sample02(testScheduler.currentTime)})
        val engine = MonitoringEngine(backgroundScope,{conn},MemorySink(),monotonicMillis={testScheduler.currentTime})
        engine.start(); runCurrent(); engine.setPeriod(1000); advanceTimeBy(4001); runCurrent()
        assertEquals(listOf(0L,2000L,3000L,4000L),times); engine.stop()
    }
    @Test fun oneHartErrorClearsCurrentValuesButKeepsBluetooth() = runTest {
        var reads=0; var connects=0
        val conn = TestConnection(onSample={reads++; if(reads==1) throw HartTimeoutException("no response"); sample02(testScheduler.currentTime)})
        val engine = MonitoringEngine(backgroundScope,{connects++;conn},MemorySink(),monotonicMillis={testScheduler.currentTime})
        engine.start(); runCurrent(); assertNull(engine.state.value.latest); assertTrue(engine.state.value.bluetoothConnected)
        advanceTimeBy(2001); runCurrent(); assertNotNull(engine.state.value.latest); assertEquals(1,connects); engine.stop()
    }
    @Test fun threeHartFailuresRecoverWithoutBreakingHealthyBluetoothAndVerifyIdentity() = runTest {
        var reads=0; var identifies=0; var connects=0
        val conn = TestConnection(onIdentify={identifies++;metran02},onSample={reads++;if(reads<=3)throw HartTimeoutException("timeout");sample02()})
        val sink=MemorySink();val engine=MonitoringEngine(backgroundScope,{connects++;conn},sink,monotonicMillis={testScheduler.currentTime})
        engine.start();runCurrent();advanceTimeBy(4001);runCurrent()
        assertEquals(2,identifies);assertEquals(1,connects);assertEquals(0,conn.closes)
        assertEquals(MonitorPhase.MONITORING,engine.state.value.phase);assertTrue(sink.events.any{it.second.startsWith("Связь восстановлена")});engine.stop()
    }
    @Test fun physicalLossImmediatelyReconnectsAndKeepsSameSession() = runTest {
        val old=TestConnection(onSample={throw IOException("Bluetooth lost")});val replacement=TestConnection()
        var connects=0;val sink=MemorySink();val engine=MonitoringEngine(backgroundScope,{if(connects++==0)old else replacement},sink)
        engine.start();runCurrent();assertEquals(2,connects);assertEquals(1,old.closes);assertEquals(1,sink.begins)
        assertEquals(MonitorPhase.MONITORING,engine.state.value.phase);engine.stop()
    }
    @Test fun differentHartDeviceIsNeverMergedWithExistingSession() = runTest {
        val old=TestConnection(onSample={throw IOException("gone")});var reads=0
        val other=TestConnection(onIdentify={metran02.copy(deviceId=1)},onSample={reads++;sample02()})
        var count=0;val sink=MemorySink();val engine=MonitoringEngine(backgroundScope,{if(count++==0)old else other},sink)
        engine.start();runCurrent();assertEquals(MonitorPhase.DIFFERENT_DEVICE,engine.state.value.phase)
        assertEquals(0,reads);assertEquals(1,sink.batches.size);assertEquals(1,other.closes);engine.stop()
    }
    @Test fun recoveryDeadlineIsExactly60SecondsAndWaitsForUserWithoutMoreAttempts() = runTest {
        var ids=0
        val conn=TestConnection(onIdentify={if(ids++==0)metran02 else throw HartTimeoutException("lost")},onSample={throw HartTimeoutException("lost")})
        val sink=MemorySink();val engine=MonitoringEngine(backgroundScope,{conn},sink,monotonicMillis={testScheduler.currentTime})
        engine.start();runCurrent();advanceTimeBy(4000);runCurrent();assertEquals(MonitorPhase.RECOVERING,engine.state.value.phase)
        advanceTimeBy(59_999);runCurrent();assertEquals(MonitorPhase.RECOVERING,engine.state.value.phase)
        advanceTimeBy(1);runCurrent();assertEquals(MonitorPhase.DECISION,engine.state.value.phase)
        val before=ids;advanceTimeBy(120_000);runCurrent();assertEquals(before,ids);assertEquals(1,sink.batches.size);engine.stop()
    }
    @Test fun nestedConnectTimeoutsCannotExtendRecoveryBudget() = runTest {
        var connects=0
        val old=TestConnection(onSample={throw IOException("gone")})
        val engine=MonitoringEngine(backgroundScope,{
            if(connects++==0)old else withTimeout(15000){delay(30000);TestConnection()}
        },MemorySink(),monotonicMillis={testScheduler.currentTime})
        engine.start();runCurrent();advanceTimeBy(60_000);runCurrent()
        assertEquals(MonitorPhase.DECISION,engine.state.value.phase);assertEquals(5,connects);engine.stop()
    }
    @Test fun initialConnectTimeoutIsAnErrorInsteadOfStuckConnecting() = runTest {
        val engine=MonitoringEngine(backgroundScope,{withTimeout(100){delay(1000);TestConnection()}},MemorySink())
        engine.start();advanceTimeBy(101);runCurrent();assertEquals(MonitorPhase.ERROR,engine.state.value.phase);engine.stop()
    }
    @Test fun stoppingCancelsRecoveryAndClosesResourcesWithoutSaving() = runTest {
        val conn=TestConnection(onSample={throw IOException("gone")});var connects=0
        val engine=MonitoringEngine(backgroundScope,{if(connects++==0)conn else {delay(10_000);TestConnection()}},MemorySink())
        engine.start();runCurrent();engine.stop();advanceTimeBy(100_000);runCurrent()
        assertEquals(2,connects);assertEquals(MonitorPhase.IDLE,engine.state.value.phase);assertEquals(1,conn.closes)
    }
    @Test fun sampleTimestampsRemainTheActualReceivedTimes() = runTest {
        val sink=MemorySink();val conn=TestConnection(onSample={delay(250);sample02(testScheduler.currentTime)})
        val engine=MonitoringEngine(backgroundScope,{conn},sink,monotonicMillis={testScheduler.currentTime})
        engine.start();advanceTimeBy(2251);runCurrent()
        assertEquals(listOf(0L,250L,2250L),sink.batches.map{it.receivedAt.toEpochMilli()});engine.stop()
    }

    @Test fun initialPvFailureAfterIdentityUsesBoundedRecovery() = runTest {
        var discovery=0
        val conn=object:MonitorConnection{
            override suspend fun identify()=metran02
            override suspend fun discover():MeasurementBatch{discovery++;throw HartTimeoutException("initial PV missing")}
            override suspend fun sample()=sample02(10)
            override fun close(){}
        }
        val sink=MemorySink();val engine=MonitoringEngine(backgroundScope,{conn},sink)
        engine.start();runCurrent();assertEquals(MonitorPhase.MONITORING,engine.state.value.phase)
        assertEquals(1,discovery);assertEquals(1,sink.begins);assertTrue(sink.events.any{it.second.startsWith("Связь восстановлена")});engine.stop()
    }
    @Test fun variableQualityFailureIsNotMisreportedAsTransportLoss() = runTest {
        val conn=TestConnection(onSample={sample02(100,Float.NaN,Quality.BAD)})
        val engine=MonitoringEngine(backgroundScope,{conn},MemorySink())
        engine.start();runCurrent();assertEquals(MonitorPhase.MONITORING,engine.state.value.phase)
        assertTrue(engine.state.value.bluetoothConnected);assertFalse(engine.state.value.latest!!.values.single().plottable);engine.stop()
    }
    @Test fun exhaustedHistoryStopsPollingAndRequestsDecisionInsteadOfReconnecting() = runTest {
        var connects=0
        val sink=object:SessionSink{
            override fun begin(identity:DeviceIdentity,at:Instant){}
            override fun sample(batch:MeasurementBatch){throw HistoryLimitException()}
            override fun event(at:Instant,text:String){}
        }
        val conn=TestConnection();val engine=MonitoringEngine(backgroundScope,{connects++;conn},sink)
        engine.start();runCurrent();assertEquals(MonitorPhase.DECISION,engine.state.value.phase)
        assertEquals(1,connects);assertEquals(1,conn.closes);engine.stop()
    }
}
