package com.kompakt.audiobookplayer.playback

/**
 * Represents the current state of audio playback.
 */
data class PlaybackState(
    val isPlaying: Boolean = false,
    val currentPositionMs: Long = 0,
    val durationMs: Long = 0,
    val currentChapterIndex: Int = 0,
    val audiobookId: Long? = null,
    val playbackSpeed: Float = 1.0f,
    val sleepTimerRemainingMs: Long? = null
) {
    val progressFraction: Float
        get() = if (durationMs > 0) currentPositionMs.toFloat() / durationMs else 0f
}
