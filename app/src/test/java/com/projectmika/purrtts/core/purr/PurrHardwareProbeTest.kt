package com.projectmika.purrtts.core.purr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ✨ PURE-JVM TESTS FOR SMART HARDWARE OFFLOAD SOC CLASSIFICATION (v1.8.5) ✨
 */
class PurrHardwareProbeTest {

    @Test
    fun `qualcomm snapdragon enables Adreno OpenCL, Hexagon NPU, and NNAPI`() {
        val profile = PurrHardwareProbe.classifyHardware(
            socManufacturer = "QTI",
            socModel = "SM8650",
            hardware = "qcom",
            board = "pineapple",
            sdkInt = 34,
            cpuCores = 8,
            hasVendorOpenCl = true,
            hasVendorQnnHtp = true
        )
        assertTrue(profile.hasAdrenoOpenCl)
        assertTrue(profile.hasHexagonNpu)
        assertTrue(profile.hasNnapi)
        assertEquals(6, profile.optimalCpuThreads)
        assertTrue(profile.recommendedStrategyLabel.contains("Adreno OpenCL"))
        assertTrue(profile.recommendedStrategyLabel.contains("Hexagon"))
    }

    @Test
    fun `google tensor enables NNAPI without claiming Adreno OpenCL`() {
        val profile = PurrHardwareProbe.classifyHardware(
            socManufacturer = "Google",
            socModel = "Tensor G4",
            hardware = "komodo",
            board = "komodo",
            sdkInt = 35,
            cpuCores = 8,
            hasVendorOpenCl = false,
            hasVendorQnnHtp = false
        )
        assertFalse(profile.hasAdrenoOpenCl)
        assertFalse(profile.hasHexagonNpu)
        assertTrue(profile.hasNnapi)
        assertTrue(profile.recommendedStrategyLabel.contains("Google Tensor NNAPI"))
    }

    @Test
    fun `mediatek dimensity selects APU NNAPI strategy`() {
        val profile = PurrHardwareProbe.classifyHardware(
            socManufacturer = "Mediatek",
            socModel = "MT6989",
            hardware = "mt6989",
            board = "k6989v1_64",
            sdkInt = 34,
            cpuCores = 8,
            hasVendorOpenCl = false,
            hasVendorQnnHtp = false
        )
        assertFalse(profile.hasAdrenoOpenCl)
        assertTrue(profile.hasNnapi)
        assertTrue(profile.recommendedStrategyLabel.contains("Dimensity"))
    }

    @Test
    fun `generic low-end or older SDK host falls back to XNNPACK ARM64 CPU`() {
        val profile = PurrHardwareProbe.classifyHardware(
            socManufacturer = "",
            socModel = "",
            hardware = "ranchu",
            board = "goldfish_arm64",
            sdkInt = 26,
            cpuCores = 4,
            hasVendorOpenCl = false,
            hasVendorQnnHtp = false
        )
        assertFalse(profile.hasAdrenoOpenCl)
        assertFalse(profile.hasHexagonNpu)
        assertFalse(profile.hasNnapi)
        assertEquals(4, profile.optimalCpuThreads)
        assertTrue(profile.recommendedStrategyLabel.contains("XNNPACK"))
    }
}
