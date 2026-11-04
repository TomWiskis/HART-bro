package ru.hartbro.app

import android.Manifest
import android.content.Intent
import android.os.SystemClock
import androidx.compose.ui.semantics.SemanticsActions
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
class Interface052Test {
    @get:Rule val ui=createEmptyComposeRule()
    @get:Rule val permission:GrantPermissionRule=GrantPermissionRule.grant(Manifest.permission.BLUETOOTH_CONNECT,Manifest.permission.POST_NOTIFICATIONS)
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val app get()=instrumentation.targetContext.applicationContext as FieldRegressionApplication
    private val c get()=app.controller
    private val device get()=UiDevice.getInstance(instrumentation)
    private var font="1.0"
    private fun waitFor(test:()->Boolean){val end=SystemClock.elapsedRealtime()+25000;while(!test() && SystemClock.elapsedRealtime()<end)SystemClock.sleep(100);assertTrue("phase=${c.state.value.monitor.phase}; range=${c.state.value.range}; owned=${c.state.value.rangeOwned}; records=${c.state.value.rangeRecords.map{it.id to it.outcome}}; notice=${c.state.value.notice}",test())}
    @Before fun reset(){
        font=device.executeShellCommand("settings get system font_scale").trim()
        device.wakeUp();device.executeShellCommand("wm dismiss-keyguard");app.stopRegressionOwners()
        app.failIdentity=false;app.identifyDelay=0;app.rangeMode=0;app.rangeHoldAck=false;app.rangeAckDelay=0;app.rangeTimeout=300;app.hartDeviceId=0x5FCA7F
        app.hartFailures.set(0);app.bluetoothFailures.set(0);app.rangeWrites.set(0);app.rangeActual=ConfiguredRange(0f,16f,12)
        c.deleteRangeRecord();waitFor{c.state.value.rangeRecords.isEmpty()}
    }
    @After fun cleanup(){
        app.stopRegressionOwners();c.deleteRangeRecord();waitFor{c.state.value.rangeRecords.isEmpty()}
        if(font.toFloatOrNull()!=null)device.executeShellCommand("settings put system font_scale $font")
        device.executeShellCommand("wm size reset");device.executeShellCommand("wm density reset")
    }
    private fun connect(s:ActivityScenario<MainActivity>,session:Boolean=false){
        s.onActivity{app.startForegroundService(Intent(app,MonitoringService::class.java).putExtra("address","00:11:22:33:44:55").putExtra("name","VIATOR-long-instrumentation-device"))}
        waitFor{c.state.value.monitor.phase==MonitorPhase.READY}
        ui.onNodeWithText("Монитор",useUnmergedTree=true).performScrollTo().performClick()
        if(session){c.beginSession();waitFor{c.state.value.monitor.latest!=null};c.period(1000)}
    }
    @Test fun compactMonitorOpensFreshDiagnosticsAndRangeReplacesFirstInputOnNarrowScreen(){
        device.executeShellCommand("wm size 720x1280");device.executeShellCommand("wm density 320");device.executeShellCommand("settings put system font_scale 1.3")
        ActivityScenario.launch(MainActivity::class.java).use{s->
            connect(s,true)
            ui.onNodeWithTag("bluetoothBadge").assertIsDisplayed()
            ui.onNodeWithTag("primaryReading").performScrollTo().assertIsDisplayed()
            ui.onNodeWithTag("loopReading").performScrollTo().assertIsDisplayed()
            ui.onNodeWithText("Нет данных").assertExists()
            ui.onNodeWithTag("series-SV").assertDoesNotExist()
            ui.onNodeWithTag("openDiagnostics").performScrollTo().performClick()
            waitFor{!c.state.value.diagnosticLoading && c.state.value.sensorDiagnostics!=null}
            ui.onNodeWithTag("sensorDiagnostics").assertExists()
            assertEquals(1,ui.onAllNodesWithTag("deviceType").fetchSemanticsNodes().size)
            ui.onNodeWithText("по данным команды 15",substring=true).assertDoesNotExist()
            ui.onNodeWithTag("openRange").performScrollTo().performClick();waitFor{c.state.value.range.phase==RangePhase.FORM}
            ui.onNodeWithTag("rangeLower").performScrollTo().performClick();ui.waitForIdle();ui.onNodeWithTag("rangeLower").performTextInput("-2,5")
            ui.onNodeWithTag("rangeLower").assertTextContains("-2,5")
            ui.onNodeWithTag("rangeUpper").performClick();ui.waitForIdle();ui.onNodeWithTag("rangeUpper").performTextInput("20")
            ui.onNodeWithTag("rangeUpper").assertTextContains("20")
            val a=ui.onNodeWithTag("rangeLower").fetchSemanticsNode().boundsInWindow
            val b=ui.onNodeWithTag("rangeUpper").fetchSemanticsNode().boundsInWindow
            val u=ui.onNodeWithTag("rangeUnits").fetchSemanticsNode().boundsInWindow
            assertEquals(a.top,b.top,1f);assertTrue(a.right<=b.left);assertTrue(b.right<=u.left);assertTrue(u.right<=device.displayWidth)
            ui.onNodeWithTag("rangeUnits").performClick()
            assertEquals(setOf(12),c.state.value.range.snapshot!!.writableUnits)
            ui.onNodeWithText("кПа",useUnmergedTree=true).performClick()
            ui.onNodeWithTag("rangeSet").performScrollTo().performClick();waitFor{c.state.value.range.phase==RangePhase.CONFIRM}
            assertEquals(0,app.rangeWrites.get());ui.onNodeWithTag("rangeConfirmWrite").performClick()
            waitFor{c.state.value.range.record?.outcome==RangeOutcome.SUCCEEDED && !c.state.value.rangeOwned}
            assertEquals(1,app.rangeWrites.get());assertEquals(-2.5f,app.rangeActual.lower,0f);assertEquals(20f,app.rangeActual.upper,0f)
            val review=java.io.File(app.getExternalFilesDir(null),"export-review").apply{mkdirs()}
            device.takeScreenshot(java.io.File(review,"interface-052-narrow.png"))
        }
    }
    @Test fun chartSeekReenablesFollowAndRecreationRetainsChosenScale(){
        ActivityScenario.launch(MainActivity::class.java).use{s->
            connect(s,true);SystemClock.sleep(4000)
            ui.onNodeWithText("Масштаб +").performScrollTo().performClick()
            waitFor{c.state.value.chartViewport.spanMillis==30_000L}
            ui.onNodeWithTag("chartSeek").performScrollTo().performSemanticsAction(SemanticsActions.SetProgress){it(0f)}
            waitFor{!c.state.value.chartViewport.following}
            val chosen=c.state.value.chartViewport;s.recreate();assertEquals(chosen,c.state.value.chartViewport)
            ui.onNodeWithTag("chartSeek").performScrollTo().performSemanticsAction(SemanticsActions.SetProgress){it(1f)}
            waitFor{c.state.value.chartViewport.following};assertEquals(30_000L,c.state.value.chartViewport.spanMillis)
            c.pause(true);waitFor{c.state.value.monitor.phase==MonitorPhase.PAUSED};val bounds=c.history.bounds()!!;SystemClock.sleep(1200);assertEquals(bounds,c.history.bounds())
            c.pause(false);waitFor{c.state.value.monitor.latest!=null};assertEquals(30_000L,c.state.value.chartViewport.spanMillis)
        }
    }
    @Test fun compactHistoryDeletesOnlyLocalRecordsAfterConfirmation(){
        ActivityScenario.launch(MainActivity::class.java).use{s->
            connect(s)
            repeat(2){index->c.openRange();waitFor{c.state.value.range.phase==RangePhase.FORM};c.selectRange("0",if(index==0)"10" else "12");c.confirmRange(c.state.value.range.token);waitFor{c.state.value.range.record?.outcome==RangeOutcome.SUCCEEDED && !c.state.value.rangeOwned};c.closeRange();waitFor{!c.state.value.rangeVisible}}
            waitFor{c.state.value.rangeRecords.size==2};val writes=app.rangeWrites.get();val connects=app.connections.get()
            c.showRangeHistory();val record=c.state.value.rangeRecords.first()
            ui.onNodeWithTag("rangeRecord-${record.id}").performScrollTo().performClick()
            ui.onNodeWithTag("deleteRange-${record.id}").performScrollTo().performClick()
            ui.onNodeWithTag("confirmDeleteRange").performClick();waitFor{c.state.value.rangeRecords.size==1}
            ui.onNodeWithTag("clearRangeHistory").performScrollTo().performClick();ui.onNodeWithTag("confirmDeleteRange").performClick();waitFor{c.state.value.rangeRecords.isEmpty()}
            assertEquals(writes,app.rangeWrites.get());assertEquals(connects,app.connections.get());assertEquals(12f,app.rangeActual.upper,0f)
        }
    }
}
