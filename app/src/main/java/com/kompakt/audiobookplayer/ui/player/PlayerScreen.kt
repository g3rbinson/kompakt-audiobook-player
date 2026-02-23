package com.kompakt.audiobookplayer.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kompakt.audiobookplayer.data.Chapter
import com.kompakt.audiobookplayer.playback.PlaybackState
import com.kompakt.audiobookplayer.ui.util.formatDuration
import com.kompakt.audiobookplayer.ui.util.formatSpeed
import com.mudita.mmd.components.top_app_bar.TopAppBarMMD
import com.mudita.mmd.components.buttons.ButtonMMD
import com.mudita.mmd.components.text.TextMMD
import androidx.compose.material3.IconButton

/**
 * Main audiobook player screen, optimized for the Kompakt E Ink display.
 *
 * Layout (800×480 landscape-capable):
 * ┌──────────────────────────────┐
 * │  ← Back     Title           │
 * ├──────────────────────────────┤
 * │  Chapter: Chapter Title     │
 * │  Author                     │
 * │                              │
 * │  ▓▓▓▓▓▓▓▓░░░░░░░░  3:45/12:30 │
 * │                              │
 * │  ⏮  ◀30s  ▶⏸  30s▶  ⏭   │
 * │                              │
 * │  Speed: 1.0×  Sleep  Bookmark│
 * └──────────────────────────────┘
 */
@Composable
fun PlayerScreen(
    viewModel: PlayerViewModel,
    onBackClick: () -> Unit,
    onChaptersClick: () -> Unit
) {
    val playbackState by viewModel.playbackState.collectAsState()
    val audiobook by viewModel.audiobook.collectAsState()
    val chapters by viewModel.chapters.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
    ) {
        // Top bar with back button
        TopAppBarMMD(
            title = {
                TextMMD(
                    text = audiobook?.title ?: "Player",
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            },
            navigationIcon = {
                IconButton(onClick = onBackClick) {
                    TextMMD("←")
                }
            }
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Chapter info
            ChapterInfo(
                chapterTitle = chapters.getOrNull(playbackState.currentChapterIndex)?.title ?: "",
                author = audiobook?.author ?: "",
                chapterNumber = playbackState.currentChapterIndex + 1,
                totalChapters = chapters.size
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Progress bar
            ProgressSection(playbackState = playbackState)

            Spacer(modifier = Modifier.height(24.dp))

            // Playback controls
            PlaybackControls(
                isPlaying = playbackState.isPlaying,
                onPlayPause = viewModel::playPause,
                onSkipForward = viewModel::skipForward,
                onSkipBackward = viewModel::skipBackward,
                onNextChapter = viewModel::nextChapter,
                onPreviousChapter = viewModel::previousChapter
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Bottom toolbar: Speed, Sleep Timer, Bookmark, Chapters
            BottomToolbar(
                playbackState = playbackState,
                onSpeedClick = viewModel::cycleSpeed,
                onSleepTimerClick = viewModel::cycleSleepTimer,
                onBookmarkClick = viewModel::addBookmark,
                onChaptersClick = onChaptersClick
            )
        }
    }
}

@Composable
private fun ChapterInfo(
    chapterTitle: String,
    author: String,
    chapterNumber: Int,
    totalChapters: Int
) {
    Column {
        TextMMD(
            text = "Chapter $chapterNumber of $totalChapters",
            color = Color.Gray
        )
        Spacer(modifier = Modifier.height(8.dp))
        TextMMD(
            text = chapterTitle,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.height(6.dp))
        TextMMD(
            text = author,
            color = Color.DarkGray
        )
    }
}

@Composable
private fun ProgressSection(playbackState: PlaybackState) {
    Column(modifier = Modifier.fillMaxWidth()) {
        // Progress bar — static, no animation (E Ink friendly)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .background(Color.LightGray)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fraction = playbackState.progressFraction)
                    .background(Color.Black)
            )
        }
        Spacer(modifier = Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            TextMMD(text = formatDuration(playbackState.currentPositionMs))
            TextMMD(text = formatDuration(playbackState.durationMs))
        }
    }
}

