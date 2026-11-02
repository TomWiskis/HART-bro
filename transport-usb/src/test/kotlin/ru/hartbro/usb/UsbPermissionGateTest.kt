package ru.hartbro.usb

import kotlinx.coroutines.runBlocking
import java.io.IOException
import org.junit.Test
import org.junit.Assert.*

class UsbPermissionGateTest {
    @Test fun grantRequiresMatchingDeviceCurrentRequestAndPhysicalPresence()=runBlocking {
        val gate=UsbPermissionGate();val request=gate.begin("/dev/usb/1")
        assertFalse(gate.resolve("other",request.sequence,true,true));assertFalse(request.result.isCompleted)
        assertTrue(gate.resolve(request.path,request.sequence,true,true));request.result.await();gate.finish(request)
    }
    @Test fun denialAndDismissalDoNotLoopUntilUserSelectsAgain()=runBlocking {
        val gate=UsbPermissionGate();val request=gate.begin("p")
        gate.resolve("p",request.sequence,true,false)
        assertTrue(runCatching{request.result.await()}.exceptionOrNull() is IOException);gate.finish(request)
        assertThrows(IOException::class.java){gate.begin("p")}
        gate.resetSelection();val next=gate.begin("p");assertTrue(next.sequence>request.sequence);gate.finish(next)
    }
    @Test fun detachBeforeGrantRejectsAndOldGrantCannotSatisfyReattachedRequest()=runBlocking {
        val gate=UsbPermissionGate();val old=gate.begin("p");gate.detached("p")
        assertTrue(runCatching{old.result.await()}.exceptionOrNull() is IOException);gate.finish(old)
        val fresh=gate.begin("p")
        assertFalse(gate.resolve("p",old.sequence,true,true));assertFalse(fresh.result.isCompleted)
        gate.resolve("p",fresh.sequence,true,true);fresh.result.await();gate.finish(fresh)
    }
    @Test fun physicallyAbsentDeviceCannotGrantAccessAndOnlyOneRequestCanOwnGate()=runBlocking {
        val gate=UsbPermissionGate();val ticket=gate.begin("p")
        assertThrows(IllegalStateException::class.java){gate.begin("other")}
        gate.resolve("p",ticket.sequence,false,true)
        assertTrue(runCatching{ticket.result.await()}.isFailure);gate.finish(ticket)
    }
}
