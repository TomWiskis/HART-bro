package ru.hartbro.usb

import com.hoho.android.usbserial.driver.*

/** Only upstream IDs, never a guessed ЭнИ product ID or a wildcard controller override. */
object EniUsbDrivers {
    val supported:Map<Int,Map<Int,Class<out UsbSerialDriver>>> = buildMap {
        fun include(products:Map<Int,IntArray>,type:Class<out UsbSerialDriver>) {
            products.forEach{(vid,pids)->put(vid,get(vid).orEmpty()+pids.associateWith{type})}
        }
        include(FtdiSerialDriver.getSupportedDevices(),FtdiSerialDriver::class.java)
        include(Ch34xSerialDriver.getSupportedDevices(),Ch34xSerialDriver::class.java)
    }
    fun driver(vid:Int,pid:Int)=supported[vid]?.get(pid)
    fun prober()=UsbSerialProber(ProbeTable().apply {
        supported.forEach{(vid,products)->products.forEach{(pid,type)->addProduct(vid,pid,type)}}
    })
}
