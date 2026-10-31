package ru.hartbro.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import ru.hartbro.app.session.*
import ru.hartbro.core.*

internal val passportNames = mapOf(PassportField.TAG to "TAG", PassportField.SERIAL to "Серийный номер", PassportField.DATE to "Дата выпуска",
    PassportField.MODEL to "Тип / марка датчика", PassportField.RANGE to "Диапазон")
private fun sourceLabel(f: PassportFact?) = when {
    f == null || f.value.isNullOrBlank() -> "нет данных"
    f.manual -> "введено пользователем"
    f.source == FactSource.PROFILE -> "профиль производителя"
    f.factoryConfirmed -> "подтверждено документацией"
    else -> "стандартное поле HART · команда ${f.command ?: "—"}"
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun WalkScreen(state: SessionState,c: SessionController,onStart:()->Unit,onEnd:()->Unit,export:(WalkBook,Boolean)->Unit) {
    var savedId by rememberSaveable { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<WalkRow?>(null) }
    var deleting by remember { mutableStateOf<WalkBook?>(null) }
    var separate by remember { mutableStateOf<WalkRow?>(null) }
    var technical by rememberSaveable { mutableStateOf(false) }
    var shown by rememberSaveable(savedId) { mutableIntStateOf(50) }
    var renaming by remember { mutableStateOf(false) }
    BackHandler(savedId!=null) { savedId=null }
    val book = if(savedId!=null) state.savedWalks.find { it.id==savedId } else state.walk.book
    if(savedId!=null) TextButton(onClick={savedId=null}) { Text("К текущему обходу") }
    Text(if(savedId==null) "Обход датчиков" else "Сохранённая ведомость",style=MaterialTheme.typography.titleLarge)
    Text("Одна строка на прибор. Поля можно уточнять со шильдика — изменения сохраняются только в ведомости.")
    if(savedId==null) {
        Text("VIATOR: ${if(state.walk.bluetoothConnected) "Bluetooth подключён" else "нет Bluetooth-соединения"}")
        Text(state.walk.message,modifier=Modifier.testTag("walkStatus"))
        if(state.walkActive) {
            Text("Интервал обнаружения после ответа")
            FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                listOf(500L,1000L,2000L).forEach { ms -> FilterChip(state.walk.intervalMillis==ms,{c.walkInterval(ms)},label={Text(if(ms==500L) "0,5 с" else "${ms/1000} с")}) }
            }
            OutlinedButton(onClick=c::rereadWalk,enabled=state.walk.bluetoothConnected) { Text("Повторить чтение текущего прибора") }
            state.walk.recovery?.let { p -> Text("Восстановление Bluetooth: осталось ${(p.remainingMillis+999)/1000} с; попытка ${p.attempt}");LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if(state.walk.phase==WalkPhase.DECISION) {
                Text("Полученные строки остаются в ведомости. Можно подключиться к VIATOR повторно или завершить обход.")
                Button(onClick=c::retryWalk) { Text("Повторить подключение VIATOR") }
                OutlinedButton(onClick=onEnd) { Text("Завершить обход…") }
            }
        } else if(state.walkDraftAvailable && book==null) {
            Card { Column(Modifier.padding(12.dp)) {
                Text("Найден временный черновик. Соединение автоматически не запускается.")
                TextButton(onClick=c::restoreWalkDraft) { Text("Восстановить черновик") }
                TextButton(onClick={deleting=WalkBook(id="draft",title="Временный черновик")}) { Text("Удалить черновик…") }
            } }
        } else Button(onClick=onStart,modifier=Modifier.testTag("startWalk")) { Text("Начать обход") }
    }
    if(book!=null) {
        Text("${book.title} · приборов ${book.rows.size}",style=MaterialTheme.typography.titleMedium,modifier=Modifier.testTag("walkCount"))
        FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            if(savedId==null && !state.walkActive && book.endedAt!=null) Button(onClick=c::saveWalk) { Text("Сохранить изменения") }
            OutlinedButton(onClick={renaming=true}) { Text("Название") }
            Button(onClick={export(book,false)},enabled=book.rows.isNotEmpty()) { Text("Сохранить Excel") }
            OutlinedButton(onClick={export(book,true)},enabled=book.rows.isNotEmpty()) { Text("Поделиться XLSX") }
        }
        if(book.pending.isNotEmpty()) {
            Text("Требуется проверка идентификаторов: ${book.pending.size}",color=MaterialTheme.colorScheme.error)
            book.pending.forEach { pending -> Card { Column(Modifier.padding(12.dp)) {
                Text("${pending.reading.identity.uniqueKey}: ${pending.explanation}")
                pending.reading.candidates.forEach { (key,fact) -> Text("${candidateName(key)}: ${fact.value ?: "нет данных"} · команда ${fact.command}") }
                run {
                    TextButton(onClick={c.resolveWalkIdentity(pending.id,null,savedId)}) { Text("Это другой прибор — отдельная строка") }
                    book.rows.filter { it.key==pending.reading.key }.forEach { row ->
                        TextButton(onClick={c.resolveWalkIdentity(pending.id,row.id,savedId)}) { Text("Это ${row.text(PassportField.TAG) ?: "прибор ${book.rows.indexOf(row)+1}"} — обновить строку") }
                    }
                }
            } } }
            Text("Excel содержит только подтверждённые строки ведомости. Несопоставленные чтения выше сохраняются в документе обхода.",style=MaterialTheme.typography.bodySmall)
        }
        TextButton(onClick={technical=!technical}) { Text(if(technical) "Скрыть происхождение и HEX" else "Происхождение полей и технические номера") }
        book.rows.take(shown).forEachIndexed { i,row -> key(row.id) { Card(Modifier.fillMaxWidth().testTag("walkRow-${row.id}")) { Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
            Text("${i+1}. ${row.text(PassportField.TAG) ?: "TAG не указан"}",style=MaterialTheme.typography.titleMedium)
            PassportField.entries.filter { it!=PassportField.TAG }.forEach { field ->
                Text("${passportNames.getValue(field)}: ${row.text(field) ?: "нет данных"}${if(row.fields[field]?.manual==true) " · вручную" else ""}")
            }
            Text("Заполнено ${row.completedFields}/5 · HART ${row.observed.identity.hartRevision} · ${row.observed.identity.uniqueKey}",style=MaterialTheme.typography.bodySmall)
            if(row.requiresIdentityCheck) Text("Идентификатор требует ручной проверки",color=MaterialTheme.colorScheme.error)
            row.pendingRange?.let { range ->
                Text("Изменился диапазон: ${range.label}. Текущее поле сохранено.")
                TextButton(onClick={c.acceptWalkRange(row.id,savedId)}) { Text("Принять новый диапазон") }
            }
            FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                TextButton(onClick={editing=row}) { Text("Уточнить поля") }
                TextButton(onClick={separate=row}) { Text("Разделить запись…") }
            }
            if(technical) {
                Text("Ключ: ${row.key}; прочитано ${row.observed.readAt}",style=MaterialTheme.typography.bodySmall)
                PassportField.entries.forEach { field -> val f=row.fields[field];Text("${passportNames.getValue(field)}: ${sourceLabel(f)}; ${f?.readAt ?: "—"}; raw=${f?.raw.orEmpty()}",style=MaterialTheme.typography.bodySmall) }
                row.observed.candidates.forEach { (name,fact) -> Text("${candidateName(name)}: ${fact.value}; команда ${fact.command}; ${fact.readAt}; raw=${fact.raw}",style=MaterialTheme.typography.bodySmall) }
                Text("Номер сенсора, номер сборки и HART Date требуют подтверждения по документации модели; они не заполняют заводской номер и дату выпуска автоматически.",style=MaterialTheme.typography.bodySmall)
                row.observed.errors.forEach { (command,error) -> Text("$command: $error",style=MaterialTheme.typography.bodySmall) }
            }
        } } } }
        if(book.rows.size>shown) TextButton(onClick={shown+=50}) { Text("Показать следующие 50") }
    }
    if(savedId==null) {
        HorizontalDivider();Text("Сохранённые обходы",style=MaterialTheme.typography.titleMedium)
        if(state.savedWalks.isEmpty()) Text("Сохранённых ведомостей нет. Сохранение выполняется вручную.")
        state.savedWalks.forEach { saved -> Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
            Text("${saved.title} · ${saved.rows.size} приборов");Text(saved.startedAt.toString(),style=MaterialTheme.typography.bodySmall)
            TextButton(onClick={savedId=saved.id}) { Text("Открыть ведомость") }
            TextButton(onClick={deleting=saved}) { Text("Удалить…") }
        } } }
    }
    editing?.let { row -> PassportEditor(row,onDismiss={editing=null},onSave={values -> c.editWalk(row.id,values,savedId);editing=null}) }
    separate?.let { row -> AlertDialog(onDismissRequest={separate=null},title={Text("Создать отдельную строку?")},
        text={Text("Используйте, если разные физические приборы имеют одинаковый HART ID. Новая строка получит считанные поля; ручные сведения исходной строки останутся в ней. Последующие совпадения потребуют выбора строки.")},
        confirmButton={TextButton(onClick={c.separateWalk(row.id,savedId);separate=null}) {Text("Разделить")}},dismissButton={TextButton(onClick={separate=null}){Text("Отмена")}}) }
    deleting?.let { target -> AlertDialog(onDismissRequest={deleting=null},title={Text("Удалить ${target.title}?")},text={Text("Удаление необратимо. Ранее экспортированные файлы останутся.")},
        confirmButton={TextButton(onClick={if(target.id=="draft")c.discardWalkDraft() else c.deleteWalk(target.id);deleting=null}){Text("Удалить")}},dismissButton={TextButton(onClick={deleting=null}){Text("Отмена")}}) }
    if(renaming && book!=null) {
        var title by remember(book.id) { mutableStateOf(book.title) }
        AlertDialog(onDismissRequest={renaming=false},title={Text("Название ведомости")},text={OutlinedTextField(title,{title=it},singleLine=true)},
            confirmButton={TextButton(onClick={c.renameWalk(title,savedId);renaming=false},enabled=title.length<=200){Text("Сохранить")}},dismissButton={TextButton(onClick={renaming=false}){Text("Отмена")}})
    }
}
private fun candidateName(key:String) = when(key) { "sensorNumber"->"Номер сенсора";"assemblyNumber"->"Номер окончательной сборки";"hartDate"->"Редактируемая HART Date (HEX)";else->key }

