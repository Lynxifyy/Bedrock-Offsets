package com.chimerateam.offsetextractor.ui.state

/**
 * Progress reported by the engine while it works.
 *
 * @property percent completion in the range 0..100.
 * @property task short description of the current step.
 */
data class ProgressTracker(
    val percent: Int,
    val task: String
) {
    companion object {
        /** A tracker clamped into the valid percentage range. */
        fun of(percent: Int, task: String): ProgressTracker =
            ProgressTracker(percent.coerceIn(0, 100), task)
    }
}
