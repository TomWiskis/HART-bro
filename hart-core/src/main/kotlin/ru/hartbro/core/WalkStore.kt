package ru.hartbro.core

import java.io.*
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.UUID

/** Separate, versioned walk documents. A recoverable draft is never listed as a manually saved book. */
class WalkStore(private val directory: File) {
    private val draft get() = File(directory, "current.draft")
    private fun file(id: String): File { require(UUID.fromString(id).toString() == id);return File(directory, "$id.walk") }
    @Synchronized fun save(book: WalkBook) = atomic(file(book.id), book)
    @Synchronized fun writeDraft(book: WalkBook) = atomic(draft, book)
    /** Fetch the newest catalog after obtaining the writer lock; an older callback cannot overwrite a manual edit. */
    @Synchronized fun writeDraft(snapshot: () -> WalkBook) = atomic(draft, snapshot())
    @Synchronized fun clearDraft() { if (draft.exists()) check(draft.delete()) { "Не удалось удалить черновик" } }
    @Synchronized fun hasDraft() = draft.exists()
    @Synchronized fun restoreDraft(): WalkBook = read(draft)
    @Synchronized fun load(id: String): WalkBook = read(file(id))
    @Synchronized fun delete(id: String) { check(file(id).delete()) { "Ведомость не удалена" } }
    @Synchronized fun list(): List<WalkBook> = directory.listFiles()?.filter { it.extension == "walk" }?.map(::read)?.sortedByDescending { it.startedAt }.orEmpty()
    private fun atomic(target: File, book: WalkBook) {
        check(directory.isDirectory || directory.mkdirs()) { "Недоступен каталог ведомостей" }
        val temp = File(directory, "${target.name}.tmp")
        try {
            FileOutputStream(temp).use { stream ->
                val out = DataOutputStream(BufferedOutputStream(stream));write(out, book);out.flush();stream.fd.sync()
            }
            require(temp.length() <= MAX_BYTES) { "Ведомость превышает 32 МиБ" }
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { temp.delete() }
    }
    private fun read(file: File): WalkBook {
        require(file.length() in 1..MAX_BYTES) { "Некорректный размер ведомости" }
        return file.inputStream().buffered().use { stream -> DataInputStream(stream).let { input ->
            require(input.readInt() == 0x48574231 && input.readInt() == 1) { "Неизвестный формат ведомости" }
            val book = WalkBook(input.readUTF(), input.instant()!!, input.instant(), input.readUTF(),
                List(input.count(10000)) { input.row() }, List(input.count(10000)) { PendingPassport(input.readUTF(), input.reading(), input.readUTF()) })
            require(input.read() == -1) { "Лишние данные ведомости" };file(book.id);book
        } }
    }
    private fun write(out: DataOutputStream, book: WalkBook) = with(out) {
        require(book.rows.size <= 10000 && book.pending.size <= 10000)
        writeInt(0x48574231);writeInt(1);writeUTF(book.id);instant(book.startedAt);instant(book.endedAt);writeUTF(book.title)
        writeInt(book.rows.size);book.rows.forEach { row(it) }
        writeInt(book.pending.size);book.pending.forEach { writeUTF(it.id);reading(it.reading);writeUTF(it.explanation) }
    }
    private fun DataOutputStream.instant(value: Instant?) { writeBoolean(value != null);if (value != null) { writeLong(value.epochSecond);writeInt(value.nano) } }
    private fun DataInputStream.instant(): Instant? = if (readBoolean()) Instant.ofEpochSecond(readLong(), readInt().toLong()) else null
    private fun DataInputStream.count(max: Int): Int = readInt().also { require(it in 0..max) { "Некорректное число полей" } }
    private fun DataOutputStream.range(value: ConfiguredRange?) { writeBoolean(value != null);value?.let { writeFloat(it.lower);writeFloat(it.upper);writeInt(it.unitCode) } }
    private fun DataInputStream.range(): ConfiguredRange? = if (readBoolean()) ConfiguredRange(readFloat(), readFloat(), readInt()).also { require(it.lower.isFinite() && it.upper.isFinite() && it.unitCode in 0..255) } else null
    private fun DataOutputStream.fact(f: PassportFact) {
        writeBoolean(f.value != null);f.value?.let(::writeUTF);writeUTF(f.source.name);writeInt(f.command ?: -1);writeUTF(f.raw);instant(f.readAt)
        writeBoolean(f.manual);writeUTF(f.confirmation.name);writeUTF(f.documentation.orEmpty());writeUTF(f.datePrecision?.name.orEmpty())
    }
    private fun DataInputStream.fact(): PassportFact = PassportFact(if (readBoolean()) readUTF() else null, FactSource.valueOf(readUTF()),
        readInt().takeIf { it >= 0 }, readUTF(), instant(), readBoolean(), FactConfirmation.valueOf(readUTF()), readUTF().ifEmpty { null }, readUTF().ifEmpty { null }?.let(DatePrecision::valueOf))
    private fun DataOutputStream.fields(fields: Map<PassportField, PassportFact>) { writeInt(fields.size);fields.forEach { (k,v) -> writeUTF(k.name);fact(v) } }
    private fun DataInputStream.fields(): Map<PassportField, PassportFact> = buildMap { repeat(count(5)) { val key = PassportField.valueOf(readUTF());require(key !in this);put(key,fact()) } }
    private fun DataOutputStream.reading(r: PassportReading) {
        val d = r.identity;writeInt(d.raw.size);write(d.raw)
        fields(r.fields);writeInt(r.candidates.size);r.candidates.forEach { (k,v) -> writeUTF(k);fact(v) };range(r.range);instant(r.readAt)
        writeInt(r.errors.size);r.errors.forEach { (k,v) -> writeUTF(k);writeUTF(v) };writeBoolean(r.transportLost)
    }
    private fun DataInputStream.reading(): PassportReading {
        val raw = ByteArray(count(255));readFully(raw);val identity = DeviceIdentity.parse(raw)
        val fields = fields();val candidates = buildMap { repeat(count(100)) { val k=readUTF();put(k,fact()) } };val range=range();val at=instant()!!
        val errors=buildMap { repeat(count(100)) { put(readUTF(),readUTF()) } }
        return PassportReading(identity,fields,candidates,range,at,errors,readBoolean())
    }
    private fun DataOutputStream.row(r: WalkRow) { writeUTF(r.id);reading(r.observed);fields(r.fields);range(r.acceptedRange);range(r.pendingRange);writeBoolean(r.pendingRangeFact != null);r.pendingRangeFact?.let { fact(it) };writeBoolean(r.manuallySeparated) }
    private fun DataInputStream.row() = WalkRow(readUTF(),reading(),fields(),range(),range(),if(readBoolean()) fact() else null,readBoolean())
    companion object { private const val MAX_BYTES = 32L * 1024 * 1024 }
}
