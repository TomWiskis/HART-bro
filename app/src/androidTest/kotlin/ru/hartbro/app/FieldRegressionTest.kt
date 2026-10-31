package ru.hartbro.app

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.runner.AndroidJUnitRunner
import androidx.test.uiautomator.UiDevice
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.IOException
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import ru.hartbro.app.session.*
import ru.hartbro.core.*

/** Instrumentation-only transport; never packaged in the production APK. */
class FieldRegressionApplication : HartApplication() {
    val walkPassportReads=AtomicInteger(); val walkBluetoothFailures=AtomicInteger(); val samples = AtomicInteger(); val connections = AtomicInteger(); val hartFailures = AtomicInteger(); val bluetoothFailures = AtomicInteger()
    val rangeWrites=AtomicInteger()
    @Volatile var rangeActual=ConfiguredRange(0f,40f,12)
    @Volatile var rangeMode=0
    @Volatile var rangeAckDelay=0L
    @Volatile var rangeHoldAck=false
    @Volatile var rangeTimeout=300L
    @Volatile var hartDeviceId = 0x5FCA7F
    @Volatile var identifyDelay = 0L
    @Volatile var failIdentity = false
    override fun createController() = SessionController(this) {
        connections.incrementAndGet()
        object : MonitorConnection {
            private var open = true
            private val rangeWire=RangeTransportFixture(this@FieldRegressionApplication)
            private val rangeOperations by lazy{RangeOperations(HartClient(rangeWire,controller::append,timeoutMillis=rangeTimeout,readAttempts=1))}
            override val isOpen get() = open && rangeWire.open
            override suspend fun rangeSnapshot()=rangeOperations.prepare()
            override suspend fun applyRange(prepared:RangeSnapshot,requested:ConfiguredRange,beforeSend:(ByteArray)->Unit,onReply:(HartExchange)->Unit,onInvalidRx:(ByteArray)->Unit)=rangeOperations.apply(prepared,requested,beforeSend,onReply,onInvalidRx)
            override suspend fun verifyRange(expected:DeviceIdentity)=rangeOperations.verify(expected)
            override suspend fun identify(): DeviceIdentity {
                delay(identifyDelay)
                if(walkBluetoothFailures.getAndUpdate { (it-1).coerceAtLeast(0) }>0) throw IOException("Instrumentation: walk RFCOMM lost")
                if (failIdentity) throw HartTimeoutException("Instrumentation: initial identification timeout")
                val raw="FE 99 72 05 07 07 28 08 00 5F CA 7F 05 09 00 2C 00 00 99 00 99 01".split(" ").map { it.toInt(16).toByte() }.toByteArray()
                raw[9]=(hartDeviceId shr 16).toByte();raw[10]=(hartDeviceId shr 8).toByte();raw[11]=hartDeviceId.toByte()
                return DeviceIdentity.parse(raw)
            }
            override suspend fun passport(identity:DeviceIdentity):PassportReading {
                walkPassportReads.incrementAndGet();val at=Instant.now();val range=rangeActual
                return PassportReading(identity,mapOf(PassportField.TAG to PassportFact("TEST-${identity.deviceId}",FactSource.STANDARD_HART,20,"54 45 53 54",at),
                    PassportField.MODEL to PassportFact("ТЕСТ модель",FactSource.STANDARD_HART,0,identity.raw.hex(),at),
                    PassportField.RANGE to PassportFact(range.label,FactSource.STANDARD_HART,15,"00",at)),
                    mapOf("sensorNumber" to PassportFact("1234",FactSource.STANDARD_HART,14,"00 04 D2",at),"assemblyNumber" to PassportFact("5678",FactSource.STANDARD_HART,16,"00 16 2E",at)),range,at)
            }
            override suspend fun configuration() = ConfigurationSnapshot(mapOf("tag" to ConfigValue("TEST-METRAN", 13),
                "rangeUnit" to ConfigValue("12", 15, 12), "lowerRange" to ConfigValue(rangeActual.lower.toString(),15,12),"upperRange" to ConfigValue(rangeActual.upper.toString(),15,12)))
            override suspend fun discover() = sample()
            override suspend fun sample(): MeasurementBatch {
                if (bluetoothFailures.getAndUpdate { (it - 1).coerceAtLeast(0) } > 0) throw IOException("Instrumentation: RFCOMM lost")
                if (hartFailures.getAndUpdate { (it - 1).coerceAtLeast(0) } > 0) throw HartTimeoutException("Instrumentation: no HART reply")
                samples.incrementAndGet(); val now = Instant.now()
                return MeasurementBatch(listOf(Measurement("PV", 1.25f, 12, Quality.GOOD, now, 9)), 0, now)
            }
            override fun close() { open = false;rangeWire.close() }
        }
    }
}
class FieldRegressionRunner : AndroidJUnitRunner() {
    override fun newApplication(cl: ClassLoader, className: String, context: Context): Application =
        super.newApplication(cl, FieldRegressionApplication::class.java.name, context)
}

