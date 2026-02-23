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
     */
    fun loadAudiobook(audiobookId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            val audiobookWithChapters = audiobookDao.getAudiobookWithChapters(audiobookId)
                ?: return@launch

            _audiobook.value = audiobookWithChapters.audiobook
            _chapters.value = audiobookWithChapters.chapters.sortedBy { it.index }

            // Load into player on Main thread
            launch(Dispatchers.Main) {
                playbackController.loadAudiobook(audiobookWithChapters)
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
     * Runs every 10 seconds to avoid excessive writes while still
     * preserving position if the app is killed.
     */
    private fun startProgressSaving() {
        progressSaveJob?.cancel()
        progressSaveJob = viewModelScope.launch(Dispatchers.IO) {
            playbackController.playbackState
                .filter { it.audiobookId != null }
                .distinctUntilChanged { old, new ->
                    old.currentChapterIndex == new.currentChapterIndex &&
                            kotlin.math.abs(old.currentPositionMs - new.currentPositionMs) < 5000
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

    /** Save progress immediately (e.g., when leaving the player screen). */
    fun saveProgressNow() {
        val state = playbackState.value
        val id = state.audiobookId ?: return
        viewModelScope.launch(Dispatchers.IO) {
            audiobookDao.updateProgress(
                audiobookId = id,
                chapterIndex = state.currentChapterIndex,
                positionMs = state.currentPositionMs
            )
        }
    }
}
