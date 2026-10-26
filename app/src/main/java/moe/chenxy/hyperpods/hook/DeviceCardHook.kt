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

object DeviceCardHook {
    fun install(framework: XposedInterface, classLoader: ClassLoader) {
        val handler = Handler(Looper.getMainLooper())
        val pendingClicks = mutableMapOf<String, (String?) -> Unit>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                context ?: return
                if (!HyperPodsBroadcasts.isTrusted(context, this, HyperPodsBroadcasts.BLUETOOTH)) return
                val request = intent?.getStringExtra("request_id") ?: return
                pendingClicks.remove(request)?.invoke(intent.getStringExtra("mac"))
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
            val info = HostReflection.call(instance, "getDeviceInfo")
            if (Looper.myLooper() != Looper.getMainLooper()) return@intercept chain.proceed()
            val type = runCatching { HostReflection.call(info, "getDeviceType") as String }.getOrDefault("unknown")
            val id = runCatching { HostReflection.call(info, "getId") as String }.getOrNull()
                ?: return@intercept chain.proceed()
            Log.d("Art_Chen", "Device card click observed: type=$type")
            if (!registered) {
                SystemUiContext.resolve(context).registerReceiver(receiver,
                    IntentFilter(HyperPodsAction.ACTION_PODS_MAC_RECEIVED), null, handler,
                    Context.RECEIVER_EXPORTED)
                registered = true
            }
            val request = UUID.randomUUID().toString()
            lateinit var timeout: Runnable
            pendingClicks[request] = { mac ->
                handler.removeCallbacks(timeout)
                try {
                    if (mac == id) {
                        Log.i("Art_Chen", "Opening PodsOnDroid headphone panel for the matching card")
                        context.startActivity(Intent("com.hxlh50k.pods.action.show_headphone_panel")
                            .setPackage(BuildConfig.APPLICATION_ID).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        panelController?.let { HostReflection.call(it, "exitOrHide") }
                    } else {
                        Log.d("Art_Chen", "Device card is not the current AirPods; preserving the original click")
                        original.invoke(instance, context)
                    }
                } catch (error: Throwable) {
                    Log.e("Art_Chen", "Unable to open headset controls", error)
                    if (mac == id) runCatching { original.invoke(instance, context) }
                        .onFailure { Log.e("Art_Chen", "Unable to restore the original device-card click", it) }
                }
            }
            timeout = Runnable {
                Log.w("Art_Chen", "AirPods identity lookup timed out; preserving the original card click")
                pendingClicks.remove(request)?.invoke(null)
            }
            try {
                if (!handler.postDelayed(timeout, 1_500)) {
                    pendingClicks.remove(request)
                    return@intercept chain.proceed()
                }
                fun requestMac() {
                    HyperPodsBroadcasts.send(context, Intent(HyperPodsAction.ACTION_GET_PODS_MAC)
                        .putExtra("request_id", request), HyperPodsBroadcasts.BLUETOOTH)
                }
                requestMac()
                handler.postDelayed({ if (request in pendingClicks) requestMac() }, 500)
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
