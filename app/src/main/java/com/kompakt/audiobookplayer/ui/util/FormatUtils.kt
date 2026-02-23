package com.kompakt.audiobookplayer.ui.util

import java.util.Locale

/**
 * Format milliseconds into a human-readable duration string.
 * e.g., 3661000 → "1:01:01", 125000 → "2:05"
 */
fun formatDuration(ms: Long): String {
    if (ms <= 0) return "0:00"
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%d:%02d", minutes, seconds)
    }
}

/**
 * Format a playback speed value.
 * e.g., 1.0 → "1.0×", 1.5 → "1.5×"
 */
fun formatSpeed(speed: Float): String {
    return String.format(Locale.US, "%.1f×", speed)
}
