package moe.chenxy.hyperpods

import android.app.Application
import moe.chenxy.hyperpods.utils.spatial.SpatialAudioRuntime

class PodsApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        SpatialAudioRuntime.initialize(this)
    }
}
