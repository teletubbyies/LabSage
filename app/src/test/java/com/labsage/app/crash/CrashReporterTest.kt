package com.labsage.app.crash

import com.labsage.app.crash.CrashReporter.Report
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CrashReporterTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** uploadPending takes the app filesDir and owns the pending subdir. */
    private fun filesDirWithPending(): Pair<File, File> {
        val root = tmp.newFolder()
        val pending = File(root, "crash_pending")
        return root to pending
    }

    private fun report(message: String? = "boom", stack: String = "at com.labsage.app.Foo") = Report(
        occurredAt = "2026-11-01T12:00:00Z",
        appVersion = "1.0",
        deviceModel = "Google Pixel 8",
        androidVersion = "API 37",
        threadName = "main",
        exceptionClass = "java.lang.NullPointerException",
        message = message,
        stackTrace = stack
    )

    @Test
    fun uploadDeliversPendingReportsAndDeletesOnlyConfirmedOnes() = runBlocking {
        val (root, pending) = filesDirWithPending()
        CrashReporter.writePending(pending, report(message = "one"))
        CrashReporter.writePending(pending, report(message = "two"))
        pending.resolve("corrupt.json").writeText("{not json")

        val seen = mutableListOf<String?>()
        val delivered = CrashReporter.uploadPending(root, "1.0") { payload ->
            seen += payload.message
            // First report uploads fine; the server "rejects" the second one.
            payload.message != "two"
        }

        assertEquals(1, delivered)
        assertEquals(listOf("one", "two"), seen)
        // Delivered + corrupt are removed; the rejected one stays for retry.
        val remainingFile = pending.listFiles()!!.single()
        val remainingReport = CrashReporter.gson.fromJson(remainingFile.readText(), Report::class.java)
        assertEquals("two", remainingReport.message)
    }

    @Test
    fun uploadWithoutPendingFilesIsANoOp() = runBlocking {
        val (root, _) = filesDirWithPending()
        assertEquals(0, CrashReporter.uploadPending(root, "1.0") { true })
    }

    @Test
    fun corruptReportIsDeletedInsteadOfRetriedForever() = runBlocking {
        val (root, pending) = filesDirWithPending()
        pending.mkdirs()
        pending.resolve("corrupt.json").writeText("{not json")
        var uploads = 0
        CrashReporter.uploadPending(root, "1.0") { uploads++; true }
        assertEquals(0, uploads)
        assertFalse(pending.resolve("corrupt.json").exists())
    }

    @Test
    fun pendingBacklogIsBounded() {
        val (_, pending) = filesDirWithPending()
        repeat(30) { CrashReporter.writePending(pending, report(message = "n$it")) }
        val count = pending.listFiles()!!.size
        assertTrue("backlog must stay bounded, got $count", count <= 20)
    }
}
