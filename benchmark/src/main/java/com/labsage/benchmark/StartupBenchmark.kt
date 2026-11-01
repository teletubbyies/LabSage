package com.labsage.benchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.benchmark.perfetto.PerfettoCapture
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Cold/warm startup timings. Compare CompilationMode.None (JIT, i.e. the
 * state without any baseline profile) against SpeedProfile (the state a
 * baseline profile provides) — the delta is the profile's contribution.
 */
@RunWith(Parameterized::class)
class StartupBenchmark(private val compilationMode: CompilationMode) {

    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun startup() = benchmarkRule.measureRepeated(
        packageName = "com.labsage.app",
        metrics = listOf(androidx.benchmark.macro.metrics.StartupTimingMetric()),
        compilationMode = compilationMode,
        startupMode = StartupMode.COLD,
        iterations = 10,
        setupBlock = { pressHome() }
    ) {
        startActivityAndWait()
    }

    companion object {
        @Parameterized.Parameters(name = "compilation={0}")
        @JvmStatic
        fun parameters() = listOf(
            CompilationMode.None(),
            CompilationMode.Partial(baselineProfileEnabled = true),
        )
    }
}
