package com.labsage.benchmark

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test

/**
 * Generates the startup baseline profile: rolls the app through cold start
 * and the conversation list (the first two screens of every session).
 *
 * Run from the benchmark build type; the .txt lands in build outputs and is
 * copied into app/src/main/baseline-prof.txt (see docs/PERF_BASELINE.md).
 */
class BaselineProfileGenerator {
    @get:Rule
    val baselineProfileRule = BaselineProfileRule()

    @Test
    fun generateStartupProfile() {
        baselineProfileRule.collect("com.labsage.app") {
            pressHome()
            startActivityAndWait()
            // Wait for the first real content of the entry screen (login form
            // when logged out, conversation list when logged in).
            device.wait(Until.hasObject(By.pkg("com.labsage.app").depth(3)), 5_000)
        }
    }
}
