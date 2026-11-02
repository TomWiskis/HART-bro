package ru.hartbro.usb

import ru.hartbro.core.*

/** Extra trial-USB precondition, independent of existing write confirmation/readback rules. */
class UsbReadQualification {
    private var identity:String?=null
    private val configuration=mutableSetOf<Int>()
    private var measurements=0
    fun identified(device:DeviceIdentity) {
        val key="${passportKey(device)}/${device.hartRevision}/${device.deviceRevision}/${device.softwareRevision}/${device.hardwareRevision}"
        if(identity!=null && identity!=key){configuration.clear();configuration+=0;measurements=0}
        identity=key
    }
    fun observe(metric:HartTransactionMetric) {
        if(metric.outcome=="OK") {
            if(metric.command in setOf(0,13,15))configuration+=metric.command
            if(metric.command in setOf(1,3,9))measurements++
        } else if(metric.outcome in setOf("TIMEOUT","IO_ERROR"))measurements=0
    }
    fun requireWriteAllowed(open:Boolean) {
        check(open && identity!=null && measurements>=3 && configuration.containsAll(setOf(0,13,15))) {
            "Запись USB пока недоступна: сначала получите конфигурацию и три успешных измерительных ответа в сессии. Проверьте стабильность чтения на подготовленном датчике."
        }
    }
}
