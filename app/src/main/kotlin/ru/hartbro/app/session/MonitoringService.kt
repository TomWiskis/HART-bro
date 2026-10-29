package ru.hartbro.app.session

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.*
import kotlinx.coroutines.*
import ru.hartbro.app.HartApplication
import ru.hartbro.app.MainActivity
import ru.hartbro.core.MonitorPhase

/** Started only by the visible activity after connection consent. Never restarts a killed session. */
class MonitoringService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val controller get() = (application as HartApplication).controller
    private var wakeLock: PowerManager.WakeLock? = null
    private var ready = false
    override fun onBind(intent: Intent?) = null
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("monitor", "HART-мониторинг", NotificationManager.IMPORTANCE_LOW))
        val notification = notification("Подключение к прибору…")
        try {
            startForeground(20, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            ready = true
        } catch (e: RuntimeException) {
            controller.notice("Android не разрешил фоновую службу: ${e.message}")
            stopSelf()
            return
        }
        wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "hartbro:monitor").apply { setReferenceCounted(false) }
        scope.launch {
            controller.state.collect { state ->
                val needsDecision = state.monitor.phase in setOf(MonitorPhase.DECISION, MonitorPhase.DIFFERENT_DEVICE, MonitorPhase.ERROR)
                if (needsDecision && wakeLock?.isHeld == true) wakeLock?.release()
                getSystemService(NotificationManager::class.java).notify(20, notification(if (needsDecision) "Требуется решение: ${state.monitor.message}" else state.monitor.message))
            }
        }
        scope.launch {
            while (isActive) {
                if (controller.state.value.monitor.phase !in setOf(MonitorPhase.DECISION, MonitorPhase.DIFFERENT_DEVICE, MonitorPhase.ERROR)) wakeLock?.acquire(10 * 60 * 1000L)
                delay(5 * 60 * 1000L)
            }
        }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val address = intent?.getStringExtra("address")
        if (!ready || address == null) { stopSelf(); return START_NOT_STICKY }
        controller.start(address, intent.getStringExtra("name") ?: "VIATOR")
        return START_NOT_STICKY
    }
    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, "monitor").setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("HART-бро · мониторинг").setContentText(text).setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).addAction(Notification.Action.Builder(null, "Открыть сессию", open).build()).build()
    }
    override fun onDestroy() {
        scope.cancel(); if (wakeLock?.isHeld == true) wakeLock?.release()
        controller.serviceDestroyed(); super.onDestroy()
    }
}
