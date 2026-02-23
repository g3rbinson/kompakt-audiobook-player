package com.kompakt.audiobookplayer.playback

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.kompakt.audiobookplayer.data.AudiobookWithChapters
import com.kompakt.audiobookplayer.data.Chapter
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.guava.await

/**
 * Manages connection to the [AudiobookPlaybackService] and exposes playback
 * controls and state as Flows for the UI layer.
 */
class PlaybackController(context: Context) {

    private val appContext = context.applicationContext
    private var mediaController: MediaController? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val _playbackState = MutableStateFlow(PlaybackState())
    val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    private var positionUpdateJob: Job? = null
    private var sleepTimerJob: Job? = null

    private var currentChapters: List<Chapter> = emptyList()
    private var currentAudiobookId: Long? = null

    /** Connect to the playback service. Call from Activity onCreate. */
    suspend fun connect() {
        val sessionToken = SessionToken(
            appContext,
            ComponentName(appContext, AudiobookPlaybackService::class.java)
        )
        val controller = MediaController.Builder(appContext, sessionToken)
            .buildAsync()
            .await()

        mediaController = controller
        controller.addListener(playerListener)
        syncState()
        startPositionUpdates()
    }

    /** Disconnect. Call from Activity onDestroy. */
    fun disconnect() {
        positionUpdateJob?.cancel()
        sleepTimerJob?.cancel()
        mediaController?.removeListener(playerListener)
        mediaController?.release()
        mediaController = null
        scope.cancel()
    }

    /**
     * Load an audiobook and start playback from its saved position.
     */
    fun loadAudiobook(audiobookWithChapters: AudiobookWithChapters) {
        val controller = mediaController ?: return
        val audiobook = audiobookWithChapters.audiobook
        val chapters = audiobookWithChapters.chapters.sortedBy { it.index }

        currentChapters = chapters
        currentAudiobookId = audiobook.id

        val mediaItems = chapters.map { chapter ->
            MediaItem.Builder()
                .setUri(Uri.parse(chapter.fileUri))
                .setMediaId("${audiobook.id}_${chapter.index}")
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(chapter.title)
                        .setArtist(audiobook.author)
                        .setAlbumTitle(audiobook.title)
                        .setTrackNumber(chapter.index + 1)
                        .build()
                )
                .build()
        }

        controller.setMediaItems(mediaItems, audiobook.currentChapterIndex, audiobook.currentPositionMs)
        controller.prepare()
        controller.play()
    }

    fun play() { mediaController?.play() }
    fun pause() { mediaController?.pause() }
    fun playPause() {
        mediaController?.let {
            if (it.isPlaying) it.pause() else it.play()
        }
    }

    /** Skip forward by the given number of milliseconds (default 30s). */
    fun skipForward(ms: Long = 30_000) {
        mediaController?.let {
            it.seekTo(minOf(it.currentPosition + ms, it.duration))
        }
    }

    /** Skip backward by the given number of milliseconds (default 15s). */
    fun skipBackward(ms: Long = 15_000) {
        mediaController?.let {
            it.seekTo(maxOf(it.currentPosition - ms, 0))
        }
    }

    fun seekTo(positionMs: Long) {
        mediaController?.seekTo(positionMs)
    }

    fun seekToChapter(index: Int) {
        mediaController?.let {
            if (index in 0 until it.mediaItemCount) {
                it.seekTo(index, 0)
            }
        }
    }

    fun nextChapter() { mediaController?.seekToNextMediaItem() }
    fun previousChapter() { mediaController?.seekToPreviousMediaItem() }

    fun setPlaybackSpeed(speed: Float) {
        mediaController?.setPlaybackSpeed(speed)
        _playbackState.value = _playbackState.value.copy(playbackSpeed = speed)
    }

    /** Start a sleep timer that pauses playback after [minutes]. */
    fun startSleepTimer(minutes: Int) {
        sleepTimerJob?.cancel()
        val totalMs = minutes * 60_000L
        sleepTimerJob = scope.launch {
            var remaining = totalMs
            while (remaining > 0) {
                _playbackState.value = _playbackState.value.copy(sleepTimerRemainingMs = remaining)
                delay(1000)
                remaining -= 1000
            }
            pause()
            _playbackState.value = _playbackState.value.copy(sleepTimerRemainingMs = null)
        }
    }

    fun cancelSleepTimer() {
        sleepTimerJob?.cancel()
        _playbackState.value = _playbackState.value.copy(sleepTimerRemainingMs = null)
    }

    /** Get the current progress as (chapterIndex, positionMs). */
    fun getCurrentProgress(): Pair<Int, Long> {
        val controller = mediaController ?: return Pair(0, 0)
        return Pair(controller.currentMediaItemIndex, controller.currentPosition)
    }

    // -- Internal --

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) { syncState() }
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) { syncState() }
        override fun onPlaybackStateChanged(playbackState: Int) { syncState() }
    }

    private fun syncState() {
        val controller = mediaController ?: return
        _playbackState.value = PlaybackState(
            isPlaying = controller.isPlaying,
            currentPositionMs = controller.currentPosition,
            durationMs = controller.duration.coerceAtLeast(0),
            currentChapterIndex = controller.currentMediaItemIndex,
            audiobookId = currentAudiobookId,
            playbackSpeed = controller.playbackParameters.speed,
            sleepTimerRemainingMs = _playbackState.value.sleepTimerRemainingMs
        )
    }

    private fun startPositionUpdates() {
        positionUpdateJob?.cancel()
        positionUpdateJob = scope.launch {
            while (isActive) {
                syncState()
                delay(1000) // 1s update interval — gentle on E Ink refresh
            }
        }
    }
}
