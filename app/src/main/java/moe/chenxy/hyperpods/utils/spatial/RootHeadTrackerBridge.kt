/*
 * Portions adapted from LibrePods v1.0.10 RootHeadTrackerBridge.
 * Copyright (C) 2025 LibrePods contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package moe.chenxy.hyperpods.utils.spatial

import android.content.Context
import android.os.Build
import android.util.Log
import moe.chenxy.hyperpods.BuildConfig
import java.io.BufferedWriter
import java.io.File
import java.io.IOException
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Streams decoded AirPods poses to a root UHID virtual head tracker. */
class RootHeadTrackerBridge {
    private var process: Process? = null
    private var writer: BufferedWriter? = null
    private var macAddress: String? = null

    @Synchronized
    fun start(context: Context, mac: String): Boolean {
        if (!MAC_PATTERN.matches(mac)) {
            Log.e(TAG, "Refusing invalid Bluetooth address")
            return false
        }
        if (process?.isAlive == true && macAddress == mac) return true
        stop()
        return try {
            if (!install(context)) return false
            val command = "exec $TARGET --mac $mac --stdin"
            val started = ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start()
            process = started
            writer = BufferedWriter(OutputStreamWriter(started.outputStream, StandardCharsets.US_ASCII))
            macAddress = mac
            Thread({
                runCatching {
                    started.inputStream.bufferedReader().useLines { lines -> lines.forEach { Log.d(TAG, it) } }
                }.onFailure { Log.d(TAG, "Head tracker log stream closed: ${it.message}") }
            }, "Pods-headtracker-log").apply { isDaemon = true; start() }
            Log.i(TAG, "Started root head tracker for $mac")
            true
        } catch (error: IOException) {
            Log.e(TAG, "Unable to start root head tracker", error)
            stop()
            false
        }
    }

    @Synchronized
    fun submit(pose: SpatialPose) {
        val output = writer ?: return
        try {
            output.write(String.format(Locale.US,
                "%.7f %.7f %.7f %.7f %.7f %.7f %d\n",
                pose.rx, pose.ry, pose.rz, pose.vx, pose.vy, pose.vz,
                pose.discontinuityCounter and 0xff))
            output.flush()
        } catch (error: IOException) {
            Log.e(TAG, "Head tracker pose stream closed", error)
            stop()
        }
    }

    @Synchronized
    fun stop() {
        val running = process
        runCatching { writer?.close() }
        writer = null
        process = null
        macAddress = null
        running?.destroy()
        if (running != null) {
            runCatching {
                if (!running.waitFor(STOP_TIMEOUT_MS, TimeUnit.MILLISECONDS)) running.destroyForcibly()
            }
        }
    }

    private fun install(context: Context): Boolean {
        val abi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty()
        if (!abi.startsWith("arm64")) {
            Log.w(TAG, "UHID helper is only packaged for arm64; current ABI=$abi")
            return false
        }
        return try {
            val module = context.createPackageContext(BuildConfig.APPLICATION_ID, 0)
            val staged = File(context.cacheDir, "pods-headtracker")
            module.assets.open(ASSET_PATH).use { input ->
                staged.outputStream().use { output -> input.copyTo(output) }
            }
            val command = "mkdir -p /data/local/tmp && cp ${shellQuote(staged.absolutePath)} $TARGET && " +
                "chmod 0755 $TARGET"
            val root = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
            val output = root.inputStream.bufferedReader().use { it.readText() }.trim()
            if (!root.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                root.destroyForcibly()
                Log.e(TAG, "Installing head tracker timed out")
                false
            } else if (root.exitValue() != 0) {
                Log.e(TAG, "Installing head tracker failed: $output")
                false
            } else true
        } catch (error: Exception) {
            Log.e(TAG, "Unable to extract/install head tracker", error)
            false
        }
    }

    private fun shellQuote(value: String): String = "'${value.replace("'", "'\''")}'"

    private companion object {
        const val TAG = "Pods-RootHeadTracker"
        const val STOP_TIMEOUT_MS = 400L
        const val COMMAND_TIMEOUT_SECONDS = 8L
        const val ASSET_PATH = "headtracker/arm64-v8a/headtracker_uhid"
        const val TARGET = "/data/local/tmp/pods-headtracker"
        val MAC_PATTERN = Regex("(?i)^[0-9a-f]{2}(:[0-9a-f]{2}){5}$")
    }
}
