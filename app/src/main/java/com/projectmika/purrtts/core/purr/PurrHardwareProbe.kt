package com.projectmika.purrtts.core.purr

import android.os.Build
import java.io.File

/**
 * ✨ SMART HARDWARE OFFLOAD PROBE (v1.8.5) ✨
 * Inspects SoC vendor/model and device accelerator libraries once to guide each TTS runtime
 * toward its fastest safe accelerator (Adreno OpenCL, Hexagon QNN / NNAPI FP16, or XNNPACK)
 * while ALWAYS failing soft to CPU if a specific model graph rejects hardware delegation.
 */
data class HardwareProfile(
    val socLabel: String,
    val hasAdrenoOpenCl: Boolean,
    val hasHexagonNpu: Boolean,
    val hasNnapi: Boolean,
    val optimalCpuThreads: Int,
    val recommendedStrategyLabel: String
)

object PurrHardwareProbe {

    private val OPENCL_CANDIDATE_PATHS = listOf(
        "/vendor/lib64/libOpenCL.so",
        "/system/vendor/lib64/libOpenCL.so",
        "/vendor/lib/libOpenCL.so"
    )

    private val QNN_HTP_CANDIDATE_PATHS = listOf(
        "/vendor/lib64/libQnnHtp.so",
        "/odm/lib64/libQnnHtp.so",
        "/system/vendor/lib64/libQnnHtp.so"
    )

    fun detectDevice(): HardwareProfile {
        val socMfg = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Build.SOC_MANUFACTURER.orEmpty()
        } else {
            ""
        }
        val socModel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Build.SOC_MODEL.orEmpty()
        } else {
            ""
        }
        val hardware = Build.HARDWARE.orEmpty()
        val board = Build.BOARD.orEmpty()
        val cpuCores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val hasOpenCl = OPENCL_CANDIDATE_PATHS.any { runCatching { File(it).exists() }.getOrDefault(false) }
        val hasQnnHtp = QNN_HTP_CANDIDATE_PATHS.any { runCatching { File(it).exists() }.getOrDefault(false) }

        return classifyHardware(
            socManufacturer = socMfg,
            socModel = socModel,
            hardware = hardware,
            board = board,
            sdkInt = Build.VERSION.SDK_INT,
            cpuCores = cpuCores,
            hasVendorOpenCl = hasOpenCl,
            hasVendorQnnHtp = hasQnnHtp
        )
    }

    /**
     * Pure-JVM hardware classification entrypoint (unit-tested without Android runtime).
     */
    fun classifyHardware(
        socManufacturer: String,
        socModel: String,
        hardware: String,
        board: String,
        sdkInt: Int,
        cpuCores: Int,
        hasVendorOpenCl: Boolean,
        hasVendorQnnHtp: Boolean
    ): HardwareProfile {
        val combined = listOf(socManufacturer, socModel, hardware, board)
            .joinToString(" ")
            .lowercase()

        val isQualcomm = combined.contains("qti") ||
            combined.contains("qualcomm") ||
            combined.contains("snapdragon") ||
            combined.contains("qcom") ||
            combined.contains("sm8") ||
            combined.contains("sm7") ||
            combined.contains("sm6")
        val isTensor = combined.contains("tensor") ||
            combined.contains("gs1") ||
            combined.contains("gs2") ||
            combined.contains("zum") ||
            (socManufacturer.equals("google", ignoreCase = true) && hardware.isNotBlank())
        val isMediaTek = combined.contains("mediatek") ||
            combined.contains("mt6") ||
            combined.contains("dimensity")
        val isExynos = combined.contains("exynos") ||
            combined.contains("s5e")

        val hasAdrenoOpenCl = hasVendorOpenCl || isQualcomm
        val hasHexagonNpu = hasVendorQnnHtp || isQualcomm
        val hasNnapi = sdkInt >= 28 && (isQualcomm || isTensor || isMediaTek || isExynos || hasVendorQnnHtp)
        val optimalThreads = cpuCores.coerceIn(2, 6)

        val socLabel = buildString {
            val cleanModel = socModel.trim()
            val cleanMfg = socManufacturer.trim()
            when {
                cleanModel.isNotEmpty() && cleanMfg.isNotEmpty() -> append("$cleanMfg $cleanModel")
                cleanModel.isNotEmpty() -> append(cleanModel)
                hardware.trim().isNotEmpty() -> append(hardware.trim().uppercase())
                else -> append("ARM64_HOST")
            }
        }

        val strategy = when {
            hasAdrenoOpenCl && hasHexagonNpu -> "Adreno OpenCL + Hexagon/NNAPI FP16 + XNNPACK (${optimalThreads}T)"
            hasAdrenoOpenCl -> "Adreno OpenCL + XNNPACK (${optimalThreads}T)"
            isTensor -> "Google Tensor NNAPI + XNNPACK (${optimalThreads}T)"
            isMediaTek -> "Dimensity APU/NNAPI + XNNPACK (${optimalThreads}T)"
            hasNnapi -> "NNAPI FP16 + XNNPACK (${optimalThreads}T)"
            else -> "ARM64 NEON / XNNPACK (${optimalThreads}T)"
        }

        return HardwareProfile(
            socLabel = socLabel,
            hasAdrenoOpenCl = hasAdrenoOpenCl,
            hasHexagonNpu = hasHexagonNpu,
            hasNnapi = hasNnapi,
            optimalCpuThreads = optimalThreads,
            recommendedStrategyLabel = strategy
        )
    }
}
