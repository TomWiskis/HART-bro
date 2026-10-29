package ru.hartbro.app

import android.app.Application
import ru.hartbro.app.session.SessionController

class HartApplication : Application() {
    val controller by lazy { SessionController(this) }
}
