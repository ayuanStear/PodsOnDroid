package moe.chenxy.hyperpods.hook

import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import moe.chenxy.hyperpods.utils.SystemEnvironment
import moe.chenxy.hyperpods.utils.SystemHook

class HookEntry : XposedModule() {
    private lateinit var processName: String
    private val installedPackages = mutableSetOf<String>()

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        processName = param.processName
        log(Log.INFO, "PODS-Hook", "module loaded in ${param.processName}; API $apiVersion")
    }

    override fun onPackageReady(param: PackageReadyParam) {
        val host = param.packageName
        log(Log.INFO, "PODS-Hook", "package ready: $host, first=${param.isFirstPackage}, process=$processName")
        if (!param.isFirstPackage) return
        val hook = SystemEnvironment.current.hookFor(host) ?: return
        if (!installedPackages.add(host)) return

        // Do not filter by processName == packageName: Bluetooth services can live in subprocesses.
        installHook("$host self-check") { ModuleStatusHook(this, host, processName).install() }
        when (hook) {
            SystemHook.HYPER_OS_SYSTEM_UI ->
                installHook("SystemUI plugin discovery") { SystemUIPluginHook.install(this, param.classLoader) }
            SystemHook.BLUETOOTH_CONTROL ->
                installHook("Bluetooth headset dispatcher") { HeadsetStateDispatcher.install(this, param.classLoader) }
            SystemHook.HYPER_OS_BLUETOOTH -> {
                installHook("Xiaomi Bluetooth notifications") { MiBluetoothToastHook.install(this, param.classLoader) }
                installHook("Xiaomi native AirPods state bridge") {
                    XiaomiAirPodsStateBridge.install(this, param.classLoader)
                }
            }
        }
    }
}
