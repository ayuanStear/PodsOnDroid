package moe.chenxy.hyperpods.utils.spatial

enum class SpatialAudioMode(val wireValue: String) {
    OFF("off"),
    FIXED("fixed"),
    HEAD_TRACKED("head_tracked");

    companion object {
        fun fromWire(value: String?): SpatialAudioMode =
            entries.firstOrNull { it.wireValue == value } ?: OFF
    }
}

/** Pose payload consumed by the root UHID helper. Angles are radians. */
data class SpatialPose(
    val rx: Float,
    val ry: Float,
    val rz: Float,
    val vx: Float = 0f,
    val vy: Float = 0f,
    val vz: Float = 0f,
    val discontinuityCounter: Int = 0,
)
