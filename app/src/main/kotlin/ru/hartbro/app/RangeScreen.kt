package ru.hartbro.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
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
    Text("Настройка диапазона",style=MaterialTheme.typography.titleLarge)
    Text(r.message,modifier=Modifier.testTag("rangeStatus"))
    if(r.busy) {LinearProgressIndicator(Modifier.fillMaxWidth());r.secondsLeft?.let{Text("Проверка / восстановление: до $it с")}}
    if(r.phase in setOf(RangePhase.READING,RangePhase.PRECHECK))OutlinedButton(onClick=c::closeRange){Text("Отменить подготовку")}
    state.notice?.let{Text(it,color=MaterialTheme.colorScheme.primary)}
    r.snapshot?.let { snapshot ->
        val d=snapshot.identity
        Text("${d.manufacturerDescription}\n${PassportProfiles().model(d)}\nDevice Type 0x%04X · Device ID %06X · HART ${d.hartRevision}".format(d.deviceType,d.deviceId))
        Text("TAG: ${snapshot.tag ?: "Не указан"}")
        Text("Bluetooth: ${if(state.monitor.bluetoothConnected || state.walk.bluetoothConnected) "подключён" else "проверяется"}; HART: параметры прочитаны ${snapshot.readAt}",style=MaterialTheme.typography.bodySmall)
        Text("Текущий диапазон: ${snapshot.range.label}",modifier=Modifier.testTag("rangeCurrent"))
        val sensor=snapshot.sensor
        if(sensor?.lowerLimit!=null && sensor.upperLimit!=null) Text("Пределы сенсора: ${ConfiguredRange(sensor.lowerLimit!!,sensor.upperLimit!!,sensor.unitCode).label}")
        else Text("Пределы сенсора не получены. Проверка физических ограничений недоступна.")
        Text("Минимальный диапазон: ${sensor?.minimumSpan?.takeIf{it>0}?.let{"${it.toString().replace('.',',')} ${rangeUnit(sensor.unitCode)}"} ?: "не получен"}")
        Text("Защита записи: ${when(snapshot.writeProtected){true->"включена";false->"не включена по данным команды 15";null->"неизвестна"}}")
        if(r.phase==RangePhase.FORM) {
            var lower by rememberSaveable(r.token){mutableStateOf(snapshot.range.lower.toString())}
            var upper by rememberSaveable(r.token){mutableStateOf(snapshot.range.upper.toString())}
            val validation=RangeRules.validate(snapshot,lower,upper)
            OutlinedTextField(lower,{lower=it},label={Text("Нижняя граница диапазона")},suffix={Text(rangeUnit(snapshot.range.unitCode))},singleLine=true,
                keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),leadingIcon={TextButton(onClick={lower=if(lower.startsWith('-'))lower.drop(1) else "-$lower"}){Text("±")}},modifier=Modifier.fillMaxWidth().testTag("rangeLower"))
            OutlinedTextField(upper,{upper=it},label={Text("Верхняя граница диапазона")},suffix={Text(rangeUnit(snapshot.range.unitCode))},singleLine=true,
                keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),leadingIcon={TextButton(onClick={upper=if(upper.startsWith('-'))upper.drop(1) else "-$upper"}){Text("±")}},modifier=Modifier.fillMaxWidth().testTag("rangeUpper"))
            validation.errors.forEach{Text(it,color=MaterialTheme.colorScheme.error)}
            validation.warnings.forEach{Text(it,style=MaterialTheme.typography.bodySmall)}
            if(validation.unchanged)Text("Новый диапазон совпадает с текущим. Запись не требуется.")
            Button(onClick={c.selectRange(lower,upper)},enabled=validation.allowed,modifier=Modifier.testTag("rangeSet")){Text("Установить")}
        }
    }
    if(r.phase==RangePhase.RESULT) {
        r.record?.let { record ->
            Text("До: ${record.original.label}\nЗапрошено: ${record.requested.label}\nОтвет 35: ${record.echoed?.label ?: "не получен / не декодирован"}\nФактически (15): ${record.actual?.label ?: "не подтверждено"}",modifier=Modifier.testTag("rangeResult"))
            record.responseCode?.let{Text("35: $it — ${RangeResponseCodes.description(it)}")}
            record.deviceStatus?.let{Text("Статус 0x%02X: ${HartStatus.deviceDescription(it)}".format(it))}
            record.errors.forEach{Text(it,style=MaterialTheme.typography.bodySmall)}
            OutlinedButton(onClick={c.openRange(record.id)},modifier=Modifier.testTag("rangeVerify")){Text("Проверить фактический диапазон чтением")}
            TextButton(onClick={export(record.id)}){Text("Сохранить операцию в TXT")}
        }
        r.otherDevice?.let{Text("Сейчас обнаружен ${it.manufacturerDescription} · ${it.uniqueKey}. Для исходного прибора нужно повторное подключение.")}
        if(state.active)Button(onClick={c.openRange(acceptCurrentDevice=true)},modifier=Modifier.testTag("rangeReload")){Text("Повторно считать текущий прибор")}
    }
    if(r.phase==RangePhase.CLOSED)Text("Настройка открывается из карточки подключённого прибора. История доступна без подключения.")
    HorizontalDivider();Text("Журнал операций настройки",style=MaterialTheme.typography.titleMedium)
    if(state.rangeRecords.isEmpty())Text("Операций записи пока нет")
    var details by rememberSaveable{mutableStateOf<String?>(null)}
    state.rangeRecords.forEach { record ->Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
        Text("${record.time} · ${record.identity.uniqueKey} · ${record.tag ?: "TAG не указан"}")
        Text("${record.original.label} → ${record.requested.label}")
        Text(RangeEngine.resultText(record))
        TextButton(onClick={details=if(details==record.id)null else record.id}){Text("Технические сведения")}
        if(details==record.id)Text(record.text(),fontFamily=FontFamily.Monospace,style=MaterialTheme.typography.bodySmall)
        TextButton(onClick={export(record.id)}){Text("Сохранить TXT")}
        if(record.outcome in setOf(RangeOutcome.UNKNOWN,RangeOutcome.DIFFERENT_DEVICE))TextButton(onClick={c.openRange(record.id)},enabled=!r.busy){Text("Проверить чтением")}
    } } }
    if(state.rangeRecords.size>=100)TextButton(onClick=c::moreRangeRecords){Text("Показать более ранние операции")}
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
