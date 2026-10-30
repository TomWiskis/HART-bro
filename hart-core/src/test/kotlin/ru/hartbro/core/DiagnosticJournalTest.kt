package ru.hartbro.core

import java.nio.file.Files
import java.time.Instant
import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Test

class DiagnosticJournalTest {
    @Test fun fullSeveralMinuteLogKeepsInitialIdentityAcrossProcessRestartAndExport() {
        val root=Files.createTempDirectory("hart-hex").toFile()
        try {
            val file=java.io.File(root,"current.txt");val j=DiagnosticJournal(file)
            j.append(DiagnosticEntry(Instant.EPOCH,"IDENTITY","FIRST IDENTIFICATION"))
            repeat(1800){j.append(DiagnosticEntry(Instant.ofEpochSecond(it.toLong()),"RX-FRAME","cycle $it",ByteArray(50)))}
            val restarted=DiagnosticJournal(file);val exported=ByteArrayOutputStream();restarted.copyTo(exported)
            val text=exported.toString("UTF-8");assertTrue(text.contains("FIRST IDENTIFICATION"));assertTrue(text.contains("cycle 1799"));assertFalse(restarted.incomplete)
            assertEquals(1,root.listFiles()!!.size) // one working log, no automatic archive
        } finally { root.deleteRecursively() }
    }
    @Test fun chunksAreRecordedOnlyWhenDetailedModeIsEnabled() {
        val root=Files.createTempDirectory("hart-hex").toFile()
        try { val j=DiagnosticJournal(java.io.File(root,"log"));val e=DiagnosticEntry(Instant.EPOCH,"RX-CHUNK","fragment")
            assertFalse(j.append(e));j.detailed=true;assertTrue(j.append(e));assertTrue(j.text().contains("RX-CHUNK"))
        } finally {root.deleteRecursively()}
    }
    @Test fun capPreservesFirstEventsReportsIncompleteAndClearIsExplicit() {
        val root=Files.createTempDirectory("hart-hex").toFile()
        try {val f=java.io.File(root,"log");val j=DiagnosticJournal(f,300)
            j.append(DiagnosticEntry(Instant.EPOCH,"IDENTITY","FIRST"));repeat(100){j.append(DiagnosticEntry(Instant.EPOCH,"HART","cycle $it"))}
            assertTrue(j.incomplete);assertTrue(f.length()<=300);assertTrue(j.text().contains("FIRST"));assertTrue(j.text().contains("неполон"))
            assertTrue(DiagnosticJournal(f,300).incomplete);j.clear();assertFalse(f.exists());assertFalse(j.incomplete)
        }finally{root.deleteRecursively()}
    }
}
