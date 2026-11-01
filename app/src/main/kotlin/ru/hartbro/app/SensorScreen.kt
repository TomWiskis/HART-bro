package ru.hartbro.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
    val connected = if(state.rangeOwned) state.rangeTransportOpen else if(state.walkActive) state.walk.bluetoothConnected else state.monitor.bluetoothConnected
    val recovering = if(state.walkActive) state.walk.recovery?.reason == LossReason.BLUETOOTH
        else state.monitor.recovery?.reason == LossReason.BLUETOOTH || state.monitor.phase == MonitorPhase.CONNECTING && !connected
    val name = state.communicator?.name ?: "Не подключён"
    val status = if(connected) "подключён" else if(recovering) "переподключение" else "отключён"
    Row(Modifier.widthIn(max=120.dp).padding(end=8.dp).testTag("bluetoothBadge")
        .semantics(mergeDescendants=true) { contentDescription = "Bluetooth: $name, $status" }, verticalAlignment=Alignment.CenterVertically) {
        val color = if(connected) success else MaterialTheme.colorScheme.error
        Canvas(Modifier.size(18.dp)) {
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
@Composable internal fun SensorHeading(state: SessionState) {
    val m=state.monitor
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(Unit) { while(true) { kotlinx.coroutines.delay(1000); now=Instant.now() } }
    val good=m.hartExchangeCurrent(now)
    Text("${sensorTitle(m.identity)}  ${if(good) "✓" else "✕"}",color=if(good)success else MaterialTheme.colorScheme.error,
        style=MaterialTheme.typography.titleMedium,modifier=Modifier.testTag("sensorStatus"))
    val r=m.recovery
    if(r!=null) {
        Text("Переподключение · ${(r.remainingMillis+999)/1000} с",modifier=Modifier.testTag("recoveryCountdown"))
        Text("Причина: ${when(r.reason){LossReason.BLUETOOTH->"потеря Bluetooth";LossReason.HART->"нет ответа HART";else->"не определена"}}")
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
    if(state.walkActive) { Text("VIATOR занят обходом. Завершите обход для мониторинга."); return }
    val m=state.monitor
    SensorHeading(state)
    Button(onClick=c::openSensorDiagnostics,enabled=m.identity!=null && !state.rangeOwned && m.phase in setOf(MonitorPhase.READY,MonitorPhase.MONITORING,MonitorPhase.PAUSED),modifier=Modifier.testTag("openDiagnostics")){Text("Открыть диагностику")}
    val batch=m.latest ?: m.lastKnown
    val pv=batch?.values?.find{it.slot=="PV"}
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(Unit){while(true){kotlinx.coroutines.delay(1000);now=Instant.now()}}
    val fresh=m.bluetoothConnected && m.latest!=null && measurementFresh(batch?.receivedAt,now,m.periodMillis) && m.phase==MonitorPhase.MONITORING
    ReadingCard(primaryParameter(pv?.unitCode),pv?.takeIf{it.plottable}?.let{"${it.value.toString().replace('.',',')} ${it.unit}"} ?: "Нет данных",batch!=null && !fresh,"primaryReading")
    val loopFresh=fresh && measurementFresh(batch?.loopCurrentAt,now,m.periodMillis)
    ReadingCard("Ток петли",batch?.loopCurrent?.takeIf{it.isFinite()}?.let{"${it.toString().replace('.',',')} мА"} ?: "Нет данных",batch?.loopCurrent!=null && !loopFresh,"loopReading")
    Text("Периодичность опроса")
    FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) { listOf(1L,2L,5L,10L).forEach{sec->FilterChip(m.periodMillis==sec*1000,{c.period(sec*1000)},label={Text("$sec с")})} }
    if(m.startedAt!=null) key(m.startedAt){MonitoringChart(c.history,null,m.revision,m.latest,m.configuration.range?.label ?: "Диапазон недоступен",m.periodMillis,state.chartViewport,c::chartViewport)}
    else Text("Начните сессию для записи графика")
}
@Composable internal fun SensorDiagnosticsScreen(state:SessionState,c:SessionController) {
    Text("Диагностика датчика",style=MaterialTheme.typography.titleLarge,modifier=Modifier.testTag("sensorDiagnostics"))
    SensorHeading(state)
    if(state.diagnosticLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
    state.diagnosticError?.let{Text(it,color=MaterialTheme.colorScheme.error)}
    val d=state.sensorDiagnostics
    if(d!=null) {
        val i=d.identity; val config=d.configuration
        Text("Информация об устройстве",style=MaterialTheme.typography.titleMedium)
        Text("Производитель: ${i.manufacturerDescription}")
        Text("Модель: ${PassportProfiles().find(i)?.model(i) ?: "не определена"}")
        Text("Device Type: 0x%04X".format(i.deviceType),modifier=Modifier.testTag("deviceType"))
        Text("Идентификатор: ${i.uniqueKey}\nHART Revision: ${i.hartRevision}\nВерсия ПО: ${i.softwareRevision}\nРевизия устройства: ${i.deviceRevision}")
        Text("Параметры измерения",style=MaterialTheme.typography.titleMedium)
        d.measurements?.let{b->
            b.values.filter{it.quality!=Quality.ABSENT}.forEach{v->Text("${v.slot}: ${if(v.value.isFinite()) "${v.value} ${v.unit}" else "Нет данных"} · ${qualityText(v.quality)}")}
            Text("Ток петли: ${b.loopCurrent?.let{"$it мА"} ?: "Нет данных"}")
            Text("Состояние: ${HartStatus.deviceDescription(b.deviceStatus)}")
        } ?: Text("Измерения недоступны")
        Text("Конфигурация",style=MaterialTheme.typography.titleMedium)
        Text("TAG: ${config.tag ?: d.range?.tag ?: "не получен"}")
        Text("Защита записи: ${when(config.parameters["writeProtect"]?.value){"0"->"отключена";"1"->"включена";else->"неизвестна"}}")
        Text("Диапазон: ${config.range?.label ?: "недоступен"}")
        d.range?.sensor?.let { sensor ->
            if(sensor.lowerLimit!=null && sensor.upperLimit!=null) Text("Пределы сенсора: ${ConfiguredRange(sensor.lowerLimit!!,sensor.upperLimit!!,sensor.unitCode).label}")
            sensor.minimumSpan?.let{Text("Минимальная ширина: $it ${HartUnits.find(sensor.unitCode)?.symbol ?: sensor.unitCode}")}
        }
        Button(onClick={c.openRange()},enabled=!state.diagnosticLoading && !state.rangeOwned && config.range!=null && config.parameters["writeProtect"]?.value!="1",modifier=Modifier.testTag("openRange")){Text("Настроить диапазон")}
        Text("Остальные параметры доступны для чтения")
        config.parameters.filterKeys{it in setOf("description","damping","transferFunction","alarm")}.forEach{(k,v)->Text("${configurationNames[k]}: ${v.value}")}
        Text("Состояние и ошибки",style=MaterialTheme.typography.titleMedium)
        config.responses.mapNotNull{it.error}.forEach{Text(it)}
        config.responses.mapNotNull{it.deviceStatus}.distinct().forEach{Text(HartStatus.deviceDescription(it))}
        d.errors.forEach{Text(it,color=MaterialTheme.colorScheme.error)}
    }
    OutlinedButton(onClick=c::openSensorDiagnostics,enabled=!state.diagnosticLoading){Text("Обновить диагностику")}
}
