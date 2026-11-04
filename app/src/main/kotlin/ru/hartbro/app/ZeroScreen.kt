package ru.hartbro.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import ru.hartbro.app.session.*
import ru.hartbro.core.*

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun ZeroEditDialog(state:SessionState,c:SessionController) {
    val edit=state.sensorEdit ?: return
    val keyboard=LocalSoftwareKeyboardController.current
    var kind by rememberSaveable(edit.identity.uniqueKey){mutableStateOf(edit.zero.pressureKind)}
    var tolerance by rememberSaveable(edit.identity.uniqueKey){mutableStateOf(edit.zero.tolerance?.toString().orEmpty())}
    var resolution by rememberSaveable(edit.identity.uniqueKey){mutableStateOf(edit.zero.resolution?.toString().orEmpty())}
    var source by rememberSaveable(edit.identity.uniqueKey){mutableStateOf(edit.zero.accuracySource)}
    var conditions by rememberSaveable(edit.identity.uniqueKey){mutableStateOf(false)}
    var pressure by rememberSaveable(edit.identity.uniqueKey){mutableStateOf(false)}
    var methodId by rememberSaveable(edit.identity.uniqueKey){mutableStateOf(edit.zero.methodId)}
    val methods=state.zeroPreparation?.methods ?: ZeroMethods().available(edit.identity)
    ActionDialog(if(state.editConfirm)"Подтвердить установку нуля" else "Установить ноль",c::closeSensorEdit,"sensorEditDialog",content={
        Text("${sensorTitle(edit.identity)} · Device ID ${edit.identity.deviceId}")
        Text("Калибровка изменяет настройку PV. Подготовьте прибор по его процедуре и исключите влияние операции на действующий контур.")
        Text(kind.warning,modifier=Modifier.testTag("zeroPressureWarning"))
        if(!state.editConfirm) {
            FlowRow(horizontalArrangement=Arrangement.spacedBy(4.dp)){PressureKind.entries.forEach{v->FilterChip(kind==v,{kind=v},label={Text(v.label)})}}
            methods.forEach{m->FilterChip(methodId==m.id,{methodId=m.id},label={Text("${m.label} · ${m.command}")})}
            Text("Стандартная команда может быть не поддержана конкретным прибором. Решение определяется его ответом. Автоматического перебора и повтора записи нет.")
            OutlinedTextField(tolerance,{tolerance=it},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal,imeAction=ImeAction.Done),keyboardActions=KeyboardActions(onDone={keyboard?.hide()}),label={Text("Допустимое отклонение PV от нуля, необязательно")},modifier=Modifier.fillMaxWidth().testTag("zeroTolerance"))
            OutlinedTextField(resolution,{resolution=it},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal,imeAction=ImeAction.Done),keyboardActions=KeyboardActions(onDone={keyboard?.hide()}),label={Text("Разрешение PV, если известно")},modifier=Modifier.fillMaxWidth().testTag("zeroResolution"))
            OutlinedTextField(source,{source=it},singleLine=true,keyboardOptions=KeyboardOptions(imeAction=ImeAction.Done),keyboardActions=KeyboardActions(onDone={keyboard?.hide()}),label={Text("Источник допуска / разрешения")},modifier=Modifier.fillMaxWidth().testTag("zeroAccuracySource"))
            Text("Без известного допуска результат не будет объявлен подтверждённым измерением. Введённые значения относятся к единице PV, которая будет показана после чтения.")
            Row {Checkbox(pressure,{pressure=it},modifier=Modifier.testTag("zeroPressureConsent"));Text("Подтверждаю: выбран датчик давления")}
            Row {Checkbox(conditions,{conditions=it},modifier=Modifier.testTag("zeroConditionsConsent"));Text("Правильное нулевое воздействие создано, прибор разрешён для настройки")}
        } else {
            val prep=state.zeroPreparation
            Text("PV до: ${prep?.before?.text ?: "не получена"}",modifier=Modifier.testTag("zeroBefore"))
            prep?.errors?.forEach{Text(it,style=MaterialTheme.typography.bodySmall)}
            if(prep?.writeProtected==true)Text("Прибор сообщает защиту записи",color=MaterialTheme.colorScheme.error)
            val selected=methods.firstOrNull{it.id==edit.zero.methodId}
            Text("Метод: ${selected?.label}; команда ${selected?.command}. ${selected?.source.orEmpty()}")
            Text("Допуск: ${edit.zero.tolerance ?: "неизвестен"}; разрешение: ${edit.zero.resolution ?: "неизвестно"}; единица PV: ${prep?.unitCode?.let{HartUnits.find(it)?.symbol ?: "код $it"} ?: "неизвестна"}")
            if(edit.zero.accuracySource.isNotBlank())Text("Источник: ${edit.zero.accuracySource}")
            Text("Будет отправлена одна команда и выполнено контрольное чтение. При потере ответа повторная запись автоматически не выполняется.")
        }
        state.editError?.let{Text(it,color=MaterialTheme.colorScheme.error,modifier=Modifier.testTag("sensorEditError"))}
        if(state.editBusy)LinearProgressIndicator(Modifier.fillMaxWidth())
    },actions={
        Button(enabled=!state.editBusy&&(state.editConfirm||conditions&&pressure),modifier=Modifier.fillMaxWidth().testTag("sensorEditApply"),onClick={
            if(state.editConfirm)c.confirmSensorEdit()
            else runCatching {
                fun number(v:String)=v.trim().takeIf{it.isNotEmpty()}?.replace(',','.')?.toFloat()
                ZeroOptions(methodId,kind,conditions,pressure,number(tolerance),number(resolution),source)
            }.onSuccess(c::prepareZeroEdit).onFailure{c.sensorEditError("Проверьте допуск и источник: ${it.message}")}
        }){Text(if(state.editConfirm)"Да, установить ноль и проверить" else "Прочитать PV и продолжить")}
        TextButton(onClick=c::closeSensorEdit,enabled=!state.editBusy,modifier=Modifier.fillMaxWidth()){Text("Отмена")}
    })
}
