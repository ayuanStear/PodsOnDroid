package moe.chenxy.hyperpods.hook

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import io.github.libxposed.api.XposedInterface
import moe.chenxy.hyperpods.pods.EarDetectionStatus
import moe.chenxy.hyperpods.utils.HostReflection
import moe.chenxy.hyperpods.utils.HyperPodsBroadcasts
import moe.chenxy.hyperpods.utils.data.BatteryParams
import moe.chenxy.hyperpods.utils.data.EarDetectionParams
import moe.chenxy.hyperpods.utils.data.HyperPodsAction
import moe.chenxy.hyperpods.utils.data.PodBatteryParams

/**
 * Bridges P.O.D.S. AACP state into Xiaomi Bluetooth's native AirPods repository.
 *
 * The Settings AirPods page is hosted by plugin.settings.java.airpods.MiuiAirpodsFragment,
 * but its state comes from com.xiaomi.bluetooth. Keeping the bridge in that process makes
 * both the native Settings page and other native consumers observe the same state.
 */
internal object XiaomiAirPodsStateBridge {
    private const val TAG = "PODS-XiaomiBridge"
    private const val AIRPODS_PREFS = "AirpodsModel"
    private const val BRIDGE_PREFS = "PodsOnDroidBridge"
    private const val AUTHORITY = "com.android.bluetooth.ble.app.headsetdata.provider"
    private const val DEVICE_EXTRA = "android.bluetooth.device.extra.DEVICE"
    private const val KEY = "extra_key"
    private const val VALUE = "extra_value"
    private const val COMMAND_STATE = "extra_command_state"
    private const val AIR_ANC = "air_anc"
    private const val AIR_WEAR_STATE = "air_wear_state"
    private const val REQUEST_THROTTLE_MS = 1500L

    internal data class Snapshot(
        val address: String = "",
        val name: String = "",
        val model: String = "",
        val left: String = "-",
        val right: String = "-",
        val case: String = "-",
        val chargeMask: Int = 0,
        val earMask: Int = 0,
        val ancCode: String = "",
    )

    @Volatile private var snapshot = Snapshot()
    private var appContext: Context? = null
    private var receiverRegistered = false
    private var lastRequestAt = 0L

    fun install(framework: XposedInterface, loader: ClassLoader) {
        val application = Class.forName("com.android.bluetooth.ble.MiuiApplication", false, loader)
        framework.intercept(HostReflection.method(application, "onCreate")) { chain ->
            val result = chain.proceed()
            runCatching { register(chain.thisObject as Context) }
                .onFailure { Log.e(TAG, "Unable to register native AirPods state receiver", it) }
            result
        }

        val provider = Class.forName(
            "com.android.bluetooth.ble.app.headset.miuibluetoothprovider.MiuiBluetoothContentProvider",
            false,
            loader,
        )
        val call = HostReflection.method(
            provider,
            "call",
            String::class.java,
            String::class.java,
            Bundle::class.java,
        )
        framework.intercept(call) { chain ->
            val providerContext = findContext(chain.thisObject)
            if (providerContext != null) {
                runCatching { register(providerContext) }
                    .onFailure { Log.e(TAG, "Unable to register receiver from native provider", it) }
            }
            val method = chain.args[0] as? String
            val argument = chain.args[1] as? String
            val extras = chain.args[2] as? Bundle
            val handled = runCatching { interceptProviderCall(providerContext, method, argument, extras) }
                .onFailure { Log.e(TAG, "Native AirPods provider interception failed", it) }
                .getOrNull()
            if (handled != null) handled else chain.proceed()
        }
        Log.i(TAG, "Native AirPods state bridge installed")
    }

