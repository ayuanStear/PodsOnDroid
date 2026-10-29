package moe.chenxy.hyperpods.hook

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewParent
import io.github.libxposed.api.XposedInterface
import java.lang.ref.WeakReference
import moe.chenxy.hyperpods.utils.HostReflection
import moe.chenxy.hyperpods.utils.HyperPodsBroadcasts
import moe.chenxy.hyperpods.utils.data.BatteryParams
import moe.chenxy.hyperpods.utils.data.HyperPodsAction

/** Projects P.O.D.S. AACP battery data into HyperOS device-center cards. */
internal object NativeDeviceCardBridge {
    private data class Snapshot(
        val address: String?,
        val name: String?,
        val battery: BatteryParams?,
    )

    @Volatile private var snapshot = Snapshot(null, null, null)
    private val handler = Handler(Looper.getMainLooper())
    private var receiverRegistered = false
    private var lastView = WeakReference<View>(null)

    fun install(framework: XposedInterface, loader: ClassLoader) {
        val wrapper = Class.forName(
            "miui.systemui.devicecenter.devices.DeviceInfoWrapper", false, loader)
        framework.intercept(HostReflection.method(wrapper, "getBatteryValues")) { chain ->
            val values = batteryValues(chain.thisObject)
            if (values != null) values else chain.proceed()
        }
        framework.intercept(HostReflection.method(wrapper, "getHasBatteryInfo")) { chain ->
            if (batteryValues(chain.thisObject) != null) true else chain.proceed()
        }
        val deviceInfo = Class.forName("com.miui.circulate.device.api.DeviceInfo", false, loader)
        val batteryInfo = Class.forName("com.miui.circulate.device.api.BatteryInfo", false, loader)
        val constructor = batteryInfo.getDeclaredConstructor(Array<Double>::class.java).apply { isAccessible = true }
        framework.intercept(HostReflection.method(deviceInfo, "getBattery")) { chain ->
            val values = directBatteryValues(chain.thisObject)
            if (values != null) constructor.newInstance(values) else chain.proceed()
        }
    }

    fun observe(context: Context) {
        if (receiverRegistered) return
        synchronized(this) {
            if (receiverRegistered) return
            val app = context.applicationContext ?: context
            app.registerReceiver(object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (!HyperPodsBroadcasts.isTrusted(context, this, HyperPodsBroadcasts.BLUETOOTH)) return
                    when (intent.action) {
                        HyperPodsAction.ACTION_PODS_BATTERY_CHANGED -> {
                            val battery = intent.getParcelableExtra("status", BatteryParams::class.java)
                            snapshot = Snapshot(
                                intent.getStringExtra("address") ?: snapshot.address,
                                intent.getStringExtra("device_name") ?: snapshot.name,
                                battery ?: snapshot.battery,
                            )
                            refreshCards()
                        }
                        HyperPodsAction.ACTION_PODS_DISCONNECTED -> {
                            snapshot = Snapshot(null, null, null)
                            refreshCards()
                        }
                    }
                }
            }, IntentFilter().apply {
                addAction(HyperPodsAction.ACTION_PODS_BATTERY_CHANGED)
                addAction(HyperPodsAction.ACTION_PODS_DISCONNECTED)
            }, Context.RECEIVER_EXPORTED)
            receiverRegistered = true
        }
    }

    fun track(view: View) {
        lastView = WeakReference(view)
    }

    private fun batteryValues(wrapper: Any?): Array<Double>? {
        val info = runCatching { HostReflection.call(wrapper, "getDeviceInfo") }.getOrNull() ?: return null
        return directBatteryValues(info)
    }

    private fun directBatteryValues(info: Any?): Array<Double>? {
        val current = snapshot
        val battery = current.battery ?: return null
        info ?: return null
        if (!matchesDeviceCard(deviceCardAddress(info), deviceCardName(info), current.address, current.name)) return null
        fun value(v: moe.chenxy.hyperpods.utils.data.PodBatteryParams?) =
            v?.takeIf { it.isConnected && it.battery in 0..100 }?.battery?.toDouble() ?: -1.0
        val values = arrayOf(value(battery.left), value(battery.right), value(battery.case))
        return values.takeIf { it.any { value -> value >= 0 } }
    }

    private fun refreshCards() {
        val view = lastView.get() ?: return
        handler.post {
            val recycler = findRecycler(view) ?: return@post
            runCatching {
                val adapter = HostReflection.call(recycler, "getAdapter") ?: return@runCatching
                HostReflection.call(adapter, "notifyDataSetChanged")
            }.onFailure { Log.w("Art_Chen", "Unable to refresh native device card", it) }
        }
    }

    private fun findRecycler(view: View): Any? {
        var parent: ViewParent? = view.parent
        while (parent != null) {
            if (parent.javaClass.name.endsWith("RecyclerView")) return parent
            parent = parent.parent
        }
        return null
    }
}
