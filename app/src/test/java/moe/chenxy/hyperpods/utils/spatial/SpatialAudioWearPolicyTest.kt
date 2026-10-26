package moe.chenxy.hyperpods.utils.spatial

import moe.chenxy.hyperpods.pods.EarDetectionStatus
import moe.chenxy.hyperpods.utils.data.EarDetectionParams
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpatialAudioWearPolicyTest {
    @Test fun requiresBothEarbudsInEar() {
        assertTrue(isBinauralWear(EarDetectionParams(EarDetectionStatus.IN_EAR, EarDetectionStatus.IN_EAR)))
        assertFalse(isBinauralWear(EarDetectionParams(EarDetectionStatus.IN_EAR, EarDetectionStatus.OUT_OF_EAR)))
        assertFalse(isBinauralWear(EarDetectionParams(EarDetectionStatus.IN_CASE, EarDetectionStatus.IN_EAR)))
        assertFalse(isBinauralWear(null))
    }
}