@Composable
private fun PlaybackControls(
    isPlaying: Boolean,
    onPlayPause: () -> Unit,
    onSkipForward: () -> Unit,
    onSkipBackward: () -> Unit,
    onNextChapter: () -> Unit,
    onPreviousChapter: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Previous chapter
        ButtonMMD(
            onClick = onPreviousChapter,
            modifier = Modifier.size(56.dp)
        ) {
            TextMMD("⏮", fontWeight = FontWeight.Bold)
        }

        Spacer(modifier = Modifier.width(8.dp))

        // Skip back 15s
        ButtonMMD(
            onClick = onSkipBackward,
            modifier = Modifier.size(56.dp)
        ) {
            TextMMD("−15s", fontWeight = FontWeight.Medium)
        }

        Spacer(modifier = Modifier.width(8.dp))

        // Play / Pause — larger tap target
        ButtonMMD(
            onClick = onPlayPause,
            modifier = Modifier.size(72.dp)
        ) {
            TextMMD(
                text = if (isPlaying) "⏸" else "▶",
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        // Skip forward 30s
        ButtonMMD(
            onClick = onSkipForward,
            modifier = Modifier.size(56.dp)
        ) {
            TextMMD("+30s", fontWeight = FontWeight.Medium)
        }

        Spacer(modifier = Modifier.width(8.dp))

        // Next chapter
        ButtonMMD(
            onClick = onNextChapter,
            modifier = Modifier.size(56.dp)
        ) {
            TextMMD("⏭", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun BottomToolbar(
    playbackState: PlaybackState,
    onSpeedClick: () -> Unit,
    onSleepTimerClick: () -> Unit,
    onBookmarkClick: () -> Unit,
    onChaptersClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp, horizontal = 4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        // Speed
        ButtonMMD(
            onClick = onSpeedClick,
            modifier = Modifier
                .weight(1f)
                .height(48.dp)
                .padding(horizontal = 4.dp)
        ) {
            TextMMD(formatSpeed(playbackState.playbackSpeed))
        }

        // Sleep timer
        ButtonMMD(
            onClick = onSleepTimerClick,
            modifier = Modifier
                .weight(1f)
                .height(48.dp)
                .padding(horizontal = 4.dp)
        ) {
            val label = playbackState.sleepTimerRemainingMs?.let { ms ->
                "Sleep ${formatDuration(ms)}"
            } ?: "Sleep"
            TextMMD(label)
        }

        // Bookmark
        ButtonMMD(
            onClick = onBookmarkClick,
            modifier = Modifier
                .weight(1f)
                .height(48.dp)
                .padding(horizontal = 4.dp)
        ) {
            TextMMD("Bookmark")
        }

        // Chapters
        ButtonMMD(
            onClick = onChaptersClick,
            modifier = Modifier
                .weight(1f)
                .height(48.dp)
                .padding(horizontal = 4.dp)
        ) {
            TextMMD("Chapters")
        }
    }
}

/**
 * Chapter list overlay / screen.
 */
@Composable
fun ChapterListScreen(
    chapters: List<Chapter>,
    currentChapterIndex: Int,
    onChapterClick: (Int) -> Unit,
    onBackClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
    ) {
        TopAppBarMMD(
            title = { TextMMD("Chapters", fontWeight = FontWeight.Bold) },
            navigationIcon = {
                IconButton(onClick = onBackClick) {
                    TextMMD("←")
                }
            }
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 12.dp)
        ) {
            itemsIndexed(chapters) { index, chapter ->
                val isActive = index == currentChapterIndex
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onChapterClick(index) }
                        .background(if (isActive) Color(0xFFE0E0E0) else Color.White)
                        .padding(horizontal = 20.dp, vertical = 18.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        TextMMD(
                            text = "${index + 1}. ${chapter.title}",
                            fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    TextMMD(
                        text = formatDuration(chapter.durationMs),
                        color = Color.Gray,
                        textAlign = TextAlign.End
                    )
                }
                // Divider
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp)
                        .height(1.dp)
                        .background(Color.LightGray)
                )
            }
        }
    }
}
