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
import com.mudita.mmd.components.buttons.ButtonMMD
import com.mudita.mmd.components.text.TextMMD

/**
 * Minimalist player screen for the Kompakt 800×480 E Ink display.
 *
 * Layout:
 * ┌────────────────────────────┐
 * │ ← Back                    │
 * │                            │
 * │ Ch 3/12  ·  Author        │
 * │ Chapter Title              │
 * │                            │
 * │ ▓▓▓▓▓▓▓▓░░░░░ 2:15 / 8:30│
 * │                            │
 * │    ◀15s     ▶⏸     30s▶   │
 * │                            │
 * │  1.0×     Sleep     Ch ☰  │
 * └────────────────────────────┘
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
            .padding(horizontal = 24.dp, vertical = 16.dp)
    ) {
        // Back button — simple text
        ButtonMMD(
            onClick = onBackClick,
            modifier = Modifier.height(44.dp)
        ) {
            TextMMD("← Back")
        }

        // Main content fills remaining space
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 8.dp),
            verticalArrangement = Arrangement.SpaceEvenly,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Chapter & author info
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.Start
            ) {
                TextMMD(
                    text = buildString {
                        append("Ch ${playbackState.currentChapterIndex + 1}/${chapters.size}")
                        val author = audiobook?.author
                        if (!author.isNullOrBlank() && author != "Unknown Author") {
                            append("  ·  $author")
                        }
                    },
                    color = Color.Gray
                )

                Spacer(modifier = Modifier.height(8.dp))

                TextMMD(
                    text = chapters.getOrNull(playbackState.currentChapterIndex)?.title
                        ?: audiobook?.title ?: "",
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Progress bar + times
            Column(modifier = Modifier.fillMaxWidth()) {
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
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    TextMMD(text = formatDuration(playbackState.currentPositionMs))
                    TextMMD(text = formatDuration(playbackState.durationMs))
                }
            }

            // Playback controls — just 3 buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                ButtonMMD(
                    onClick = viewModel::skipBackward,
                    modifier = Modifier
                        .weight(1f)
                        .height(56.dp)
                ) {
                    TextMMD("−15s")
                }

                Spacer(modifier = Modifier.width(16.dp))

                ButtonMMD(
                    onClick = viewModel::playPause,
                    modifier = Modifier
                        .weight(1.4f)
                        .height(56.dp)
                ) {
                    TextMMD(
                        text = if (playbackState.isPlaying) "Pause" else "Play",
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.width(16.dp))

                ButtonMMD(
                    onClick = viewModel::skipForward,
                    modifier = Modifier
                        .weight(1f)
                        .height(56.dp)
                ) {
                    TextMMD("+30s")
                }
            }

            // Bottom row — speed, sleep, chapters
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                ButtonMMD(
                    onClick = viewModel::cycleSpeed,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                ) {
                    TextMMD(formatSpeed(playbackState.playbackSpeed))
                }

                Spacer(modifier = Modifier.width(12.dp))

                ButtonMMD(
                    onClick = viewModel::cycleSleepTimer,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                ) {
                    val label = playbackState.sleepTimerRemainingMs?.let { ms ->
                        formatDuration(ms)
                    } ?: "Sleep"
                    TextMMD(label)
                }

                Spacer(modifier = Modifier.width(12.dp))

                ButtonMMD(
                    onClick = onChaptersClick,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                ) {
                    TextMMD("Chapters")
                }
            }
        }
    }
}

/**
 * Chapter list screen — clean list for E Ink.
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
        // Simple back + title row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ButtonMMD(
                onClick = onBackClick,
                modifier = Modifier.height(44.dp)
            ) {
                TextMMD("← Back")
            }
            Spacer(modifier = Modifier.width(16.dp))
            TextMMD("Chapters", fontWeight = FontWeight.Bold)
        }

        // Divider
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(Color.LightGray)
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 4.dp)
        ) {
            itemsIndexed(chapters) { index, chapter ->
                val isActive = index == currentChapterIndex
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onChapterClick(index) }
                        .background(if (isActive) Color(0xFFE0E0E0) else Color.White)
                        .padding(horizontal = 24.dp, vertical = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextMMD(
                        text = chapter.title,
                        fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    TextMMD(
                        text = formatDuration(chapter.durationMs),
                        color = Color.Gray
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                        .height(1.dp)
                        .background(Color(0xFFEEEEEE))
                )
            }
        }
    }
}
