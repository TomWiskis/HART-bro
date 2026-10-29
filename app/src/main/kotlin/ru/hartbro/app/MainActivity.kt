package ru.hartbro.app

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
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
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import ru.hartbro.app.session.SessionState
import ru.hartbro.app.session.SessionViewModel
import ru.hartbro.core.HartStatus

class MainActivity : ComponentActivity() {
    private val model: SessionViewModel by viewModels()
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        model.refreshDevices(showPrompt = true)
    }
    private val enableBluetooth = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        model.refreshDevices(showPrompt = true)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val dark = isSystemInDarkTheme()
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                val state by model.state.collectAsStateWithLifecycle()
                HartApp(state, model,
                    requestPermission = { if (Build.VERSION.SDK_INT >= 31) permission.launch(Manifest.permission.BLUETOOTH_CONNECT) else model.refreshDevices(true) },
                    enableBluetooth = { enableBluetooth.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) },
                    pairDevice = { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) },
                    copyDiagnostics = {
                        val text = "HART-бро ${BuildConfig.VERSION_NAME} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n" +
                            state.diagnostics.joinToString("\n\n") { it.text() }
                        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Диагностика HART", text))
                    },
                )
            }
        }
        model.refreshDevices(showPrompt = true)
    }

    override fun onResume() { super.onResume(); model.refreshDevices(showPrompt = true) }
    override fun onStop() {
        // Background monitoring and foreground service belong to phase 2, not this prototype.
        if (!isChangingConfigurations) model.disconnect("Прототип приостановлен: приложение свёрнуто")
        super.onStop()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HartApp(
    state: SessionState,
    model: SessionViewModel,
    requestPermission: () -> Unit,
    enableBluetooth: () -> Unit,
    pairDevice: () -> Unit,
    copyDiagnostics: () -> Unit,
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var showOtherDevices by rememberSaveable { mutableStateOf(false) }
    Scaffold(topBar = { TopAppBar(title = { Text("HART-бро ${BuildConfig.VERSION_NAME} · связь") }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            PrimaryTabRow(selectedTabIndex = tab) {
                listOf("Связь", "Результат", "HEX").forEachIndexed { index, title ->
                    Tab(selected = tab == index, onClick = { tab = index }, text = { Text(title) })
                }
            }
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(state.status, style = MaterialTheme.typography.titleMedium)
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                state.error?.let { message ->
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                        Text(message, Modifier.padding(16.dp))
                    }
                }
                when (tab) {
                    0 -> {
                        Text("VIATOR HM-MT-BT-EX-010041", style = MaterialTheme.typography.titleLarge)
                        Text("Первое испытание — на отдельной лабораторной петле. Прототип отправляет только команды чтения 0 и 1.")
                        when {
                            !state.permissionGranted -> {
                                Text("Для поиска сопряжённых устройств требуется разрешение Bluetooth. Поиск в эфире и геолокация не используются.")
                                Button(onClick = requestPermission) { Text("Разрешить Bluetooth") }
                            }
                            !state.bluetoothAvailable -> Text("На этом устройстве Bluetooth недоступен.")
                            !state.bluetoothEnabled -> Button(onClick = enableBluetooth) { Text("Включить Bluetooth") }
                            else -> {
                                if (!state.connected && !state.busy) {
                                    OutlinedButton(onClick = pairDevice) { Text("Сопряжение в настройках Android") }
                                    OutlinedButton(onClick = { model.refreshDevices() }) { Text("Обновить список") }
                                    Row {
                                        Checkbox(checked = showOtherDevices, onCheckedChange = { showOtherDevices = it })
                                        Text("Показать также устройства без имени VIATOR", Modifier.padding(top = 12.dp))
                                    }
                                    val devices = state.devices.filter { it.likelyViator || showOtherDevices }
                                    if (devices.isEmpty()) Text("Сопряжённый VIATOR не найден. Выполните сопряжение в системных настройках и обновите список.")
                                    devices.forEach { device ->
                                        Card(Modifier.fillMaxWidth()) {
                                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                                Text(device.name, style = MaterialTheme.typography.titleMedium)
                                                Text(device.address, fontFamily = FontFamily.Monospace)
                                                if (!device.likelyViator) Text("Имя не содержит VIATOR. Убедитесь, что выбран нужный HART-модем.")
                                                Button(onClick = { model.requestConnection(device) }) { Text("Подключить…") }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        Text("Подключение выполняется только после подтверждения. При сворачивании приложения соединение закрывается. Автоматическое восстановление появится на этапе 2.", style = MaterialTheme.typography.bodySmall)
                    }
                    1 -> ResultPanel(state, onRead = model::readAgain)
                    2 -> {
                        Text("Диагностика обмена", style = MaterialTheme.typography.titleLarge)
                        Text("Последние 256 событий хранятся только в памяти. HEX содержит реальные переданные и полученные байты; журнал не сохраняется автоматически.")
                        Text("При копировании в буфер попадут адрес Bluetooth и идентификатор прибора.", style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick = copyDiagnostics, enabled = state.diagnostics.isNotEmpty()) { Text("Копировать диагностику") }
                        TextButton(onClick = model::clearDiagnostics, enabled = !state.busy) { Text("Очистить HEX") }
                        SelectionContainer {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                state.diagnostics.forEach { entry ->
                                    Text(entry.text(), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                                    HorizontalDivider()
                                }
                            }
                        }
                    }
                }
                if (state.connected || state.busy) {
                    OutlinedButton(onClick = { model.disconnect() }) { Text(if (state.busy) "Отменить и отключиться" else "Отключиться") }
                }
            }
        }
    }
    state.pendingConnection?.let { device ->
        AlertDialog(
            onDismissRequest = model::cancelConnectionPrompt,
            title = { Text(if (device.likelyViator) "Обнаружен коммуникатор VIATOR. Продолжить подключение?" else "Подключить выбранный HART-модем?") },
            text = { Text("${device.name}\n${device.address}\nБудут отправлены только команды чтения HART 0 и 1, адрес опроса 0.") },
            confirmButton = { TextButton(onClick = { model.confirmConnection(); tab = 1 }) { Text("Продолжить подключение") } },
            dismissButton = { TextButton(onClick = model::cancelConnectionPrompt) { Text("Отмена") } },
        )
    }
}

@Composable
private fun ResultPanel(state: SessionState, onRead: () -> Unit) {
    Text("Данные прибора", style = MaterialTheme.typography.titleLarge)
    val identity = state.identity
    if (identity == null) Text("Прибор ещё не идентифицирован. Выполните подключение во вкладке «Связь».")
    else Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("HART ${identity.hartRevision}", style = MaterialTheme.typography.titleMedium)
            Text("Уникальный адрес: ${identity.uniqueKey}", fontFamily = FontFamily.Monospace)
            Text("Производитель: ${identity.manufacturerDescription}")
            Text("Модель: тип 0x%04X".format(identity.deviceType))
            Text("Ревизия ПО прибора: ${identity.softwareRevision}")
            Text("Ревизия команд устройства: ${identity.deviceRevision}; аппаратная ревизия: ${identity.hardwareRevision}")
            identity.extendedDeviceStatus?.let { Text("Расширенный статус из команды 0: 0x%02X".format(it)) }
            Text("Коды показаны без предположений о торговом названии. Профили моделей появятся на последующих этапах.", style = MaterialTheme.typography.bodySmall)
        }
    }
    state.deviceStatus?.let { status ->
        Text("Статус прибора: 0x%02X".format(status))
        Text(HartStatus.deviceDescription(status), color = if (status != 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
    }
    val variable = state.primaryVariable
    if (variable != null) {
        Text("Основная цифровая переменная · команда 1")
        Text("${variable.value} ${variable.unit}", style = MaterialTheme.typography.headlineLarge)
        Text("Единица: ${variable.unitName} (код HART ${variable.unitCode})")
        val time = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss.SSS XXX").withZone(ZoneId.systemDefault()).format(variable.receivedAt)
        Text("Ответ получен: $time", style = MaterialTheme.typography.bodySmall)
        Text("Разовое измерение. Значение получено из HART, без пересчёта тока 4–20 мА.")
    } else Text("Достоверного значения основной переменной пока нет.")
    if (state.connected && identity != null) Button(onClick = onRead, enabled = !state.busy) { Text("Считать снова") }
}
