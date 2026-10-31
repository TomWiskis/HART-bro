package ru.hartbro.testing

import java.io.File
import ru.hartbro.core.*

object ExportControlReview {
    @JvmStatic fun main(args: Array<String>) {
        val target = File(args.single()).apply { mkdirs() }
        val data = ControlSessionFixture.load(File(target, "reconstructed-session"),
            javaClass.getResourceAsStream("/export/session-148d023e.csv")!!,
            javaClass.getResourceAsStream("/export/session-148d023e-events.tsv")!!)
        File(target, "hart-${data.id}.xlsx").outputStream().use { SessionXlsx.write(data.history, data.id, it) }
        File(target, "hart-${data.id}-delimiter-semicolon.csv").outputStream().use { SessionCsv.write(data.history, data.id, it, ';') }
        val profile = exportProfile(data.history, data.id)
        println("CONTROL: source=${profile.sourceValues}, rows=${profile.rows}, exported=${profile.exportedValues}")
    }
}
