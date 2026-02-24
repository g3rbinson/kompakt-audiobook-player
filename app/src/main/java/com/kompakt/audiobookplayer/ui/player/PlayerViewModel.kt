package com.kompakt.audiobookplayer.ui.player

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kompakt.audiobookplayer.data.*
import com.kompakt.audiobookplayer.playback.PlaybackController
import com.kompakt.audiobookplayer.playback.PlaybackState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class PlayerViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.getInstance(application)
    private val audiobookDao = db.audiobookDao()
    private val chapterDao = db.chapterDao()
    private val bookmarkDao = db.bookmarkDao()

    lateinit var playbackController: PlaybackController
        private set

    private val _audiobook = MutableStateFlow<Audiobook?>(null)
    val audiobook: StateFlow<Audiobook?> = _audiobook.asStateFlow()

    private val _chapters = MutableStateFlow<List<Chapter>>(emptyList())
    val chapters: StateFlow<List<Chapter>> = _chapters.asStateFlow()

    val playbackState: StateFlow<PlaybackState>
        get() = playbackController.playbackState

    private var progressSaveJob: kotlinx.coroutines.Job? = null

    fun initController(controller: PlaybackController) {
        playbackController = controller
        startProgressSaving()
    }

    /**
     * Load an audiobook by ID and begin playback.
     * Reads the saved position from the database so playback resumes
     * where the user left off.
     */
    fun loadAudiobook(audiobookId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            val audiobookWithChapters = audiobookDao.getAudiobookWithChapters(audiobookId)
                ?: return@launch

            // Re-read the audiobook row to get the latest saved position
            // (it may have been updated by progress saving since the Flow snapshot)
            val freshAudiobook = audiobookDao.getAudiobookById(audiobookId)
                ?: audiobookWithChapters.audiobook

            val withFreshProgress = AudiobookWithChapters(
                audiobook = freshAudiobook,
                chapters = audiobookWithChapters.chapters
            )

            _audiobook.value = freshAudiobook
            _chapters.value = withFreshProgress.chapters.sortedBy { it.index }

            // Load into player on Main thread
            launch(Dispatchers.Main) {
                playbackController.loadAudiobook(withFreshProgress)
            }
        }
    }

    // -- Playback controls --

    fun playPause() = playbackController.playPause()
    fun skipForward() = playbackController.skipForward()
    fun skipBackward() = playbackController.skipBackward()
    fun nextChapter() = playbackController.nextChapter()
    fun previousChapter() = playbackController.previousChapter()
    fun seekToChapter(index: Int) = playbackController.seekToChapter(index)

    /** Cycle through common playback speeds. */
    private val speeds = listOf(0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)
    fun cycleSpeed() {
        val current = playbackState.value.playbackSpeed
        val nextIndex = (speeds.indexOf(current) + 1) % speeds.size
        playbackController.setPlaybackSpeed(speeds[nextIndex])
    }

    /** Cycle through sleep timer presets (off → 15 → 30 → 45 → 60 → off). */
    private val sleepMinutes = listOf(0, 15, 30, 45, 60)
    private var currentSleepIndex = 0
    fun cycleSleepTimer() {
        currentSleepIndex = (currentSleepIndex + 1) % sleepMinutes.size
        val minutes = sleepMinutes[currentSleepIndex]
        if (minutes == 0) {
            playbackController.cancelSleepTimer()
        } else {
            playbackController.startSleepTimer(minutes)
        }
    }

    /** Add a bookmark at the current position. */
    fun addBookmark() {
        val (chapterIndex, positionMs) = playbackController.getCurrentProgress()
        val bookId = _audiobook.value?.id ?: return
        viewModelScope.launch(Dispatchers.IO) {
            bookmarkDao.insertBookmark(
                Bookmark(
                    audiobookId = bookId,
                    chapterIndex = chapterIndex,
                    positionMs = positionMs
                )
            )
        }
    }

    /**
     * Periodically save playback progress to the database.
     * Saves whenever chapter changes or position moves by more than 3 seconds.
     */
    private fun startProgressSaving() {
        progressSaveJob?.cancel()
        progressSaveJob = viewModelScope.launch(Dispatchers.IO) {
            playbackController.playbackState
                .filter { it.audiobookId != null }
                .distinctUntilChanged { old, new ->
                    old.currentChapterIndex == new.currentChapterIndex &&
                            kotlin.math.abs(old.currentPositionMs - new.currentPositionMs) < 3000
                }
                .collect { state ->
                    state.audiobookId?.let { id ->
                        audiobookDao.updateProgress(
                            audiobookId = id,
                            chapterIndex = state.currentChapterIndex,
                            positionMs = state.currentPositionMs
                        )
                    }
                }
        }
    }

    /** Save progress immediately (e.g., when leaving the player screen or app going to background). */
    fun saveProgressNow() {
        val (chapterIndex, positionMs) = playbackController.getCurrentProgress()
        val id = _audiobook.value?.id ?: return
        viewModelScope.launch(Dispatchers.IO) {
            audiobookDao.updateProgress(
                audiobookId = id,
                chapterIndex = chapterIndex,
                positionMs = positionMs
            )
        }
    }

    /**
     * Blocking save for use in onDestroy where viewModelScope may be cancelled.
     * Ensures progress is written to disk before the process exits.
     */
    fun saveProgressBlocking() {
        val (chapterIndex, positionMs) = playbackController.getCurrentProgress()
        val id = _audiobook.value?.id ?: return
        try {
            runBlocking(Dispatchers.IO) {
                audiobookDao.updateProgress(
                    audiobookId = id,
                    chapterIndex = chapterIndex,
                    positionMs = positionMs
                )
            }
        } catch (_: Exception) {
            // If blocked too long or cancelled, at least we tried
        }
    }
}
