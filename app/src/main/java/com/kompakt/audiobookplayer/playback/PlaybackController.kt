package com.kompakt.audiobookplayer.playback

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.kompakt.audiobookplayer.data.AppDatabase
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
 *
 * Progress is saved directly to the database every 5 seconds during playback,
 * independent of ViewModel or Activity lifecycle. This ensures progress persists
 * even when MuditaOS aggressively kills the process without lifecycle callbacks.
 */
class PlaybackController(context: Context) {

    private val appContext = context.applicationContext
    private val audiobookDao = AppDatabase.getInstance(appContext).audiobookDao()
    private var mediaController: MediaController? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val _playbackState = MutableStateFlow(PlaybackState())
    val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    private var positionUpdateJob: Job? = null
    private var sleepTimerJob: Job? = null

    private var currentChapters: List<Chapter> = emptyList()
    private var currentAudiobookId: Long? = null
    private var isSingleFileBook: Boolean = false

    /** Tracks the last saved values to avoid redundant writes. */
    private var lastSavedChapter: Int = -1
    private var lastSavedPositionMs: Long = -1
    private var lastSaveTimeMs: Long = 0

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
        // Final save before disconnecting
        saveProgressToDb(force = true)
        positionUpdateJob?.cancel()
        sleepTimerJob?.cancel()
        mediaController?.removeListener(playerListener)
        mediaController?.release()
        mediaController = null
        scope.cancel()
    }

    /**
     * Load an audiobook and start playback from its saved position.
     * Handles both multi-file audiobooks and single-file M4B with embedded chapters.
     */
    fun loadAudiobook(audiobookWithChapters: AudiobookWithChapters) {
        val controller = mediaController ?: return
        val audiobook = audiobookWithChapters.audiobook
        val chapters = audiobookWithChapters.chapters.sortedBy { it.index }

        currentChapters = chapters
        currentAudiobookId = audiobook.id

        // Reset save tracking for the new book
        lastSavedChapter = -1
        lastSavedPositionMs = -1
        lastSaveTimeMs = 0

        // Detect if this is a single-file audiobook (M4B with embedded chapters)
        val distinctFiles = chapters.map { it.fileUri }.distinct()
        isSingleFileBook = distinctFiles.size == 1 && chapters.size > 1

        if (isSingleFileBook) {
            // Single M4B file — load as one MediaItem, handle chapters via seeking
            val mediaItem = MediaItem.Builder()
                .setUri(Uri.parse(chapters.first().fileUri))
                .setMediaId("${audiobook.id}_m4b")
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(audiobook.title)
                        .setArtist(audiobook.author)
                        .setAlbumTitle(audiobook.title)
                        .build()
                )
                .build()

            // Calculate the absolute seek position from saved chapter + position
            val savedChapter = chapters.getOrNull(audiobook.currentChapterIndex)
            val seekMs = (savedChapter?.startTimeMs ?: 0) + audiobook.currentPositionMs

            controller.setMediaItem(mediaItem)
            controller.prepare()
            controller.seekTo(seekMs)
            controller.play()
        } else {
            // Multi-file audiobook — one MediaItem per chapter
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
    }

    fun play() { mediaController?.play() }
    fun pause() {
        mediaController?.pause()
        // Save immediately on pause — the user is explicitly stopping
        saveProgressToDb(force = true)
    }
    fun playPause() {
        mediaController?.let {
            if (it.isPlaying) {
                it.pause()
                saveProgressToDb(force = true)
            } else {
                it.play()
            }
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
        if (isSingleFileBook) {
            val chapter = currentChapters.getOrNull(index) ?: return
            mediaController?.seekTo(chapter.startTimeMs)
        } else {
            mediaController?.let {
                if (index in 0 until it.mediaItemCount) {
                    it.seekTo(index, 0)
                }
            }
        }
    }

    fun nextChapter() {
        if (isSingleFileBook) {
            val currentIdx = getCurrentChapterIndex()
            if (currentIdx < currentChapters.size - 1) {
                seekToChapter(currentIdx + 1)
            }
        } else {
            mediaController?.seekToNextMediaItem()
        }
    }

    fun previousChapter() {
        if (isSingleFileBook) {
            val currentIdx = getCurrentChapterIndex()
            if (currentIdx > 0) {
                seekToChapter(currentIdx - 1)
            }
        } else {
            mediaController?.seekToPreviousMediaItem()
        }
    }

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

    /** Get the current progress as (chapterIndex, positionMs within that chapter). */
    fun getCurrentProgress(): Pair<Int, Long> {
        val controller = mediaController ?: return Pair(-1, -1)
        if (isSingleFileBook) {
            val absPos = controller.currentPosition
            val chapterIdx = getCurrentChapterIndex()
            val chapter = currentChapters.getOrNull(chapterIdx)
            val posInChapter = if (chapter != null) absPos - chapter.startTimeMs else absPos
            return Pair(chapterIdx, posInChapter.coerceAtLeast(0))
        }
        return Pair(controller.currentMediaItemIndex, controller.currentPosition)
    }

    /**
     * For single-file M4B books, determine which chapter the absolute position falls in.
     */
    private fun getCurrentChapterIndex(): Int {
        val controller = mediaController ?: return 0
        if (!isSingleFileBook) return controller.currentMediaItemIndex
        val absPos = controller.currentPosition
        for (i in currentChapters.indices.reversed()) {
            if (absPos >= currentChapters[i].startTimeMs) return i
        }
        return 0
    }

    // ── Progress saving (directly to DB, independent of ViewModel) ────────

    /**
     * Save current progress to the database.
     * Called every 5 seconds during playback and on pause/disconnect.
     *
     * Guards:
     *   - Won't save if no audiobook is loaded
     *   - Won't save (0,0) to avoid overwriting real progress
     *   - Won't save if position hasn't meaningfully changed (unless forced)
     */
    private fun saveProgressToDb(force: Boolean = false) {
        val id = currentAudiobookId ?: return
        val (chapterIndex, positionMs) = getCurrentProgress()

        // Never save invalid values (mediaController was null)
        if (chapterIndex < 0 || positionMs < 0) return

        // Don't save (0,0) — this would overwrite real progress with startup values
        if (chapterIndex == 0 && positionMs == 0L) return

        // Skip if nothing changed meaningfully (unless forced)
        if (!force) {
            if (chapterIndex == lastSavedChapter &&
                kotlin.math.abs(positionMs - lastSavedPositionMs) < 2000) {
                return
            }
        }

        lastSavedChapter = chapterIndex
        lastSavedPositionMs = positionMs
        lastSaveTimeMs = System.currentTimeMillis()

        // Fire-and-forget DB write on IO thread
        scope.launch(Dispatchers.IO) {
            try {
                audiobookDao.updateProgress(
                    audiobookId = id,
                    chapterIndex = chapterIndex,
                    positionMs = positionMs
                )
            } catch (_: Exception) { }
        }
    }

    /**
     * Blocking save for use during shutdown.
     * Runs on the calling thread to guarantee completion.
     */
    fun saveProgressBlocking() {
        val id = currentAudiobookId ?: return
        val (chapterIndex, positionMs) = getCurrentProgress()
        if (chapterIndex < 0 || positionMs < 0) return
        if (chapterIndex == 0 && positionMs == 0L) return

        try {
            runBlocking(Dispatchers.IO) {
                audiobookDao.updateProgress(
                    audiobookId = id,
                    chapterIndex = chapterIndex,
                    positionMs = positionMs
                )
            }
        } catch (_: Exception) { }
    }

    // ── Internal ──

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) { syncState() }
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) { syncState() }
        override fun onPlaybackStateChanged(playbackState: Int) { syncState() }
    }

    private fun syncState() {
        val controller = mediaController ?: return
        if (isSingleFileBook) {
            val absPos = controller.currentPosition
            val chapterIdx = getCurrentChapterIndex()
            val chapter = currentChapters.getOrNull(chapterIdx)
            val posInChapter = if (chapter != null) absPos - chapter.startTimeMs else absPos
            val chapterDuration = chapter?.durationMs ?: controller.duration.coerceAtLeast(0)

            _playbackState.value = PlaybackState(
                isPlaying = controller.isPlaying,
                currentPositionMs = posInChapter.coerceAtLeast(0),
                durationMs = chapterDuration,
                currentChapterIndex = chapterIdx,
                audiobookId = currentAudiobookId,
                playbackSpeed = controller.playbackParameters.speed,
                sleepTimerRemainingMs = _playbackState.value.sleepTimerRemainingMs
            )
        } else {
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
    }

    private var saveTickCounter = 0

    private fun startPositionUpdates() {
        positionUpdateJob?.cancel()
        positionUpdateJob = scope.launch {
            while (isActive) {
                syncState()

                // Save progress every 5 seconds during playback
                saveTickCounter++
                if (saveTickCounter >= 5 && mediaController?.isPlaying == true) {
                    saveTickCounter = 0
                    saveProgressToDb()
                }

                delay(1000) // 1s update interval — gentle on E Ink refresh
            }
        }
    }
}
