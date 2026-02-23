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
import com.mudita.mmd.components.buttons.ButtonMMD
import com.mudita.mmd.components.text.TextMMD

/**
 * Minimalist library screen for the Kompakt 800×480 E Ink display.
 * High contrast, large tap targets, no animations.
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
        // Header row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextMMD("Library", fontWeight = FontWeight.Bold)
            ButtonMMD(
                onClick = onAddClick,
                modifier = Modifier.height(44.dp)
            ) {
                TextMMD("+ Add")
            }
        }

        // Divider
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(Color.LightGray)
        )

        if (audiobooks.isEmpty()) {
            EmptyLibrary(onAddClick = onAddClick)
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize(),
                contentPadding = PaddingValues(vertical = 4.dp)
            ) {
                items(audiobooks, key = { it.id }) { audiobook ->
                    AudiobookListItem(
                        audiobook = audiobook,
                        onClick = { onAudiobookClick(audiobook.id) }
                    )
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
            .padding(48.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        TextMMD(
            text = "No audiobooks yet",
            fontWeight = FontWeight.Medium
        )
        Spacer(modifier = Modifier.height(12.dp))
        TextMMD(
            text = "Tap + Add to select a folder."
        )
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
            .padding(horizontal = 24.dp, vertical = 14.dp)
    ) {
        // Title and duration on one row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            TextMMD(
                text = audiobook.title,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(12.dp))
            TextMMD(
                text = formatDuration(audiobook.totalDurationMs),
                color = Color.Gray
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Author and status
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            TextMMD(
                text = audiobook.author,
                color = Color.DarkGray,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            val status = when {
                audiobook.isCompleted -> "Done"
                audiobook.lastPlayedAt > 0 -> "In progress"
                else -> ""
            }
            if (status.isNotEmpty()) {
                TextMMD(text = status, color = Color.Gray)
            }
        }

        // Progress bar — only if in progress
        if (audiobook.lastPlayedAt > 0 && !audiobook.isCompleted && audiobook.totalDurationMs > 0) {
            Spacer(modifier = Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
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
    }

    // Divider
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .height(1.dp)
            .background(Color(0xFFEEEEEE))
    )
}
