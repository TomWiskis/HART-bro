package ru.hartbro.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ru.hartbro.app.session.*
import ru.hartbro.core.*
import java.time.Instant

private val success = Color(0xFF168047)
@Composable internal fun BluetoothBadge(state: SessionState) {
    val connected = if(state.rangeOwned) state.rangeTransportOpen else if(state.walkActive) state.walk.transportConnected else state.monitor.transportConnected
    val recovering = if(state.rangeOwned) state.range.phase == RangePhase.RECOVERING && !connected else if(state.walkActive) state.walk.recovery?.reason in setOf(LossReason.BLUETOOTH,LossReason.USB)
        else state.monitor.recovery?.reason in setOf(LossReason.BLUETOOTH,LossReason.USB) || state.monitor.phase == MonitorPhase.CONNECTING && !connected
    val usb=state.communicator?.kind==TransportKind.USB
    val name = state.communicator?.name ?: "Не подключён"
    val status = if(connected) "подключён" else if(recovering) "переподключение" else "отключён"
    Row(Modifier.widthIn(max=120.dp).padding(end=8.dp).testTag("bluetoothBadge")
        .semantics(mergeDescendants=true) { contentDescription = "${if(usb)"USB" else "Bluetooth"}: $name, $status" }, verticalAlignment=Alignment.CenterVertically) {
        val color = if(connected) success else MaterialTheme.colorScheme.error
        if(usb)Text("USB",color=if(connected)success else MaterialTheme.colorScheme.error,style=MaterialTheme.typography.labelSmall)
        else Canvas(Modifier.size(18.dp)) {
            val points=listOf(0.5f to 0f, 0.5f to 1f, 0.9f to 0.7f, 0.15f to 0.2f, 0.15f to 0.8f, 0.9f to 0.3f, 0.5f to 0f)
            listOf(0 to 1,1 to 2,2 to 3,4 to 5,5 to 6).forEach { (a,b) ->
                drawLine(color, Offset(points[a].first*size.width,points[a].second*size.height), Offset(points[b].first*size.width,points[b].second*size.height),2.dp.toPx())
            }
        }
        Text(name, maxLines=1, overflow=TextOverflow.Ellipsis, style=MaterialTheme.typography.labelMedium, modifier=Modifier.weight(1f))
        if(recovering && !connected) CircularProgressIndicator(Modifier.size(14.dp),strokeWidth=2.dp)
        else Text(if(connected) "✓" else "✕",color=color)
    }
}
@Composable internal fun SensorHeading(state: SessionState,showType:Boolean=true) {
    val m=state.monitor
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(Unit) { while(true) { kotlinx.coroutines.delay(1000); now=Instant.now() } }
    val good=m.hartExchangeCurrent(maxOf(now,Instant.now()))
    val title=if(showType)sensorTitle(m.identity) else m.identity?.let{listOfNotNull(it.manufacturerName,HartDeviceCatalog.model(it)).joinToString(" · ").ifBlank{"HART-датчик"}} ?: "HART-датчик"
    Text("$title  ${if(good) "✓" else "✕"}",color=if(good)success else MaterialTheme.colorScheme.error,
        style=MaterialTheme.typography.titleMedium,modifier=Modifier.testTag("sensorStatus"))
    val r=m.recovery
    if(r!=null) {
        Text("Переподключение · ${(r.remainingMillis+999)/1000} с",modifier=Modifier.testTag("recoveryCountdown"))
        Text("Причина: ${when(r.reason){LossReason.BLUETOOTH->"потеря Bluetooth";LossReason.HART->"нет ответа HART";else->"не определена"}}")
        Text(r.lastAttempt,style=MaterialTheme.typography.bodySmall)
    } else if(!good) { Text(when(m.phase){MonitorPhase.PAUSED->"Опрос приостановлен";MonitorPhase.DECISION->"Связь не восстановлена";else->m.message.takeUnless{it=="Ответ HART получен"} ?: "Нет актуального ответа HART"}) }
}
@Composable private fun ReadingCard(label:String,value:String,stale:Boolean,tag:String) {
    Card(Modifier.fillMaxWidth().testTag(tag)) { Column(Modifier.padding(12.dp)) {
        Text(label,style=MaterialTheme.typography.labelLarge)
        Text(value,style=MaterialTheme.typography.headlineMedium)
        if(stale) Text("Неактуальное показание",color=MaterialTheme.colorScheme.error)
    } }
}
@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun MonitorScreen(state:SessionState,c:SessionController) {
    if(state.walkActive) { Text("Коммуникатор занят обходом. Завершите обход для мониторинга."); return }
    val m=state.monitor
    if(m.startedAt==null) { QuickSensorCard(state,c); return }
    SensorHeading(state)
    Button(onClick=c::openSensorDiagnostics,enabled=m.identity!=null && !state.rangeOwned && m.phase in setOf(MonitorPhase.READY,MonitorPhase.MONITORING,MonitorPhase.PAUSED),modifier=Modifier.testTag("openDiagnostics")){Text("Открыть диагностику")}
    val batch=m.latest ?: m.lastKnown
    val pv=batch?.values?.find{it.slot=="PV"}
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(Unit){while(true){kotlinx.coroutines.delay(1000);now=Instant.now()}}
    // A new reply can arrive between timer ticks. Always age it against the current render time.
    val currentTime=maxOf(now,Instant.now())
    val fresh=m.transportConnected && m.latest!=null && measurementFresh(batch?.receivedAt,currentTime,m.periodMillis) && m.phase==MonitorPhase.MONITORING
    ReadingCard(primaryParameter(pv?.unitCode),pv?.takeIf{it.plottable}?.let{"${it.value.toString().replace('.',',')} ${it.unit}"} ?: "Нет данных",batch!=null && !fresh,"primaryReading")
    val loopFresh=fresh && measurementFresh(batch?.loopCurrentAt,currentTime,m.periodMillis)
    ReadingCard("Ток петли",batch?.loopCurrent?.takeIf{it.isFinite()}?.let{"${it.toString().replace('.',',')} мА"} ?: "Нет данных",batch?.loopCurrent!=null && !loopFresh,"loopReading")
    Text("Периодичность опроса")
    FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) { listOf(1L,2L,5L,10L).forEach{sec->FilterChip(m.periodMillis==sec*1000,{c.period(sec*1000)},label={Text("$sec с")})} }
    if(m.startedAt!=null) key(m.startedAt){MonitoringChart(c.history,null,m.revision,m.latest,m.configuration.range?.label ?: "Диапазон недоступен",m.periodMillis,state.chartViewport,c::chartViewport)}
    else Text("Начните сессию для записи графика")
}
@Composable private fun QuickSensorCard(state:SessionState,c:SessionController) {
    val m=state.monitor
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(Unit) { while(true) { kotlinx.coroutines.delay(1000); now=Instant.now() } }
    val good=m.hartExchangeCurrent(maxOf(now,Instant.now()))
    val ready=m.identity!=null && m.phase==MonitorPhase.READY && !state.rangeOwned && !state.editBusy
    val config=m.configuration
    Card(Modifier.fillMaxWidth().testTag("quickSensorCard")) {
        Column(Modifier.padding(10.dp),horizontalAlignment=Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                Column(Modifier.weight(1f).clickable(enabled=ready,onClick=c::openSensorDiagnostics).testTag("quickSensorInfo")) {
                    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                        Canvas(Modifier.size(9.dp).semantics { contentDescription=if(good) "Датчик отвечает" else "Датчик не отвечает" }) { drawCircle(if(good)success else Color(0xFFBA1A1A)) }
                        Text(sensorTitle(m.identity)+(config.tag?.let{" ($it)"} ?: ""),style=MaterialTheme.typography.titleSmall,modifier=Modifier.testTag("sensorStatus"))
                    }
                    val numbers=listOfNotNull(m.identity?.deviceId?.toString(),config.productionDate?.let(HartDate::format))
                    if(numbers.isNotEmpty())Text(numbers.joinToString("   "),style=MaterialTheme.typography.labelSmall,modifier=Modifier.padding(start=15.dp).testTag("quickSensorNumbers"))
                }
                IconButton(onClick={c.openRange()},enabled=ready && config.range!=null,modifier=Modifier.size(32.dp).testTag("quickRangeEdit")) { Text("✎",style=MaterialTheme.typography.titleLarge) }
                config.range?.let{Text(it.label,style=MaterialTheme.typography.bodySmall)}
            }
            TextButton(onClick={c.openSensorEdit(SensorField.ZERO)},enabled=ready && good,modifier=Modifier.testTag("setSensorZero")){Text("Установить ноль")}
            Button(onClick=c::beginSession,enabled=ready && good,modifier=Modifier.testTag("startSession")){Text("Начать измерительную сессию")}
        }
    }
    if(!good) {
        val recovery=m.recovery
        Text(if(recovery!=null) "Переподключение · ${(recovery.remainingMillis+999)/1000} с" else m.message,
            color=MaterialTheme.colorScheme.error,modifier=Modifier.testTag(if(recovery!=null)"recoveryCountdown" else "quickSensorError"))
        recovery?.lastAttempt?.let{Text(it,style=MaterialTheme.typography.bodySmall)}
    }
    Button(onClick=c::openSensorDiagnostics,enabled=ready,modifier=Modifier.testTag("openDiagnostics")){Text("Открыть диагностику")}
}
@Composable internal fun SensorDiagnosticsScreen(state:SessionState,c:SessionController) {
    Text("Диагностика датчика",style=MaterialTheme.typography.titleLarge,modifier=Modifier.testTag("sensorDiagnostics"))
    SensorHeading(state,showType=false)
    if(state.diagnosticLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
    state.diagnosticError?.let{Text(it,color=MaterialTheme.colorScheme.error)}
    state.notice?.let{Text(it,style=MaterialTheme.typography.bodySmall)}
    val d=state.sensorDiagnostics
    if(d!=null) {
        val i=d.identity; val config=d.configuration
        Text("Информация об устройстве",style=MaterialTheme.typography.titleMedium)
        Text("Производитель: ${i.manufacturerDescription}")
        HartDeviceCatalog.model(i)?.let{Text("Модель: $it")}
        Text("Device Type: 0x%04X".format(i.deviceType),modifier=Modifier.testTag("deviceType"))
        Text("Серийный номер: ${i.deviceId}\nHART Revision: ${i.hartRevision}\nВерсия ПО: ${i.softwareRevision}\nРевизия устройства: ${i.deviceRevision}")
        Text("Параметры измерения",style=MaterialTheme.typography.titleMedium)
        d.measurements?.let{b->
            b.values.filter{it.quality!=Quality.ABSENT}.forEach{v->Text("${v.slot}: ${if(v.value.isFinite()) "${v.value} ${v.unit}" else "Нет данных"} · ${qualityText(v.quality)}")}
            Text("Ток петли: ${b.loopCurrent?.let{"$it мА"} ?: "Нет данных"}")
            Text("Состояние: ${HartStatus.deviceDescription(b.deviceStatus)}")
        } ?: Text("Измерения недоступны")
        Text("Конфигурация",style=MaterialTheme.typography.titleMedium)
        EditableSensorRow("TAG: ${config.parameters["tag"]?.value ?: "не получен"}",!state.diagnosticLoading && !state.editBusy && config.parameters.containsKey("tag"),"editShortTag"){c.openSensorEdit(SensorField.TAG)}
        if(i.hartRevision>=6 && config.parameters.containsKey("longTag"))EditableSensorRow("Длинный TAG: ${config.parameters["longTag"]?.value.orEmpty()}",!state.diagnosticLoading && !state.editBusy,"editLongTag"){c.openSensorEdit(SensorField.LONG_TAG)}
        EditableSensorRow("Дата производства: ${config.productionDate?.let(HartDate::format) ?: "не заполнена"}",!state.diagnosticLoading && !state.editBusy && config.parameters.containsKey("deviceDateRaw"),"editProductionDate"){c.openSensorEdit(SensorField.DATE)}
        Text("Дата хранится в редактируемом поле HART Date; её можно заполнить по шильду.",style=MaterialTheme.typography.bodySmall)
        Text("Защита записи: ${when(config.parameters["writeProtect"]?.value){"0"->"отключена";"1"->"включена";else->"неизвестна"}}")
        Text("Диапазон: ${config.range?.label ?: "недоступен"}")
        d.range?.sensor?.let { sensor ->
            if(sensor.lowerLimit!=null && sensor.upperLimit!=null) Text("Пределы сенсора: ${ConfiguredRange(sensor.lowerLimit!!,sensor.upperLimit!!,sensor.unitCode).label}")
            sensor.minimumSpan?.let{Text("Минимальная ширина: $it ${HartUnits.find(sensor.unitCode)?.symbol ?: sensor.unitCode}")}
        }
        Button(onClick={c.openRange()},enabled=!state.diagnosticLoading && !state.rangeOwned && config.range!=null && config.parameters["writeProtect"]?.value!="1",modifier=Modifier.testTag("openRange")){Text("Настроить диапазон")}
        config.parameters.filterKeys{it in setOf("description","damping","transferFunction","alarm")}.forEach{(k,v)->Text("${configurationNames[k]}: ${v.value}")}
        Text("Состояние и ошибки",style=MaterialTheme.typography.titleMedium)
        config.responses.mapNotNull{it.error}.forEach{Text(it)}
        config.responses.mapNotNull{it.deviceStatus}.distinct().forEach{Text(HartStatus.deviceDescription(it))}
        d.errors.forEach{Text(it,color=MaterialTheme.colorScheme.error)}
    }
    OutlinedButton(onClick=c::openSensorDiagnostics,enabled=!state.diagnosticLoading){Text("Обновить диагностику")}
}

@Composable private fun EditableSensorRow(label:String,enabled:Boolean,tag:String,onClick:()->Unit) {
    Row(Modifier.fillMaxWidth().clickable(enabled=enabled,onClick=onClick).padding(vertical=8.dp).testTag(tag),verticalAlignment=Alignment.CenterVertically) {
        Text(label,Modifier.weight(1f));if(enabled)Text("✎")
    }
}
@Composable internal fun SensorEditDialog(state:SessionState,c:SessionController) {
    val edit=state.sensorEdit ?: return
    var value by rememberSaveable(edit.identity.uniqueKey,edit.field,edit.original) { mutableStateOf(edit.requested) }
    ActionDialog(if(state.editConfirm)"Подтвердить: ${edit.field.label}" else edit.field.label,c::closeSensorEdit,"sensorEditDialog",content={
        Text("${sensorTitle(edit.identity)} · ${edit.identity.deviceId}")
        if(edit.field==SensorField.ZERO) {
            Text("Подайте нулевое избыточное / дифференциальное давление. Для абсолютного датчика требуется эталонный вакуум. Изолируйте влияние на автоматику перед настройкой.")
            Text("После одной команды установки нуля приложение проверит PV тремя чтениями. Допуск проверки — 0,01 % ширины пределов сенсора.")
        } else if(state.editConfirm)Text("${edit.original.ifBlank{"не заполнено"}} → ${edit.requested}")
        else OutlinedTextField(value,{value=it},singleLine=true,label={Text(if(edit.field==SensorField.DATE)"ДД.ММ.ГГГГ · по шильду" else edit.field.label)},modifier=Modifier.fillMaxWidth().testTag("sensorEditValue"))
        state.editError?.let{Text(it,color=MaterialTheme.colorScheme.error,modifier=Modifier.testTag("sensorEditError"))}
        if(state.editBusy)LinearProgressIndicator(Modifier.fillMaxWidth())
    },actions={
        Button(onClick={if(state.editConfirm)c.confirmSensorEdit() else c.prepareSensorEdit(value)},enabled=!state.editBusy,modifier=Modifier.fillMaxWidth().testTag("sensorEditApply")) { Text(if(state.editConfirm)"Да, записать и проверить" else "Продолжить") }
        TextButton(onClick=c::closeSensorEdit,enabled=!state.editBusy,modifier=Modifier.fillMaxWidth()){Text("Отмена")}
    })
}
