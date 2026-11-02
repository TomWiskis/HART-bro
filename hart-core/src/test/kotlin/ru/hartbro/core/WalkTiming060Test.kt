package ru.hartbro.core

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.*
import org.junit.Test
import org.junit.Assert.*

/** Identical scripted benchmark run against 0.5.3 and 0.6.0. Virtual time excludes host CPU/Gradle/human switching. */
class WalkTiming060Test {
    @Test fun measuredConfigurationAndNoResponseCycles()=runTest {
        val identity=identity040(revision=7)
        val startTimes=mutableListOf<Long>();val commands=mutableListOf<Int>();var missing=setOf<Int>()
        val incoming=Channel<ByteArray>(Channel.UNLIMITED)
        val wire=object:HartTransport {
            override suspend fun write(bytes:ByteArray) {
                val f=(HartDecoder().accept(bytes).single() as DecodeEvent.Frame).value
                startTimes+=testScheduler.currentTime;commands+=f.command
                if(f.command in missing)return
                delay(150)
                val data=when(f.command) {
                    0->identity.raw
                    13->byteArrayOf(4,32,0xE0.toByte(),0x82.toByte(),8,32)+ByteArray(12){0x82.toByte()}+byteArrayOf(1,2,126)
                    15->range030(0f,10f,12)
                    14->byteArrayOf(0,4,0xD2.toByte(),12)+java.nio.ByteBuffer.allocate(12).putFloat(200f).putFloat(-100f).putFloat(1f).array()
                    16->byteArrayOf(0,1,44)
                    20->"USED-LONG-TAG".padEnd(32,'\u0000').toByteArray()
                    else->error("unexpected ${f.command}")
                }
                val payload=byteArrayOf(0,0)+data
                val body=byteArrayOf(if(f.address.size==1)6 else 0x86.toByte())+f.address+byteArrayOf(f.command.toByte(),payload.size.toByte())+payload
                incoming.send(ByteArray(4){0xFF.toByte()}+body+body.fold(0){a,b->a xor(b.toInt() and 255)}.toByte())
            }
            override suspend fun receive()=incoming.receive()
            override fun discardBufferedInput()=buildList{while(true)add(incoming.tryReceive().getOrNull()?:break)}
            override fun close(){}
        }
        for(silence in listOf(false,true)) {
            commands.clear();startTimes.clear();missing=if(silence)setOf(14,16) else emptySet()
            val client=HartClient(wire,timeoutMillis=1500,readAttempts=1)
            val t0=testScheduler.currentTime;val found=client.identify().first;val t1=testScheduler.currentTime
            val reading=PassportReader(client).read(found);val t2=testScheduler.currentTime
            assertNotNull(reading.range)
            println("WALK_BENCH config silent14and16=$silence discoveryMs=${t1-t0} configurationMs=${t2-t1} totalMs=${t2-t0} commands=$commands timeouts=${reading.errors.size}")
        }
        missing=setOf(0);commands.clear();startTimes.clear()
        val client=HartClient(wire,timeoutMillis=1500,readAttempts=1)
        val connection=object:MonitorConnection {
            override suspend fun identify()=client.identify().first
            override suspend fun discover():MeasurementBatch=error("walk must not sample")
            override suspend fun sample():MeasurementBatch=error("walk must not sample")
            override fun close(){}
        }
        val t0=testScheduler.currentTime
        val engine=WalkEngine(backgroundScope,{connection},clock={testScheduler.currentTime})
        engine.start();advanceTimeBy(9000);runCurrent();engine.stop()
        val periods=startTimes.zipWithNext{a,b->b-a}
        assertTrue(periods.size>=2)
        println("WALK_BENCH noResponse txOffsetsMs=${startTimes.map{it-t0}} cycleMs=$periods rows=${engine.catalog.snapshot().rows.size}")
    }
}
