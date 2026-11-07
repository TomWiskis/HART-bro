package ru.hartbro.app

import androidx.test.platform.app.InstrumentationRegistry

/** Test-only wall time. Protocol deadlines already use virtual time in hart-core. */
internal object RegressionProfile {
    val name: String
        get() = InstrumentationRegistry.getArguments().getString("hartbroProfile", "fast").also {
            require(it in setOf("fast", "soak")) { "Unknown regression profile: $it" }
        }
    val backgroundWindowMillis: Long get() = if (name == "soak") 300_000L else 20_000L
}
