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
class RangeRegressionTest {
    @get:Rule val ui=createEmptyComposeRule()
    @get:Rule val permissions:GrantPermissionRule=GrantPermissionRule.grant(Manifest.permission.BLUETOOTH_CONNECT,Manifest.permission.POST_NOTIFICATIONS)
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val app get()=instrumentation.targetContext.applicationContext as FieldRegressionApplication
    private val c get()=app.controller
    private val device get()=UiDevice.getInstance(instrumentation)
    private var font="1.0"
    private fun waitFor(check:()->Boolean) {
        val deadline=SystemClock.elapsedRealtime()+25000
        while(!check() && SystemClock.elapsedRealtime()<deadline)SystemClock.sleep(100)
        assertTrue("Range condition timed out: ${c.state.value.range}; monitor=${c.state.value.monitor.phase}",check())
    }
    @Before fun reset() {
        device.wakeUp();device.executeShellCommand("wm dismiss-keyguard")
        font=device.executeShellCommand("settings get system font_scale").trim()
        app.stopRegressionOwners()
        app.failIdentity=false;app.identifyDelay=0;app.hartDeviceId=0x5FCA7F;app.hartFailures.set(0);app.bluetoothFailures.set(0);app.walkBluetoothFailures.set(0)
        app.rangeActual=ConfiguredRange(0f,16f,12);app.rangeMode=0;app.rangeAckDelay=0;app.rangeHoldAck=false;app.rangeTimeout=300;app.rangeWrites.set(0)
        c.refreshRangeRecords()
    }
    @After fun cleanup() {
        device.wakeUp();device.executeShellCommand("wm dismiss-keyguard");app.rangeMode=0;app.rangeAckDelay=0;app.rangeHoldAck=false
        app.stopRegressionOwners()
        java.io.File(app.filesDir,"range-operations").deleteRecursively();c.refreshRangeRecords();waitFor{c.state.value.rangeRecords.isEmpty()}
        if(font.toFloatOrNull()!=null)device.executeShellCommand("settings put system font_scale $font") else device.executeShellCommand("settings delete system font_scale")
        device.executeShellCommand("wm size reset");device.executeShellCommand("wm density reset")
        instrumentation.waitForIdleSync()
    }
    private fun connect(scenario:ActivityScenario<MainActivity>,walk:Boolean=false) {
        scenario.onActivity{app.startForegroundService(Intent(app,MonitoringService::class.java).putExtra("address","00:11:22:33:44:55").putExtra("name","Instrumentation-only").putExtra("mode",if(walk)ConnectionMode.WALK.name else ConnectionMode.MONITOR.name))}
        waitFor{if(walk)c.state.value.walk.book?.rows?.size==1 && c.state.value.walkActive else c.state.value.monitor.phase==MonitorPhase.READY}
        ui.waitUntil(15000){runCatching{ui.onAllNodesWithText("Связь",useUnmergedTree=true).fetchSemanticsNodes().isNotEmpty()}.getOrDefault(false)}
    }
    private fun openForm(action:()->Unit) {
        val previous=c.state.value.range.token
        action()
        waitFor{c.state.value.let{it.rangeOwned && it.range.phase==RangePhase.FORM && it.range.token!=previous}}
    }
    private fun verifyRecord(id:String) {
        val previous=c.state.value.range.token
        c.openRange(id)
        waitFor{c.state.value.let{it.range.token!=previous && it.range.record?.id==id && it.range.record?.outcome==RangeOutcome.SUCCEEDED && !it.rangeOwned}}
    }
    private fun open(scenario:ActivityScenario<MainActivity>,session:Boolean=false) {
        connect(scenario)
        if(session){c.beginSession();waitFor{c.state.value.monitor.latest!=null};c.period(1000)}
        ui.onNodeWithText("Монитор",useUnmergedTree=true).performScrollTo().performClick()
        openForm{ui.onNodeWithTag("openRange").performScrollTo().performClick()}
    }
    private fun chooseTen(){ui.onNodeWithTag("rangeUpper").performScrollTo().performTextReplacement("10");ui.onNodeWithTag("rangeSet").performScrollTo().performClick();waitFor{c.state.value.range.phase==RangePhase.CONFIRM}}
    @Test fun confirmedWriteSurvivesHomeScreenLockAndRecreateWithoutRepeatAndResumesSameSession() {
        ActivityScenario.launch(MainActivity::class.java).use {scenario->
            app.rangeTimeout=30000;app.rangeHoldAck=true;open(scenario,session=true)
            val started=c.state.value.monitor.startedAt;val samples=app.samples.get();SystemClock.sleep(1200);assertEquals(samples,app.samples.get())
            chooseTen();ui.onNodeWithTag("rangeConfirmCancel").performClick();assertEquals(0,app.rangeWrites.get())
            chooseTen();val token=c.state.value.range.token
            ui.onNodeWithTag("rangeConfirmWrite").performClick();c.confirmRange(token);waitFor{app.rangeWrites.get()==1}
            device.pressHome();device.sleep();assertFalse(device.isScreenOn);SystemClock.sleep(500)
            device.wakeUp();device.executeShellCommand("wm dismiss-keyguard");device.executeShellCommand("am start -W -n ${app.packageName}/.MainActivity");scenario.recreate();assertTrue(c.state.value.range.busy);app.rangeHoldAck=false
            waitFor{c.state.value.range.record?.outcome==RangeOutcome.SUCCEEDED && !c.state.value.rangeOwned && c.state.value.monitor.configuration.range?.upper==10f}
            assertEquals(1,app.rangeWrites.get());assertEquals(started,c.state.value.monitor.startedAt)
            assertEquals(10f,c.state.value.monitor.configuration.range!!.upper,0f);waitFor{app.samples.get()>samples && c.state.value.monitor.latest!=null}
            val record=c.state.value.range.record!!;assertTrue(record.tx.isNotBlank());assertTrue(record.rx.isNotBlank());assertEquals(10f,record.actual!!.upper,0f)
            val text=java.io.ByteArrayOutputStream().also{c.writeRangeReport(record.id,it)}.toString("UTF-8");assertTrue(text.contains("команда 35"));assertTrue(text.contains("TX:"));assertTrue(text.contains("0…10"))
            assertEquals(1.25f,c.state.value.monitor.latest!!.values.first().value,0f)
        }
    }
    @Test fun timeoutAndRestoredPendingJournalUseOnlyVerificationReads() {
        ActivityScenario.launch(MainActivity::class.java).use{scenario->
            app.rangeMode=1;open(scenario);chooseTen();ui.onNodeWithTag("rangeConfirmWrite").performClick()
            waitFor{c.state.value.range.record?.outcome==RangeOutcome.SUCCEEDED && !c.state.value.rangeOwned}
            val record=c.state.value.range.record!!;assertEquals(1,app.rangeWrites.get())
            c.rangeJournal.save(record.copy(outcome=RangeOutcome.PENDING,stage=WriteStage.SENT,actual=null,verifiedAt=null))
            val connections=app.connections.get();val restored=SessionController(app)
            waitFor{restored.state.value.rangeRecords.any{it.id==record.id && it.outcome==RangeOutcome.UNKNOWN}}
            assertFalse(restored.state.value.active);assertEquals(connections,app.connections.get());assertEquals(1,app.rangeWrites.get())
            verifyRecord(record.id)
            assertEquals(1,app.rangeWrites.get())
        }
    }
    @Test fun walkLocalRangeEditNeverWritesAndExplicitConfigurationKeepsTheBook() {
        ActivityScenario.launch(MainActivity::class.java).use{scenario->
            connect(scenario,walk=true);val bookId=c.state.value.walk.book!!.id;val row=c.state.value.walk.book!!.rows.single()
            c.editWalk(row.id,mapOf(PassportField.RANGE to "Локальная подпись",PassportField.SERIAL to "00042"))
            waitFor{c.state.value.walk.book!!.rows.single().text(PassportField.RANGE)=="Локальная подпись"};c.rereadWalk();SystemClock.sleep(1200);assertEquals(0,app.rangeWrites.get())
            openForm{c.openRange()};assertEquals(16f,c.state.value.range.snapshot!!.range.upper,0f)
            c.selectRange("0","10");c.confirmRange(c.state.value.range.token)
            waitFor{c.state.value.range.record?.outcome==RangeOutcome.SUCCEEDED && !c.state.value.rangeOwned}
            waitFor{c.state.value.walk.book!!.rows.single().pendingRange?.upper==10f}
            val current=c.state.value.walk.book!!;assertEquals(bookId,current.id);assertEquals(1,current.rows.size);assertEquals("00042",current.rows.single().text(PassportField.SERIAL));assertEquals("Локальная подпись",current.rows.single().text(PassportField.RANGE))
            assertEquals(1,app.rangeWrites.get());assertNull(c.history.bounds())
        }
    }
    @Test fun unknownReadbackPausesPollingAndDeviceReplacementCannotReceivePreparedWrite() {
        ActivityScenario.launch(MainActivity::class.java).use{scenario->
            app.rangeMode=4;open(scenario,session=true);chooseTen();ui.onNodeWithTag("rangeConfirmWrite").performClick()
            waitFor{c.state.value.range.record?.outcome==RangeOutcome.UNKNOWN && !c.state.value.rangeOwned}
            assertEquals(1,app.rangeWrites.get());val samples=app.samples.get();SystemClock.sleep(1200);assertEquals(samples,app.samples.get())
            app.rangeMode=0;val id=c.state.value.range.record!!.id;verifyRecord(id)
            waitFor{app.samples.get()>samples && c.state.value.monitor.latest!=null}
            openForm{c.openRange()};c.selectRange("0","12");app.hartDeviceId=99;c.confirmRange(c.state.value.range.token)
            waitFor{c.state.value.range.record?.outcome==RangeOutcome.DIFFERENT_DEVICE && !c.state.value.rangeOwned};assertEquals(1,app.rangeWrites.get())
            openForm{c.openRange(acceptCurrentDevice=true)};assertEquals(99,c.state.value.range.snapshot!!.identity.deviceId);assertNull(c.state.value.range.requested)
        }
    }
    private fun assertFullButtons() {
        val nodes=listOf("walkEndSave","walkEndDiscard","walkEndCancel").map{tag->ui.onNodeWithTag(tag).assertIsDisplayed().fetchSemanticsNode()}
        nodes.forEach { n->val b=n.boundsInWindow;assertTrue(b.left>=0 && b.top>=0 && b.right<=device.displayWidth && b.bottom<=device.displayHeight);assertEquals(n.size.height.toFloat(),b.height,1f) }
        nodes.zipWithNext().forEach{(a,b)->assertTrue(a.boundsInWindow.bottom<=b.boundsInWindow.top)}
    }
    @Test fun walkEndAndMonitorTransitionDialogsKeepAllThreeActionsOnSmallLargeFontScreen() {
        device.executeShellCommand("wm size 720x1280");device.executeShellCommand("wm density 320");device.executeShellCommand("settings put system font_scale 1.6")
        instrumentation.waitForIdleSync();SystemClock.sleep(500)
        ActivityScenario.launch(MainActivity::class.java).use{scenario->
            connect(scenario,walk=true);ui.onNodeWithText("Обход",useUnmergedTree=true).performScrollTo().performClick()
            ui.onNodeWithTag("endWalk").performClick();assertFullButtons()
            val review=java.io.File(app.getExternalFilesDir(null),"export-review").apply{mkdirs()};assertTrue(device.takeScreenshot(java.io.File(review,"walk-end-large-font.png")))
            ui.onNodeWithTag("walkEndCancel").performClick();assertTrue(c.state.value.walkActive)
            ui.onNodeWithTag("endWalk").performClick();device.pressBack();ui.onNodeWithTag("walkEndDialog").assertDoesNotExist();assertTrue(c.state.value.walkActive)
            ui.onNodeWithText("Монитор",useUnmergedTree=true).performScrollTo().performClick()
            ui.onNodeWithText("Завершить обход и перейти к мониторингу…").performScrollTo().performClick();assertFullButtons()
            ui.onNodeWithTag("walkEndCancel").performClick();assertTrue(c.state.value.walkActive)
            ui.onNodeWithText("Завершить обход и перейти к мониторингу…").performScrollTo().performClick();ui.onNodeWithTag("walkEndSave").performClick()
            waitFor{!c.state.value.walkActive && c.state.value.monitor.phase==MonitorPhase.READY}
            openForm{c.openRange()};c.selectRange("0","10");waitFor{c.state.value.range.phase==RangePhase.CONFIRM}
            listOf("rangeConfirmWrite","rangeConfirmCancel").forEach{tag->
                val n=ui.onNodeWithTag(tag).assertIsDisplayed().fetchSemanticsNode();assertEquals(n.size.height.toFloat(),n.boundsInWindow.height,1f)
            }
            assertTrue(device.takeScreenshot(java.io.File(review,"range-confirm-large-font.png")))
            ui.onNodeWithTag("rangeConfirmCancel").performClick();assertEquals(0,app.rangeWrites.get())
        }
    }
}