    private fun register(rawContext: Context) {
        val context = rawContext.applicationContext ?: rawContext
        if (context.packageName != HyperPodsBroadcasts.XIAOMI_BLUETOOTH) return
        synchronized(this) {
            if (receiverRegistered) return
            appContext = context
            restore(context)
            context.registerReceiver(receiver, IntentFilter().apply {
                addAction(HyperPodsAction.ACTION_PODS_BATTERY_CHANGED)
                addAction(HyperPodsAction.ACTION_PODS_ANC_CHANGED)
                addAction(HyperPodsAction.ACTION_EAR_DETECTION_STATUS_CHANGED)
                addAction(HyperPodsAction.ACTION_PODS_DISCONNECTED)
            }, Context.RECEIVER_EXPORTED)
            receiverRegistered = true
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!HyperPodsBroadcasts.isTrusted(context, this, HyperPodsBroadcasts.BLUETOOTH)) return
            val app = context.applicationContext ?: context
            when (intent.action) {
                HyperPodsAction.ACTION_PODS_BATTERY_CHANGED -> onBattery(app, intent)
                HyperPodsAction.ACTION_PODS_ANC_CHANGED -> onAnc(app, intent)
                HyperPodsAction.ACTION_EAR_DETECTION_STATUS_CHANGED -> onEarDetection(app, intent)
                HyperPodsAction.ACTION_PODS_DISCONNECTED -> onDisconnected(app, intent)
            }
        }
    }

    private fun onBattery(context: Context, intent: Intent) {
        val battery = intent.getParcelableExtra("status", BatteryParams::class.java) ?: return
        val current = snapshot
        val address = normalizedMac(intent.getStringExtra("address")) ?: current.address
        if (address.isEmpty()) return
        val next = current.copy(
            address = address,
            name = intent.getStringExtra("device_name")?.takeIf { it.isNotBlank() } ?: current.name,
            model = intent.getStringExtra("model_number")?.takeIf { it.isNotBlank() } ?: current.model,
            left = batteryValue(battery.left),
            right = batteryValue(battery.right),
            case = batteryValue(battery.case),
            chargeMask = chargeMask(battery),
        )
        snapshot = next
        persistState(context, next)
        notifyStateChanged(context)
        Log.i(TAG, "Native state updated: left=${next.left}, right=${next.right}, case=${next.case}")
    }

    private fun onEarDetection(context: Context, intent: Intent) {
        val status = intent.getParcelableExtra("status", EarDetectionParams::class.java) ?: return
        val current = snapshot
        val address = normalizedMac(intent.getStringExtra("address")) ?: current.address
        if (address.isEmpty()) return
        val next = current.copy(address = address, earMask = earMask(status))
        snapshot = next
        persistState(context, next)
        persistWearState(context, address, status)
        notifyStateChanged(context)
        Log.i(TAG, "Native wear state updated: earMask=${next.earMask} airWearState=${nativeWearState(earMask(status))}")
    }

    private fun onAnc(context: Context, intent: Intent) {
        val status = intent.getIntExtra("status", 0)
        val code = ancCode(status) ?: return
        val current = snapshot
        if (current.address.isEmpty()) return
        val next = current.copy(ancCode = code)
        snapshot = next
        val prefs = context.getSharedPreferences(AIRPODS_PREFS, Context.MODE_PRIVATE)
        prefs.edit().putString("${next.address}-$AIR_ANC", code).apply()
        notifyAncChanged(context, next.address, code)
        Log.i(TAG, "Native ANC state updated: $code")
    }

    private fun onDisconnected(context: Context, intent: Intent) {
        val address = normalizedMac(intent.getStringExtra("address")) ?: snapshot.address
        if (address.isNotEmpty() && snapshot.address.isNotEmpty() && address != snapshot.address) return
        snapshot = Snapshot()
    }

    private fun interceptProviderCall(
        context: Context?,
        method: String?,
        argument: String?,
        extras: Bundle?,
    ): Bundle? {
        if (context == null) return null
        if (method == "getAirpodsState") {
            requestFreshState(context)
            return null
        }
        if (method != "airpodsRepository" || argument != "send_command") return null
        val key = extras?.getString(KEY) ?: return null
        if (key != AIR_ANC) return null
        val value = extras.getString(VALUE) ?: return null
        val mode = nativeAncMode(value) ?: return null
        @Suppress("DEPRECATION")
        val device = extras.getParcelable<BluetoothDevice>(DEVICE_EXTRA) ?: return null
        val expected = snapshot.address
        if (expected.isEmpty() || normalizedMac(device.address) != expected) return null
        HyperPodsBroadcasts.send(
            context,
            Intent(HyperPodsAction.ACTION_ANC_SELECT)
                .putExtra("status", mode)
                .addFlags(Intent.FLAG_RECEIVER_FOREGROUND),
            HyperPodsBroadcasts.BLUETOOTH,
        )
        Log.i(TAG, "Forwarded native ANC command $value to P.O.D.S.")
        return Bundle().apply { putInt(COMMAND_STATE, 1) }
    }

    private fun requestFreshState(context: Context) {
        val now = SystemClock.elapsedRealtime()
        synchronized(this) {
            if (now - lastRequestAt < REQUEST_THROTTLE_MS) return
            lastRequestAt = now
        }
        HyperPodsBroadcasts.send(
            context,
            Intent(HyperPodsAction.ACTION_XIAOMI_STATE_REQUEST)
                .addFlags(Intent.FLAG_RECEIVER_FOREGROUND),
            HyperPodsBroadcasts.BLUETOOTH,
        )
    }

    private fun restore(context: Context) {
        val prefs = context.getSharedPreferences(AIRPODS_PREFS, Context.MODE_PRIVATE)
        val bridge = context.getSharedPreferences(BRIDGE_PREFS, Context.MODE_PRIVATE)
        val address = normalizedMac(bridge.getString("address", null)) ?: return
        val parsed = parseState(prefs.getString("$address-state", null))
        snapshot = Snapshot(
            address = address,
            name = bridge.getString("name", "").orEmpty(),
            model = bridge.getString("model", "").orEmpty(),
            left = parsed[0],
            right = parsed[1],
            case = parsed[2],
            chargeMask = parsed[3].toIntOrNull() ?: 0,
            earMask = parsed[4].toIntOrNull() ?: 0,
            ancCode = prefs.getString("$address-$AIR_ANC", "").orEmpty(),
        )
        prefs.edit().putString("$address-$AIR_WEAR_STATE", nativeWearState(snapshot.earMask)).apply()
    }

    private fun persistState(context: Context, state: Snapshot) {
        if (state.address.isEmpty()) return
        context.getSharedPreferences(AIRPODS_PREFS, Context.MODE_PRIVATE).edit()
            .putString("${state.address}-state", statePayload(
                state.left, state.right, state.case, state.chargeMask, state.earMask, state.model))
            .apply()
        context.getSharedPreferences(BRIDGE_PREFS, Context.MODE_PRIVATE).edit()
            .putString("address", state.address)
            .putString("name", state.name)
            .putString("model", state.model)
            .apply()
    }

    private fun notifyStateChanged(context: Context) {
        context.contentResolver.notifyChange(
            Uri.parse("content://$AUTHORITY/airpodsstate"), null)
    }

    private fun persistWearState(context: Context, address: String, status: EarDetectionParams) {
        val wear = nativeWearState(earMask(status))
        context.getSharedPreferences(AIRPODS_PREFS, Context.MODE_PRIVATE).edit()
            .putString("$address-$AIR_WEAR_STATE", wear)
            .apply()
        val uri = Uri.parse("content://$AUTHORITY/airpodsRepository").buildUpon()
            .appendQueryParameter(AIR_WEAR_STATE, wear)
            .appendQueryParameter("address", address)
            .build()
        context.contentResolver.notifyChange(uri, null)
    }

    private fun notifyAncChanged(context: Context, address: String, code: String) {
        val uri = Uri.parse("content://$AUTHORITY/airpodsRepository").buildUpon()
            .appendQueryParameter(AIR_ANC, code)
            .appendQueryParameter("address", address)
            .build()
        context.contentResolver.notifyChange(uri, null)
    }

    private fun findContext(instance: Any): Context? {
        var type: Class<*>? = instance.javaClass
        while (type != null) {
            for (field in type.declaredFields) {
                if (!Context::class.java.isAssignableFrom(field.type)) continue
                runCatching {
                    field.isAccessible = true
                    return field.get(instance) as? Context
                }
            }
            type = type.superclass
        }
        return null
    }

    internal fun statePayload(
        left: String,
        right: String,
        case: String,
        chargeMask: Int,
        earMask: Int,
        model: String,
    ): String = "$left|$right|$case|${chargeMask.coerceIn(0, 7)}|" +
        "${earMask.coerceIn(0, 3)}|${model.filter { it != '|' }}"

    internal fun chargeMask(battery: BatteryParams): Int {
        var mask = 0
        if (battery.left?.isCharging == true) mask = mask or 1
        if (battery.right?.isCharging == true) mask = mask or 2
        if (battery.case?.isCharging == true) mask = mask or 4
        return mask
    }

    internal fun earMask(status: EarDetectionParams): Int {
        var mask = 0
        if (status.left == EarDetectionStatus.IN_EAR) mask = mask or 1
        if (status.right == EarDetectionStatus.IN_EAR) mask = mask or 2
        return mask
    }

    internal fun nativeWearState(earMask: Int): String {
        val left = if (earMask and 1 != 0) "01" else "00"
        val right = if (earMask and 2 != 0) "01" else "00"
        return left + right
    }

    internal fun batteryValue(value: PodBatteryParams?): String =
        value?.takeIf { it.isConnected && it.battery in 0..100 }?.battery?.toString() ?: "-"

    internal fun ancCode(status: Int): String? = when (status) {
        1 -> "01"
        2 -> "02"
        3 -> "03"
        else -> null
    }

    internal fun nativeAncMode(code: String): Int? = when (code) {
        "01" -> 1
        "02" -> 2
        "03" -> 3
        else -> null
    }

    private fun normalizedMac(value: String?): String? {
        val normalized = value?.trim()?.uppercase()?.replace('-', ':') ?: return null
        return normalized.takeIf {
            BluetoothAdapter.checkBluetoothAddress(it)
        }
    }

    private fun parseState(value: String?): List<String> {
        val parts = value?.split('|').orEmpty()
        fun at(index: Int, fallback: String) = parts.getOrNull(index)?.takeIf { it.isNotBlank() } ?: fallback
        return listOf(at(0, "-"), at(1, "-"), at(2, "-"), at(3, "0"), at(4, "0"), at(5, ""))
    }
}