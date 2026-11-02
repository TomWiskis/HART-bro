package ru.hartbro.usb
import org.junit.Test
import org.junit.Assert.*
import ru.hartbro.core.*
class UsbReadQualificationTest {
    private fun device(id:Int=1)=DeviceIdentity.parse(byteArrayOf(0xFE.toByte(),0x26,0x62,5,5,7,0x28,8,0,0,0,id.toByte()))
    private fun UsbReadQualification.ok(command:Int)=observe(HartTransactionMetric(command,1,20,"OK"))
    @Test fun usbWriteRequiresIdentityConfigurationAndStableMeasurementsOnCurrentOpenPort() {
        val gate=UsbReadQualification();gate.identified(device());gate.ok(0);gate.ok(13);gate.ok(15)
        assertThrows(IllegalStateException::class.java){gate.requireWriteAllowed(true)}
        repeat(3){gate.ok(1)};gate.requireWriteAllowed(true)
        assertThrows(IllegalStateException::class.java){gate.requireWriteAllowed(false)}
        gate.identified(device(2));assertThrows(IllegalStateException::class.java){gate.requireWriteAllowed(true)}
    }
    @Test fun timeoutRequiresThreeFreshMeasurementsButUnsupportedOptionalReadDoesNotInvalidateGoodConfig() {
        val gate=UsbReadQualification();gate.identified(device());listOf(0,13,15,1,1,1).forEach{gate.ok(it)}
        gate.observe(HartTransactionMetric(48,1,20,"RESPONSE_64"));gate.requireWriteAllowed(true)
        gate.observe(HartTransactionMetric(1,1,1500,"TIMEOUT"));repeat(2){gate.ok(1)}
        assertThrows(IllegalStateException::class.java){gate.requireWriteAllowed(true)}
        gate.ok(1);gate.requireWriteAllowed(true)
    }
}
