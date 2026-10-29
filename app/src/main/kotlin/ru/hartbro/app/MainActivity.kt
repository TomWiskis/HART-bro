package ru.hartbro.app

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.*
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.hartbro.app.session.*
import ru.hartbro.core.*

class MainActivity : ComponentActivity() {
    private val model: SessionViewModel by viewModels()
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { model.refreshDevices(true) }
    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { model.controller.confirmConnection() }
    private val enableBluetooth = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { model.refreshDevices(true) }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                val state by model.state.collectAsStateWithLifecycle()
                HartApp(state, model.controller,
                    permission = { if (Build.VERSION.SDK_INT >= 31) permission.launch(Manifest.permission.BLUETOOTH_CONNECT) else model.refreshDevices(true) },
                    enableBluetooth = { enableBluetooth.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) },
                    pair = { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) },
                    confirm = { if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                        notifications.launch(Manifest.permission.POST_NOTIFICATIONS) else model.controller.confirmConnection() },
                    copy = {
                        val text = "HART-бро ${BuildConfig.VERSION_NAME} · Android ${Build.VERSION.RELEASE} API ${Build.VERSION.SDK_INT}\n" + state.diagnostics.joinToString("\n\n") { it.text() }
                        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Диагностика HART", text))
                    })
            }
        }
    }
    override fun onResume() { super.onResume(); model.refreshDevices(true) }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun HartApp(state: SessionState, c: SessionController, permission: () -> Unit, enableBluetooth: () -> Unit,
                    pair: () -> Unit, confirm: () -> Unit, copy: () -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var showOther by rememberSaveable { mutableStateOf(false) }
    var endPrompt by remember { mutableStateOf(false) }
    var deleteId by remember { mutableStateOf<String?>(null) }
    var archiveId by rememberSaveable { mutableStateOf<String?>(null) }
    val monitor = state.monitor
    val decision = state.active && monitor.phase in setOf(MonitorPhase.DECISION, MonitorPhase.DIFFERENT_DEVICE)
    Scaffold(topBar = { TopAppBar(title = { Text("HART-бро ${BuildConfig.VERSION_NAME}") }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            PrimaryScrollableTabRow(selectedTabIndex = tab, edgePadding = 0.dp) {
                listOf("Связь", "Монитор", "Журнал", "HEX").forEachIndexed { i, text -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(text, maxLines = 1) }) }
            }
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(monitor.message, style = MaterialTheme.typography.titleMedium)
                state.notice?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                if (monitor.phase in setOf(MonitorPhase.CONNECTING, MonitorPhase.RECOVERING)) LinearProgressIndicator(Modifier.fillMaxWidth())
                when (tab) {
                    0 -> {
                        Text("Bluetooth VIATOR", style = MaterialTheme.typography.titleLarge)
                        Text("Мониторинг цифровых HART-данных. Только чтение; давление и температура не вычисляются из 4–20 мА.")
                        when {
                            !state.permissionGranted -> Button(onClick = permission) { Text("Разрешить Bluetooth") }
                            !state.bluetoothAvailable -> Text("Bluetooth недоступен")
                            !state.bluetoothEnabled -> Button(onClick = enableBluetooth) { Text("Включить Bluetooth") }
                        }
                        if (!state.active && state.permissionGranted) {
                            OutlinedButton(onClick = pair) { Text("Сопряжение в настройках Android") }
                            OutlinedButton(onClick = { c.refreshDevices() }) { Text("Обновить список") }
                            Row { Checkbox(showOther, { showOther = it }); Text("Показать устройства без имени VIATOR", Modifier.padding(top = 12.dp)) }
                            val devices = state.devices.filter { it.likelyViator || showOther }
                            if (devices.isEmpty()) Text("Сопряжённый VIATOR не найден")
                            devices.forEach { d -> Card(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(16.dp)) {
                                    Text(d.name); Text(d.address, fontFamily = FontFamily.Monospace)
                                    Button(onClick = { c.requestConnection(d) }) { Text("Подключить…") }
                                }
                            } }
                        }
                        Text("В фоне работает видимая служба с уведомлением. Разрешите уведомления, чтобы получать запрос решения после потери связи. Android может остановить процесс; после принудительного завершения автоматического продолжения нет.", style = MaterialTheme.typography.bodySmall)
                    }
                    1 -> {
                        Text("Bluetooth: ${if (monitor.bluetoothConnected) "подключён" else "нет соединения"} · HART: ${monitor.phase}")
                        monitor.identity?.let { d ->
                            Text("${d.manufacturerDescription}\nID ${d.uniqueKey} · HART ${d.hartRevision}\nРевизия ПО ${d.softwareRevision}; устройства ${d.deviceRevision}")
                        }
                        monitor.startedAt?.let { Text("Начало: ${timeLabel(it.toEpochMilli())}") }
                        var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
                        LaunchedEffect(state.active) { while (state.active) { now = System.currentTimeMillis(); kotlinx.coroutines.delay(1000) } }
                        monitor.startedAt?.let { Text("Длительность: ${((now - it.toEpochMilli()) / 1000).coerceAtLeast(0)} с") }
                        val latest = monitor.latest
                        if (latest == null) Text("Текущих достоверных измерений нет; прежние точки остаются в истории.")
                        else {
                            Text("Статус прибора 0x%02X: %s".format(latest.deviceStatus, HartStatus.deviceDescription(latest.deviceStatus)))
                            latest.values.forEach { v ->
                                Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
                                    Text(v.slot, style = MaterialTheme.typography.titleMedium)
                                    Text(if (v.plottable) "${v.value} ${v.unit}" else "Нет достоверного значения", style = if (v.slot == "PV") MaterialTheme.typography.headlineLarge else MaterialTheme.typography.titleLarge)
                                    Text("${qualityText(v.quality)} · ${timeLabel(v.receivedAt.toEpochMilli())}")
                                    Text("Команда ${v.command}; единица HART ${v.unitCode}; код переменной ${v.variableCode ?: "не сообщён"}; классификация ${v.classification ?: "не сообщена"}; статус ${v.rawStatus?.let { "0x%02X".format(it) } ?: "не передан"}", style = MaterialTheme.typography.bodySmall)
                                } }
                            }
                            latest.loopCurrent?.let { Text("Снимок диагностики токового выхода по данным датчика: $it мА; ${latest.loopCurrentAt?.let { at -> timeLabel(at.toEpochMilli()) } ?: "время не сообщено"}. Это не измерение мультиметром.") }
                            latest.extendedStatus?.let { Text("Дополнительный статус: 0x%02X".format(it)) }
                        }
                        Text("Целевой интервал опроса")
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(1L, 2L, 5L, 10L).forEach { sec -> FilterChip(selected = monitor.periodMillis == sec * 1000, onClick = { c.period(sec * 1000) }, label = { Text("$sec с") }) }
                        }
                        if (monitor.phase in setOf(MonitorPhase.MONITORING, MonitorPhase.PAUSED)) Button(onClick = { c.pause(monitor.phase != MonitorPhase.PAUSED) }) { Text(if (monitor.phase == MonitorPhase.PAUSED) "Продолжить" else "Приостановить") }
                        if (state.active && monitor.identity != null) {
                            Button(onClick = { c.save() }, enabled = !state.saving) { Text(if (state.saving) "Сохранение…" else "Сохранить сессию") }
                            MonitoringChart(c.history, null, monitor.revision)
                        }
                        TextButton(onClick = { tab = 3 }) { Text("Открыть диагностику") }
                    }
                    2 -> {
                        Text("Сохранённые сессии", style = MaterialTheme.typography.titleLarge)
                        Text("Сюда попадают только снимки, сохранённые вами. Повторное сохранение создаёт новую запись.")
                        OutlinedButton(onClick = c::refreshArchives) { Text("Обновить журнал") }
                        if (archiveId == null) {
                            if (state.archives.isEmpty()) Text("Сохранённых сессий нет")
                            state.archives.forEach { (id, info) -> Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
                                Text("${timeLabel(info.startedAt)} · ${info.deviceKey}\n${info.manufacturer}\n${info.communicator}")
                                OutlinedButton(onClick = { archiveId = id }) { Text("Открыть") }
                                TextButton(onClick = { deleteId = id }) { Text("Удалить…") }
                            } } }
                        } else {
                            OutlinedButton(onClick = { archiveId = null }) { Text("К списку") }
                            MonitoringChart(c.history, archiveId, 0)
                        }
                    }
                    3 -> {
                        Text("Технический обмен и события сессии", style = MaterialTheme.typography.titleLarge)
                        Text("Последние 512 событий в памяти. HEX не сохраняется автоматически. Копия содержит адрес Bluetooth и HART ID.")
                        OutlinedButton(onClick = copy, enabled = state.diagnostics.isNotEmpty()) { Text("Копировать журнал") }
                        TextButton(onClick = c::clearDiagnostics) { Text("Очистить HEX") }
                        SelectionContainer { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            state.diagnostics.asReversed().forEach { Text(it.text(), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall); HorizontalDivider() }
                        } }
                    }
                }
                if (state.active) OutlinedButton(onClick = { endPrompt = true }, enabled = !state.saving) { Text("Завершить сессию…") }
            }
        }
    }
    state.pendingConnection?.let { d -> AlertDialog(onDismissRequest = c::cancelConnectionPrompt,
        title = { Text("Подключить ${d.name}?") }, text = { Text("${d.address}\nБудет начат непрерывный опрос безопасными командами чтения. Восстановление к этому коммуникатору в рамках сессии выполняется без повторного подтверждения.") },
        confirmButton = { TextButton(onClick = { confirm(); tab = 1 }) { Text("Подключить") } }, dismissButton = { TextButton(onClick = c::cancelConnectionPrompt) { Text("Отмена") } }) }
    if (decision || endPrompt) AlertDialog(onDismissRequest = { if (!decision) endPrompt = false },
        title = { Text(if (monitor.phase == MonitorPhase.DIFFERENT_DEVICE) "Обнаружен другой прибор" else if (decision) "Связь не восстановлена" else "Завершить сессию?") },
        text = { Text(if (decision) monitor.message else "Сохранить текущий снимок или удалить временную историю? Ранее сохранённые снимки останутся в журнале.") },
        confirmButton = { TextButton(onClick = { c.save(finish = true); endPrompt = false }, enabled = !state.saving && monitor.identity != null) { Text("Сохранить сессию") } },
        dismissButton = { TextButton(onClick = { c.end(); endPrompt = false }, enabled = !state.saving) { Text("Завершить без сохранения") } })
    deleteId?.let { id -> AlertDialog(onDismissRequest = { deleteId = null }, title = { Text("Удалить сохранённую сессию?") },
        text = { Text("Запись будет удалена из локального журнала.") }, confirmButton = { TextButton(onClick = { c.delete(id); deleteId = null }) { Text("Удалить") } },
        dismissButton = { TextButton(onClick = { deleteId = null }) { Text("Отмена") } }) }
}
private fun qualityText(q: Quality) = when(q) {
    Quality.GOOD -> "Качество хорошее"; Quality.UNVERIFIED -> "Индивидуальное качество не сообщено"
    Quality.UNCERTAIN -> "Качество неопределённое"; Quality.FIXED -> "Значение зафиксировано"
    Quality.BAD -> "Недостоверно"; Quality.ABSENT -> "Не поддерживается"
}
