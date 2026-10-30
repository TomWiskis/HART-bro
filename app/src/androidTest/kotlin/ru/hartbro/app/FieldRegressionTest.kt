package ru.hartbro.app

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.SystemClock
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
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import ru.hartbro.app.session.*
import ru.hartbro.core.*

/** Instrumentation-only transport. These values are never part of the shipped application. */
class FieldRegressionApplication : HartApplication() {
    val samples = AtomicInteger()
    override fun createController() = SessionController(this) {
        object : MonitorConnection {
            override suspend fun identify() = DeviceIdentity.parse("FE 99 72 05 07 07 28 08 00 5F CA 7F 05 09 00 2C 00 00 99 00 99 01".split(" ").map { it.toInt(16).toByte() }.toByteArray())
            override suspend fun discover() = sample()
            override suspend fun sample(): MeasurementBatch {
                samples.incrementAndGet(); val now = Instant.now()
                return MeasurementBatch(listOf(Measurement("PV", 1.25f, 12, Quality.GOOD, now, 9)), 0, now)
            }
            override fun close() {}
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
        val deadline = SystemClock.elapsedRealtime() + 15000
        while (!check() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100)
        assertTrue("Condition not reached before deadline", check())
    }
    private fun start(scenario: ActivityScenario<MainActivity>) {
        scenario.onActivity {
            app.startForegroundService(Intent(app, MonitoringService::class.java).putExtra("address", "00:11:22:33:44:55").putExtra("name", "Instrumentation-only"))
        }
        waitFor { app.controller.state.value.monitor.latest != null }
        app.controller.period(1000)
    }
    @After fun stop() { app.controller.end(); waitFor { !app.controller.state.value.active } }

    @Test fun homeScreenLockAndActivityRecreationKeepOneForegroundSession() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            start(scenario)
            val started = app.controller.state.value.monitor.startedAt
            val key = app.controller.state.value.monitor.identity!!.uniqueKey
            var previous = app.samples.get()
            device.pressHome()
            val deadline = SystemClock.elapsedRealtime() + 120000 // two actual minutes, not simulated Android time
            while (SystemClock.elapsedRealtime() < deadline) {
                SystemClock.sleep(5000)
                assertTrue(app.samples.get() > previous); previous = app.samples.get()
                assertTrue(app.controller.state.value.serviceRunning)
                assertEquals(started, app.controller.state.value.monitor.startedAt)
            }
            device.sleep(); SystemClock.sleep(10000)
            assertTrue(app.samples.get() > previous)
            device.wakeUp(); device.executeShellCommand("wm dismiss-keyguard")
            scenario.onActivity { app.startActivity(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)) }
            waitFor { app.controller.state.value.monitor.latest != null }
            scenario.recreate()
            assertEquals(started, app.controller.state.value.monitor.startedAt)
            assertEquals(key, app.controller.state.value.monitor.identity!!.uniqueKey)
            // Regression: empty service deliveries previously stopped an active session.
            scenario.onActivity { app.startService(Intent(app, MonitoringService::class.java)) }
            SystemClock.sleep(1500)
            assertTrue(app.controller.state.value.active)
            assertTrue(app.getSystemService(NotificationManager::class.java).activeNotifications.any { it.id == 20 })
            assertTrue(app.controller.diagnosticText().contains("onStop: Activity в фоне"))
            val bounds = app.controller.history.bounds()!!
            assertTrue(bounds.second - bounds.first >= 120000)
        }
    }
    @Test fun archiveBackRemainsVisibleAfterScrollingAndSystemBackReturnsToList() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            start(scenario)
            repeat(2) {
                app.controller.save()
                waitFor { !app.controller.state.value.saving }
                SystemClock.sleep(1200)
            }
            ui.onNodeWithText("Журнал", useUnmergedTree = true).performClick()
            ui.waitUntil(15000) { ui.onAllNodesWithText("Открыть").fetchSemanticsNodes().size >= 2 }
            ui.onAllNodesWithText("Открыть")[0].performScrollTo().performClick()
            ui.onNodeWithTag("archiveScroll").performTouchInput { repeat(4) { swipeUp() } }
            ui.onNodeWithTag("archiveBack").assertIsDisplayed().performClick()
            ui.onAllNodesWithText("Открыть")[1].performScrollTo().performClick()
            ui.onNodeWithTag("archiveScroll").performTouchInput { swipeUp() }
            device.pressBack()
            ui.onNodeWithText("Сохранённые сессии").assertIsDisplayed()
            assertTrue(app.controller.state.value.active)
        }
    }
}
