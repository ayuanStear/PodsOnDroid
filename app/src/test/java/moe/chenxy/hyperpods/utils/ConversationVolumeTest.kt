package moe.chenxy.hyperpods.utils

import org.junit.Assert.*
import org.junit.Test

class ConversationVolumeTest {
    private var volume = 10
    private var playing = true
    private val controller = ConversationVolume({ volume }, { volume = it }, { playing },
        fadeInMs = 150, fadeOutMs = 250)

    private fun stepUntilStable(): Int {
        var steps = 0
        while (controller.step()) {
            check(++steps < 100) { "Fade did not settle" }
        }
        return steps
    }

    private fun duck(expected: Int = 2) {
        assertTrue(controller.onStatus(1))
        stepUntilStable()
        assertEquals(expected, volume)
    }

    @Test fun repeatedSpeechEventsDoNotLowerVolumeAgain() {
        duck()
        for (status in listOf(1, 2, 3)) assertFalse(controller.onStatus(status))
        assertEquals(2, volume)
        assertTrue(controller.onStatus(6, smoothRestore = true))
        stepUntilStable()
        assertEquals(10, volume)
    }

    @Test fun everyEndStateRestoresVolume() {
        for (status in 6..9) {
            duck()
            assertFalse(controller.onStatus(status))
            assertEquals(10, volume)
        }
    }

    @Test fun manualVolumeChangesArePreserved() {
        duck()
        volume = 4
        assertFalse(controller.onStatus(6, smoothRestore = true))
        assertEquals(4, volume)
        controller.reset()
        assertEquals(4, volume)
    }

    @Test fun disablingOrDisconnectingRestoresOnce() {
        duck()
        controller.reset()
        controller.reset()
        assertEquals(10, volume)
    }

    @Test fun changingOutputsDiscardsTheOldTargetEvenWhenVolumesHappenToMatch() {
        duck()
        assertTrue(controller.onStatus(6, smoothRestore = true))
        controller.reset(restore = false)
        assertEquals(2, volume)
        assertFalse(controller.restoreStep())
        volume = 15
        duck(expected = 3)
        controller.reset()
        assertEquals(15, volume)
    }

    @Test fun idleMediaAndUnknownEventsDoNotChangeVolume() {
        playing = false
        assertFalse(controller.onStatus(1))
        assertEquals(10, volume)
        playing = true
        assertFalse(controller.onStatus(5))
        assertEquals(10, volume)
    }

    @Test fun configuredFadeInAndOutUseTheRequestedStepCounts() {
        var inSteps = 0
        assertTrue(controller.onStatus(1))
        do { inSteps++ } while (controller.step() && inSteps < 20)
        assertEquals(3, inSteps)
        assertEquals(2, volume)

        var outSteps = 0
        assertTrue(controller.onStatus(6, smoothRestore = true))
        do { outSteps++ } while (controller.step() && outSteps < 20)
        assertEquals(5, outSteps)
        assertEquals(10, volume)
    }

    @Test fun aNewConversationDuringRestorationKeepsTheOriginalTarget() {
        duck()
        assertTrue(controller.onStatus(6, smoothRestore = true))
        repeat(3) { assertTrue(controller.restoreStep()) }
        val partial = volume
        assertTrue(partial in 3..8)
        assertTrue(controller.onStatus(1))
        stepUntilStable()
        assertEquals(2, volume)
        assertTrue(controller.onStatus(6, smoothRestore = true))
        stepUntilStable()
        assertEquals(10, volume)
    }

    @Test fun aManualChangeDuringTheFadeStopsAutomaticRestoration() {
        duck()
        assertTrue(controller.onStatus(6, smoothRestore = true))
        assertTrue(controller.restoreStep())
        volume = 5
        assertFalse(controller.restoreStep())
        controller.reset()
        assertEquals(5, volume)
    }

    @Test fun disablingDuringTheFadeRestoresImmediatelyOnce() {
        duck()
        assertTrue(controller.onStatus(6, smoothRestore = true))
        controller.restoreStep()
        controller.reset()
        assertEquals(10, volume)
        assertFalse(controller.restoreStep())
    }

    @Test fun aRejectedVolumeStepCannotLeaveAnEndlessRestorationLoop() {
        var reject = false
        val limited = ConversationVolume({ volume }, { if (!reject) volume = it }, { true },
            fadeInMs = 0, fadeOutMs = 250)
        assertFalse(limited.onStatus(1))
        assertEquals(2, volume)
        assertTrue(limited.onStatus(6, smoothRestore = true))
        reject = true
        assertFalse(limited.step())
        assertFalse(limited.step())
    }

    @Test fun highResolutionVolumeRestoresToTheExactOriginalLevel() {
        volume = 150
        duck(expected = 30)
        assertTrue(controller.onStatus(6, smoothRestore = true))
        stepUntilStable()
        assertEquals(150, volume)
    }

    @Test fun outOfRangeDurationsAreClampedToSupportedBounds() {
        val immediate = ConversationVolume({ volume }, { volume = it }, { true },
            fadeInMs = -1, fadeOutMs = ConversationFade.MAX_MS + 10_000)
        assertFalse(immediate.onStatus(1))
        assertEquals(2, volume)
        assertTrue(immediate.onStatus(6, smoothRestore = true))
        var steps = 0
        do { steps++ } while (immediate.step() && steps < 200)
        assertTrue(steps in 1..100)
        assertEquals(10, volume)
    }
}
