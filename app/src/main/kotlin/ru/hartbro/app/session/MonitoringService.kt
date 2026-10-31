package ru.hartbro.app.session

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.*
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import ru.hartbro.app.HartApplication
import ru.hartbro.app.MainActivity
import ru.hartbro.core.MonitorPhase
import ru.hartbro.core.WalkPhase

/** Visible, user-started owner; Activity visibility never ends the session. No restart after process death. */
class MonitoringService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val controller get() = (application as HartApplication).controller
    private var wakeLock: PowerManager.WakeLock? = null
    private var ready = false
    private var awaitingStart = true
    private var lease = 0L
    override fun onBind(intent: Intent?) = null
    override fun onCreate() {
        super.onCreate()
        lease = controller.serviceCreated()
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("monitor", "HART-мониторинг", NotificationManager.IMPORTANCE_LOW))
        try {
            startForeground(20, notification("Подключение к прибору…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            ready = true
        } catch (e: RuntimeException) {
            controller.serviceLifecycle("startForeground отклонён: ${e.message}")
            controller.notice("Android не разрешил фоновую службу: ${e.message}"); stopSelf(); return
        }
        wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "hartbro:monitor").apply { setReferenceCounted(false); acquire(600_000) } // Protect initial connect immediately, including Activity going into background.
        scope.launch {
            // Diagnostics no longer cause dozens of synchronous notification updates per reply.
            controller.state.map { state ->
                if(state.rangeOwned) return@map "Настройка диапазона · ${state.range.message}"
                if (state.walkActive) {
                    val w=state.walk;val r=w.recovery
                    return@map "Обход · ${w.book?.rows?.size ?: 0} приборов · ${if(r!=null) "Bluetooth: осталось ${(r.remainingMillis+999)/1000} с; попытка ${r.attempt}" else w.message}"
                }
                val m = state.monitor
                val recovery = m.recovery
                val latest = m.latest
                when {
                    recovery != null -> "Восстановление: осталось ${((recovery.remainingMillis + 999) / 1000)} с; попытка ${recovery.attempt}. ${recovery.lastAttempt}"
                    m.phase in setOf(MonitorPhase.DECISION, MonitorPhase.DIFFERENT_DEVICE, MonitorPhase.ERROR) -> "Требуется решение: ${m.message}"
                    latest != null -> "${m.message} · ${DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault()).format(latest.receivedAt)}"
                    else -> m.message
                }
            }.distinctUntilChanged().collect { text ->
                runCatching { getSystemService(NotificationManager::class.java).notify(20, notification(text)) }
                    .onFailure { controller.serviceLifecycle("Ошибка уведомления: ${it.message}") }
                delay(1000)
            }
        }
        scope.launch {
            var renewed = 0L
            while (isActive) {
                val state = controller.state.value
                val working = awaitingStart || state.rangeOwned && state.range.busy || !state.rangeOwned && (state.walkActive && state.walk.phase !in setOf(WalkPhase.DECISION,WalkPhase.STOPPED,WalkPhase.INTERRUPTED) || state.active && !state.walkActive && state.monitor.phase !in setOf(MonitorPhase.IDLE, MonitorPhase.READY, MonitorPhase.PAUSED, MonitorPhase.DECISION, MonitorPhase.DIFFERENT_DEVICE, MonitorPhase.ERROR))
                if (working && (wakeLock?.isHeld != true || SystemClock.elapsedRealtime() - renewed >= 300_000)) {
                    wakeLock?.acquire(600_000); renewed = SystemClock.elapsedRealtime()
                } else if (!working && wakeLock?.isHeld == true) wakeLock?.release()
                delay(1000)
            }
        }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val address = intent?.getStringExtra("address")
        controller.serviceLifecycle("onStartCommand id=$startId; active=${controller.state.value.active}; hasAddress=${address != null}")
        if (!ready) { awaitingStart = false; stopSelf(); return START_NOT_STICKY }
        if (address != null) {
            if(intent.getStringExtra("mode")==ConnectionMode.WALK.name) controller.startWalkConnection(address,intent.getStringExtra("name") ?: "VIATOR")
            else controller.start(address,intent.getStringExtra("name") ?: "VIATOR")
        }
        else if (!controller.state.value.active) stopSelf() // An empty delivery must not stop an already active session.
        awaitingStart = false
        return START_NOT_STICKY
    }
    override fun onTaskRemoved(rootIntent: Intent?) {
        controller.serviceLifecycle("onTaskRemoved; активная сессия продолжает работу")
        super.onTaskRemoved(rootIntent)
    }
    private fun notification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val open = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, "monitor").setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(if(controller.state.value.rangeOwned) "HART-бро · настройка диапазона" else if(controller.state.value.walkActive) "HART-бро · обход датчиков" else "HART-бро · мониторинг").setContentText(text).setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, "Открыть сессию", open).build()).build()
    }
    override fun onDestroy() {
        controller.serviceLifecycle("onDestroy получен; lease=$lease")
        scope.cancel(); if (wakeLock?.isHeld == true) wakeLock?.release()
        controller.serviceDestroyed(lease); super.onDestroy()
    }
}
