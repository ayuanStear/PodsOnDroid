package moe.chenxy.hyperpods.utils.spatial

import moe.chenxy.hyperpods.pods.EarDetectionStatus
import moe.chenxy.hyperpods.utils.data.EarDetectionParams

internal const val SPATIAL_AUDIO_BINAURAL_ERROR = "spatial_requires_both_ears"

/** Spatial audio is immersive only with two worn earbuds. */
internal fun isBinauralWear(status: EarDetectionParams?): Boolean =
    status?.left == EarDetectionStatus.IN_EAR && status.right == EarDetectionStatus.IN_EAR
