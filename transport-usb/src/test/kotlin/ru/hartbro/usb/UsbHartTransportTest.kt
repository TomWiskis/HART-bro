package ru.hartbro.usb

import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import ru.hartbro.core.*

private fun settings()=UsbPortSettings(1200,8,1,1,SerialFlow.NONE,RtsMode.LOW,false,"Scripted test fixture, not an ЭнИ UART claim")
private class Port:SerialPortIo {
    lateinit var receive:(ByteArray)->Unit
    lateinit var fail:(Exception)->Unit
    val writes=mutableListOf<ByteArray>();val closes=AtomicInteger()
    var answer:(ByteArray)->Unit={}
    override fun start(received:(ByteArray)->Unit,failed:(Exception)->Unit){receive=received;fail=failed}
    override fun write(bytes:ByteArray){synchronized(writes){writes+=bytes.copyOf()};answer(bytes)}
    override fun rts(value:Boolean){}
    override fun close(){closes.incrementAndGet()}
}
class UsbHartTransportTest {
    @Test(timeout=10000) fun rawFragmentsDrainAndCloseReleaseExactlyOnce()=runBlocking {
        val port=Port();val transport=UsbHartTransport(port,settings());var lost=0;transport.onDisconnect{lost++}
        port.receive(byteArrayOf(1));port.receive(byteArrayOf(2,3))
        assertArrayEquals(byteArrayOf(1),transport.receive());assertArrayEquals(byteArrayOf(2,3),transport.discardBufferedInput().single())
        transport.write(byteArrayOf(4,5));assertArrayEquals(byteArrayOf(4,5),port.writes.single())
        transport.close();transport.close();assertFalse(transport.isOpen);assertEquals(1,lost);assertEquals(1,port.closes.get())
    }
    @Test(timeout=10000) fun detachDuringReadFailsPendingReadAndPreventsAnyNewTx()=runBlocking {
        val port=Port();val transport=UsbHartTransport(port,settings())
        val receive=async{runCatching{transport.receive()}};yield();transport.detached()
        assertTrue(receive.await().exceptionOrNull() is IOException)
        assertTrue(runCatching{transport.write(byteArrayOf(35))}.exceptionOrNull() is IOException)
        assertTrue(port.writes.isEmpty());assertFalse(transport.isOpen)
    }
    @Test(timeout=10000) fun nativeReadErrorAndWriteErrorClosePortAndPublishDisconnect()=runBlocking {
        for(write in listOf(false,true)) {
            val port=Port();val transport=UsbHartTransport(port,settings());var lost=0;transport.onDisconnect{lost++}
            if(write){port.answer={throw IOException("busy/unplug")};assertTrue(runCatching{transport.write(byteArrayOf(1))}.isFailure)}
            else port.fail(IOException("read failed"))
            assertFalse(transport.isOpen);assertEquals(1,lost);assertEquals(1,port.closes.get())
        }
    }
    @Test(timeout=10000) fun cancellingBlockedWriteReleasesPortAndLateCloseErrorDoesNotBecomeReadFailure()=runBlocking {
        val started=CompletableDeferred<Unit>();val released=java.util.concurrent.CountDownLatch(1)
        val closes=AtomicInteger();val writes=AtomicInteger();var lateFailure:((Exception)->Unit)?=null
        val logs=java.util.Collections.synchronizedList(mutableListOf<String>())
        val port=object:SerialPortIo {
            override fun start(received:(ByteArray)->Unit,failed:(Exception)->Unit){lateFailure=failed}
            override fun write(bytes:ByteArray){writes.incrementAndGet();started.complete(Unit);released.await()}
            override fun rts(value:Boolean){}
            override fun close(){closes.incrementAndGet();released.countDown();lateFailure?.invoke(IOException("native reader cancelled by close"))}
        }
        val transport=UsbHartTransport(port,settings(),{logs+=it})
        val writing=launch{transport.write(byteArrayOf(35))};withTimeout(2000){started.await()}
        withTimeout(2000){writing.cancelAndJoin()}
        assertFalse(transport.isOpen);assertEquals(1,closes.get());assertEquals(1,writes.get())
        assertTrue(logs.none{it.contains("READ/WRITE ERROR")})
    }
    @Test(timeout=10000) fun uartSettingsRequireExplicitSourceAndRejectConflictingFlowAndRts() {
        assertThrows(IllegalArgumentException::class.java){settings().copy(source="")}
        assertThrows(IllegalArgumentException::class.java){settings().copy(baud=0)}
        assertThrows(IllegalArgumentException::class.java){settings().copy(flow=SerialFlow.RTS_CTS,rts=RtsMode.TRANSMIT_HIGH)}
        assertEquals(202,settings().wireMillis(22)) // 22 * 11 bits at 1200, ceiling.
    }
    private fun response(request:ByteArray):ByteArray {
        val f=(HartDecoder().accept(request).single() as DecodeEvent.Frame).value
        val data=byteArrayOf(0,0,0xFE.toByte(),0x26,0x62,5,5,7,0x28,8,0,0xBF.toByte(),0x2C,0x34)
        val body=byteArrayOf(6)+f.address+byteArrayOf(f.command.toByte(),data.size.toByte())+data
        return ByteArray(4){0xFF.toByte()}+body+body.fold(0){a,b->a xor (b.toInt() and 255)}.toByte()
    }
    @Test(timeout=10000) fun noSensorTimeoutKeepsUsbOpenThenFragmentedFourFfReplyIdentifiesOnSamePort()=runBlocking {
        val port=Port();val transport=UsbHartTransport(port,settings());val client=HartClient(transport,timeoutMillis=100,readAttempts=1)
        assertTrue(runCatching{client.identify()}.exceptionOrNull() is HartTimeoutException);assertTrue(transport.isOpen)
        port.answer={tx->response(tx).forEach{port.receive(byteArrayOf(it))}}
        assertEquals(0xBF2C34,client.identify().first.deviceId);assertEquals(2,port.writes.size);assertTrue(transport.isOpen);transport.close()
    }
    @Test(timeout=10000) fun missingXorThenLateByteIsDrainedBeforeNextSuccessfulUsbIdentification()=runBlocking {
        val port=Port();val transport=UsbHartTransport(port,settings());val diagnostics=mutableListOf<DiagnosticEntry>()
        val client=HartClient(transport,{synchronized(diagnostics){diagnostics+=it}},timeoutMillis=100,readAttempts=1)
        var last:Byte=0
        port.answer={tx->val frame=response(tx);last=frame.last();port.receive(frame.dropLast(1).toByteArray())}
        assertTrue(runCatching{client.identify()}.exceptionOrNull() is HartTimeoutException)
        port.receive(byteArrayOf(last));port.answer={port.receive(response(it))}
        assertEquals(0xBF2C34,client.identify().first.deviceId)
        assertTrue(diagnostics.any{it.kind=="STALE"});assertEquals(2,port.writes.size);transport.close()
    }
    @Test(timeout=10000) fun detachAndReattachUsesNewPortWithoutRevivingOldReader()=runBlocking {
        val oldPort=Port();val old=UsbHartTransport(oldPort,settings());old.detached()
        val port=Port();val fresh=UsbHartTransport(port,settings());port.answer={port.receive(response(it))}
        oldPort.receive(byteArrayOf(0xFF.toByte()))
        assertEquals(0xBF2C34,HartClient(fresh,readAttempts=1).identify().first.deviceId)
        assertFalse(old.isOpen);assertTrue(fresh.isOpen);assertEquals(1,oldPort.closes.get());fresh.close()
    }
    @Test(timeout=10000) fun command35NeverRetriesAfterMissingReplyOnUsb()=runBlocking {
        val port=Port();val transport=UsbHartTransport(port,settings());val client=HartClient(transport,timeoutMillis=80,readAttempts=3)
        val result=runCatching{client.transaction{writeRange(HartAddress.Long(0x2662,0xBF2C34),WriteRange(ConfiguredRange(0f,1f,12))) {}}}
        assertTrue(result.exceptionOrNull() is HartTimeoutException)
        assertEquals(1,port.writes.size);assertTrue(transport.isOpen);transport.close()
    }
    @Test(timeout=10000) fun universalZeroUsesTheSameUsbPortAndNeverRepeatsAfterMissingAck()=runBlocking {
        for(lostAck in listOf(false,true)) {
            val port=Port();val transport=UsbHartTransport(port,settings())
            val requests=java.util.Collections.synchronizedList(mutableListOf<HartFrame>())
            var zeroSent=false
            port.answer={tx->
                val q=(HartDecoder().accept(tx).single() as DecodeEvent.Frame).value;requests+=q
                val data=when(q.command) {
                    0->byteArrayOf(0xFE.toByte(),0x26,0x62,5,5,9,3,8,0,0xBF.toByte(),0x2C,0x34)
                    15->ByteArray(17).also{it[0]=250.toByte();it[2]=12;java.nio.ByteBuffer.wrap(it).putFloat(3,16f).putFloat(7,0f)}
                    1->byteArrayOf(12)+java.nio.ByteBuffer.allocate(4).putFloat(if(zeroSent)0f else .25f).array()
                    43->{zeroSent=true;byteArrayOf()}
                    else->error("Unexpected command ${q.command}")
                }
                if(q.command!=43 || !lostAck) {
                    val payload=byteArrayOf(0,0)+data
                    val body=byteArrayOf(if(q.address.size==1)6 else 0x86.toByte())+q.address+byteArrayOf(q.command.toByte(),payload.size.toByte())+payload
                    port.receive(ByteArray(4){0xFF.toByte()}+body+HartCodec.checksum(body))
                }
            }
            val client=HartClient(transport,timeoutMillis=100,readAttempts=3)
            val id=client.identify().first
            val dir=java.nio.file.Files.createTempDirectory("usb-zero060").toFile()
            try {
                val options=ZeroOptions(conditionsConfirmed=true,pressureConfirmed=true,tolerance=.001f,accuracySource="Synthetic USB test accuracy",unitCode=12)
                val r=SensorOperations(client).apply(SensorEdit(id,SensorField.ZERO,"","0",options,"Scripted USB port"),SensorJournal(dir)).record
                assertEquals(if(lostAck)SensorOutcome.NO_ACK else SensorOutcome.SUCCEEDED,r.outcome)
                assertEquals(1,requests.count{it.command==43});assertEquals(0,requests.single{it.command==43}.payload.size)
                assertArrayEquals(id.address.bytes,requests.single{it.command==43}.address)
                assertTrue(requests.none{it.command==35});assertTrue(transport.isOpen);assertEquals(3,r.pvAfter.size)
            } finally {transport.close();dir.deleteRecursively()}
        }
    }
    @Test(timeout=10000) fun nativeWriteTimeoutIsTransportFailureAndDoesNotRetryCommand35()=runBlocking {
        val released=java.util.concurrent.CountDownLatch(1);val writes=AtomicInteger()
        val port=object:SerialPortIo {
            override fun start(received:(ByteArray)->Unit,failed:(Exception)->Unit){}
            override fun write(bytes:ByteArray){writes.incrementAndGet();released.await()}
            override fun rts(value:Boolean){}
            override fun close(){released.countDown()}
        }
        val transport=UsbHartTransport(port,settings());val metrics=mutableListOf<HartTransactionMetric>()
        val client=HartClient(transport,timeoutMillis=100,readAttempts=3,observed={metrics+=it})
        val error=runCatching{client.transaction{writeRange(HartAddress.Long(0x2662,0xBF2C34),WriteRange(ConfiguredRange(0f,1f,12))) {}}}.exceptionOrNull()
        assertTrue(error is HartWriteIOException);assertFalse(transport.isOpen);assertEquals(1,writes.get())
        assertEquals(setOf(HartFailureKind.TRANSPORT),metrics.single().failures)
    }

}
