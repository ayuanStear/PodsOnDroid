package moe.chenxy.hyperpods.hook

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.util.Log
import io.github.libxposed.api.XposedInterface
import moe.chenxy.hyperpods.BuildConfig
import moe.chenxy.hyperpods.utils.HostReflection
import moe.chenxy.hyperpods.utils.HyperPodsBroadcasts
import moe.chenxy.hyperpods.utils.SystemUiContext
import moe.chenxy.hyperpods.utils.data.HyperPodsAction
import java.util.UUID

internal fun deviceCardAddress(info: Any): String? =
    runCatching { HostReflection.call(info, "getMac") as? String }.getOrNull()
        ?.takeIf { it.isNotBlank() }
        ?: runCatching { HostReflection.call(info, "getId") as? String }.getOrNull()

internal fun deviceCardName(info: Any): String? =
    runCatching { HostReflection.call(info, "getTitle") as? String }.getOrNull()
        ?.takeIf { it.isNotBlank() }

internal fun matchesDeviceCard(
    cardAddress: String?,
    cardName: String?,
    replyAddress: String?,
    replyName: String?,
): Boolean {
    fun normalizedMac(value: String?): String? {
        val normalized = value?.trim()?.uppercase()?.replace('-', ':') ?: return null
        return normalized.takeIf { it.matches(Regex("([0-9A-F]{2}:){5}[0-9A-F]{2}")) }
    }
    val cardMac = normalizedMac(cardAddress)
    val replyMac = normalizedMac(replyAddress)
    if (cardMac != null && replyMac != null) return cardMac == replyMac
    if (!cardAddress.isNullOrBlank() && cardAddress.equals(replyAddress, ignoreCase = true)) return true
    // Some HyperOS builds omit MAC from DeviceInfo. A unique third_headset card
    // can still be safely matched by its displayed name in that case.
    return !cardName.isNullOrBlank() && cardName.equals(replyName, ignoreCase = true)
}

object DeviceCardHook {
    fun install(framework: XposedInterface, classLoader: ClassLoader) {
        val handler = Handler(Looper.getMainLooper())
        val pendingClicks = mutableMapOf<String, (Intent?) -> Unit>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                context ?: return
                if (!HyperPodsBroadcasts.isTrusted(context, this, HyperPodsBroadcasts.BLUETOOTH)) return
                val request = intent?.getStringExtra("request_id") ?: return
                pendingClicks.remove(request)?.invoke(intent)
            }
        }
        var registered = false
        var panelController: Any? = null
        installHook("device-card panel") {
            val panel = Class.forName("miui.systemui.controlcenter.panel.main.MainPanelController", false, classLoader)
            HostReflection.methods(panel, "onCreate").forEach { method ->
                framework.intercept(method) { chain ->
                    val result = chain.proceed()
                    panelController = chain.thisObject
                    result
                }
            }
        }

        val wrapper = Class.forName("miui.systemui.devicecenter.devices.DeviceInfoWrapper", false, classLoader)
        val clicked = HostReflection.method(wrapper, "performClicked", Context::class.java)
        // Invokers can outlive an interception. Chains cannot be retained by the reply or timeout.
        val original = framework.getInvoker(clicked).setType(XposedInterface.Invoker.Type.ORIGIN)
        framework.intercept(clicked) { chain ->
            val instance = chain.thisObject
            val context = chain.args[0] as Context
            val info = HostReflection.call(instance, "getDeviceInfo") ?: return@intercept chain.proceed()
            val observedType = runCatching { HostReflection.call(info, "getDeviceType") as? String }.getOrNull()
            Log.d("Art_Chen", "Device card click: type=$observedType, " +
                "mac=${deviceCardAddress(info) != null}, name=${deviceCardName(info) != null}")
            if (observedType != "third_headset" ||
                Looper.myLooper() != Looper.getMainLooper()) return@intercept chain.proceed()
            val cardAddress = deviceCardAddress(info)
            val cardName = deviceCardName(info)
            if (!registered) {
                SystemUiContext.resolve(context).registerReceiver(receiver,
                    IntentFilter(HyperPodsAction.ACTION_PODS_MAC_RECEIVED), null, handler,
                    Context.RECEIVER_EXPORTED)
                registered = true
            }
            val request = UUID.randomUUID().toString()
            lateinit var timeout: Runnable
            pendingClicks[request] = { reply ->
                handler.removeCallbacks(timeout)
                val mac = reply?.getStringExtra("mac")
                try {
                    val matched = matchesDeviceCard(cardAddress, cardName, mac,
                        reply?.getStringExtra("device_name"))
                    Log.i("Art_Chen", "Device card identity result: match=$matched, reply=${reply != null}")
                    if (matched) {
                        context.startActivity(Intent("com.hxlh50k.pods.action.show_headphone_panel")
                            .setPackage(BuildConfig.APPLICATION_ID).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        panelController?.let { HostReflection.call(it, "exitOrHide") }
                    } else {
                        original.invoke(instance, context)
                    }
                } catch (error: Throwable) {
                    Log.e("Art_Chen", "Unable to open headset controls", error)
                    if (matchesDeviceCard(cardAddress, cardName, mac,
                            reply?.getStringExtra("device_name"))) runCatching { original.invoke(instance, context) }
                        .onFailure { Log.e("Art_Chen", "Unable to restore the original device-card click", it) }
                }
            }
            timeout = Runnable { pendingClicks.remove(request)?.invoke(null) }
            try {
                if (!handler.postDelayed(timeout, 500)) {
                    pendingClicks.remove(request)
                    return@intercept chain.proceed()
                }
                HyperPodsBroadcasts.send(context, Intent(HyperPodsAction.ACTION_GET_PODS_MAC)
                    .putExtra("request_id", request), HyperPodsBroadcasts.BLUETOOTH)
            } catch (error: Throwable) {
                handler.removeCallbacks(timeout)
                pendingClicks.remove(request)
                Log.e("Art_Chen", "Unable to request headset identity", error)
                return@intercept chain.proceed()
            }
            null
        }
    }
}
