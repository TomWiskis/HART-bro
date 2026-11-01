package ru.hartbro.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.*
import androidx.compose.foundation.clickable
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import ru.hartbro.app.session.*
import ru.hartbro.core.*

private fun rangeUnit(code:Int)=when(code){11->"Па";12->"кПа";237->"МПа";else->HartUnits.find(code)?.symbol ?: "HART $code"}

@Composable
internal fun RangeScreen(state:SessionState,c:SessionController,export:(String)->Unit) {
    val r=state.range
    Text(if(state.rangeHistoryOnly) "История изменений" else "Настройка диапазона",style=MaterialTheme.typography.titleLarge)
    if(!state.rangeHistoryOnly) {
    Text(r.message,modifier=Modifier.testTag("rangeStatus"))
    if(r.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    if(r.phase==RangePhase.RECOVERING)r.secondsLeft?.let{Text("Переподключение · $it с")}
    if(r.phase in setOf(RangePhase.READING,RangePhase.PRECHECK))OutlinedButton(onClick=c::closeRange){Text("Отменить подготовку")}
    state.notice?.let{Text(it,color=MaterialTheme.colorScheme.primary)}
    r.snapshot?.let { snapshot ->
        val d=snapshot.identity
        Text("${d.manufacturerDescription}\nМодель: ${PassportProfiles().find(d)?.model(d) ?: "не определена"}\nDevice Type 0x%04X · Device ID %06X · HART ${d.hartRevision}".format(d.deviceType,d.deviceId))
        Text("TAG: ${snapshot.tag ?: "Не указан"}")
        Text("Текущий диапазон: ${snapshot.range.label}",modifier=Modifier.testTag("rangeCurrent"))
        val sensor=snapshot.sensor
        if(sensor?.lowerLimit!=null && sensor.upperLimit!=null) Text("Пределы сенсора: ${ConfiguredRange(sensor.lowerLimit!!,sensor.upperLimit!!,sensor.unitCode).label}")
        else Text("Пределы сенсора не получены. Проверка физических ограничений недоступна.")
        Text("Минимальный диапазон: ${sensor?.minimumSpan?.takeIf{it>0}?.let{"${it.toString().replace('.',',')} ${rangeUnit(sensor.unitCode)}"} ?: "не получен"}")
        Text("Защита записи: ${when(snapshot.writeProtected){true->"включена";false->"отключена";null->"неизвестна"}}")
        if(r.phase==RangePhase.FORM) {
            var lower by rememberSaveable(r.token,stateSaver=TextFieldValue.Saver){mutableStateOf(TextFieldValue(snapshot.range.lower.toString()))}
            var upper by rememberSaveable(r.token,stateSaver=TextFieldValue.Saver){mutableStateOf(TextFieldValue(snapshot.range.upper.toString()))}
            var unit by rememberSaveable(r.token){mutableIntStateOf(snapshot.range.unitCode)}
            var unitsOpen by remember{mutableStateOf(false)}
            var lowerInitial by rememberSaveable(r.token){mutableStateOf(true)}
            var upperInitial by rememberSaveable(r.token){mutableStateOf(true)}
            var lowerFocused by remember{mutableStateOf(false)}
            var upperFocused by remember{mutableStateOf(false)}
            // Apply selection after the tap's internal cursor update, so the first key replaces the number.
            LaunchedEffect(lowerFocused){if(lowerFocused && lowerInitial){lowerInitial=false;lower=lower.copy(selection=TextRange(0,lower.text.length))}}
            LaunchedEffect(upperFocused){if(upperFocused && upperInitial){upperInitial=false;upper=upper.copy(selection=TextRange(0,upper.text.length))}}
            val validation=RangeRules.validate(snapshot,lower.text,upper.text,unit)
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                OutlinedTextField(lower,{lower=it},label={Text("Низ")},singleLine=true,textStyle=MaterialTheme.typography.bodyMedium,
                    keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),modifier=Modifier.weight(1f).testTag("rangeLower").onFocusChanged{lowerFocused=it.isFocused})
                Text("—")
                OutlinedTextField(upper,{upper=it},label={Text("Верх")},singleLine=true,textStyle=MaterialTheme.typography.bodyMedium,
                    keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),modifier=Modifier.weight(1f).testTag("rangeUpper").onFocusChanged{upperFocused=it.isFocused})
                Box {
                    TextButton(onClick={unitsOpen=true},modifier=Modifier.widthIn(max=90.dp).testTag("rangeUnits")){Text("${rangeUnit(unit)} ▾")}
                    DropdownMenu(expanded=unitsOpen,onDismissRequest={unitsOpen=false}) {
                        (listOf(snapshot.range.unitCode)+(1..249).toList()+253).distinct().forEach { target -> DropdownMenuItem(text={Text(rangeUnit(target))},onClick={
                            if(target!=unit) {
                                runCatching { convertRange(ConfiguredRange(RangeRules.parse(lower.text),RangeRules.parse(upper.text),unit),target) }
                                    .onSuccess{lower=TextFieldValue(it.lower.toString());upper=TextFieldValue(it.upper.toString())}
                                unit=target
                            }
                            unitsOpen=false
                        }) }
                    }
                }
            }
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                TextButton(onClick={val value=if(lower.text.startsWith('-'))lower.text.drop(1) else "-${lower.text}";lower=TextFieldValue(value,TextRange(value.length))}){Text("± Низ")}
                TextButton(onClick={val value=if(upper.text.startsWith('-'))upper.text.drop(1) else "-${upper.text}";upper=TextFieldValue(value,TextRange(value.length))}){Text("± Верх")}
            }
            if(unit !in snapshot.writableUnits)Text("Поддержка записи выбранной единицы этим прибором не подтверждена. Запись недоступна.",style=MaterialTheme.typography.bodySmall)
            validation.errors.forEach{Text(it,color=MaterialTheme.colorScheme.error)}
            validation.warnings.forEach{Text(it,style=MaterialTheme.typography.bodySmall)}
            if(validation.unchanged)Text("Новый диапазон совпадает с текущим. Запись не требуется.")
            Button(onClick={c.selectRange(lower.text,upper.text,unit)},enabled=validation.allowed,modifier=Modifier.testTag("rangeSet")){Text("Установить")}
        }
    }
    if(r.phase==RangePhase.RESULT) {
        r.record?.let { record ->
            Text("До: ${record.original.label}\nЗапрошено: ${record.requested.label}\nОтвет 35: ${record.echoed?.label ?: "не получен / не декодирован"}\nФактически (15): ${record.actual?.label ?: "не подтверждено"}",modifier=Modifier.testTag("rangeResult"))
            record.responseCode?.let{Text("35: $it — ${RangeResponseCodes.description(it)}")}
            record.deviceStatus?.let{Text("Статус: ${HartStatus.deviceDescription(it)}")}
            record.errors.forEach{Text(it,style=MaterialTheme.typography.bodySmall)}
            OutlinedButton(onClick={c.openRange(record.id)},modifier=Modifier.testTag("rangeVerify")){Text("Проверить фактический диапазон чтением")}
            TextButton(onClick={export(record.id)}){Text("Сохранить операцию в TXT")}
        }
        r.otherDevice?.let{Text("Сейчас обнаружен ${it.manufacturerDescription} · ${it.uniqueKey}. Для исходного прибора нужно повторное подключение.")}
        if(state.active)Button(onClick={c.openRange(acceptCurrentDevice=true)},modifier=Modifier.testTag("rangeReload")){Text("Повторно считать текущий прибор")}
    }
    if(r.phase==RangePhase.CLOSED)Text("Настройка открывается из карточки подключённого прибора. История доступна без подключения.")
    }
    HorizontalDivider();Text("Журнал операций настройки",style=MaterialTheme.typography.titleMedium)
    if(state.rangeRecords.isEmpty() && state.sensorRecords.isEmpty())Text("Операций записи пока нет")
    SensorChangeHistory(state,c)
    var details by rememberSaveable{mutableStateOf<String?>(null)}
    var deleteId by remember{mutableStateOf<String?>(null)}
    var clearAll by remember{mutableStateOf(false)}
    state.rangeRecords.forEach { record ->
        val succeeded=record.outcome==RangeOutcome.SUCCEEDED
        val uncertain=record.outcome in setOf(RangeOutcome.PENDING,RangeOutcome.UNKNOWN,RangeOutcome.DIFFERENT_DEVICE,RangeOutcome.APPLIED_CHANGED)
        Card(Modifier.fillMaxWidth().testTag("rangeRecord-${record.id}").clickable{details=if(details==record.id)null else record.id}) {
            Column(Modifier.padding(8.dp)) {
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text(DateTimeFormatter.ofPattern("dd.MM\nHH:mm").withZone(ZoneId.systemDefault()).format(record.time),style=MaterialTheme.typography.labelSmall)
                    Column(Modifier.weight(1f)){rangeChanges(record).forEach{Text(it,style=MaterialTheme.typography.bodySmall)}}
                    Text(if(succeeded) "✓" else "✕",color=if(succeeded)Color(0xFF168047) else MaterialTheme.colorScheme.error,
                        modifier=Modifier.testTag("rangeOutcome-${record.outcome}").semantics{contentDescription=if(succeeded)"Подтверждено" else if(uncertain)"Результат не подтверждён полностью" else "Не выполнено"})
                }
                if(details==record.id) {
                    Text(RangeEngine.resultText(record))
                    Text(record.text(),fontFamily=FontFamily.Monospace,style=MaterialTheme.typography.bodySmall)
                    TextButton(onClick={export(record.id)}){Text("Сохранить TXT")}
                    if(record.outcome in setOf(RangeOutcome.UNKNOWN,RangeOutcome.DIFFERENT_DEVICE))TextButton(onClick={c.openRange(record.id)},enabled=!r.busy){Text("Проверить чтением")}
                    TextButton(onClick={deleteId=record.id},enabled=!state.rangeOwned && !r.busy,modifier=Modifier.testTag("deleteRange-${record.id}")){Text("Удалить запись")}
                }
            }
        }
    }
    if(state.rangeRecords.isNotEmpty() || state.sensorRecords.isNotEmpty())TextButton(onClick={clearAll=true},enabled=!state.rangeOwned && !r.busy && !state.editBusy,modifier=Modifier.testTag("clearRangeHistory")){Text("Удалить всю историю изменений")}
    if(deleteId!=null || clearAll) ActionDialog(if(clearAll) "Удалить всю историю изменений?" else "Удалить запись?",{deleteId=null;clearAll=false},"deleteRangeDialog",content={Text("Удаляются только записи приложения. Конфигурация датчика не меняется.")},actions={
        Button(onClick={c.deleteRangeRecord(if(clearAll)null else deleteId);deleteId=null;clearAll=false},modifier=Modifier.fillMaxWidth().testTag("confirmDeleteRange")){Text("Удалить")}
        TextButton(onClick={deleteId=null;clearAll=false},modifier=Modifier.fillMaxWidth()){Text("Отмена")}
    })
    if(state.rangeRecords.size>=100 || state.sensorRecords.size>=100)TextButton(onClick=c::moreRangeRecords){Text("Показать более ранние операции")}
    if(r.phase==RangePhase.CONFIRM && state.rangeVisible) {
        val snapshot=r.snapshot!!;val requested=r.requested!!
        ActionDialog("Подтвердить изменение диапазона",c::cancelRangeConfirmation,"rangeConfirmDialog",content={
            Text("${PassportProfiles().model(snapshot.identity)}\nTAG: ${snapshot.tag ?: "Не указан"}\nDevice ID: ${snapshot.identity.uniqueKey}")
            Text("Текущий диапазон: ${snapshot.range.label}\nНовый диапазон: ${requested.label}")
            snapshot.sensor?.let{sensor->if(sensor.lowerLimit!=null && sensor.upperLimit!=null)Text("Пределы сенсора: ${ConfiguredRange(sensor.lowerLimit!!,sensor.upperLimit!!,sensor.unitCode).label}")}
            r.validation?.warnings?.forEach{Text(it,style=MaterialTheme.typography.bodySmall)}
            Text("Изменение диапазона может повлиять на выходной ток 4–20 мА и связанную автоматику.")
        },actions={
            Button(onClick={c.confirmRange(r.token)},modifier=Modifier.fillMaxWidth().testTag("rangeConfirmWrite")){Text("Да, записать")}
            TextButton(onClick=c::cancelRangeConfirmation,modifier=Modifier.fillMaxWidth().testTag("rangeConfirmCancel")){Text("Отмена")}
        })
    }
}

