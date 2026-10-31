package ru.hartbro.core

import java.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

private class RangeLeaseSink:SessionSink {
    var begins=0;var samples=0;val events=mutableListOf<String>()
    override fun begin(identity:DeviceIdentity,at:Instant){begins++}
    override fun sample(batch:MeasurementBatch){samples++}
    override fun event(at:Instant,text:String){events+=text}
}
@OptIn(ExperimentalCoroutinesApi::class)
class ConfigurationLeaseTest {
    @Test fun activeSessionWaitsForInFlightSampleAndContinuesSameHistoryAfterVerifiedRangeChange()=runTest {
        val sampleGate=CompletableDeferred<Unit>();val sink=RangeLeaseSink();var closes=0;var calls=0
        val c=object:MonitorConnection {
            override suspend fun identify()=identity040()
            override suspend fun discover()=sample02()
            override suspend fun sample():MeasurementBatch { calls++;if(calls==1)sampleGate.await();return sample02(testScheduler.currentTime) }
            override fun close(){closes++}
        }
        val engine=MonitoringEngine(backgroundScope,{c},sink,monotonicMillis={testScheduler.currentTime});engine.start();runCurrent()
        val started=engine.state.value.startedAt
        val lease=async{engine.acquireConfiguration()};runCurrent();assertFalse(lease.isCompleted)
        sampleGate.complete(Unit);runCurrent();assertSame(c,lease.await());val samples=sink.samples
        advanceTimeBy(5000);runCurrent();assertEquals(samples,sink.samples);assertEquals(0,closes)
        val config=ConfigurationSnapshot(ConfigurationDecoder.deviceInformation(range030(0f,10f,12),7))
        engine.releaseConfiguration(c,RangeVerification(identity040(),config),true,"Диапазон 0…16 → 0…10 кПа")
        advanceTimeBy(2001);runCurrent();assertTrue(sink.samples>samples);assertEquals(1,sink.begins);assertEquals(started,engine.state.value.startedAt)
        assertEquals(10f,engine.state.value.configuration.range!!.upper,0f);assertTrue(sink.events.any{it.contains("0…16 → 0…10")});engine.stop()
    }
    @Test fun uncertainResultStaysPausedUntilVerificationAndOriginalUserPauseIsPreserved()=runTest {
        val sink=RangeLeaseSink();val c=object:MonitorConnection {
            override suspend fun identify()=identity040()
            override suspend fun discover()=sample02()
            override suspend fun sample()=sample02()
            override fun close(){}
        }
        val e=MonitoringEngine(backgroundScope,{c},sink);e.start();runCurrent();e.acquireConfiguration()
        e.releaseConfiguration(c,null,false,"Не подтверждено");val samples=sink.samples;advanceTimeBy(10000);runCurrent();assertEquals(samples,sink.samples)
        assertEquals(MonitorPhase.DECISION,e.state.value.phase)
        e.acquireConfiguration();e.releaseConfiguration(c,RangeVerification(identity040(),ConfigurationSnapshot(ConfigurationDecoder.deviceInformation(range030(0f,10f,12),7))),true,"Проверено")
        advanceTimeBy(2001);runCurrent();assertTrue(sink.samples>samples)
        e.pause(true);e.acquireConfiguration();e.releaseConfiguration(c,null,true,"Без записи");advanceTimeBy(3000);runCurrent();assertEquals(MonitorPhase.PAUSED,e.state.value.phase);e.stop()
    }
    @Test fun walkLeaseStopsProbingWithoutDestroyingBookAndResumesWithFreshPassport()=runTest {
        var probes=0;var upper=16f
        val c=object:MonitorConnection {
            override suspend fun identify()=identity040().also{probes++}
            override suspend fun passport(identity:DeviceIdentity)=reading040(range=ConfiguredRange(0f,upper,12))
            override suspend fun discover()=error("must not measure")
            override suspend fun sample()=error("must not measure")
            override fun close(){}
        }
        val e=WalkEngine(backgroundScope,{c},clock={testScheduler.currentTime});e.start();runCurrent();val book=e.state.value.book!!.id
        assertSame(c,e.acquireConfiguration());val before=probes;advanceTimeBy(10000);runCurrent();assertEquals(before,probes)
        upper=10f;e.releaseConfiguration(c,true,"Проверен новый диапазон");advanceTimeBy(1001);runCurrent()
        assertEquals(book,e.state.value.book!!.id);assertEquals(1,e.state.value.book!!.rows.size);assertEquals(10f,e.state.value.book!!.rows.single().pendingRange!!.upper,0f);e.stop()
    }
}
