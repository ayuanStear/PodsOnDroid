package moe.chenxy.hyperpods.utils.spatial

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import moe.chenxy.hyperpods.utils.HyperPodsBroadcasts
import moe.chenxy.hyperpods.utils.data.HyperPodsAction

/** App-process owner for root Spatializer and UHID operations. Bluetooth only
 * forwards poses because KernelSU root grants are app-scoped, not Bluetooth-UID
 * scoped. */
object SpatialAudioRuntime {
    private const val TAG = "Pods-SpatialAudio"
    private val scope = CoroutineScope(Dispatchers.IO)
    private val bridge = RootHeadTrackerBridge()
    private val processor = SpatialHeadPoseProcessor()
    private lateinit var context: Context
    private var initialized = false
    private var requested = SpatialAudioMode.OFF
    private var lastApplied: SpatialAudioMode? = null
    private var applyJob: Job? = null
    @Volatile private var trackingActive = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(receiverContext: Context, intent: Intent) {
            if (!HyperPodsBroadcasts.isTrusted(receiverContext, this, HyperPodsBroadcasts.BLUETOOTH)) return
            when (intent.action) {
                HyperPodsAction.ACTION_SPATIAL_AUDIO_POSE -> submitPose(intent)
                HyperPodsAction.ACTION_SPATIAL_AUDIO_TRACKING_STATE -> {
                    if (!intent.getBooleanExtra("tracking_active", false)) {
                        trackingActive = false
                        bridge.stop()
                    }
                }
                HyperPodsAction.ACTION_PODS_DISCONNECTED -> {
                    trackingActive = false
                    bridge.stop()
                    processor.reset()
                }
                HyperPodsAction.ACTION_PODS_MAC_RECEIVED -> {
                    val mac = intent.getStringExtra("mac").orEmpty()
                    if (requested == SpatialAudioMode.HEAD_TRACKED && MAC.matches(mac)) {
                        schedule { startHeadTracked(mac) }
                    }
                }
            }
        }
    }

    @Synchronized
    fun initialize(appContext: Context) {
        if (initialized) return
        context = appContext.applicationContext
        context.registerReceiver(receiver, IntentFilter().apply {
            addAction(HyperPodsAction.ACTION_SPATIAL_AUDIO_POSE)
            addAction(HyperPodsAction.ACTION_SPATIAL_AUDIO_TRACKING_STATE)
            addAction(HyperPodsAction.ACTION_PODS_DISCONNECTED)
            addAction(HyperPodsAction.ACTION_PODS_MAC_RECEIVED)
        }, Context.RECEIVER_EXPORTED)
        initialized = true
    }

    fun setMode(mode: SpatialAudioMode) {
        if (!initialized) {
            Log.w(TAG, "Runtime used before initialization")
            return
        }
        if (mode == requested && mode == lastApplied) return
        Log.i(TAG, "Requested spatial audio mode=" + mode.wireValue)
        requested = mode
        applyJob?.cancel()
        applyJob = scope.launch { apply(mode) }
    }

    private fun schedule(block: suspend () -> Unit) {
        applyJob?.cancel()
        applyJob = scope.launch { block() }
    }

    private suspend fun apply(mode: SpatialAudioMode) {
        trackingActive = false
        processor.reset()
        HyperPodsBroadcasts.send(context, Intent(HyperPodsAction.ACTION_SPATIAL_AUDIO_APPLY)
            .putExtra("mode", mode.wireValue), HyperPodsBroadcasts.BLUETOOTH)
        val controller = RootSpatialAudioController(context.applicationInfo.sourceDir)
        when (mode) {
            SpatialAudioMode.OFF -> {
                bridge.stop()
                if (lastApplied != null && lastApplied != SpatialAudioMode.OFF) {
                    broadcast(controller.setMode(mode))
                } else {
                    broadcast(SpatialAudioRootState(
                        helperAvailable = true,
                        spatializerAvailable = true,
                        spatializerEnabled = false,
                    ))
                }
                lastApplied = SpatialAudioMode.OFF
            }
            SpatialAudioMode.FIXED -> {
                bridge.stop()
                val state = controller.setMode(mode)
                broadcast(state)
                if (state.supported) lastApplied = SpatialAudioMode.FIXED
            }
            SpatialAudioMode.HEAD_TRACKED -> {
                bridge.stop()
                broadcast(SpatialAudioRootState(
                    helperAvailable = true,
                    spatializerAvailable = false,
                    error = "Waiting for the Bluetooth headset address",
                ))
                Log.i(TAG, "Waiting for the Bluetooth headset address")
        HyperPodsBroadcasts.send(context, Intent(HyperPodsAction.ACTION_GET_PODS_MAC),
                    HyperPodsBroadcasts.BLUETOOTH)
            }
        }
    }

    private suspend fun startHeadTracked(mac: String) {
        if (requested != SpatialAudioMode.HEAD_TRACKED) return
        Log.i(TAG, "Starting root UHID head tracker for the connected AirPods")
        if (!bridge.start(context, mac)) {
            Log.e(TAG, "Root UHID head tracker start failed")
            broadcast(SpatialAudioRootState(
                helperAvailable = false,
                spatializerAvailable = false,
                error = "Unable to start the root UHID head tracker. Check root permission for " + context.packageName,
            ))
            return
        }
        val controller = RootSpatialAudioController(context.applicationInfo.sourceDir)
        val state = controller.setMode(SpatialAudioMode.HEAD_TRACKED)
        Log.i(TAG, "Spatializer enabled=" + state.spatializerEnabled + " desired=" + state.desiredMode + " actual=" + state.actualMode + " error=" + state.error)
        trackingActive = state.supported
        if (!trackingActive) bridge.stop() else lastApplied = SpatialAudioMode.HEAD_TRACKED
        broadcast(state)
    }

    private fun submitPose(intent: Intent) {
        if (!trackingActive || !initialized) return
        val sample = processor.processRawComponents(
            intent.getIntExtra("x", 0), intent.getIntExtra("y", 0),
            intent.getIntExtra("z", 0), intent.getLongExtra("at", SystemClock.elapsedRealtimeNanos())
        ) ?: return
        bridge.submit(sample.pose)
    }

    private fun broadcast(state: SpatialAudioRootState) {
        HyperPodsBroadcasts.send(context, Intent(HyperPodsAction.ACTION_SPATIAL_AUDIO_STATE).apply {
            putExtra("mode", requested.wireValue)
            putExtra("supported", state.supported)
            putExtra("helper_available", state.helperAvailable)
            putExtra("enabled", state.spatializerEnabled)
            putExtra("desired", state.desiredMode)
            putExtra("actual", state.actualMode)
            putExtra("error", state.error)
        })
    }

    private val MAC = Regex("(?i)^[0-9a-f]{2}(:[0-9a-f]{2}){5}$")
}