@Composable private fun SensorChangeHistory(state:SessionState,c:SessionController) {
    var expanded by remember{mutableStateOf<String?>(null)}
    var deleteId by remember{mutableStateOf<String?>(null)}
    state.sensorRecords.forEach { record ->
        Card(Modifier.fillMaxWidth().clickable{expanded=if(expanded==record.id)null else record.id}.testTag("sensorRecord-${record.id}")) {
            Column(Modifier.padding(8.dp)) {
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text(DateTimeFormatter.ofPattern("dd.MM\nHH:mm").withZone(ZoneId.systemDefault()).format(record.time),style=MaterialTheme.typography.labelSmall)
                    Text(record.change,Modifier.weight(1f),style=MaterialTheme.typography.bodySmall)
                    Text(if(record.succeeded)"✓" else "✕",color=if(record.succeeded)Color(0xFF168047)else MaterialTheme.colorScheme.error,modifier=Modifier.testTag("sensorOutcome-${record.succeeded}"))
                }
                if(expanded==record.id) {
                    record.actual?.let{Text("Прочитано: $it")}
                    if(record.error.isNotBlank())Text(record.error)
                    record.responseCode?.let{Text("HART: $it · ${HartStatus.responseDescription(it)}")}
                    TextButton(onClick={deleteId=record.id},enabled=!state.editBusy,modifier=Modifier.testTag("deleteSensor-${record.id}")){Text("Удалить запись")}
                }
            }
        }
    }
    if(deleteId!=null)ActionDialog("Удалить запись?",{deleteId=null},"deleteSensorDialog",content={Text("Удаляется только запись приложения. Настройки датчика не меняются.")},actions={
        Button(onClick={deleteId?.let(c::deleteSensorRecord);deleteId=null},modifier=Modifier.fillMaxWidth().testTag("confirmDeleteSensor")){Text("Удалить")}
        TextButton(onClick={deleteId=null}){Text("Отмена")}
    })
}