@Composable
private fun PassportEditor(row:WalkRow,onDismiss:()->Unit,onSave:(Map<PassportField,String>)->Unit) {
    var values by remember(row.id) { mutableStateOf(PassportField.entries.associateWith { row.text(it).orEmpty() }) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest=onDismiss,title={Text("Уточнение паспортных сведений")},text={
        Column(Modifier.heightIn(max=440.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text("Локальный ввод со шильдика. HART-команды записи не отправляются.",style=MaterialTheme.typography.bodySmall)
            PassportField.entries.forEach { field -> OutlinedTextField(values.getValue(field),{values=values+(field to it);error=null},label={Text(passportNames.getValue(field))},singleLine=true,
                supportingText=if(field==PassportField.DATE) {{Text("ДД.ММ.ГГГГ / ММ.ГГГГ / ГГГГ или пусто")}} else null) }
            error?.let { Text(it,color=MaterialTheme.colorScheme.error) }
        }
    },confirmButton={TextButton(onClick={
        try { values.values.forEach { require(it.length<=200){"Не более 200 символов в поле"} };values[PassportField.DATE]?.takeIf{it.isNotBlank()}?.let(FactoryDate::parse);onSave(values) }
        catch(e:Exception) { error=e.message ?: "Проверьте дату" }
    }){Text("Сохранить")}},dismissButton={TextButton(onClick=onDismiss){Text("Отмена")}})
}
