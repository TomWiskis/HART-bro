package ru.hartbro.usb

import android.app.PendingIntent
import android.content.*
import android.hardware.usb.*
import android.os.Build
import java.io.IOException
import kotlinx.coroutines.*
import com.hoho.android.usbserial.driver.*
import com.hoho.android.usbserial.util.SerialInputOutputManager

/** Inventory comes only from UsbManager. Recognition does not certify an ЭнИ revision. */
data class UsbTarget(val path:String,val vid:Int,val pid:Int,val name:String,val driver:String?,val ports:Int,
    val descriptors:String,val permission:Boolean) {
    val ids get() = "%04X:%04X".format(vid,pid)
}
class UsbHostController(private val context:Context,private val diagnostic:(String)->Unit,
    private val changed:(List<UsbTarget>)->Unit) {
    private val manager=context.getSystemService(UsbManager::class.java)
    private val action="${context.packageName}.USB_PERMISSION"
    private val prober=EniUsbDrivers.prober()
    private val lock=Any()
    private val permissionGate=UsbPermissionGate()
    private var active:Pair<String,UsbHartTransport>?=null
    private var opening=false
    private var openedBefore=false
    private var selected:UsbTarget?=null
    private var serial:String?=null
    private var portNumber=0
    private var settings:UsbPortSettings?=null
    private val receiver=object:BroadcastReceiver() {
        override fun onReceive(c:Context,intent:Intent) {
            val device = if(Build.VERSION.SDK_INT>=33)intent.getParcelableExtra(UsbManager.EXTRA_DEVICE,UsbDevice::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
            synchronized(lock) {
                when(intent.action) {
                    action -> {
                        if(device!=null) {
                            val present=manager?.deviceList?.get(device.deviceName)!=null
                            val granted=present && manager!!.hasPermission(device)
                            if(permissionGate.resolve(device.deviceName,intent.getLongExtra("sequence",-1),present,granted))
                                diagnostic("USB PERMISSION ${if(granted)"GRANTED" else "DENIED/DETACHED"}: ${device.deviceName}")
                        }
                    }
                    UsbManager.ACTION_USB_DEVICE_DETACHED -> if(device!=null) {
                        permissionGate.detached(device.deviceName)
                        diagnostic("USB DETACH: ${device.deviceName}")
                        if(active?.first==device.deviceName)active?.second?.detached()
                    }
                    UsbManager.ACTION_USB_DEVICE_ATTACHED -> diagnostic("USB ATTACH: ${device?.deviceName}")
                }
            }
            refresh()
        }
    }
    init {
        val filter=IntentFilter(action).apply { addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);addAction(UsbManager.ACTION_USB_DEVICE_DETACHED) }
        if(Build.VERSION.SDK_INT>=33)context.registerReceiver(receiver,filter,Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") context.registerReceiver(receiver,filter)
    }
    fun inventory():List<UsbTarget> = manager?.deviceList?.values.orEmpty().map { device ->
        val driver=runCatching{prober.probeDevice(device)}.getOrNull()
        UsbTarget(device.deviceName,device.vendorId,device.productId,device.productName ?: "USB-устройство",driver?.javaClass?.simpleName,driver?.ports?.size ?: 0,
            buildString {
                append("device=${device.deviceName}; VID/PID=%04X:%04X; class=%d/%d/%d".format(device.vendorId,device.productId,device.deviceClass,device.deviceSubclass,device.deviceProtocol))
                for(i in 0 until device.interfaceCount) {
                    val inf=device.getInterface(i);append("\ninterface=${inf.id}, alt=${inf.alternateSetting}, class=${inf.interfaceClass}/${inf.interfaceSubclass}/${inf.interfaceProtocol}")
                    for(j in 0 until inf.endpointCount) { val ep=inf.getEndpoint(j);append("; endpoint=0x%02X direction=%d type=%d packet=%d".format(ep.address,ep.direction,ep.type,ep.maxPacketSize)) }
                }
            },manager!!.hasPermission(device))
    }.sortedBy{it.path}
    private var lastInventory=""
    fun refresh() {
        val list=inventory();val summary=list.joinToString("\n"){"${it.descriptors}; driver=${it.driver ?: "не распознан"}; permission=${it.permission}"}
        synchronized(lock) {
            if(summary!=lastInventory) { diagnostic("USB INVENTORY: ${if(summary.isBlank())"устройства не найдены" else summary}");lastInventory=summary }
            active?.let { if(list.none{device->device.path==it.first})it.second.detached() }
        }
        changed(list)
    }
    fun select(target:UsbTarget,port:Int,configuration:UsbPortSettings) = synchronized(lock) {
        check(active==null && !opening) { "USB уже занят" }
        require(port in 0 until target.ports) { "Выбранного USB-порта нет" }
        selected=target;serial=null;openedBefore=false;portNumber=port;settings=configuration;permissionGate.resetSelection()
        diagnostic("USB SELECT: ${target.ids}; port=$port; ${configuration.label}")
    }
    /** A reconnect never picks another VID/PID, a different known serial, or an ambiguous device. */
    suspend fun open():UsbHartTransport {
        val selection:UsbTarget;val config:UsbPortSettings
        synchronized(lock) {
            check(active==null && !opening) { "USB-порт занят" };opening=true
            selection=selected ?: run {opening=false;throw IOException("USB не выбран")}
            config=settings ?: run {opening=false;throw IOException("Настройки USB не подтверждены")}
        }
        var native:UsbDeviceConnection?=null;var port:UsbSerialPort?=null
        try {
            val candidates=manager?.deviceList?.values.orEmpty().filter { it.vendorId==selection.vid && it.productId==selection.pid }
                .let { devices -> if(devices.size<=1 || serial==null)devices else devices.filter {
                    manager!!.hasPermission(it) && runCatching{it.serialNumber}.getOrNull()==serial
                } }
            val device=(if(!openedBefore)candidates.firstOrNull{it.deviceName==selection.path} else candidates.singleOrNull()) ?: throw IOException(if(candidates.isEmpty())"Выбранный USB-модем не найден" else "Несколько одинаковых USB-модемов: отключите лишний и выберите нужный")
            ensurePermission(device)
            if(manager!!.deviceList[device.deviceName]==null)throw IOException("USB отключён перед открытием")
            val actualSerial=runCatching{device.serialNumber}.getOrNull()
            if(serial!=null && serial!=actualSerial)throw IOException("Подключён другой USB-модем")
            if(serial==null)serial=actualSerial
            if(serial==null)diagnostic("USB SERIAL недоступен: один кандидат VID/PID; HART ID проверяет движок при восстановлении")
            val driver=prober.probeDevice(device) ?: throw IOException("USB-контроллер не распознан: автоматическое назначение драйвера запрещено")
            port=driver.ports.getOrNull(portNumber) ?: throw IOException("USB-порт $portNumber отсутствует")
            native=manager.openDevice(device) ?: throw IOException("Не удалось открыть USB: нет доступа или устройство занято")
            port.open(native)
            port.setParameters(config.baud,config.dataBits,config.stopBits,config.parity)
            val flow=if(config.flow==SerialFlow.NONE)UsbSerialPort.FlowControl.NONE else UsbSerialPort.FlowControl.RTS_CTS
            require(flow in port.supportedFlowControl) { "Драйвер не поддерживает $flow" }
            port.setFlowControl(flow);port.dtr=config.dtr
            if(config.flow==SerialFlow.NONE)port.rts=config.rts in setOf(RtsMode.HIGH,RtsMode.TRANSMIT_LOW)
            currentCoroutineContext().ensureActive()
            val io=NativePort(port,native)
            val transport=UsbHartTransport(io,config,diagnostic) { synchronized(lock){active=null} }
            synchronized(lock) {
                if(manager.deviceList[device.deviceName]==null || !transport.isOpen) { transport.close();throw IOException("USB отключён во время открытия") }
                active=device.deviceName to transport;openedBefore=true
            }
            native=null;port=null
            diagnostic("USB OPEN: ${device.deviceName}; VID/PID=${selection.ids}; driver=${driver.javaClass.simpleName}; port=$portNumber; ${config.label}")
            return transport
        } catch(e:Exception) {
            runCatching{port?.close()};runCatching{native?.close()}
            diagnostic("USB OPEN ERROR: ${e.javaClass.simpleName}: ${e.message}");throw e
        } finally { synchronized(lock){opening=false};refresh() }
    }
    suspend fun authorizeSelection() {
        val target=synchronized(lock){selected} ?: throw IOException("USB не выбран")
        val device=manager?.deviceList?.get(target.path) ?: throw IOException("Выбранный USB отключён")
        ensurePermission(device)
    }
    private suspend fun ensurePermission(device:UsbDevice) {
        if(manager!!.hasPermission(device))return
        val ticket=permissionGate.begin(device.deviceName)
        try {
            val intent=Intent(action).setPackage(context.packageName).putExtra("sequence",ticket.sequence)
                .setData(android.net.Uri.parse("hartbro-usb://permission/${ticket.sequence}"))
            val flags=PendingIntent.FLAG_UPDATE_CURRENT or if(Build.VERSION.SDK_INT>=31)PendingIntent.FLAG_MUTABLE else 0
            manager.requestPermission(device,PendingIntent.getBroadcast(context,17,intent,flags))
            diagnostic("USB PERMISSION REQUEST: ${device.deviceName}")
            withTimeout(15_000){ticket.result.await()}
        } finally { permissionGate.finish(ticket) }
    }
    private class NativePort(private val port:UsbSerialPort,private val connection:UsbDeviceConnection):SerialPortIo {
        private var reader:SerialInputOutputManager?=null
        override fun start(received:(ByteArray)->Unit,failed:(Exception)->Unit) {
            reader=SerialInputOutputManager(port,object:SerialInputOutputManager.Listener {
                override fun onNewData(data:ByteArray)=received(data)
                override fun onRunError(e:Exception)=failed(e)
            }).apply{start()}
        }
        override fun write(bytes:ByteArray)=port.write(bytes,2000)
        override fun rts(value:Boolean){port.rts=value}
        override fun close(){reader?.stop();runCatching{port.close()};connection.close()}
    }
}
