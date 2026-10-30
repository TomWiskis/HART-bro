package ru.hartbro.app

import android.app.Application
import android.app.ActivityManager
import android.os.Build
import java.time.Instant
import ru.hartbro.core.DiagnosticEntry
import ru.hartbro.app.session.SessionController

open class HartApplication : Application() {
    val controller by lazy { createController() }
    override fun onCreate() {
        super.onCreate()
        controller.append(DiagnosticEntry(Instant.now(), "PROCESS", "Запуск HART-бро ${BuildConfig.VERSION_NAME}; Android ${Build.VERSION.RELEASE}/API ${Build.VERSION.SDK_INT}; ${Build.MODEL}; PID ${android.os.Process.myPid()}. Временный HEX-журнал сохранён до явной очистки."))
        if (Build.VERSION.SDK_INT >= 30) runCatching {
            getSystemService(ActivityManager::class.java).getHistoricalProcessExitReasons(packageName, 0, 1).firstOrNull()?.let {
                controller.append(DiagnosticEntry(Instant.now(), "PROCESS", "Предыдущее завершение: reason=${it.reason}; time=${Instant.ofEpochMilli(it.timestamp)}; ${it.description}"))
            }
        }
    }
    protected open fun createController() = SessionController(this)
}
