package moe.chenxy.hyperpods.ui.components

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import moe.chenxy.hyperpods.BuildConfig
import moe.chenxy.hyperpods.R
import moe.chenxy.hyperpods.utils.AppPreferences
import moe.chenxy.hyperpods.utils.HyperPodsBroadcasts
import moe.chenxy.hyperpods.utils.data.HyperPodsAction
import moe.chenxy.hyperpods.utils.spatial.SpatialAudioMode
import moe.chenxy.hyperpods.utils.spatial.SpatialAudioRuntime
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import moe.chenxy.hyperpods.ui.components.DashboardGroup as Card

private data class SpatialUiState(
    val supported: Boolean? = null,
    val helperAvailable: Boolean? = null,
    val enabled: Boolean? = null,
    val actual: Int = -1,
    val error: String? = null,
)

private fun SpatialAudioMode.label(): Int = when (this) {
    SpatialAudioMode.OFF -> R.string.spatial_audio_off
    SpatialAudioMode.FIXED -> R.string.spatial_audio_fixed
    SpatialAudioMode.HEAD_TRACKED -> R.string.spatial_audio_head_tracked
}

private fun SpatialAudioMode.summary(): Int = when (this) {
    SpatialAudioMode.OFF -> R.string.spatial_audio_off_summary
    SpatialAudioMode.FIXED -> R.string.spatial_audio_fixed_summary
    SpatialAudioMode.HEAD_TRACKED -> R.string.spatial_audio_head_tracked_summary
}

@Composable
fun SpatialAudioPage(isActive: Boolean) {
    val context = LocalContext.current
    var selected by remember { mutableStateOf(AppPreferences.read(context).spatialAudioMode) }
    var status by remember { mutableStateOf(SpatialUiState()) }
    var pending by remember { mutableStateOf<SpatialAudioMode?>(null) }

    fun apply(mode: SpatialAudioMode) {
        val snapshot = AppPreferences.read(context).copy(spatialAudioMode = mode)
        if (!AppPreferences.save(context, snapshot)) return
        AppPreferences.publish(context, snapshot)
        selected = mode
        pending = mode
        status = SpatialUiState()
        SpatialAudioRuntime.setMode(mode)
    }

    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context, intent: Intent) {
                val trusted = HyperPodsBroadcasts.isTrusted(receiverContext, this,
                    HyperPodsBroadcasts.BLUETOOTH) ||
                    HyperPodsBroadcasts.isTrusted(receiverContext, this, BuildConfig.APPLICATION_ID)
                if (!trusted) return
                val mode = SpatialAudioMode.fromWire(intent.getStringExtra("mode"))
                if (mode != selected) return
                pending = null
                if (intent.action == HyperPodsAction.ACTION_SPATIAL_AUDIO_TRACKING_STATE) {
                    intent.getStringExtra("error")?.let { error -> status = status.copy(error = error) }
                    return
                }
                status = SpatialUiState(
                    supported = intent.getBooleanExtra("supported", false),
                    helperAvailable = intent.getBooleanExtra("helper_available", false),
                    enabled = intent.getBooleanExtra("enabled", false),
                    actual = intent.getIntExtra("actual", -1),
                    error = intent.getStringExtra("error"),
                )
            }
        }
        context.registerReceiver(receiver, IntentFilter().apply {
            addAction(HyperPodsAction.ACTION_SPATIAL_AUDIO_STATE)
            addAction(HyperPodsAction.ACTION_SPATIAL_AUDIO_TRACKING_STATE)
        }, Context.RECEIVER_EXPORTED)
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }

    LaunchedEffect(isActive) {
        if (isActive) SpatialAudioRuntime.setMode(selected)
    }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(stringResource(R.string.spatial_audio_intro), fontSize = 14.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.padding(horizontal = 8.dp))
        Card {
            Column {
                SpatialAudioMode.entries.forEachIndexed { index, mode ->
                    if (index > 0) DashboardDivider(Modifier.padding(start = 64.dp, end = 16.dp))
                    Row(Modifier.fillMaxWidth().clickable { apply(mode) }
                        .padding(horizontal = 18.dp, vertical = 15.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Box(Modifier.size(30.dp).clip(RoundedCornerShape(10.dp))
                            .background(if (selected == mode) MiuixTheme.colorScheme.primary
                            else MiuixTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center) {
                            Text(if (selected == mode) "✓" else "", color = MiuixTheme.colorScheme.onPrimary, fontSize = 17.sp)
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(stringResource(mode.label()), fontSize = 17.sp)
                            Text(stringResource(mode.summary()), fontSize = 13.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        }
                    }
                }
            }
        }
        if (pending != null) Text(stringResource(R.string.spatial_audio_applying), fontSize = 13.sp,
            color = MiuixTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 8.dp))
        status.error?.let { error ->
            val rootPermissionError = error.contains("Permission denied", ignoreCase = true) ||
                error.contains("Cannot run program \"su\"", ignoreCase = true) ||
                error.contains("root permission", ignoreCase = true)
            Text(if (rootPermissionError) stringResource(R.string.spatial_audio_root_denied) else error,
                fontSize = 13.sp, color = MiuixTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 8.dp))
            if (rootPermissionError || error.contains("root", ignoreCase = true)) {
                Text(stringResource(R.string.spatial_audio_open_root_manager), fontSize = 14.sp,
                    color = MiuixTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                        .clickable { openRootManager(context) })
            }
        }
        status.supported?.let {
            Text(stringResource(when {
                it && selected == SpatialAudioMode.HEAD_TRACKED && status.actual == 1 ->
                    R.string.spatial_audio_status_head_tracked
                it && selected == SpatialAudioMode.HEAD_TRACKED && status.enabled == true ->
                    R.string.spatial_audio_waiting_for_spatial_playback
                it && status.enabled == true -> R.string.spatial_audio_status_enabled
                it -> R.string.spatial_audio_status_disabled
                status.helperAvailable == false -> R.string.spatial_audio_helper_unavailable
                else -> R.string.spatial_audio_unsupported
            }), fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(horizontal = 8.dp))
        }
    }
}

private fun openRootManager(context: Context) {
    for (pkg in listOf("me.weishu.kernelsu", "com.topjohnwu.magisk")) {
        val intent = context.packageManager.getLaunchIntentForPackage(pkg) ?: continue
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return
    }
}
