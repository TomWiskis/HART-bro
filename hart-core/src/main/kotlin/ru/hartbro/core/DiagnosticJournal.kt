package ru.hartbro.core

import java.io.File
import java.io.OutputStream

/** One bounded working log, retained across process death. Persistent exports require a user action. */
class DiagnosticJournal(private val file: File, private val maxBytes: Long = 16L * 1024 * 1024) {
    private val truncated = File(file.path + ".truncated")
    @Volatile var detailed: Boolean = false
    val incomplete: Boolean get() = truncated.exists()
    init { file.parentFile.mkdirs() }
    @Synchronized fun append(entry: DiagnosticEntry): Boolean {
        if (entry.kind == "RX-CHUNK" && !detailed) return false
        if (incomplete) return false
        val bytes = (entry.text() + "\n\n").toByteArray(Charsets.UTF_8)
        if (file.length() + bytes.size > maxBytes) { truncated.writeText("Лимит $maxBytes байтов достигнут; последующие события не записаны.\n"); return false }
        file.appendBytes(bytes)
        return true
    }
    @Synchronized fun copyTo(output: OutputStream) {
        output.write("HART-бро · диагностический журнал\n".toByteArray())
        if (incomplete) output.write("ВНИМАНИЕ: журнал неполон, достигнут предел временного буфера.\n".toByteArray())
        if (file.exists()) file.inputStream().use { it.copyTo(output) }
    }
    @Synchronized fun text(maximum: Int = 500_000): String {
        val prefix = if (file.length() > maximum) "Копия ограничена $maximum байтами. Для полного журнала сохраните TXT.\n" else ""
        val buffer = java.io.ByteArrayOutputStream()
        if (file.exists()) file.inputStream().use { input ->
            val data = ByteArray(8192); var remaining = maximum
            while (remaining > 0) { val n = input.read(data, 0, minOf(remaining, data.size)); if (n < 0) break; buffer.write(data, 0, n); remaining -= n }
        }
        return prefix + (if (incomplete) "Журнал неполон: достигнут предел буфера.\n" else "") + buffer.toString("UTF-8")
    }
    @Synchronized fun clear() { if (file.exists()) check(file.delete()); if (truncated.exists()) check(truncated.delete()) }
}
