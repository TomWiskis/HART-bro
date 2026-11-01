package ru.hartbro.app

import java.io.IOException
import java.nio.ByteBuffer
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import ru.hartbro.core.*

/** Instrumentation-only HART device. All writes mutate in-memory values, never a Bluetooth socket. */
class RangeTransportFixture(private val app:FieldRegressionApplication):HartTransport {
    private val incoming=Channel<ByteArray>(Channel.UNLIMITED)
    @Volatile var open=true;private set
    private fun identity():ByteArray {
        val raw="FE 99 72 05 07 07 28 08 00 5F CA 7F 05 09 00 2C 00 00 99 00 99 01".split(' ').map{it.toInt(16).toByte()}.toByteArray()
        raw[9]=(app.hartDeviceId shr 16).toByte();raw[10]=(app.hartDeviceId shr 8).toByte();raw[11]=app.hartDeviceId.toByte()
        if(app.viatranProfile){raw[1]=0x32;raw[2]=1;raw[4]=5;raw[5]=2}
        return raw
    }
    override suspend fun write(bytes:ByteArray) {
        if(!open)throw IOException("Instrumentation: range RFCOMM closed")
        val frame=(HartDecoder().accept(bytes).single() as DecodeEvent.Frame).value
        val mode=app.rangeMode
        var code=0
        if(frame.command==35) {
            app.rangeWrites.incrementAndGet()
            val buffer=ByteBuffer.wrap(frame.payload);val unit=buffer.get().toInt() and 255;val hi=buffer.float;val lo=buffer.float
            code=when(mode){2->7;3->8;else->0}
            if(mode!=2)app.rangeActual=ConfiguredRange(lo,if(mode==3)hi-.5f else hi,unit)
            if(mode==5)app.hartDeviceId=99
            if(mode==6){open=false;throw IOException("Instrumentation: applied, connection lost")}
            while(app.rangeHoldAck)delay(50)
            delay(app.rangeAckDelay)
            if(mode==1)return
        }
        if(frame.command==15 && mode==4 && app.rangeWrites.get()>0)return
        if(app.sensorMode && frame.command in setOf(18,22,43)) {
            app.sensorWrites.incrementAndGet()
            when(frame.command){18->app.sensorTagDate=frame.payload.copyOf();22->app.sensorLongTag=frame.payload.toString(Charsets.ISO_8859_1).trimEnd('\u0000');43->app.sensorPv=0f}
        }
        val range=app.rangeActual
        val data=when(frame.command) {
            0->identity()
            1->ByteBuffer.allocate(5).put(12).putFloat(app.sensorPv).array()
            14->ByteBuffer.allocate(16).put(byteArrayOf(0,4,0xD2.toByte())).put(12).putFloat(40f).putFloat(-40f).putFloat(.267f).array()
            15->ByteBuffer.allocate(18).put(1).put(0).put(range.unitCode.toByte()).putFloat(range.upper).putFloat(range.lower).putFloat(.125f).put(0).put(0).put(1).array()
            20,22->app.sensorLongTag.padEnd(32,'\u0000').toByteArray()
            13,18->app.sensorTagDate
            35->ByteBuffer.allocate(9).put(range.unitCode.toByte()).putFloat(range.upper).putFloat(range.lower).array()
            else->byteArrayOf()
        }
        val payload=byteArrayOf(code.toByte(),0x40)+data
        val body=byteArrayOf(if(frame.address.size==1)6 else 0x86.toByte())+frame.address+byteArrayOf(frame.command.toByte(),payload.size.toByte())+payload
        var xor=0;body.forEach{xor=xor xor (it.toInt() and 255)}
        (ByteArray(4){0xFF.toByte()}+body+xor.toByte()).forEach{incoming.send(byteArrayOf(it))}
    }
    override suspend fun receive()=incoming.receive()
    override fun discardBufferedInput()=buildList{while(true)add(incoming.tryReceive().getOrNull() ?: break)}
    override fun close(){open=false}
}
