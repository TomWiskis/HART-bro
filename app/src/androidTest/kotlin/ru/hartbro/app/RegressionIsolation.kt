package ru.hartbro.app

import android.content.Intent
import android.os.SystemClock
import org.junit.Assert.assertTrue
import ru.hartbro.app.session.MonitoringService

/** Finish each asynchronous owner before changing the shared, instrumentation-only device. */
internal fun FieldRegressionApplication.stopRegressionOwners() {
    fun await(check: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 25_000
        while (!check() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100)
        val s = controller.state.value
        assertTrue("Previous owner did not stop: active=${s.active}, service=${s.serviceRunning}, walk=${s.walk.phase}, range=${s.range.phase}/${s.rangeOwned}", check())
    }
    rangeHoldAck = false
    rangeMode = 0
    stopService(Intent(this, MonitoringService::class.java))
    await { controller.state.value.let { !it.serviceRunning && !it.active && !it.walkActive && !it.rangeOwned } }
    controller.closeRange()
    if (controller.state.value.monitor.startedAt != null) {
        controller.end()
        await { controller.state.value.monitor.startedAt == null }
    }
    if (controller.state.value.walk.book != null) {
        controller.finishWalk(false)
        await { controller.state.value.walk.book == null }
    }
    if (controller.state.value.walkDraftAvailable) {
        controller.discardWalkDraft()
        await { !controller.state.value.walkDraftAvailable }
    }
}
