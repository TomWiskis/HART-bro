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
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import ru.hartbro.app.session.*
import ru.hartbro.core.*

/** USB UI/native inventory on emulator; connectionFactory simulates HART/port loss. No hardware compatibility claim. */
@RunWith(AndroidJUnit4::class)
class Usb060Test {
    @get:Rule val ui=createEmptyComposeRule()
    @get:Rule val permission:GrantPermissionRule=GrantPermissionRule.grant(Manifest.permission.BLUETOOTH_CONNECT,Manifest.permission.POST_NOTIFICATIONS)
    private val app get()=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as FieldRegressionApplication
    private val c get()=app.controller
    private fun waitFor(test:()->Boolean){val end=SystemClock.elapsedRealtime()+25_000;while(!test()&&SystemClock.elapsedRealtime()<end)SystemClock.sleep(100);assertTrue(c.state.value.toString(),test())}
    @Before fun reset(){app.stopRegressionOwners();app.sensorMode=false;app.viatranProfile=false;app.failIdentity=false;app.identifyDelay=0;app.hartDeviceId=1;app.hartFailures.set(0);app.bluetoothFailures.set(0);app.walkBluetoothFailures.set(0)}
    @After fun finish(){app.failIdentity=false;app.stopRegressionOwners()}
    @Test fun usbInventoryWithoutHardwareHasNoFictitiousPortAndSelectionSurvivesRecreation(){
        ActivityScenario.launch(MainActivity::class.java).use{s->
            ui.onNodeWithTag("chooseUsb").performScrollTo().performClick()
            ui.onNodeWithTag("usbRefresh").performScrollTo().performClick()
            ui.onNodeWithTag("usbAbsent").performScrollTo().assertExists()
            assertTrue(c.state.value.usbDevices.isEmpty());assertFalse(c.state.value.active)
            s.recreate();ui.onNodeWithTag("usbAbsent").performScrollTo().assertExists()
            assertFalse(c.state.value.usbPermissionPending)
        }
    }
    private fun connect(s:ActivityScenario<MainActivity>){s.onActivity{app.startForegroundService(Intent(app,MonitoringService::class.java).putExtra("address","usb:test-fixture:0").putExtra("name","USB-TEST"))}}
    @Test fun usbHartTimeoutKeepsTransportOpenAndRetryAfterActivityRecreationReusesConnection(){
        ActivityScenario.launch(MainActivity::class.java).use{s->
            app.failIdentity=true;val count=app.connections.get();connect(s)
            waitFor{c.state.value.monitor.phase==MonitorPhase.ERROR}
            assertEquals(TransportKind.USB,c.state.value.communicator?.kind);assertTrue(c.state.value.monitor.transportConnected)
            s.recreate();app.failIdentity=false
            ui.onNodeWithTag("retryIdentification").performScrollTo().performClick()
            waitFor{c.state.value.monitor.phase==MonitorPhase.READY && c.state.value.monitor.identificationResponding}
            ui.onNodeWithTag("bluetoothBadge").assertContentDescriptionContains("USB: USB-TEST, подключён")
            assertEquals(count+1,app.connections.get());assertNull(c.state.value.monitor.startedAt)
        }
    }
    @Test fun usbDetachImmediatelyClearsIndicatorAndReattachesWithoutStartingSession(){
        ActivityScenario.launch(MainActivity::class.java).use{s->
            connect(s);waitFor{c.state.value.monitor.phase==MonitorPhase.READY};val count=app.connections.get()
            app.closeRfcomm!!.invoke();waitFor{!c.state.value.monitor.transportConnected}
            waitFor{c.state.value.monitor.phase==MonitorPhase.READY && app.connections.get()==count+1}
            s.recreate();assertEquals(count+1,app.connections.get());assertNull(c.state.value.monitor.startedAt)
            ui.onNodeWithTag("bluetoothBadge").assertContentDescriptionContains("USB: USB-TEST, подключён")
        }
    }
}