@RunWith(AndroidJUnit4::class)
class FieldRegressionTest {
    @get:Rule val ui = createEmptyComposeRule()
    @get:Rule val permission: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.POST_NOTIFICATIONS)
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as FieldRegressionApplication
    private val device get() = UiDevice.getInstance(instrumentation)
    private fun waitFor(check: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 20000
        while (!check() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100)
        assertTrue("Condition not reached before deadline: ${app.controller.state.value.monitor}", check())
    }
    private var previousScreenTimeout = ""
    @Before fun reset() {
        previousScreenTimeout = device.executeShellCommand("settings get system screen_off_timeout").trim()
        device.executeShellCommand("settings put system screen_off_timeout 1200000")
        device.wakeUp(); device.executeShellCommand("wm dismiss-keyguard")
        app.rangeActual=ConfiguredRange(0f,40f,12);app.rangeMode=0;app.rangeAckDelay=0;app.rangeTimeout=300;app.walkBluetoothFailures.set(0); app.hartDeviceId = 0x5FCA7F; app.identifyDelay = 0; app.failIdentity = false; app.hartFailures.set(0); app.bluetoothFailures.set(0)
        app.controller.deleteMany(app.controller.state.value.archives.map { it.first }.toSet())
        waitFor { app.controller.state.value.archives.isEmpty() }
    }
    private fun connect(scenario: ActivityScenario<MainActivity>) {
        scenario.onActivity { app.startForegroundService(Intent(app, MonitoringService::class.java).putExtra("address", "00:11:22:33:44:55").putExtra("name", "Instrumentation-only")) }
        waitFor { app.controller.state.value.monitor.phase == MonitorPhase.READY }
        assertNull(app.controller.history.bounds()); assertNull(app.controller.state.value.monitor.startedAt)
    }
    private fun start(scenario: ActivityScenario<MainActivity>) {
        connect(scenario); app.controller.beginSession()
        waitFor { app.controller.state.value.monitor.latest != null }; app.controller.period(1000)
    }
    @After fun stop() {
        device.wakeUp(); device.executeShellCommand("wm dismiss-keyguard")
        app.controller.end(); waitFor { app.controller.state.value.monitor.startedAt == null }
        app.controller.disconnect(); waitFor { !app.controller.state.value.active && !app.controller.state.value.serviceRunning }
        if (previousScreenTimeout.toLongOrNull() != null) device.executeShellCommand("settings put system screen_off_timeout $previousScreenTimeout")
    }
    private fun returnToApp() {
        device.executeShellCommand("am start -W -n ${app.packageName}/.MainActivity")
        waitFor { device.currentPackageName == app.packageName }
    }
    private fun observeFor(duration: Long, started: Instant?, key: String, screenOn: Boolean, foregroundPackage: String? = null) {
        var previous = app.samples.get(); var end = app.controller.history.bounds()!!.second
        val deadline = SystemClock.elapsedRealtime() + duration
        while (SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(5000)
            assertEquals("Unexpected screen power state", screenOn, device.isScreenOn)
            if (foregroundPackage != null) assertEquals("Expected application must actually be foreground", foregroundPackage, device.currentPackageName)
            assertTrue("No samples while Android lifecycle changed", app.samples.get() > previous); previous = app.samples.get()
            assertTrue("History did not advance", app.controller.history.bounds()!!.second > end); end = app.controller.history.bounds()!!.second
            assertTrue(app.controller.state.value.serviceRunning)
            assertEquals(started, app.controller.state.value.monitor.startedAt)
            assertEquals(key, app.controller.state.value.monitor.identity!!.uniqueKey)
        }
    }
    @Test fun homeScreenLockAndActivityRecreationKeepOneForegroundSession() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            start(scenario)
            val started = app.controller.state.value.monitor.startedAt; val key = app.controller.state.value.monitor.identity!!.uniqueKey
            observeFor(300000, started, key, true, app.packageName) // five actual minutes foreground
            device.sleep(); observeFor(300000, started, key, false) // five actual minutes screen off
            device.wakeUp(); device.executeShellCommand("wm dismiss-keyguard")
            scenario.onActivity { app.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            waitFor { device.currentPackageName == "com.android.settings" }
            observeFor(300000, started, key, true, "com.android.settings") // five actual minutes with Settings in foreground
            repeat(3) {
                device.pressHome(); SystemClock.sleep(1200)
                returnToApp(); SystemClock.sleep(1200)
            }
            scenario.recreate()
            assertEquals(androidx.lifecycle.Lifecycle.State.RESUMED, scenario.state)
            assertEquals(started, app.controller.state.value.monitor.startedAt)
            scenario.onActivity { app.startService(Intent(app, MonitoringService::class.java)) }
            SystemClock.sleep(1500); assertTrue(app.controller.state.value.active)
            assertTrue(app.getSystemService(NotificationManager::class.java).activeNotifications.any { it.id == 20 })
            val diagnostics = app.controller.diagnosticText()
            assertTrue(diagnostics.contains("onStop: Activity в фоне")); assertTrue(diagnostics.contains("lastTX=")); assertTrue(diagnostics.contains("lastMeasurement="))
            assertTrue(app.controller.history.bounds()!!.second - app.controller.history.bounds()!!.first >= 900000)
        }
    }
    @Test fun archiveBackRemainsVisibleAfterScrollingAndSystemBackReturnsToList() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            start(scenario)
            repeat(2) { app.controller.save(); waitFor { !app.controller.state.value.saving }; SystemClock.sleep(1200) }
            ui.onNodeWithText("Журнал", useUnmergedTree = true).performClick()
            ui.waitUntil(15000) { ui.onAllNodesWithText("Открыть").fetchSemanticsNodes().size >= 2 }
            ui.onAllNodesWithText("Открыть")[0].performScrollTo().performClick()
            ui.onNodeWithTag("archiveScroll").performTouchInput { repeat(4) { swipeUp() } }
            ui.onNodeWithTag("endSession").assertIsDisplayed()
            ui.onNodeWithTag("archiveBack").assertIsDisplayed().performClick()
            ui.onAllNodesWithText("Открыть")[1].performScrollTo().performClick()
            ui.onNodeWithTag("archiveScroll").performTouchInput { swipeUp() }; device.pressBack()
            ui.onNodeWithText("Сохранённые сессии").assertIsDisplayed()
            assertTrue(app.controller.state.value.active)
        }
    }
    @Test fun explicitSessionStartPauseSaveFinishAndNewDeviceReuseBluetooth() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            connect(scenario); val connections = app.connections.get()
            ui.onNodeWithText("Монитор", useUnmergedTree = true).performClick()
            ui.onNodeWithTag("startSession").assertIsDisplayed().performClick()
            waitFor { app.controller.state.value.monitor.latest != null }
            app.controller.pause(true); waitFor { app.controller.state.value.monitor.phase == MonitorPhase.PAUSED }
            val end = app.controller.history.bounds()!!.second; SystemClock.sleep(2200)
            assertEquals(end, app.controller.history.bounds()!!.second)
            app.controller.pause(false); waitFor { app.controller.state.value.monitor.latest != null }
            app.controller.save(); waitFor { !app.controller.state.value.saving && app.controller.state.value.archives.size == 1 }
            val saved = app.controller.state.value.archives.single().second
            assertEquals("TEST-METRAN", saved.configuration.tag)
            app.controller.end(); waitFor { app.controller.state.value.monitor.phase == MonitorPhase.READY }
            assertNull(app.controller.history.bounds()); assertEquals(connections, app.connections.get())
            app.hartDeviceId = 1; app.controller.beginSession(); waitFor { app.controller.state.value.monitor.latest != null }
            assertEquals("9972-000001", app.controller.history.view()!!.info.deviceKey)
            assertEquals(connections, app.connections.get()); assertNotEquals(saved.deviceKey, app.controller.state.value.monitor.identity!!.uniqueKey)
        }
    }
    @Test fun initialIdentificationFailureWhileActivityIsBackgroundedDoesNotCreateSession() {
        app.identifyDelay = 3000; app.failIdentity = true
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { app.startForegroundService(Intent(app, MonitoringService::class.java).putExtra("address", "00:11:22:33:44:55")) }
            waitFor { app.controller.state.value.monitor.phase == MonitorPhase.CONNECTING }; device.pressHome()
            waitFor { app.controller.state.value.monitor.phase == MonitorPhase.ERROR }
            assertNull(app.controller.history.bounds()); assertNull(app.controller.state.value.monitor.startedAt)
            assertTrue(app.controller.diagnosticText().contains("initial identification timeout"))
            returnToApp()
            assertEquals(androidx.lifecycle.Lifecycle.State.RESUMED, scenario.state)
            assertEquals(MonitorPhase.ERROR, app.controller.state.value.monitor.phase)
        }
    }
    @Test fun initialConnectionWhileActivityIsBackgroundedRemainsReadyWithoutSession() {
        app.identifyDelay = 3000
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val connects = app.connections.get(); val samples = app.samples.get()
            scenario.onActivity { app.startForegroundService(Intent(app, MonitoringService::class.java).putExtra("address", "00:11:22:33:44:55")) }
            waitFor { app.controller.state.value.monitor.phase == MonitorPhase.CONNECTING }; device.pressHome()
            waitFor { app.controller.state.value.monitor.phase == MonitorPhase.READY }
            SystemClock.sleep(2000)
            assertEquals(samples, app.samples.get()); assertEquals(connects + 1, app.connections.get())
            assertNull(app.controller.history.bounds()); assertNull(app.controller.state.value.monitor.startedAt)
            returnToApp()
            assertEquals(androidx.lifecycle.Lifecycle.State.RESUMED, scenario.state)
            assertEquals(MonitorPhase.READY, app.controller.state.value.monitor.phase)
        }
    }
    @Test fun journalRenameCommentSearchComparisonAndConfirmedMultiDelete() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            start(scenario)
            repeat(2) { app.controller.save(); waitFor { !app.controller.state.value.saving }; SystemClock.sleep(500) }
            waitFor { app.controller.state.value.archives.size == 2 }
            ui.onNodeWithText("Журнал", useUnmergedTree = true).performClick()
            ui.onAllNodesWithText("Изменить")[0].performScrollTo().performClick()
            ui.onNodeWithText("Название").performTextInput("Проверка А")
            ui.onNodeWithText("Комментарий").performTextInput("Комментарий цеха")
            ui.onNodeWithText("Сохранить").performClick()
            waitFor { app.controller.state.value.archives.any { it.second.title == "Проверка А" && it.second.comment == "Комментарий цеха" } }
            ui.onNodeWithText("Поиск: TAG, ID, название").performScrollTo().performTextInput("Проверка А")
            ui.waitUntil(10000) { ui.onAllNodesWithText("Открыть").fetchSemanticsNodes().size == 1 }
            ui.onNodeWithText("Поиск: TAG, ID, название").performTextClearance()
            app.controller.state.value.archives.forEach { (id, _) -> ui.onNodeWithTag("select-$id").performScrollTo().performClick() }
            ui.onNodeWithText("Сравнить две").performScrollTo().performClick()
            ui.onNodeWithText("Сравнение конфигураций").assertIsDisplayed()
            ui.onNodeWithText("Закрыть").performClick()
            ui.onNodeWithText("Удалить выбранные…").performScrollTo().performClick()
            ui.onNodeWithText("Отмена").performClick(); assertEquals(2, app.controller.state.value.archives.size)
            ui.onNodeWithText("Удалить выбранные…").performScrollTo().performClick()
            ui.onNodeWithText("Удалить").performClick()
            waitFor { app.controller.state.value.archives.isEmpty() }
            assertNotNull(app.controller.state.value.monitor.startedAt)
        }
    }
    @Test fun temporaryHartAndBluetoothLossRecoverWithoutDuplicateSessions() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            start(scenario); val started = app.controller.state.value.monitor.startedAt; val connects = app.connections.get()
            var samples = app.samples.get(); app.hartFailures.set(3)
            waitFor { app.hartFailures.get() == 0 && app.samples.get() > samples }
            assertEquals(connects, app.connections.get()); assertEquals(started, app.controller.state.value.monitor.startedAt)
            samples = app.samples.get(); app.bluetoothFailures.set(1)
            waitFor { app.bluetoothFailures.get() == 0 && app.samples.get() > samples }
            assertEquals(connects + 1, app.connections.get()); assertEquals(started, app.controller.state.value.monitor.startedAt)
            assertTrue(app.controller.diagnosticText().contains("Связь восстановлена; идентификатор проверен"))
            assertTrue(app.controller.history.view()!!.events.any { it.second.startsWith("Связь потеряна") })
        }
    }
    @Test fun serviceDestructionKeepsTemporaryHistoryUntilExplicitUserDecision() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            start(scenario); val bounds = app.controller.history.bounds()
            scenario.onActivity { app.stopService(Intent(app, MonitoringService::class.java)) }
            waitFor { !app.controller.state.value.serviceRunning && app.controller.state.value.monitor.phase == MonitorPhase.DECISION }
            assertNotNull(app.controller.history.bounds()); assertEquals(bounds!!.first, app.controller.history.bounds()!!.first)
            assertTrue(app.controller.state.value.archives.isEmpty())
            app.controller.save(true); waitFor { app.controller.state.value.monitor.startedAt == null && app.controller.state.value.archives.size == 1 }
        }
    }
}
