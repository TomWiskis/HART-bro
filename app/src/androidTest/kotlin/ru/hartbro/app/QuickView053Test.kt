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
class QuickView053Test {
    @get:Rule val ui=createEmptyComposeRule()
    @get:Rule val permission:GrantPermissionRule=GrantPermissionRule.grant(Manifest.permission.BLUETOOTH_CONNECT,Manifest.permission.POST_NOTIFICATIONS)
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val app get()=instrumentation.targetContext.applicationContext as FieldRegressionApplication
    private val c get()=app.controller
    private fun waitFor(test:()->Boolean){val end=SystemClock.elapsedRealtime()+25_000;while(!test() && SystemClock.elapsedRealtime()<end)SystemClock.sleep(100);assertTrue(c.state.value.toString(),test())}
    @Before fun reset(){
        app.stopRegressionOwners();c.closeSensorEdit();app.sensorMode=true;app.viatranProfile=false;app.metran150RProfile=false;app.sensorPv=.25f;app.sensorWrites.set(0)
        app.sensorLongTag="TEST-TAG";app.sensorTagDate=byteArrayOf(4,32,0xE0.toByte(),0x82.toByte(),8,32)+ByteArray(12){0x82.toByte()}+byteArrayOf(1,2,126)
        app.hartDeviceId=0x5FCA7F;app.failIdentity=false;app.identifyDelay=0;app.rangeMode=0;app.rangeHoldAck=false;app.rangeAckDelay=0;app.rangeTimeout=300
        app.hartFailures.set(0);app.bluetoothFailures.set(0);app.rangeWrites.set(0);app.rangeActual=ConfiguredRange(0f,16f,12)
        c.deleteRangeRecord();waitFor{c.state.value.rangeRecords.isEmpty() && c.state.value.sensorRecords.isEmpty()}
    }
    @After fun cleanup(){app.stopRegressionOwners();c.closeSensorEdit();app.sensorMode=false;app.viatranProfile=false;app.failIdentity=false;c.deleteRangeRecord();waitFor{c.state.value.sensorRecords.isEmpty()}}
    private fun connect(s:ActivityScenario<MainActivity>){
        s.onActivity{app.startForegroundService(Intent(app,MonitoringService::class.java).putExtra("address","00:11:22:33:44:55").putExtra("name","VIATOR-053"))}
        waitFor{c.state.value.monitor.phase==MonitorPhase.READY}
        ui.onNodeWithText("Монитор",useUnmergedTree=true).performScrollTo().performClick()
        ui.onNodeWithTag("quickSensorCard").assertExists()
    }
    private fun edit(tag:String,value:String){
        val records=c.state.value.sensorRecords.size
        ui.onNodeWithTag(tag).performScrollTo().performClick()
        ui.onNodeWithTag("sensorEditValue").performTextReplacement(value)
        ui.onNodeWithTag("sensorEditApply").performClick()
        ui.onNodeWithTag("sensorEditApply").performClick()
        waitFor{!c.state.value.editBusy && c.state.value.sensorEdit==null && c.state.value.sensorRecords.size==records+1}
    }
    @Test fun quickCardHasNoMeasurementsAndMetadataWritesSurviveRecreationWithVerifiedJournal(){
        ActivityScenario.launch(MainActivity::class.java).use{s->
            connect(s);val samples=app.samples.get();SystemClock.sleep(3500)
            assertEquals(samples,app.samples.get());assertNull(c.state.value.monitor.startedAt)
            ui.onNodeWithTag("primaryReading").assertDoesNotExist();ui.onNodeWithTag("loopReading").assertDoesNotExist()
            ui.onNodeWithTag("quickSensorNumbers",useUnmergedTree=true).assertTextContains("6277759   01.02.2026")
            assertTrue(c.state.value.monitor.identificationResponding)
            ui.onNodeWithTag("quickRangeEdit").performClick();waitFor{c.state.value.range.phase==RangePhase.FORM}
            assertFalse(c.state.value.diagnosticVisible)
            ui.onNodeWithTag("rangeUnits").performClick();ui.onNodeWithText("psi",useUnmergedTree=true).performClick()
            ui.onNodeWithTag("rangeSet").performScrollTo().assertIsNotEnabled();assertEquals(0,app.rangeWrites.get())
            c.closeRange();waitFor{!c.state.value.rangeVisible && !c.state.value.rangeOwned && c.state.value.monitor.phase==MonitorPhase.READY}
            ui.waitForIdle()
            ui.onNodeWithTag("quickSensorInfo").performClick();waitFor{!c.state.value.diagnosticLoading && c.state.value.sensorDiagnostics!=null}
            edit("editLongTag","NEW-053")
            assertEquals(1,app.sensorWrites.get());assertTrue(c.state.value.sensorRecords.first().succeeded)
            edit("editProductionDate","30.11.2025")
            assertEquals(2,app.sensorWrites.get());assertEquals("30.11.2025",c.state.value.monitor.configuration.productionDate?.let(HartDate::format))
            s.recreate();assertEquals(2,app.sensorWrites.get())
            c.closeSensorDiagnostics();waitFor{!c.state.value.diagnosticVisible}
            ui.onNodeWithTag("quickSensorNumbers",useUnmergedTree=true).assertTextContains("6277759   30.11.2025")
            c.showRangeHistory();waitFor{c.state.value.rangeVisible}
            val id=c.state.value.sensorRecords.first().id
            ui.onNodeWithTag("sensorRecord-$id").performScrollTo().performClick()
            ui.onNodeWithTag("deleteSensor-$id").performScrollTo().performClick()
            ui.onNodeWithTag("confirmDeleteSensor").performClick();waitFor{c.state.value.sensorRecords.size==1}
            assertEquals(2,app.sensorWrites.get());assertEquals(0,app.rangeWrites.get())
        }
    }
    @Test fun quickReplacementAndRfcommRecoveryKeepNoSessionUntilExplicitButton(){
        ActivityScenario.launch(MainActivity::class.java).use{s->
            connect(s);val initialConnections=app.connections.get();val samples=app.samples.get()
            app.hartDeviceId=12345;app.sensorLongTag="REPLACED"
            waitFor{c.state.value.monitor.identity?.deviceId==12345 && c.state.value.monitor.configuration.tag=="REPLACED"}
            app.failIdentity=true;waitFor{!c.state.value.monitor.identificationResponding}
            assertTrue(c.state.value.monitor.transportConnected);assertNull(c.state.value.monitor.startedAt)
            app.failIdentity=false;waitFor{c.state.value.monitor.identificationResponding}
            app.closeRfcomm!!.invoke();waitFor{!c.state.value.monitor.transportConnected}
            waitFor{c.state.value.monitor.phase==MonitorPhase.READY && c.state.value.monitor.transportConnected}
            assertEquals(initialConnections+1,app.connections.get());assertEquals(samples,app.samples.get())
            ui.waitForIdle()
            ui.onNodeWithTag("setSensorZero").performScrollTo().assertIsEnabled().performClick()
            ui.onNodeWithTag("sensorEditDialog").assertExists();ui.onNodeWithTag("sensorEditApply").assertIsNotEnabled()
            assertEquals(0,app.sensorWrites.get());c.closeSensorEdit();waitFor{c.state.value.sensorEdit==null}
            s.recreate();ui.onNodeWithTag("startSession").performScrollTo().performClick()
            waitFor{c.state.value.monitor.startedAt!=null && c.state.value.monitor.latest!=null}
            ui.onNodeWithTag("primaryReading").performScrollTo().assertExists();assertEquals(0,app.rangeWrites.get())
        }
    }
    @Test fun documentedPressureZeroNeedsConfirmationWritesOnceAndChecksActualPv(){
        app.viatranProfile=true
        ActivityScenario.launch(MainActivity::class.java).use{s->
            connect(s);ui.onNodeWithTag("setSensorZero").performScrollTo().performClick()
            assertEquals(0,app.sensorWrites.get())
            ui.onNodeWithTag("zeroTolerance").performScrollTo().performTextReplacement("0.001")
            ui.onNodeWithTag("zeroAccuracySource").performScrollTo().performTextReplacement("Test fixture accuracy");ui.onNodeWithTag("zeroAccuracySource").performImeAction()
            ui.onNodeWithTag("zeroPressureConsent").performScrollTo().performClick();ui.onNodeWithTag("zeroConditionsConsent").performScrollTo().performClick()
            ui.onNodeWithTag("sensorEditApply").performClick();waitFor{!c.state.value.editBusy && c.state.value.editConfirm && c.state.value.zeroPreparation!=null}
            assertEquals(0,app.sensorWrites.get());s.recreate()
            ui.onNodeWithTag("sensorEditApply").performClick()
            c.confirmSensorEdit();c.confirmSensorEdit()
            waitFor{!c.state.value.editBusy && c.state.value.sensorRecords.isNotEmpty()}
            assertEquals(1,app.sensorWrites.get());assertEquals(0f,app.sensorPv,0f);assertTrue(c.state.value.sensorRecords.single().succeeded)
            assertNull(c.state.value.monitor.startedAt);assertEquals(0,app.rangeWrites.get())
            val review=java.io.File(app.getExternalFilesDir(null),"export-review").apply{mkdirs()}
            UiDevice.getInstance(instrumentation).takeScreenshot(java.io.File(review,"quick-view-053.png"))
        }
    }
}
