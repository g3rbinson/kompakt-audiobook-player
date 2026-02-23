package com.kompakt.audiobookplayer.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kompakt.audiobookplayer.data.Audiobook
import com.kompakt.audiobookplayer.ui.util.formatDuration
import com.mudita.mmd.components.top_app_bar.TopAppBarMMD
import com.mudita.mmd.components.buttons.ButtonMMD
import com.mudita.mmd.components.text.TextMMD

/**
 * Library screen — shows all imported audiobooks.
 *
 * Designed for the Kompakt's 800×480 E Ink display:
 * - High contrast black-on-white
 * - Large tap targets
 * - No animations or color gradients
 * - Minimal recompositions to reduce E Ink ghosting
 */
@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    onAudiobookClick: (Long) -> Unit,
    onAddClick: () -> Unit
) {
    val audiobooks by viewModel.audiobooks.collectAsState(initial = emptyList())

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
    ) {
        // Top bar
        TopAppBarMMD(
            title = { TextMMD("Audiobook Library", fontWeight = FontWeight.Bold) }
        )

        if (audiobooks.isEmpty()) {
            // Empty state
            EmptyLibrary(onAddClick = onAddClick)
        } else {
            // Audiobook list
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                items(audiobooks, key = { it.id }) { audiobook ->
                    AudiobookListItem(
                        audiobook = audiobook,
                        onClick = { onAudiobookClick(audiobook.id) }
                    )
                }
            }

            // Add button at bottom
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                ButtonMMD(onClick = onAddClick) {
                    TextMMD("+ Add Audiobook")
                }
            }
        }
    }
}

@Composable
private fun EmptyLibrary(onAddClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        TextMMD(
            text = "No audiobooks yet",
            fontWeight = FontWeight.Medium
        )
        Spacer(modifier = Modifier.height(8.dp))
        TextMMD(
            text = "Add a folder containing audio files to get started."
        )
        Spacer(modifier = Modifier.height(24.dp))
        ButtonMMD(onClick = onAddClick) {
            TextMMD("+ Add Audiobook")
        }
    }
}

@Composable
private fun AudiobookListItem(
    audiobook: Audiobook,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        TextMMD(
            text = audiobook.title,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.height(4.dp))
        TextMMD(
            text = audiobook.author,
            color = Color.DarkGray,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.height(4.dp))

        // Progress indicator
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            val status = when {
                audiobook.isCompleted -> "Completed"
                audiobook.lastPlayedAt > 0 -> "In progress"
                else -> "Not started"
            }
            TextMMD(text = status, color = Color.Gray)
            TextMMD(
                text = formatDuration(audiobook.totalDurationMs),
                color = Color.Gray
            )
        }

        // Simple progress bar — E Ink friendly (no animation)
        if (audiobook.lastPlayedAt > 0 && !audiobook.isCompleted && audiobook.totalDurationMs > 0) {
            Spacer(modifier = Modifier.height(6.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .background(Color.LightGray)
            ) {
                val progress = audiobook.currentPositionMs.toFloat() / audiobook.totalDurationMs
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(fraction = progress.coerceIn(0f, 1f))
                        .background(Color.Black)
                )
            }
        }

        // Divider
        Spacer(modifier = Modifier.height(12.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(Color.LightGray)
        )
    }
}
