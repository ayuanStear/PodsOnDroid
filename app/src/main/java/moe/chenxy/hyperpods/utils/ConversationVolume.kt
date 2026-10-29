package moe.chenxy.hyperpods.utils

/** Only restore volume that we changed and that the user has not subsequently adjusted. */
class ConversationVolume(
    private val getVolume: () -> Int,
    private val setVolume: (Int) -> Unit,
    private val isPlaying: () -> Boolean,
    fadeInMs: Int = ConversationFade.DEFAULT_IN_MS,
    fadeOutMs: Int = ConversationFade.DEFAULT_OUT_MS,
) {
    private enum class Phase { IDLE, DUCKING, DUCKED, RESTORING }

    private var original: Int? = null
    private var applied: Int? = null
    private var startVolume = 0
    private var targetVolume = 0
    private var step = 0
    private var stepCount = 0
    private var phase = Phase.IDLE
    private var fadeInMs = ConversationFade.normalize(fadeInMs)
    private var fadeOutMs = ConversationFade.normalize(fadeOutMs)

    fun setFadeDurations(fadeInMs: Int, fadeOutMs: Int) {
        this.fadeInMs = ConversationFade.normalize(fadeInMs)
        this.fadeOutMs = ConversationFade.normalize(fadeOutMs)
    }

    /** Returns true when the caller must drive [step] until it returns false. */
    fun onStatus(status: Int, smoothRestore: Boolean = false): Boolean = when (status) {
        1, 2, 3 -> beginDucking()
        6, 7, 8, 9 -> beginRestoring(smoothRestore)
        else -> false
    }

    private fun beginDucking(): Boolean {
        if (!isPlaying()) return false
        // Repeated speech reports must not restart an active ducking transition.
        if (phase == Phase.DUCKING) return false
        val current = getVolume()
        if (applied != null && current != applied) {
            clear()
            return false
        }
        val base = original ?: current
        val target = base / 5
        if (target == current && phase == Phase.DUCKED) return false
        original = base
        startVolume = current
        targetVolume = target
        applied = current
        if (fadeInMs == 0 || current == target) {
            setVolume(target)
            applied = getVolume()
            phase = Phase.DUCKED
            return false
        }
        step = 0
        stepCount = ConversationFade.stepCount(fadeInMs)
        phase = Phase.DUCKING
        return true
    }

    private fun beginRestoring(smoothRestore: Boolean): Boolean {
        val target = original ?: return false
        if (!smoothRestore) {
            reset()
            return false
        }
        if (phase == Phase.RESTORING) return false
        val current = getVolume()
        if (applied != null && current != applied) {
            clear()
            return false
        }
        startVolume = current
        targetVolume = target
        applied = current
        if (fadeOutMs == 0 || current == target) {
            setVolume(target)
            clear()
            return false
        }
        step = 0
        stepCount = ConversationFade.stepCount(fadeOutMs)
        phase = Phase.RESTORING
        return true
    }

    /** Called at [ConversationFade.STEP_MS] intervals. */
    fun step(): Boolean {
        if (phase != Phase.DUCKING && phase != Phase.RESTORING) return false
        val previous = applied ?: return false
        if (getVolume() != previous) {
            // A manual volume change wins over an in-flight automatic transition.
            clear()
            return false
        }
        step++
        val desired = if (step >= stepCount) targetVolume else
            startVolume + ((targetVolume - startVolume) * step + stepCount / 2) / stepCount
        setVolume(desired)
        val actual = getVolume()
        if (actual != desired) {
            clear()
            return false
        }
        applied = actual
        if (actual == targetVolume || step >= stepCount) {
            if (phase == Phase.RESTORING) clear()
            else phase = Phase.DUCKED
            return false
        }
        return true
    }

    /** Kept for the existing restoration tests and callers. */
    fun restoreStep(): Boolean = step()

    fun reset(restore: Boolean = true) {
        val target = original
        val canRestore = restore && phase != Phase.IDLE && getVolume() == applied
        clear()
        if (canRestore && target != null) setVolume(target)
    }

    private fun clear() {
        original = null
        applied = null
        startVolume = 0
        targetVolume = 0
        step = 0
        stepCount = 0
        phase = Phase.IDLE
    }
}
