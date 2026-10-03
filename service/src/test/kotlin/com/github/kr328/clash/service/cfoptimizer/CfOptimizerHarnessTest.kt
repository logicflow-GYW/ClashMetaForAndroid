package com.github.kr328.clash.service.cfoptimizer

import org.junit.Test

/**
 * The regression harnesses are deliberately data-driven and throw on any failed
 * assertion. Keep them behind JUnit so CI executes them instead of merely compiling
 * the service APK.
 */
class CfOptimizerHarnessTest {
    @Test
    fun engine() = runCfOptimizerEngineHarness()

    @Test
    fun memory() = runCfOptimizerMemoryHarness()

    @Test
    fun tuning() = runCfOptimizerTuningHarness()

    @Test
    fun qualityGate() = runCfQualityGateHarness()
}
