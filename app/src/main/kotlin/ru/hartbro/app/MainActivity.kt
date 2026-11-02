package ru.hartbro.app

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.*
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.core.content.FileProvider
import kotlinx.coroutines.*
import ru.hartbro.app.session.*
import ru.hartbro.app.reports.SessionPdf
import java.io.File
import java.io.OutputStream
import ru.hartbro.core.*

class MainActivity : ComponentActivity() {
    private val model: SessionViewModel by viewModels()
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { model.refreshDevices(true) }
    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { model.controller.confirmConnection() }
    private val enableBluetooth = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { model.refreshDevices(true) }
    private val saveHex = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { contentResolver.openOutputStream(uri, "wt")?.use(model.controller::writeHex) ?: error("Файл недоступен") }
                model.controller.notice("HEX-журнал сохранён")
            } catch (e: Exception) { model.controller.notice("Ошибка сохранения HEX: ${e.message}") }
        }
    }
    private var pendingRangeReport:String?=null
    private val saveRangeLog=registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")){uri->
        val id=pendingRangeReport;pendingRangeReport=null
        if(uri!=null && id!=null)lifecycleScope.launch{
            try{withContext(Dispatchers.IO){contentResolver.openOutputStream(uri,"wt")?.use{model.controller.writeRangeReport(id,it)} ?: error("Файл недоступен")};model.controller.notice("Операция сохранена в TXT")}
            catch(e:Exception){model.controller.notice("Ошибка TXT: ${e.message}")}
        }
    }
    private data class ExportRequest(val ids: List<String>, val format: ExportFormat, val separator: Char)
    private var pendingExport: ExportRequest? = null
    private var pendingWalkFile: File? = null
    private val savePdf = registerForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri -> finishExport(uri) }
    private val saveCsv = registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri -> finishExport(uri) }
    private val saveExcel = registerForActivityResult(ActivityResultContracts.CreateDocument(SessionXlsx.MIME)) { uri -> finishExport(uri) }
    private fun writeReport(request: ExportRequest, output: OutputStream) {
        when (request.format) {
            ExportFormat.XLSX -> SessionXlsx.write(model.controller.history, request.ids, output)
            ExportFormat.PDF -> SessionPdf.write(model.controller.history, request.ids.single(), output)
            ExportFormat.CSV -> SessionCsv.write(model.controller.history, request.ids.single(), output, request.separator)
        }
    }
    private fun finishExport(uri: android.net.Uri?) {
        val walkFile=pendingWalkFile;pendingWalkFile=null
        if(walkFile!=null) {
            if(uri!=null) lifecycleScope.launch {
                try { withContext(Dispatchers.IO) { contentResolver.openOutputStream(uri,"wt")?.use { output -> walkFile.inputStream().use { it.copyTo(output) } } ?: error("Файл недоступен") };model.controller.notice("Excel-ведомость сохранена") }
                catch(e:Exception) { model.controller.notice("Ошибка экспорта ведомости: ${e.message}") }
            }
            return
        }
        val request = pendingExport; pendingExport = null
        if (request == null || uri == null) return
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { contentResolver.openOutputStream(uri, "wt")?.use { writeReport(request, it) } ?: error("Файл недоступен") }
                model.controller.notice("Отчёт сохранён")
            } catch (e: Exception) { model.controller.notice("Ошибка экспорта: ${e.message}") }
        }
    }
    private fun export(ids: List<String>, format: ExportFormat, share: Boolean, separator: Char) {
        val request = ExportRequest(ids, format, separator)
        val id = ids.singleOrNull() ?: "devices-${System.currentTimeMillis()}"
        val extension = format.extension
        val suffix = if (format == ExportFormat.CSV) "-delimiter-${if (separator == ';') "semicolon" else "comma"}" else ""
        if (!share) {
            pendingExport = request
            val name = "hart-$id$suffix.$extension"
            when (format) { ExportFormat.XLSX -> saveExcel.launch(name); ExportFormat.PDF -> savePdf.launch(name); ExportFormat.CSV -> saveCsv.launch(name) }
            return
        }
        lifecycleScope.launch {
            try {
                val file = withContext(Dispatchers.IO) {
                    val directory = File(cacheDir, "report-exports").apply { mkdirs() }
                    directory.listFiles()?.sortedByDescending { it.lastModified() }?.drop(5)?.forEach { it.delete() }
                    File(directory, "hart-$id-${System.currentTimeMillis()}$suffix.$extension").also { file -> file.outputStream().use { writeReport(request, it) } }
                }
                val uri = FileProvider.getUriForFile(this@MainActivity, "$packageName.files", file)
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(format.mime)
                    .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Отправить отчёт"))
            } catch (e: Exception) { model.controller.notice("Ошибка экспорта: ${e.message}") }
        }
    }
    private fun exportWalk(book:WalkBook,share:Boolean) { lifecycleScope.launch {
        try {
            val exportStarted=System.nanoTime()
            val file=withContext(Dispatchers.IO) {
                val directory=File(cacheDir,"report-exports").apply { mkdirs() }
                File(directory,"hart-walk-${book.id}-${System.currentTimeMillis()}.xlsx").also { file -> file.outputStream().use { WalkXlsx.write(book,it) } }
            }
            model.controller.append(DiagnosticEntry(java.time.Instant.now(),"PERF","XLSX durationMs=${(System.nanoTime()-exportStarted)/1_000_000}; rows=${book.rows.size}"))
            if(share) {
                val uri=FileProvider.getUriForFile(this@MainActivity,"$packageName.files",file)
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(SessionXlsx.MIME).putExtra(Intent.EXTRA_STREAM,uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),"Отправить ведомость"))
            } else { pendingExport=null;pendingWalkFile=file;saveExcel.launch("hart-walk-${book.id}.xlsx") }
        } catch(e:Exception) { model.controller.notice("Ошибка экспорта ведомости: ${e.message}") }
    } }
    override fun onSaveInstanceState(outState: Bundle) {
        pendingExport?.let { outState.putStringArrayList("exportIds", ArrayList(it.ids)); outState.putString("exportFormat", it.format.name); outState.putInt("exportSeparator", it.separator.code) }
        pendingWalkFile?.let { outState.putString("walkExportFile",it.name) }
        pendingRangeReport?.let{outState.putString("rangeReportId",it)}
        super.onSaveInstanceState(outState)
    }
    private fun shareHex() { lifecycleScope.launch {
        try {
            val file = withContext(Dispatchers.IO) { model.controller.shareHex() }
            val uri = FileProvider.getUriForFile(this@MainActivity, "$packageName.files", file)
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Поделиться HEX-журналом"))
        } catch (e: Exception) { model.controller.notice("Не удалось поделиться журналом: ${e.message}") }
    } }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); enableEdgeToEdge()
        savedInstanceState?.let { saved ->
            saved.getString("walkExportFile")?.takeIf { it==File(it).name && it.startsWith("hart-walk-") && it.endsWith(".xlsx") }?.let { pendingWalkFile=File(cacheDir,"report-exports/$it").takeIf(File::isFile) }
            pendingRangeReport=saved.getString("rangeReportId")
            val ids = saved.getStringArrayList("exportIds") ?: saved.getString("exportId")?.let { arrayListOf(it) }
            if (ids != null) pendingExport = ExportRequest(ids, saved.getString("exportFormat")?.let(ExportFormat::valueOf) ?: if (saved.getBoolean("exportPdf")) ExportFormat.PDF else ExportFormat.CSV, saved.getInt("exportSeparator", 59).toChar())
        }
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                val state by model.state.collectAsStateWithLifecycle()
                HartApp(state, model.controller,
                    permission = { if (Build.VERSION.SDK_INT >= 31) permission.launch(Manifest.permission.BLUETOOTH_CONNECT) else model.refreshDevices(true) },
                    enableBluetooth = { enableBluetooth.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) },
                    pair = { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) },
                    confirm = { if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                        notifications.launch(Manifest.permission.POST_NOTIFICATIONS) else model.controller.confirmConnection() },
                    copy = { lifecycleScope.launch {
                        try {
                            val text = withContext(Dispatchers.IO) { model.controller.diagnosticText() }
                            getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Диагностика HART", text))
                            model.controller.notice("Доступный текст скопирован; полный журнал можно сохранить в TXT")
                        } catch (e: CancellationException) { throw e
                        } catch (e: Exception) { model.controller.notice("Буфер обмена недоступен: ${e.message}. Сохраните TXT.") }
                    } },
                    saveHex = { saveHex.launch("hart-hex-${System.currentTimeMillis()}.txt") }, shareHex = ::shareHex, export = ::export,exportWalk=::exportWalk,exportRange={id->pendingRangeReport=id;saveRangeLog.launch("hart-range-$id.txt")})
            }
        }
    }
    override fun onStart() { super.onStart(); model.controller.activityLifecycle("onStart: Activity видима") }
    override fun onResume() { super.onResume(); model.refreshDevices(true) }
    override fun onStop() { model.controller.activityLifecycle("onStop: Activity в фоне; опрос не останавливается"); super.onStop() }
    override fun onDestroy() { model.controller.activityLifecycle("onDestroy; changingConfiguration=$isChangingConfigurations"); super.onDestroy() }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun HartApp(state: SessionState, c: SessionController, permission: () -> Unit, enableBluetooth: () -> Unit,
                    pair: () -> Unit, confirm: () -> Unit, copy: () -> Unit, saveHex: () -> Unit, shareHex: () -> Unit, export: (List<String>, ExportFormat, Boolean, Char) -> Unit, exportWalk:(WalkBook,Boolean)->Unit,exportRange:(String)->Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var transportKind by rememberSaveable { mutableStateOf(TransportKind.BLUETOOTH) }
    var showOther by rememberSaveable { mutableStateOf(false) }
    var endPrompt by remember { mutableStateOf(false) }
    var archiveId by rememberSaveable { mutableStateOf<String?>(null) }
    var clearHexPrompt by remember { mutableStateOf(false) }
    var switchToWalk by remember { mutableStateOf(false) }
    var walkEnd by remember { mutableStateOf(false) }
    var monitorAfterWalk by remember { mutableStateOf(false) }
    val startWalk:()->Unit = {
        if(state.monitor.startedAt!=null) switchToWalk=true
        else { c.requestWalk();if(!state.active) tab=0 }
    }
    val scroll = remember(tab, archiveId) { ScrollState(0) }
    BackHandler(enabled = !state.rangeVisible && !state.diagnosticVisible && tab == 2 && archiveId != null) { archiveId = null }
    BackHandler(state.diagnosticVisible && !state.rangeVisible){c.closeSensorDiagnostics()}
    BackHandler(state.rangeVisible){c.closeRange()}
    val monitor = state.monitor
    val decision = !state.rangeVisible && !state.diagnosticVisible && monitor.startedAt != null && monitor.phase in setOf(MonitorPhase.DECISION, MonitorPhase.DIFFERENT_DEVICE)
    Scaffold(topBar = { TopAppBar(title = { Text("HART-бро ${BuildConfig.VERSION_NAME}",maxLines=1,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.titleMedium) },
        actions = { BluetoothBadge(state) },
        navigationIcon = { if(state.rangeVisible)TextButton(onClick=c::closeRange,modifier=Modifier.testTag("rangeBack")){Text("К прибору")}
            else if(state.diagnosticVisible)TextButton(onClick=c::closeSensorDiagnostics,modifier=Modifier.testTag("diagnosticsBack")){Text("Назад")}
            else if (tab == 2 && archiveId != null) TextButton(onClick = { archiveId = null }, modifier = Modifier.testTag("archiveBack")) { Text("К списку") } }) },
        bottomBar = {
            if(!state.rangeVisible && !state.diagnosticVisible) {
            if(state.walkActive || tab==4 && state.walk.book?.let { it.endedAt==null }==true) Surface(tonalElevation=3.dp) {
                FlowRow(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal=12.dp,vertical=6.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    Button(onClick=c::saveWalk,enabled=state.walk.book?.rows?.isNotEmpty()==true,modifier=Modifier.testTag("saveWalk")){Text("Сохранить обход")}
                    OutlinedButton(onClick={monitorAfterWalk=false;walkEnd=true},modifier=Modifier.testTag("endWalk")){Text("Завершить обход…")}
                }
            } else if (monitor.startedAt != null) Surface(tonalElevation = 3.dp) {
                FlowRow(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (monitor.phase in setOf(MonitorPhase.MONITORING, MonitorPhase.PAUSED)) Button(onClick = { c.pause(monitor.phase != MonitorPhase.PAUSED) }) { Text(if (monitor.phase == MonitorPhase.PAUSED) "Продолжить" else "Приостановить") }
                        Button(onClick = { c.save() }, enabled = !state.saving) { Text(if (state.saving) "Сохранение…" else "Сохранить сессию") }
                        OutlinedButton(onClick = { endPrompt = true }, enabled = !state.saving, modifier = Modifier.testTag("endSession")) { Text("Завершить…") }
                }
            }
            }
        }) { padding ->
        SensorEditDialog(state,c)
        Column(Modifier.fillMaxSize().padding(padding)) {
            if(state.rangeVisible) {
                Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){RangeScreen(state,c,exportRange)}
            } else if(state.diagnosticVisible) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){SensorDiagnosticsScreen(state,c)}
            } else {
            PrimaryScrollableTabRow(selectedTabIndex = tab, edgePadding = 0.dp) {
                listOf("Связь", "Монитор", "Журнал", "HEX", "Обход").forEachIndexed { i, text -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(text, maxLines = 1) }) }
            }
            // Dispose the report's scroll/fling node before showing a different page.
            key(tab,archiveId) {
            Column(Modifier.fillMaxSize().testTag(if (tab == 2 && archiveId != null) "archiveScroll" else "sectionScroll").verticalScroll(scroll).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if(tab != 1) Text(if(state.walkActive) state.walk.message else monitor.message.takeUnless{it=="Ответ HART получен"} ?: "Мониторинг", style = MaterialTheme.typography.titleMedium)
                state.notice?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                if(state.range.busy)TextButton(onClick=c::showRangeResult){Text("Настройка диапазона: ${state.range.message}")}
                if(state.rangeRecords.any{it.outcome==RangeOutcome.UNKNOWN || it.outcome==RangeOutcome.PENDING})TextButton(onClick=c::showRangeHistory){Text("Есть неподтверждённые операции диапазона — проверить чтением")}
                if (state.diagnosticIncomplete) Text("HEX-журнал неполон: достигнут предел. Сохраните доступный TXT и очистите буфер.", color = MaterialTheme.colorScheme.error)
                if (monitor.phase == MonitorPhase.CONNECTING) LinearProgressIndicator(Modifier.fillMaxWidth())
                if(tab != 1) monitor.recovery?.let { RecoveryPanel(it) }
                if (state.active && !state.walkActive && monitor.phase == MonitorPhase.ERROR && monitor.startedAt == null) {
                    Text("Датчик не идентифицирован")
                    Button(onClick = c::retryIdentification, enabled = !state.rangeOwned, modifier = Modifier.testTag("retryIdentification")) { Text("Повторить опрос датчика") }
                }
                when (tab) {
                    0 -> {
                        Text("Подключение для: ${if(state.requestedMode==ConnectionMode.WALK) "обхода датчиков" else "мониторинга"}")
                        if(!state.active) FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) { ConnectionMode.entries.forEach { mode -> FilterChip(state.requestedMode==mode,{c.chooseMode(mode)},label={Text(if(mode==ConnectionMode.WALK) "Обход датчиков" else "Мониторинг")}) } }
                        TextButton(onClick=c::showRangeHistory){Text("История настройки диапазона")}
                        if(!state.active)Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            FilterChip(transportKind==TransportKind.BLUETOOTH,{transportKind=TransportKind.BLUETOOTH},label={Text("VIATOR Bluetooth")})
                            FilterChip(transportKind==TransportKind.USB,{transportKind=TransportKind.USB},label={Text("ЭнИ-406 USB")},modifier=Modifier.testTag("chooseUsb"))
                        }
                        if((state.communicator?.kind.takeIf{state.active} ?: transportKind)==TransportKind.USB)UsbConnectionScreen(state,c)
                        else {
                        Text("Bluetooth VIATOR", style = MaterialTheme.typography.titleLarge)
                        Text("Цифровые HART-данные; настройка диапазона — по отдельному подтверждению. Давление и температура не вычисляются из 4–20 мА.")
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
                        }
                        if (state.active && !state.walkActive && monitor.startedAt == null) OutlinedButton(onClick = c::disconnect) { Text("Отключить коммуникатор") }
                        Text("В фоне работает видимая служба с уведомлением. Разрешите уведомления, чтобы получать запрос решения после потери связи. Android может остановить процесс; после принудительного завершения автоматического продолжения нет.", style = MaterialTheme.typography.bodySmall)
                    }
                    1 -> {
                        if(state.walkActive) Button(onClick={monitorAfterWalk=true;walkEnd=true}) { Text("Завершить обход и перейти к мониторингу…") }
                        MonitorScreen(state,c)
                    }
                    4 -> WalkScreen(state,c,startWalk,{monitorAfterWalk=false;walkEnd=true},exportWalk)
                    2 -> JournalScreen(state, c, archiveId, { archiveId = it }, export)
                    3 -> {
                        Text("Технический обмен и события сессии", style = MaterialTheme.typography.titleLarge)
                        Text("Полный временный журнал до 16 МиБ. В списке — последние события. Копия содержит адрес коммуникатора, USB-дескрипторы и HART ID.")
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Switch(state.detailedDiagnostics, c::detailedDiagnostics)
                            Text("Подробно: RX-CHUNK")
                        }
                        Button(onClick = saveHex) { Text("Сохранить HEX-журнал") }
                        OutlinedButton(onClick = shareHex) { Text("Поделиться TXT") }
                        OutlinedButton(onClick = copy, enabled = state.diagnostics.isNotEmpty()) { Text("Копировать журнал") }
                        TextButton(onClick = { clearHexPrompt = true }) { Text("Очистить HEX") }
                        SelectionContainer { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            state.diagnostics.asReversed().forEach { Text(it.text(), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall); HorizontalDivider() }
                        } }
                    }
                }
            }
            }
            }
        }
    }
    state.pendingConnection?.let { d -> AlertDialog(onDismissRequest = c::cancelConnectionPrompt,
        title = { Text("Подключить ${d.name}?") }, text = { Text(if(state.requestedMode==ConnectionMode.WALK) "${d.address}\nКоммуникатор будет подключён для обхода. Приборы определяются автоматически; отсутствие щупов означает ожидание следующего датчика. Сохранение ведомости выполняется вручную." else "${d.address}\nБудут открыты ${if(d.kind==TransportKind.USB)"USB-порт" else "Bluetooth-соединение"} и выполнена идентификация датчика. Измерительная сессия запускается отдельно кнопкой «Начать сессию». Восстановление к этому коммуникатору в рамках сессии выполняется без повторного подтверждения.") },
        confirmButton = { TextButton(onClick = { confirm(); tab = if(state.requestedMode==ConnectionMode.WALK) 4 else 1 }) { Text("Подключить") } }, dismissButton = { TextButton(onClick = c::cancelConnectionPrompt) { Text("Отмена") } }) }
    if (decision || endPrompt) AlertDialog(onDismissRequest = { if (!decision) endPrompt = false },
        title = { Text(if (monitor.phase == MonitorPhase.DIFFERENT_DEVICE) "Обнаружен другой прибор" else if (decision) "Связь не восстановлена" else "Завершить сессию?") },
        text = { Text(if (decision) monitor.message else "Сохранить текущий снимок или удалить временную историю? Ранее сохранённые снимки останутся в журнале.") },
        confirmButton = { TextButton(onClick = { c.save(finish = true); endPrompt = false }, enabled = !state.saving && monitor.identity != null) { Text("Сохранить сессию") } },
        dismissButton = { TextButton(onClick = { c.end(); endPrompt = false }, enabled = !state.saving) { Text("Завершить без сохранения") } })
    if(switchToWalk) ActionDialog("Перейти к обходу датчиков?",{switchToWalk=false},"switchWalkDialog",content={
        Text("Текущая измерительная сессия будет завершена. Сохранить её перед переключением?")
    },actions={
        Button(onClick={c.requestWalk(saveSession=true);switchToWalk=false;tab=4},modifier=Modifier.fillMaxWidth()){Text("Сохранить и перейти")}
        OutlinedButton(onClick={c.requestWalk(saveSession=false);switchToWalk=false;tab=4},modifier=Modifier.fillMaxWidth()){Text("Завершить без сохранения")}
        TextButton(onClick={switchToWalk=false},modifier=Modifier.fillMaxWidth()){Text("Отмена")}
    })
    if(walkEnd) ActionDialog("Завершить обход?",{walkEnd=false},"walkEndDialog",content={
        Text("Сохранить текущую ведомость или завершить обход без сохранения.")
    },actions={
        Button(onClick={c.finishWalk(true,monitorAfterWalk);walkEnd=false;if(monitorAfterWalk)tab=1},modifier=Modifier.fillMaxWidth().testTag("walkEndSave")){Text("Сохранить и завершить")}
        OutlinedButton(onClick={c.finishWalk(false,monitorAfterWalk);walkEnd=false;if(monitorAfterWalk)tab=1},modifier=Modifier.fillMaxWidth().testTag("walkEndDiscard")){Text("Завершить без сохранения")}
        TextButton(onClick={walkEnd=false},modifier=Modifier.fillMaxWidth().testTag("walkEndCancel")){Text("Отмена")}
    })
    if (clearHexPrompt) AlertDialog(onDismissRequest = { clearHexPrompt = false }, title = { Text("Очистить временный HEX-журнал?") },
        text = { Text("Сначала сохраните нужные данные в TXT. Сохранённые вами файлы не удаляются.") },
        confirmButton = { TextButton(onClick = { c.clearDiagnostics(); clearHexPrompt = false }) { Text("Очистить") } },
        dismissButton = { TextButton(onClick = { clearHexPrompt = false }) { Text("Отмена") } })
}

internal fun qualityText(q: Quality) = when(q) {
    Quality.GOOD -> "Качество хорошее"; Quality.UNVERIFIED -> "Индивидуальное качество не сообщено"
    Quality.UNCERTAIN -> "Качество неопределённое"; Quality.FIXED -> "Значение зафиксировано"
    Quality.BAD -> "Недостоверно"; Quality.ABSENT -> "Не поддерживается"
}

@Composable
private fun RecoveryPanel(progress: RecoveryProgress) {
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Переподключение · ${(progress.remainingMillis+999)/1000} с", style = MaterialTheme.typography.titleMedium)
        Text("Причина: ${when(progress.reason) { LossReason.BLUETOOTH -> "потеря Bluetooth"; LossReason.USB -> "отключение USB"; LossReason.HART -> "нет ответа HART"; LossReason.UNKNOWN -> "неизвестно" }}")
        Text("Попытка ${progress.attempt}: ${progress.lastAttempt}", style = MaterialTheme.typography.bodySmall)
    } }
}
