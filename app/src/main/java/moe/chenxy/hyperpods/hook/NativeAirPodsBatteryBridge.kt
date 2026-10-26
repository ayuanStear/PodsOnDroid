package moe.chenxy.hyperpods.hook

import android.bluetooth.BluetoothDevice
import android.content.Context
import android.util.Log
import io.github.libxposed.api.XposedInterface
import moe.chenxy.hyperpods.utils.HostReflection

/**
 * Bridges HyperOS's native L2CAP battery record into its AirPods UI helper.
 *
 * Some HyperOS builds persist the `air_battery` preference but do not notify
 * the companion ContentObserver. Reusing the host parser keeps the bridge
 * model-agnostic and avoids synthesizing battery values.
 */
internal object NativeAirPodsBatteryBridge {
    private const val TAG = "PODS-Battery"
    private const val BATTERY_KEY = "air_battery"
    private const val STORAGE_CLASS =
        "com.xiaomi.bluetooth.modules.airlibrary.db.AirLocalStorage"
    private const val SERVICE_CLASS =
        "com.android.bluetooth.ble.app.fastconnect.MiuiFastConnectService"
    private const val HELPER_CLASS =
        "com.android.bluetooth.ble.app.fastconnect.airpods.MiuiAirPodsFastConnectHelper"

    @Volatile private var lastStage: String? = null

    fun install(framework: XposedInterface, classLoader: ClassLoader) {
        val storage = Class.forName(STORAGE_CLASS, false, classLoader)
        val method = HostReflection.method(
            storage,
            "notifyAndStoreDeviceState",
            Context::class.java,
            String::class.java,
            String::class.java,
            BluetoothDevice::class.java,
        )
        framework.intercept(method) { chain ->
            val result = chain.proceed()
            val context = chain.args[0] as? Context
            val key = chain.args[1] as? String
            val value = chain.args[2] as? String
            val device = chain.args[3] as? BluetoothDevice
            if (context != null && key == BATTERY_KEY && !value.isNullOrBlank() && device != null) {
                val stage = runCatching { forward(context, classLoader, device, value) }
                    .onFailure { Log.w(TAG, "Unable to forward native AirPods battery state", it) }
                    .getOrDefault("forward failed")
                if (stage != lastStage) {
                    Log.i(TAG, "Native AirPods battery bridge: $stage")
                    lastStage = stage
                }
            }
            result
        }
    }

    private fun forward(
        context: Context,
        baseClassLoader: ClassLoader,
        device: BluetoothDevice,
        value: String,
    ): String {
        val serviceClass = findHostClass(SERVICE_CLASS, context, baseClassLoader)
        val service = HostReflection.callStatic(serviceClass, "getInstance")
            ?: return "service unavailable"
        val helperClass = findHostClass(
            HELPER_CLASS,
            context,
            service.javaClass.classLoader ?: baseClassLoader,
        )
        val helper = runCatching { HostReflection.staticField(helperClass, "sInstance") }
            .getOrNull()
            ?: HostReflection.callStatic(helperClass, "getInstance", service)
            ?: return "helper unavailable"
        val records = HostReflection.field(helper, "mDeviceInfoRecordMap") as? Map<*, *>
            ?: return "record map unavailable"
        val record = records.entries.firstOrNull {
            (it.key as? BluetoothDevice)?.address == device.address
        }?.value ?: return "record unavailable"
        val batteryHelper = HostReflection.field(record, "mBLEBatteryHelper")
            ?: return "battery helper unavailable"
        HostReflection.call(batteryHelper, "updateBatteryInfo", device.address, value)
        return "forwarded"
    }

    /** Qigsaw installs feature code after the base package class loader is created. */
    private fun findHostClass(name: String, context: Context, fallback: ClassLoader): Class<*> {
        val loaders = buildList {
            context.applicationContext?.classLoader?.let(::add)
            Thread.currentThread().contextClassLoader?.let(::add)
            add(fallback)
        }.distinct()
        for (loader in loaders) {
            runCatching { Class.forName(name, false, loader) }.getOrNull()?.let { return it }
        }
        runCatching {
            val splitManagerClass = Class.forName(
                "com.iqiyi.android.qigsaw.core.splitload.f",
                false,
                fallback,
            )
            val splitManager = HostReflection.callStatic(splitManagerClass, "c")
            val splitLoaders = HostReflection.call(splitManager, "e") as? Collection<*>
            splitLoaders?.asSequence()
                ?.filterIsInstance<ClassLoader>()
                ?.mapNotNull { loader -> runCatching { Class.forName(name, false, loader) }.getOrNull() }
                ?.firstOrNull()
                ?.let { return it }
        }
        throw ClassNotFoundException(name)
    }
}
