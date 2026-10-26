package moe.chenxy.hyperpods.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HeadTrackingStreamSpatialTest {
    @Test fun spatialPreemptsPreviewButCannotPreemptACall() {
        val sends = mutableListOf<Boolean>()
        val stream = HeadTrackingStream(send = { start -> sends += start; true })
        assertTrue(stream.acquire(HeadTrackingStream.Owner.PREVIEW))
        var interrupted = false
        assertTrue(stream.acquire(HeadTrackingStream.Owner.SPATIAL) { interrupted = true })
        assertFalse(stream.acquire(HeadTrackingStream.Owner.PREVIEW))
        assertTrue(stream.acquire(HeadTrackingStream.Owner.CALL))
        assertTrue(interrupted)
        assertFalse(stream.acquire(HeadTrackingStream.Owner.SPATIAL))
        assertTrue(stream.release(HeadTrackingStream.Owner.CALL))
        assertEquals(listOf(true, false, true, false, true, false), sends)
    }
}
