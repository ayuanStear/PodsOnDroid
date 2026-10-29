package moe.chenxy.hyperpods.utils

/** Shared bounds and defaults for the phone-side conversation volume fade. */
object ConversationFade {
    const val MIN_MS = 0
    const val MAX_MS = 5_000
    const val STEP_MS = 50
    const val DEFAULT_IN_MS = 300
    const val DEFAULT_OUT_MS = 700

    fun normalize(durationMs: Int): Int =
        durationMs.coerceIn(MIN_MS, MAX_MS) / STEP_MS * STEP_MS

    fun stepCount(durationMs: Int): Int =
        maxOf(1, (normalize(durationMs) + STEP_MS - 1) / STEP_MS)
}
