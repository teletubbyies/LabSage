package com.labsage.app.data.remote

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okio.HashingSink
import okio.blackholeSink
import okio.buffer
import okio.source
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * Reproducible ART benchmark for the client-side portion of Markdown upload.
 *
 * It intentionally terminates both request bodies in a hashing black-hole sink:
 * this exercises RequestBody.writeTo without mixing backend embedding latency or
 * retaining the uploaded bytes in the benchmark itself.
 */
@RunWith(AndroidJUnit4::class)
class UploadPipelineBenchmarkTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val mediaType = "text/markdown; charset=utf-8".toMediaType()

    @Test
    fun compareLegacyAndStreamingPipelines() {
        assumeTrue(
            "Opt-in benchmark: pass -e runUploadBenchmark true",
            InstrumentationRegistry.getArguments()
                .getString("runUploadBenchmark")
                .toBoolean()
        )
        val output = File(
            requireNotNull(context.getExternalFilesDir(null)),
            "upload-benchmark-results.csv"
        )
        val fixtureDir = File(context.cacheDir, "upload-benchmark-fixtures").apply { mkdirs() }
        val sizesMiB = listOf(1, 5, 10)
        val warmups = 3
        val measuredIterations = 30

        output.bufferedWriter().use { writer ->
            writer.appendLine(
                "variant,size_mib,iteration,elapsed_ms,peak_heap_delta_bytes," +
                    "temp_disk_bytes,body_bytes,sha256"
            )

            for (sizeMiB in sizesMiB) {
                val fixture = File(fixtureDir, "$sizeMiB-mib.md")
                writeMarkdownFixture(fixture, sizeMiB * MIB)
                val expectedSha256 = sha256(fixture)

                repeat(warmups) {
                    runLegacy(fixture, measured = false)
                    runStreaming(fixture, measured = false)
                }

                repeat(measuredIterations) { iteration ->
                    // Alternate order between iterations to reduce thermal/JIT bias.
                    val variants = if (iteration % 2 == 0) {
                        listOf(::runLegacy, ::runStreaming)
                    } else {
                        listOf(::runStreaming, ::runLegacy)
                    }
                    variants.forEach { run ->
                        val result = run(fixture, true)
                        assertEquals(fixture.length(), result.bodyBytes)
                        assertEquals(expectedSha256, result.sha256)
                        writer.appendLine(
                            listOf(
                                result.variant,
                                sizeMiB,
                                iteration + 1,
                                String.format(Locale.US, "%.3f", result.elapsedNanos / 1_000_000.0),
                                result.peakHeapDeltaBytes,
                                result.tempDiskBytes,
                                result.bodyBytes,
                                result.sha256
                            ).joinToString(",")
                        )
                        writer.flush()
                    }
                }
            }
        }
    }

    private fun runLegacy(fixture: File, measured: Boolean): RunResult {
        stabilizeHeap()
        val tempFile = File.createTempFile("legacy-upload-", ".md", context.cacheDir)
        var requestBodyBytes: ByteArray? = null
        return measure("legacy", measured) {
            fixture.inputStream().use { input ->
                tempFile.outputStream().use { output -> input.copyTo(output) }
            }
            requestBodyBytes = tempFile.readBytes()
            val hashingSink = HashingSink.sha256(blackholeSink())
            hashingSink.buffer().use { sink ->
                requireNotNull(requestBodyBytes).toRequestBody(mediaType).writeTo(sink)
            }
            BodyResult(
                tempDiskBytes = tempFile.length(),
                bodyBytes = requireNotNull(requestBodyBytes).size.toLong(),
                sha256 = hashingSink.hash.hex()
            )
        }.also {
            requestBodyBytes = null
            tempFile.delete()
        }
    }

    private fun runStreaming(fixture: File, measured: Boolean): RunResult {
        stabilizeHeap()
        return measure("streaming", measured) {
            val requestBody = StreamingRequestBody(fixture.length(), mediaType) {
                fixture.source()
            }
            val hashingSink = HashingSink.sha256(blackholeSink())
            hashingSink.buffer().use { sink -> requestBody.writeTo(sink) }
            BodyResult(
                tempDiskBytes = 0,
                bodyBytes = requestBody.contentLength(),
                sha256 = hashingSink.hash.hex()
            )
        }
    }

    private fun measure(
        variant: String,
        measured: Boolean,
        block: () -> BodyResult
    ): RunResult {
        val runtime = Runtime.getRuntime()
        val baseline = usedHeap(runtime)
        val peak = AtomicLong(baseline)
        val sampling = AtomicBoolean(true)
        val sampler = thread(name = "upload-heap-sampler", start = true) {
            while (sampling.get()) {
                peak.accumulateAndGet(usedHeap(runtime), ::maxOf)
                Thread.yield()
            }
        }

        val started = System.nanoTime()
        val body = try {
            block()
        } finally {
            sampling.set(false)
            sampler.join()
        }
        val elapsed = System.nanoTime() - started

        return RunResult(
            variant = variant,
            elapsedNanos = if (measured) elapsed else 0,
            peakHeapDeltaBytes = if (measured) {
                (peak.get() - baseline).coerceAtLeast(0)
            } else {
                0
            },
            tempDiskBytes = body.tempDiskBytes,
            bodyBytes = body.bodyBytes,
            sha256 = body.sha256
        )
    }

    private fun stabilizeHeap() {
        repeat(2) {
            Runtime.getRuntime().gc()
            System.runFinalization()
            Thread.sleep(100)
        }
    }

    private fun usedHeap(runtime: Runtime): Long =
        runtime.totalMemory() - runtime.freeMemory()

    private fun writeMarkdownFixture(file: File, targetBytes: Int) {
        val line = "# Paper\n\nDeterministic upload benchmark note.\n\n"
            .encodeToByteArray()
        file.outputStream().buffered().use { output ->
            var remaining = targetBytes
            while (remaining > 0) {
                val count = minOf(remaining, line.size)
                output.write(line, 0, count)
                remaining -= count
            }
        }
        assertEquals(targetBytes.toLong(), file.length())
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        file.inputStream().use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private data class BodyResult(
        val tempDiskBytes: Long,
        val bodyBytes: Long,
        val sha256: String
    )

    private data class RunResult(
        val variant: String,
        val elapsedNanos: Long,
        val peakHeapDeltaBytes: Long,
        val tempDiskBytes: Long,
        val bodyBytes: Long,
        val sha256: String
    )

    private companion object {
        const val MIB = 1024 * 1024
    }
}
