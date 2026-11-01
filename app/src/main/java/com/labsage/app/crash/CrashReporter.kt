package com.labsage.app.crash

import android.os.Build
import android.util.Log
import com.google.gson.Gson
import java.io.File

/**
 * Minimal crash-capture loop: an uncaught exception is serialized to a pending
 * file (crash-free launches continue normally), and the next app start uploads
 * the backlog to the backend, deleting files only on confirmed delivery.
 *
 * Deliberately dependency-light — the handler itself must not be able to
 * throw (a crashing crash handler would lose the report AND break the system
 * crash flow). Every step is wrapped; the original handler always runs last.
 */
object CrashReporter {

    private const val TAG = "CrashReporter"
    private const val PENDING_DIR = "crash_pending"
    private const val STACK_LIMIT = 8_000
    private const val MAX_PENDING_FILES = 20

    internal val gson = Gson()

    data class Report(
        val occurredAt: String,
        val appVersion: String,
        val deviceModel: String,
        val androidVersion: String,
        val threadName: String,
        val exceptionClass: String,
        val message: String?,
        val stackTrace: String,
        /** Filled from the local account (if any) at upload time, not capture time. */
        val userId: String? = null
    )

    fun install(filesDir: File, appVersion: String, previous: Thread.UncaughtExceptionHandler?) {
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                writePending(
                    filesDir = File(filesDir, PENDING_DIR),
                    report = buildReport(appVersion, thread.name, throwable)
                )
            } catch (t: Throwable) {
                // Never let the reporter mask the original crash.
                Log.e(TAG, "failed to persist crash report", t)
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    /** Uploads the pending backlog; returns how many were delivered. */
    suspend fun uploadPending(
        filesDir: File,
        appVersion: String,
        upload: suspend (Report) -> Boolean
    ): Int {
        val dir = File(filesDir, PENDING_DIR)
        val files = dir.listFiles { f -> f.isFile }?.sortedBy { it.name } ?: return 0
        var delivered = 0
        for (file in files.take(MAX_PENDING_FILES)) {
            val report = runCatching {
                gson.fromJson(file.readText(), Report::class.java)
            }.getOrNull()
            if (report == null) {
                // Unreadable report — delete instead of retrying forever.
                file.delete()
                continue
            }
            if (runCatching { upload(report.copy(appVersion = appVersion)) }.getOrDefault(false)) {
                file.delete()
                delivered++
            }
        }
        return delivered
    }

    internal fun buildReport(appVersion: String, threadName: String, throwable: Throwable): Report =
        Report(
            // Epoch millis (string) — java.time needs API 26 / desugaring, minSdk is 24.
            occurredAt = System.currentTimeMillis().toString(),
            appVersion = appVersion,
            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
            androidVersion = "API ${Build.VERSION.SDK_INT}",
            threadName = threadName,
            exceptionClass = throwable.javaClass.name,
            message = throwable.message?.take(500),
            stackTrace = throwable.stackTraceToString().take(STACK_LIMIT)
        )

    /** File name is sortable by time so uploads stay ordered. */
    internal fun writePending(filesDir: File, report: Report) {
        filesDir.mkdirs()
        // Bound the backlog: a crash loop must not fill the disk.
        filesDir.listFiles()?.let { files ->
            if (files.size >= MAX_PENDING_FILES) {
                files.sortedBy { it.name }.take(files.size - MAX_PENDING_FILES + 1)
                    .forEach { it.delete() }
            }
        }
        val name = "crash-${System.currentTimeMillis()}-${report.hashCode().toUInt()}.json"
        File(filesDir, name).writeText(gson.toJson(report))
    }
}
