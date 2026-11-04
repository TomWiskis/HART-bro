package ru.hartbro.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import ru.hartbro.app.session.*
import ru.hartbro.core.*

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun JournalScreen(state: SessionState, c: SessionController, archiveId: String?, open: (String) -> Unit,
                           export: (List<String>, ExportFormat, Boolean, Char) -> Unit) {
    var query by remember { mutableStateOf("") }
    var byName by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var deletion by remember { mutableStateOf<Set<String>?>(null) }
    var editing by remember { mutableStateOf<Pair<String, SessionInfo>?>(null) }
    var comparison by remember { mutableStateOf<Pair<ConfigurationSnapshot, ConfigurationSnapshot>?>(null) }
    var technicalCsv by remember { mutableStateOf(false) }
    var separator by remember { mutableStateOf(';') }
    LaunchedEffect(state.archives) { selected = selected.intersect(state.archives.map { it.first }.toSet()) }
    Text("Сохранённые сессии", style = MaterialTheme.typography.titleLarge)
    if (archiveId == null) {
        Text("Только сохранённые вами снимки. Повторное сохранение создаёт самостоятельную запись.")
        OutlinedTextField(query, { query = it }, label = { Text("Поиск: TAG, ID, название") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(!byName, { byName = false }, label = { Text("По дате ↓") })
            FilterChip(byName, { byName = true }, label = { Text("По названию ↑") })
            TextButton(onClick = c::refreshArchives) { Text("Обновить журнал") }
        }
        if (selected.isNotEmpty()) {
            Text("Выбрано: ${selected.size}")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { export(selected.sorted(), ExportFormat.XLSX, false, separator) }) { Text("Excel по выбранным") }
                OutlinedButton(onClick = { export(selected.sorted(), ExportFormat.XLSX, true, separator) }) { Text("Отправить ведомость") }
                OutlinedButton(onClick = { deletion = selected }) { Text("Удалить выбранные…") }
                OutlinedButton(onClick = {
                    val configs = state.archives.filter { it.first in selected }.map { it.second.configuration }
                    comparison = configs[0] to configs[1]
                }, enabled = selected.size == 2) { Text("Сравнить две") }
                OutlinedButton(onClick = {
                    comparison = state.archives.first { it.first in selected }.second.configuration to state.monitor.configuration
                }, enabled = selected.size == 1 && state.monitor.identity != null) { Text("Сравнить с текущим") }
                TextButton(onClick = { selected = emptySet() }) { Text("Снять выбор") }
            }
        }
        if (state.archives.isNotEmpty()) TextButton(onClick = { deletion = state.archives.map { it.first }.toSet() }) { Text("Очистить весь журнал…") }
        val filtered = state.archives.filter { (_, info) -> listOf(info.title, info.deviceKey, info.configuration.tag ?: "", info.configuration.parameters["tag"]?.value ?: "").any { it.contains(query, true) } }
        val records = if (byName) filtered.sortedBy { it.second.title.ifBlank { it.second.configuration.tag ?: it.second.deviceKey }.lowercase() } else filtered.sortedByDescending { it.second.startedAt }
        if (records.isEmpty()) Text("Записей не найдено")
        records.forEach { (id, info) -> Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
            Row {
                Checkbox(id in selected, { checked -> selected = if (checked) selected + id else selected - id }, Modifier.testTag("select-$id"))
                Column {
                    Text(info.title.ifBlank { info.configuration.tag ?: info.deviceKey }, style = MaterialTheme.typography.titleMedium)
                    Text("${timeLabel(info.startedAt)} · ${info.durationMillis / 1000} с")
                    Text("${info.deviceTitle} · ${info.configuration.range?.label ?: "Диапазон неизвестен"}", style = MaterialTheme.typography.bodySmall)
                }
            }
            FlowRow {
                OutlinedButton(onClick = { open(id) }) { Text("Открыть") }
                TextButton(onClick = { editing = id to info }) { Text("Изменить") }
                TextButton(onClick = { deletion = setOf(id) }) { Text("Удалить…") }
            }
        } } }
    } else {
        val info = state.archives.firstOrNull { it.first == archiveId }?.second
        if (info == null) { Text("Запись недоступна"); return }
        Text(info.title.ifBlank { info.configuration.tag ?: info.deviceKey }, style = MaterialTheme.typography.titleMedium)
        Text("${timeLabel(info.startedAt)} — ${timeLabel(info.endedAt)} · ${info.durationMillis / 1000} с")
        Text("${info.communicator}\n${info.deviceTitle} · ${info.deviceKey}")
        TextButton(onClick = { editing = archiveId to info }) { Text("Название и комментарий…") }
        if (info.comment.isNotBlank()) Text(info.comment)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { export(listOf(archiveId), ExportFormat.XLSX, false, separator) }) { Text("Сохранить Excel (.xlsx)") }
            OutlinedButton(onClick = { export(listOf(archiveId), ExportFormat.XLSX, true, separator) }) { Text("Отправить Excel") }
            OutlinedButton(onClick = { export(listOf(archiveId), ExportFormat.PDF, false, separator) }) { Text("Сохранить PDF") }
            OutlinedButton(onClick = { export(listOf(archiveId), ExportFormat.PDF, true, separator) }) { Text("Отправить PDF") }
        }
        TextButton(onClick = { technicalCsv = !technicalCsv }) { Text(if (technicalCsv) "Скрыть технический CSV" else "Технический CSV…") }
        if (technicalCsv) {
            Text("CSV: UTF-8, время UTC, десятичная точка. Первая строка sep= указывает разделитель; при программном чтении пропустите её.", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(separator == ';', { separator = ';' }, label = { Text("Точка с запятой ;") })
                FilterChip(separator == ',', { separator = ',' }, label = { Text("Запятая ,") })
            }
            Text("Используемый разделитель: ${if (separator == ';') "точка с запятой (;)" else "запятая (,)"}", style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { export(listOf(archiveId), ExportFormat.CSV, false, separator) }) { Text("Сохранить CSV") }
                OutlinedButton(onClick = { export(listOf(archiveId), ExportFormat.CSV, true, separator) }) { Text("Отправить CSV") }
            }
        }
        MonitoringChart(c.history, archiveId, 0)
        ConfigurationPanel(info.configuration)
        TextButton(onClick = { comparison = info.configuration to state.monitor.configuration }, enabled = state.monitor.identity != null) { Text("Сравнить с текущими параметрами") }
    }
    deletion?.let { ids -> AlertDialog(onDismissRequest = { deletion = null }, title = { Text("Удалить проверок: ${ids.size}?") },
        text = { Text("Из локального журнала будут удалены только выбранные записи. Экспортированные файлы сохранятся.") },
        confirmButton = { TextButton(onClick = { c.deleteMany(ids); selected -= ids; deletion = null }) { Text("Удалить") } },
        dismissButton = { TextButton(onClick = { deletion = null }) { Text("Отмена") } }) }
    editing?.let { (id, info) ->
        var title by remember(id) { mutableStateOf(info.title) }; var comment by remember(id) { mutableStateOf(info.comment) }
        AlertDialog(onDismissRequest = { editing = null }, title = { Text("Название и комментарий") },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(title, { title = it.take(200) }, label = { Text("Название") })
                OutlinedTextField(comment, { comment = it.take(10000) }, label = { Text("Комментарий") }, minLines = 3, maxLines = 8)
            } }, confirmButton = { TextButton(onClick = { c.edit(id, title, comment); editing = null }) { Text("Сохранить") } },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Отмена") } })
    }
    comparison?.let { (a, b) -> AlertDialog(onDismissRequest = { comparison = null }, title = { Text("Сравнение конфигураций") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row { Text("Параметр", Modifier.weight(1f)); Text("Проверка А", Modifier.weight(1f)); Text("Проверка Б / текущий", Modifier.weight(1f)) }
            val rows = compareConfigurations(a, b)
            if (rows.isEmpty()) Text("Нет данных")
            rows.forEach { row ->
                val color = if (row.different) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                Row {
                    Text(configurationNames[row.key] ?: row.key, Modifier.weight(1f))
                    Text(row.a?.let { "${it.value} (к. ${it.command})" } ?: "Нет данных", Modifier.weight(1f), color = color)
                    Text(row.b?.let { "${it.value} (к. ${it.command})" } ?: "Нет данных", Modifier.weight(1f), color = color)
                }; HorizontalDivider()
            }
            Text("Отсутствующий параметр не считается изменением. Текущие измерения исключены.")
        } }, confirmButton = { TextButton(onClick = { comparison = null }) { Text("Закрыть") } }) }
}

@Composable
internal fun ConfigurationPanel(snapshot: ConfigurationSnapshot) {
    var expanded by remember(snapshot) { mutableStateOf(false) }
    Text("${snapshot.range?.label ?: "Диапазон неизвестен"} · TAG: ${snapshot.tag ?: "Нет данных"}")
    TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Скрыть конфигурацию" else "Снимок доступной конфигурации") }
    if (expanded) {
        Text("Частичный снимок стандартных параметров, только чтение.", style = MaterialTheme.typography.bodySmall)
        if (snapshot.parameters.isEmpty()) Text("Нет данных")
        snapshot.parameters.forEach { (key, value) -> Text("${configurationNames[key] ?: key}: ${value.value} · команда ${value.command}${value.unitCode?.let { "; единица HART $it" } ?: ""}", style = MaterialTheme.typography.bodySmall) }
        snapshot.responses.filter { it.error != null }.forEach { Text("Команда ${it.command}: ${it.error}", style = MaterialTheme.typography.bodySmall) }
    }
}
