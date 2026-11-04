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
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import ru.hartbro.app.session.*
import ru.hartbro.core.*
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class Supplement060AndroidTest {
    @get:Rule val ui=createEmptyComposeRule()
    @get:Rule val permission:GrantPermissionRule=GrantPermissionRule.grant(Manifest.permission.BLUETOOTH_CONNECT,Manifest.permission.POST_NOTIFICATIONS)
    private val app get()=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as FieldRegressionApplication
    private val c get()=app.controller
    private fun waitFor(test:()->Boolean){val end=SystemClock.elapsedRealtime()+25_000;while(!test()&&SystemClock.elapsedRealtime()<end)SystemClock.sleep(100);assertTrue("phase=${c.state.value.monitor.phase};edit=${c.state.value.editError};capture=${c.state.value.capture.phase};notice=${c.state.value.notice}",test())}
    private fun goodFrame()=InstrumentationRegistry.getInstrumentation().context.assets.open("success-four-ff-043449.txt").bufferedReader().use{it.readLines().last{line->line.matches(Regex("[0-9A-F]{2}( [0-9A-F]{2})+"))}}
    @Before fun before(){
        app.stopRegressionOwners();c.showAnalysis(false);c.closeSensorEdit()
        app.sensorMode=true;app.viatranProfile=false;app.metran150RProfile=false;app.failIdentity=false;app.identifyDelay=0;app.sensorPv=.25f;app.hartDeviceId=0x5FCA7F
        app.rangeTimeout=300;app.rangeMode=0;app.rangeHoldAck=false;app.rangeAckDelay=0;app.rangeActual=ConfiguredRange(0f,16f,12)
        app.hartFailures.set(0);app.bluetoothFailures.set(0);app.walkBluetoothFailures.set(0);app.sensorWrites.set(0);app.rangeWrites.set(0);app.captureWrites.set(0)
        while(app.captureInput.tryReceive().isSuccess){}
        c.deleteRangeRecord();waitFor{c.state.value.rangeRecords.isEmpty()&&c.state.value.sensorRecords.isEmpty()}
    }
    @After fun after(){app.stopRegressionOwners();c.showAnalysis(false);c.closeSensorEdit();app.metran150RProfile=false;app.sensorMode=false;app.viatranProfile=false}
    private fun connect(s:ActivityScenario<MainActivity>){val count=app.connections.get();s.onActivity{app.startForegroundService(Intent(app,MonitoringService::class.java).putExtra("address","00:11:22:33:44:55").putExtra("name","VIATOR-060"))};waitFor{app.connections.get()==count+1&&c.state.value.monitor.phase==MonitorPhase.READY&&c.state.value.active};ui.onNodeWithText("Монитор",useUnmergedTree=true).performScrollTo().performClick()}
    @Test fun registeredMetran150RUsesCommercialNameAndKeepsRawRosemountIdentityInDiagnostics(){
        app.metran150RProfile=true
        ActivityScenario.launch(MainActivity::class.java).use{s->
            connect(s);ui.onNodeWithTag("sensorStatus",useUnmergedTree=true).assertTextContains("Метран-150R",substring=true)
            assertEquals(0x26,c.state.value.monitor.identity!!.manufacturerCode);assertEquals(0x2662,c.state.value.monitor.identity!!.deviceType);assertEquals(9,c.state.value.monitor.identity!!.deviceRevision)
            ui.onNodeWithTag("quickSensorInfo").performClick();waitFor{!c.state.value.diagnosticLoading&&c.state.value.sensorDiagnostics!=null}
            ui.onNodeWithText("Производитель: Rosemount (0x0026)",useUnmergedTree=true).performScrollTo().assertExists()
            ui.onNodeWithText("Модель: Метран-150R",useUnmergedTree=true).performScrollTo().assertExists()
        }
    }
    @Test fun unknownPressureModelZeroNeedsTwoStepsSurvivesRecreationAndWritesOnce(){
        ActivityScenario.launch(MainActivity::class.java).use{s->
            connect(s);assertNull(HartDeviceCatalog.model(c.state.value.monitor.identity!!))
            ui.onNodeWithTag("setSensorZero").performScrollTo().performClick();ui.onNodeWithTag("sensorEditApply").assertIsNotEnabled()
            ui.onNodeWithTag("zeroPressureWarning").performScrollTo().assertTextContains("Атмосферное давление нельзя автоматически",substring=true)
            ui.onNodeWithTag("zeroTolerance").performScrollTo().performTextReplacement("0.001")
            ui.onNodeWithTag("zeroAccuracySource").performScrollTo().performTextReplacement("Test-only accuracy source");ui.onNodeWithTag("zeroAccuracySource").performImeAction()
            ui.onNodeWithTag("zeroPressureConsent").performScrollTo().performClick();ui.onNodeWithTag("zeroConditionsConsent").performScrollTo().performClick()
            ui.onNodeWithTag("sensorEditApply").performClick();waitFor{!c.state.value.editBusy&&c.state.value.editConfirm&&c.state.value.zeroPreparation!=null}
            assertEquals(0,app.sensorWrites.get());s.recreate();ui.onNodeWithTag("zeroBefore").assertTextContains("0.25",substring=true)
            ui.onNodeWithTag("sensorEditApply").performClick();c.confirmSensorEdit();c.confirmSensorEdit()
            waitFor{!c.state.value.editBusy&&c.state.value.sensorRecords.isNotEmpty()}
            val r=c.state.value.sensorRecords.first();assertEquals(43,r.command);assertEquals(SensorOutcome.SUCCEEDED,r.outcome);assertEquals(1,app.sensorWrites.get());assertEquals(0,app.rangeWrites.get());assertEquals(3,r.pvAfter.size)
            assertEquals(16f,app.rangeActual.upper,0f);assertNull(c.state.value.monitor.startedAt)
        }
    }
    @Test fun importingAndSavingHexAnalysisDoesNotConnectOrWriteAndSurvivesActivityRecreation(){
        ActivityScenario.launch(MainActivity::class.java).use{s->
            val connections=app.connections.get();c.importAnalysis(goodFrame(),source="Field frame fixture")
            waitFor{c.state.value.analysis?.valid==1&&c.state.value.analysisVisible}
            ui.onNodeWithTag("analysisCounts").assertTextContains("Корректных кадров: 1",substring=true)
            s.recreate();ui.onNodeWithTag("hartAnalysis").assertExists();ui.onNodeWithTag("saveHartScenario").performScrollTo().performClick()
            waitFor{c.state.value.analysisFiles.isNotEmpty()};assertEquals(connections,app.connections.get());assertEquals(0,app.sensorWrites.get());assertEquals(0,app.captureWrites.get())
            assertFalse(c.state.value.active)
        }
    }
    @Test fun passiveCaptureKeepsForegroundOwnerAcrossRecreateWithoutAnyHartTransmit(){
        ActivityScenario.launch(MainActivity::class.java).use{s->
            val oldConnections=app.connections.get();val oldSamples=app.samples.get();val count=app.captureOpens.get()
            s.onActivity{app.startForegroundService(Intent(app,MonitoringService::class.java).putExtra("address","00:11:22:33:44:55").putExtra("name","VIATOR-RX").putExtra("mode",ConnectionMode.LISTEN.name))}
            waitFor{c.state.value.captureActive&&app.captureOpens.get()==count+1&&c.state.value.capture.phase==CapturePhase.LISTENING}
            runBlocking{app.captureInput.send(HartFragment(goodFrame().split(" ").map{it.toInt(16).toByte()}.toByteArray(),Instant.parse("2026-11-03T04:34:49.927393Z")))}
            waitFor{c.state.value.capture.valid==1L};s.recreate();assertEquals(count+1,app.captureOpens.get());assertEquals(0,app.captureWrites.get());assertEquals(oldConnections,app.connections.get());assertEquals(oldSamples,app.samples.get())
            assertNull(c.state.value.monitor.identity);assertNull(c.state.value.monitor.startedAt)
            ui.onNodeWithTag("stopPassiveCapture").performScrollTo().performClick();waitFor{!c.state.value.captureActive&&!c.state.value.serviceRunning}
            assertEquals(0,app.captureWrites.get());assertEquals(1,c.state.value.analysis!!.valid)
        }
    }
}
