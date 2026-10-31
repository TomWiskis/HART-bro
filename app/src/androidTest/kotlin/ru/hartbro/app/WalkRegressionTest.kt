package ru.hartbro.app

import android.Manifest
import android.content.Intent
import android.os.SystemClock
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.uiautomator.UiDevice
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import ru.hartbro.app.session.*
import ru.hartbro.core.*

@RunWith(AndroidJUnit4::class)
class WalkRegressionTest {
    @get:Rule val ui=createEmptyComposeRule()
    @get:Rule val permissions:GrantPermissionRule=GrantPermissionRule.grant(Manifest.permission.BLUETOOTH_CONNECT,Manifest.permission.POST_NOTIFICATIONS)
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val app get()=instrumentation.targetContext.applicationContext as FieldRegressionApplication
    private val c get()=app.controller
    private val device get()=UiDevice.getInstance(instrumentation)
    private fun waitFor(check:()->Boolean) {
        val deadline=SystemClock.elapsedRealtime()+20000
        while(!check() && SystemClock.elapsedRealtime()<deadline) SystemClock.sleep(100)
        assertTrue("Walk condition timed out: ${c.state.value.walk}; monitor=${c.state.value.monitor.phase}",check())
    }
    @Before fun reset() {
        device.wakeUp();device.executeShellCommand("wm dismiss-keyguard")
        app.failIdentity=false;app.identifyDelay=0;app.hartDeviceId=0x5FCA7F;app.hartFailures.set(0);app.bluetoothFailures.set(0);app.walkBluetoothFailures.set(0)
        c.finishWalk(false);waitFor{!c.state.value.walkActive && !c.state.value.serviceRunning}
        c.state.value.savedWalks.forEach{c.deleteWalk(it.id)};waitFor{c.state.value.savedWalks.isEmpty()}
    }
    @After fun stop() {
        device.wakeUp();device.executeShellCommand("wm dismiss-keyguard")
        app.failIdentity=false;app.walkBluetoothFailures.set(0)
        if(c.state.value.walkActive || c.state.value.walk.book!=null) {c.finishWalk(false);waitFor{!c.state.value.walkActive}}
        c.end();waitFor{c.state.value.monitor.startedAt==null};c.disconnect();waitFor{!c.state.value.active && !c.state.value.serviceRunning}
        c.discardWalkDraft()
    }
    private fun connect(scenario:ActivityScenario<MainActivity>,walk:Boolean) {
        scenario.onActivity { app.startForegroundService(Intent(app,MonitoringService::class.java).putExtra("address","00:11:22:33:44:55").putExtra("name","Instrumentation-only").putExtra("mode",if(walk)ConnectionMode.WALK.name else ConnectionMode.MONITOR.name)) }
        waitFor{if(walk)c.state.value.walkActive else c.state.value.monitor.phase==MonitorPhase.READY}
    }
    private fun returnToApp() {device.executeShellCommand("am start -W -n ${app.packageName}/.MainActivity");waitFor{device.currentPackageName==app.packageName}}
    @Test fun walkAWaitBReturnHomeAndRecreateKeepOneConnectionAndManualPassport() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            connect(scenario,false);val connects=app.connections.get();val samples=app.samples.get()
            c.requestWalk();waitFor{c.state.value.walk.book?.rows?.size==1}
            assertEquals(connects,app.connections.get());assertNull(c.state.value.monitor.startedAt)
            val row=c.state.value.walk.book!!.rows.single();c.editWalk(row.id,mapOf(PassportField.SERIAL to "00042",PassportField.DATE to "07.2025"))
            waitFor{c.state.value.walk.book!!.rows.single().text(PassportField.SERIAL)=="00042"}
            app.failIdentity=true;waitFor{c.state.value.walk.phase==WalkPhase.WAITING}
            assertTrue(c.state.value.walk.bluetoothConnected);assertNull(c.state.value.walk.recovery)
            // Two missing probes explicitly delimit a new physical attachment.
            SystemClock.sleep(2200);app.hartDeviceId=2;app.failIdentity=false;waitFor{c.state.value.walk.book!!.rows.size==2}
            device.pressHome();SystemClock.sleep(2100)
            device.sleep();assertFalse(device.isScreenOn)
            val lockedReads=app.walkPassportReads.get();c.rereadWalk();waitFor{app.walkPassportReads.get()>lockedReads}
            device.wakeUp();device.executeShellCommand("wm dismiss-keyguard");returnToApp();scenario.recreate()
            ui.onNodeWithText("Обход",useUnmergedTree=true).performScrollTo().performClick()
            ui.onNodeWithTag("walkCount").assertExists();ui.onNodeWithTag("endWalk").assertExists()
            val reads=app.walkPassportReads.get();app.hartDeviceId=0x5FCA7F;waitFor{app.walkPassportReads.get()>reads}
            assertEquals(2,c.state.value.walk.book!!.rows.size);assertEquals("00042",c.state.value.walk.book!!.rows.first().text(PassportField.SERIAL))
            assertEquals(samples,app.samples.get());assertEquals(connects,app.connections.get());assertNull(c.history.bounds())
            val review=java.io.File(app.getExternalFilesDir(null),"export-review").apply{mkdirs()}
            repeat(2){java.io.File(review,"walk-active-test-$it.xlsx").outputStream().use{WalkXlsx.write(c.state.value.walk.book!!,it)}}
            assertTrue(c.state.value.walkActive);assertTrue(c.state.value.serviceRunning)
        }
    }
    @Test fun walkCanStartWithoutSensorAndRecoversBluetoothForAnyNextSensor() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            app.failIdentity=true;connect(scenario,true);waitFor{c.state.value.walk.phase==WalkPhase.WAITING}
            assertTrue(c.state.value.walk.book!!.rows.isEmpty());assertNull(c.state.value.walk.recovery)
            app.failIdentity=false;waitFor{c.state.value.walk.book!!.rows.size==1}
            val connects=app.connections.get();app.walkBluetoothFailures.set(1);app.hartDeviceId=3
            waitFor{app.connections.get()>connects && c.state.value.walk.book!!.rows.size==2}
            assertNull(c.state.value.monitor.startedAt);assertFalse(c.state.value.walk.book!!.rows.first().fields[PassportField.SERIAL]?.factoryConfirmed==true)
            c.beginSession();SystemClock.sleep(300);assertNull(c.state.value.monitor.startedAt)
            c.finishWalk(true,monitorNext=true);waitFor{c.state.value.monitor.phase==MonitorPhase.READY && !c.state.value.walkActive && c.state.value.savedWalks.size==1}
            assertEquals(2,c.state.value.savedWalks.single().rows.size);assertNull(c.state.value.monitor.startedAt)
            c.beginSession();waitFor{c.state.value.monitor.latest!=null};assertFalse(c.state.value.walkActive)
        }
    }
    @Test fun stoppedWalkKeepsDraftAndNewControllerRestoresItWithoutHiddenServiceRestart() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            connect(scenario,true);waitFor{c.state.value.walk.book?.rows?.size==1}
            val row=c.state.value.walk.book!!.rows.single();c.editWalk(row.id,mapOf(PassportField.SERIAL to "0007",PassportField.DATE to "2025"))
            waitFor{c.state.value.walk.book!!.rows.single().text(PassportField.SERIAL)=="0007"};c.saveWalk();waitFor{c.state.value.savedWalks.size==1}
            val currentSavedId=c.state.value.savedWalks.single().id
            c.editWalk(row.id,mapOf(PassportField.MODEL to "Марка активной сохранённой ведомости"),currentSavedId)
            waitFor{c.state.value.walk.book!!.rows.single().text(PassportField.MODEL)=="Марка активной сохранённой ведомости"}
            val reads=app.walkPassportReads.get();c.rereadWalk();waitFor{app.walkPassportReads.get()>reads}
            assertEquals("Марка активной сохранённой ведомости",c.state.value.walk.book!!.rows.single().text(PassportField.MODEL))
            scenario.onActivity{app.stopService(Intent(app,MonitoringService::class.java))};waitFor{!c.state.value.walkActive && !c.state.value.serviceRunning}
            assertEquals(WalkPhase.INTERRUPTED,c.state.value.walk.phase);val connects=app.connections.get()
            val restored=SessionController(app);waitFor{restored.state.value.walkDraftAvailable};assertFalse(restored.state.value.active)
            restored.restoreWalkDraft();waitFor{restored.state.value.walk.book!=null}
            val recovered=restored.state.value.walk.book!!.rows.single();assertEquals("0007",recovered.text(PassportField.SERIAL));assertEquals(DatePrecision.YEAR,recovered.fields[PassportField.DATE]!!.datePrecision)
            assertEquals(connects,app.connections.get());assertFalse(restored.state.value.serviceRunning);assertEquals(1,restored.walkStore.list().size)
            val savedId=c.state.value.savedWalks.single().id;c.editWalk(row.id,mapOf(PassportField.MODEL to "Марка со шильдика"),savedId)
            waitFor{c.state.value.savedWalks.single().rows.single().text(PassportField.MODEL)=="Марка со шильдика"}
        }
    }
}
