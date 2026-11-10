package com.chimerateam.offsetextractor.util

import java.util.Locale

/**
 * Formats a byte count for display.
 *
 * @param bytes size in bytes; negative values are reported as unknown.
 * @return a compact human-readable size such as `12.3 MB`.
 */
fun formatBytes(bytes: Long): String {
    if (bytes < 0) return "unknown size"
    if (bytes < 1024) return "$bytes B"

    val units = arrayOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble() / 1024.0
    var unitIndex = 0
    while (value >= 1024.0 && unitIndex < units.lastIndex) {
        value /= 1024.0
        unitIndex++
    }
    return String.format(Locale.US, "%.1f %s", value, units[unitIndex])
}
