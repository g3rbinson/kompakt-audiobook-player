package com.kompakt.audiobookplayer.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.*
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.kompakt.audiobookplayer.playback.PlaybackController
import com.kompakt.audiobookplayer.ui.library.LibraryScreen
import com.kompakt.audiobookplayer.ui.library.LibraryViewModel
import com.kompakt.audiobookplayer.ui.player.ChapterListScreen
import com.kompakt.audiobookplayer.ui.player.PlayerScreen
import com.kompakt.audiobookplayer.ui.player.PlayerViewModel
import com.kompakt.audiobookplayer.ui.theme.AudiobookTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val libraryViewModel: LibraryViewModel by viewModels()
    private val playerViewModel: PlayerViewModel by viewModels()
    private lateinit var playbackController: PlaybackController

    /** SAF folder picker for importing audiobooks */
    private val folderPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let {
            // Persist read permission across reboots
            contentResolver.takePersistableUriPermission(
                it,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            libraryViewModel.importAudiobookFromFolder(it)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        playbackController = PlaybackController(this)
        playerViewModel.initController(playbackController)

        // Connect to playback service
        lifecycleScope.launch {
            playbackController.connect()
        }

        setContent {
            AudiobookTheme {
                AppNavigation(
                    libraryViewModel = libraryViewModel,
                    playerViewModel = playerViewModel,
                    onAddAudiobook = { folderPicker.launch(null) }
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        playerViewModel.saveProgressNow()
        playbackController.disconnect()
    }
}

@Composable
private fun AppNavigation(
    libraryViewModel: LibraryViewModel,
    playerViewModel: PlayerViewModel,
    onAddAudiobook: () -> Unit
) {
    val navController = rememberNavController()

    // Observe playback state for mini player
    val playbackState by playerViewModel.playbackState.collectAsState()
    val currentAudiobook by playerViewModel.audiobook.collectAsState()

    NavHost(
        navController = navController,
        startDestination = "library"
    ) {
        composable("library") {
            LibraryScreen(
                viewModel = libraryViewModel,
                onAudiobookClick = { audiobookId ->
                    playerViewModel.loadAudiobook(audiobookId)
                    navController.navigate("player/$audiobookId")
                },
                onAddClick = onAddAudiobook,
                onDeleteAudiobook = { audiobook ->
                    libraryViewModel.deleteAudiobook(audiobook)
                },
                nowPlayingTitle = currentAudiobook?.title,
                isPlaying = playbackState.isPlaying,
                onPlayPauseClick = { playerViewModel.playPause() },
                onMiniPlayerClick = {
                    currentAudiobook?.id?.let { id ->
                        navController.navigate("player/$id")
                    }
                }
            )
        }

        composable(
            route = "player/{audiobookId}",
            arguments = listOf(navArgument("audiobookId") { type = NavType.LongType })
        ) {
            PlayerScreen(
                viewModel = playerViewModel,
                onBackClick = {
                    playerViewModel.saveProgressNow()
                    navController.popBackStack()
                },
                onChaptersClick = {
                    navController.navigate("chapters")
                }
            )
        }

        composable("chapters") {
            val chapters by playerViewModel.chapters.collectAsState()
            val playbackState by playerViewModel.playbackState.collectAsState()

            ChapterListScreen(
                chapters = chapters,
                currentChapterIndex = playbackState.currentChapterIndex,
                onChapterClick = { index ->
                    playerViewModel.seekToChapter(index)
                    navController.popBackStack()
                },
                onBackClick = { navController.popBackStack() }
            )
        }
    }
}
