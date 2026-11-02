package ru.hartbro.usb
import org.junit.Test
import org.junit.Assert.*
import com.hoho.android.usbserial.driver.*
class EniUsbDriversTest {
    @Test fun detectionUsesOnlyActualUpstreamFtdiAndWchIdTablesWithoutWildcardFallback() {
        for((vid,pids)in FtdiSerialDriver.getSupportedDevices())for(pid in pids)assertEquals(FtdiSerialDriver::class.java,EniUsbDrivers.driver(vid,pid))
        for((vid,pids)in Ch34xSerialDriver.getSupportedDevices())for(pid in pids)assertEquals(Ch34xSerialDriver::class.java,EniUsbDrivers.driver(vid,pid))
        assertNull(EniUsbDrivers.driver(0xFFFF,0xFFFF));assertNull(EniUsbDrivers.driver(0,0))
        assertEquals(setOf(FtdiSerialDriver::class.java,Ch34xSerialDriver::class.java),EniUsbDrivers.supported.values.flatMap{it.values}.toSet())
    }
}
