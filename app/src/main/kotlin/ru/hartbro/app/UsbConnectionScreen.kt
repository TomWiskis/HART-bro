package ru.hartbro.app

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import ru.hartbro.app.session.*
import ru.hartbro.usb.*

@Composable internal fun UsbConnectionScreen(state:SessionState,c:SessionController) {
    var selected by remember { mutableStateOf<UsbTarget?>(null) }
    Text("ЭнИ-406 · USB OTG",style=MaterialTheme.typography.titleLarge)
    Text("Модем питается от телефона. Датчику нужен внешний источник питания токовой петли и HART-нагрузка. Подключение USB само по себе не означает ответ датчика.")
    OutlinedButton(onClick={c.refreshDevices()},modifier=Modifier.testTag("usbRefresh")){Text("Обновить USB-устройства")}
    if(state.usbPermissionPending) { LinearProgressIndicator(Modifier.fillMaxWidth());Text("Ожидание разрешения Android на USB…") }
    if(state.usbDevices.isEmpty())Text("USB-устройства не найдены. Подключите модем кабелем USB OTG.",Modifier.testTag("usbAbsent"))
    state.usbDevices.forEach { device -> Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
        Text(device.name);Text("VID/PID ${device.ids}")
        Text("${device.driver ?: "Драйвер не распознан"} · портов ${device.ports} · ${if(device.permission)"доступ разрешён" else "требуется разрешение Android"}")
        Text(device.descriptors,style=MaterialTheme.typography.bodySmall)
        if(device.driver==null)Text("Автоматическое назначение другого драйвера не выполняется. Дескрипторы сохранены в HEX-журнале.")
        Button(onClick={selected=device},enabled=!state.active && !state.usbPermissionPending && device.ports>0){Text("Настроить и подключить…")}
    } } }
    Text("Для этого выпуска включено распознавание FTDI и WCH CH34x по таблицам USB Serial 3.11.0. Совместимость вашего ЭнИ-406 требует проверки на оборудовании.",style=MaterialTheme.typography.bodySmall)
    selected?.let { target -> UsbSettingsDialog(target,{selected=null}) { settings,port ->
        c.requestUsbConnection(target,settings,port);selected=null
    } }
}
@Composable private fun UsbSettingsDialog(target:UsbTarget,dismiss:()->Unit,apply:(UsbPortSettings,Int)->Unit) {
    val prefs=LocalContext.current.getSharedPreferences("usb-settings",Context.MODE_PRIVATE)
    val prefix="${target.ids}/${target.driver}/"
    fun saved(key:String)=prefs.getString(prefix+key,"").orEmpty()
    var baud by rememberSaveable(target.ids){mutableStateOf(saved("baud"))}
    var bits by rememberSaveable(target.ids){mutableStateOf(saved("bits"))}
    var parity by rememberSaveable(target.ids){mutableStateOf(saved("parity"))}
    var stops by rememberSaveable(target.ids){mutableStateOf(saved("stops"))}
    var port by rememberSaveable(target.ids){mutableStateOf(saved("port").ifEmpty{"0"})}
    var flow by rememberSaveable(target.ids){mutableStateOf(saved("flow"))}
    var rts by rememberSaveable(target.ids){mutableStateOf(saved("rts"))}
    var dtr by rememberSaveable(target.ids){mutableStateOf(saved("dtr"))}
    var source by rememberSaveable(target.ids){mutableStateOf(saved("source"))}
    var confirmed by rememberSaveable(target.ids){mutableStateOf(false)}
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest=dismiss,title={Text("Порт ${target.ids}")},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(6.dp)) {
        Text("Введите настройки из документации или работающей программы для этого модема. Скорость HART-линии не определяет скорость USB UART. Перебора настроек нет.")
        OutlinedTextField(baud,{baud=it},label={Text("Скорость UART, бит/с")},singleLine=true)
        OutlinedTextField(bits,{bits=it},label={Text("Биты данных: 5–8")},singleLine=true)
        OutlinedTextField(parity,{parity=it},label={Text("Чётность: 0 нет, 1 нечётная, 2 чётная")},supportingText={Text("3 — mark, 4 — space")},singleLine=true)
        OutlinedTextField(stops,{stops=it},label={Text("Стоп-биты: 1 / 2 / 3 (=1,5)")},singleLine=true)
        if(target.ports>1)OutlinedTextField(port,{port=it},label={Text("Порт: 0…${target.ports-1}")},singleLine=true)
        Text("Управление потоком")
        FlowRow { SerialFlow.entries.forEach{v->FilterChip(flow==v.name,{flow=v.name},label={Text(v.name)})} }
        Text("RTS: постоянный уровень или переключение на время передачи")
        FlowRow { RtsMode.entries.forEach{v->FilterChip(rts==v.name,{rts=v.name},label={Text(when(v){RtsMode.LOW->"Низкий";RtsMode.HIGH->"Высокий";RtsMode.TRANSMIT_HIGH->"TX высокий";RtsMode.TRANSMIT_LOW->"TX низкий"})})} }
        Text("DTR")
        Row { FilterChip(dtr=="0",{dtr="0"},label={Text("Низкий")});FilterChip(dtr=="1",{dtr="1"},label={Text("Высокий")}) }
        OutlinedTextField(source,{source=it},label={Text("Источник настроек / проверенная программа")})
        Row { Checkbox(confirmed,{confirmed=it});Text("Подтверждаю настройки для выбранного модема") }
        error?.let{Text(it,color=MaterialTheme.colorScheme.error)}
    }},dismissButton={TextButton(onClick=dismiss){Text("Отмена")}},confirmButton={TextButton(enabled=confirmed,onClick={
        try {
            require(dtr in listOf("0","1")) {"Выберите DTR"}
            val settings=UsbPortSettings(baud.toInt(),bits.toInt(),parity.toInt(),stops.toInt(),SerialFlow.valueOf(flow),RtsMode.valueOf(rts),dtr=="1",source.trim())
            val n=port.toInt();require(n in 0 until target.ports){"Порт отсутствует"}
            prefs.edit().apply { mapOf("baud" to baud,"bits" to bits,"parity" to parity,"stops" to stops,"port" to port,"flow" to flow,"rts" to rts,"dtr" to dtr,"source" to source).forEach{(k,v)->putString(prefix+k,v)} }.apply()
            apply(settings,n)
        } catch(e:Exception){error="Проверьте все параметры: ${e.message}"}
    }){Text("Продолжить")}})
}
