/*
 * Portions adapted from LibrePods v1.0.10 RootSpatialAudioController.
 * Copyright (C) 2025 LibrePods contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package moe.chenxy.hyperpods.utils.spatial

import java.io.IOException
import java.util.concurrent.TimeUnit

data class SpatialAudioRootState(
    val helperAvailable: Boolean,
    val spatializerAvailable: Boolean,
    val spatializerEnabled: Boolean = false,
    val desiredMode: Int = -1,
    val actualMode: Int = -1,
    val error: String? = null,
) {
    val supported: Boolean get() = spatializerAvailable && error == null
}

/** Controls Android's hidden Spatializer through a short-lived root app_process. */
class RootSpatialAudioController(private val apkPath: String) {
    fun query(): SpatialAudioRootState {
        val helperAvailable = runRoot(HELPER_PROBE).exitCode == 0
        return parseState(helperAvailable, runSpatializer(null))
    }

    fun setMode(mode: SpatialAudioMode): SpatialAudioRootState {
        val helperAvailable = runRoot(HELPER_PROBE).exitCode == 0
        if (mode == SpatialAudioMode.HEAD_TRACKED && !helperAvailable) {
            return SpatialAudioRootState(
                helperAvailable = false,
                spatializerAvailable = false,
                error = "Root head-tracker helper is unavailable",
            )
        }
        val enabled = mode != SpatialAudioMode.OFF
        val headTrackingMode = if (mode == SpatialAudioMode.HEAD_TRACKED) 1 else -1
        return parseState(helperAvailable, runSpatializer(enabled, headTrackingMode), mode)
    }

    private fun runSpatializer(
        spatializerEnabled: Boolean? = null,
        headTrackingMode: Int = -1,
    ): CommandResult {
        val modeArguments = spatializerEnabled?.let { " ${if (it) 1 else 0} $headTrackingMode" }.orEmpty()
        val command = "CLASSPATH=${shellQuote(apkPath)} exec /system/bin/app_process " +
            "/system/bin $COMMAND_CLASS$modeArguments"
        return runRoot(command)
    }

    private fun parseState(
        helperAvailable: Boolean,
        command: CommandResult,
        requested: SpatialAudioMode? = null,
    ): SpatialAudioRootState {
        val values = command.output.lineSequence().mapNotNull { line ->
            val separator = line.indexOf('=')
            if (separator <= 0) null else line.substring(0, separator) to line.substring(separator + 1)
        }.toMap()
        val desiredMode = values["desired"]?.toIntOrNull()
        val actualMode = values["actual"]?.toIntOrNull()
        val enabled = values["enabled"]?.toBooleanStrictOrNull()
        val error = values["error"] ?: command.error
        val expectedDesired = when (requested) {
            SpatialAudioMode.OFF -> -1
            SpatialAudioMode.FIXED -> -1
            SpatialAudioMode.HEAD_TRACKED -> 1
            null -> null
        }
        val mismatch = requested != null && enabled != null && desiredMode != null &&
            (enabled != (requested != SpatialAudioMode.OFF) || desiredMode != expectedDesired)
        return SpatialAudioRootState(
            helperAvailable = helperAvailable,
            spatializerAvailable = command.exitCode == 0 && enabled != null &&
                desiredMode != null && actualMode != null && !mismatch,
            spatializerEnabled = enabled ?: false,
            desiredMode = desiredMode ?: -1,
            actualMode = actualMode ?: -1,
            error = error ?: if (mismatch) "Spatializer did not accept the requested mode" else null,
        )
    }

    private fun runRoot(command: String): CommandResult = try {
        val process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
        if (!process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            CommandResult(-1, "", "Root command timed out")
        } else {
            val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
            val code = process.exitValue()
            CommandResult(code, output, if (code == 0) null else output.ifBlank { "Root command failed" })
        }
    } catch (error: IOException) {
        CommandResult(-1, "", error.message ?: "Unable to start root command")
    }

    private fun shellQuote(value: String): String = "'${value.replace("'", "'\''")}'"

    private data class CommandResult(val exitCode: Int, val output: String, val error: String? = null)

    private companion object {
        const val COMMAND_CLASS = "moe.chenxy.hyperpods.utils.spatial.SpatializerRootCommand"
        const val COMMAND_TIMEOUT_SECONDS = 8L
        const val HELPER_PROBE = "test -r /dev/uhid && test -w /dev/uhid && " +
            "(test -x /data/local/tmp/pods-headtracker || " +
            "test -x /system/bin/librepods-headtracker)"
    }
}
