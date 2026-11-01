package ru.hartbro.core

import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class QuickView053Test {
    private class Wire : MonitorConnection {
        var device=metran02;var open=true;var fail=false;var ids=0;var configurations=0;var samples=0;var disconnect:(()->Unit)?=null
        override val isOpen get()=open
        override fun onDisconnect(listener:()->Unit){disconnect=listener}
        override suspend fun identify():DeviceIdentity { if(!open)throw IOException("closed");ids++;if(fail)throw HartResponseException(32,0);return device }
        override suspend fun configuration()=ConfigurationSnapshot(mapOf("tag" to ConfigValue("TAG-${device.deviceId}",13))).also{configurations++}
        override suspend fun sample()=sample02().also{samples++}
        override suspend fun discover()=sample()
        override fun close(){open=false;disconnect?.invoke()}
    }
    private class Sink : SessionSink {
        var begins=0;var samples=0
        override fun begin(identity:DeviceIdentity,at:Instant){begins++}
        override fun sample(batch:MeasurementBatch){samples++}
        override fun event(at:Instant,text:String){}
    }
    @Test fun quickPresencePollUpdatesSwappedDeviceWithoutAnyMeasurementOrSession()=runTest {
        val wire=Wire();val sink=Sink();val engine=MonitoringEngine(backgroundScope,{wire},sink,wallTime={Instant.ofEpochMilli(testScheduler.currentTime)})
        engine.start(false);runCurrent();advanceTimeBy(4501);runCurrent()
        assertEquals(4,wire.ids);assertEquals(1,wire.configurations);assertTrue(engine.state.value.hartExchangeCurrent(Instant.ofEpochMilli(testScheduler.currentTime)))
        wire.device=metran02.copy(deviceId=22);advanceTimeBy(1500);runCurrent()
        assertEquals(22,engine.state.value.identity!!.deviceId);assertEquals("TAG-22",engine.state.value.configuration.tag);assertEquals(2,wire.configurations)
        assertEquals(0,sink.begins);assertEquals(0,sink.samples);assertEquals(0,wire.samples);assertNull(engine.state.value.latest);engine.stop()
    }
    @Test fun failedHeartbeatKeepsConfigurationAndHealthyRfcommButTurnsSensorRed()=runTest {
        val wire=Wire();val engine=MonitoringEngine(backgroundScope,{wire},Sink())
        engine.start(false);runCurrent();val config=engine.state.value.configuration
        wire.fail=true;advanceTimeBy(1501);runCurrent()
        assertEquals(config,engine.state.value.configuration);assertTrue(engine.state.value.bluetoothConnected);assertFalse(engine.state.value.identificationResponding)
        assertTrue(engine.state.value.message.contains("0x20"));assertFalse(engine.state.value.hartExchangeCurrent(Instant.now()))
        wire.fail=false;advanceTimeBy(1500);runCurrent();assertTrue(engine.state.value.identificationResponding);assertTrue(wire.open);engine.stop()
    }
    @Test fun lostRfcommReconnectsForQuickViewAndAcceptsNextSensorWithoutPollingPv()=runTest {
        val first=Wire();val second=Wire().also{it.device=metran02.copy(deviceId=77)};var connects=0;val sink=Sink()
        val engine=MonitoringEngine(backgroundScope,{if(connects++==0)first else second},sink,monotonicMillis={testScheduler.currentTime})
        engine.start(false);runCurrent();first.close();assertFalse(engine.state.value.bluetoothConnected)
        advanceTimeBy(1501);runCurrent()
        assertEquals(2,connects);assertEquals(MonitorPhase.READY,engine.state.value.phase);assertTrue(engine.state.value.bluetoothConnected)
        assertEquals(77,engine.state.value.identity!!.deviceId);assertEquals(0,second.samples);assertEquals(0,sink.begins);engine.stop()
    }
    @Test fun finishedSessionReturnsToPresencePollingAndOnlyButtonStartsAnotherHistory()=runTest {
        val wire=Wire();val sink=Sink();val engine=MonitoringEngine(backgroundScope,{wire},sink)
        engine.start(false);runCurrent();engine.startSession();runCurrent();assertEquals(1,sink.begins)
        engine.finishSession();runCurrent();val samples=wire.samples;val ids=wire.ids
        advanceTimeBy(6001);runCurrent();assertTrue(wire.ids>ids);assertEquals(samples,wire.samples);assertEquals(1,sink.begins)
        engine.startSession();runCurrent();assertEquals(2,sink.begins);engine.stop()
    }
    @Test fun chartHistoricalZoomNeverTurnsFollowOnAndPanDoesNotChangeScale() {
        val manual=ChartViewport(30_000,99_500,false)
        val zoom=manual.transform(0,100_000,2f,0f)
        assertFalse(zoom.following)
        val pan=zoom.transform(0,101_000,1f,.25f)
        assertFalse(pan.following);assertEquals(zoom.spanMillis,pan.spanMillis)
        val bounds=pan.bounds(0,101_000);assertEquals(bounds,pan.bounds(0,200_000))
        assertTrue(pan.seek(0,200_000,1f).following)
    }
}
