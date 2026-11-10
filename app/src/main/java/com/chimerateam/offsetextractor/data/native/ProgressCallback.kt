package com.chimerateam.offsetextractor.data.native

/**
 * Receives progress reports from the engine while a scan runs.
 *
 * The engine calls this from a background thread. Implementations must return
 * quickly and must not assume they run on the main thread; the production
 * implementation in the ViewModel forwards each report onto its own state.
 */
fun interface ProgressCallback {
    /**
     * Called as the scan advances.
     *
     * @param percent completion between 0 and 100.
     * @param message short description of the current step.
     */
    fun onProgress(percent: Int, message: String)
}
