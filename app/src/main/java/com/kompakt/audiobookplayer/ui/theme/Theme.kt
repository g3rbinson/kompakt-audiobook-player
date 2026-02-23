package com.kompakt.audiobookplayer.ui.theme

import androidx.compose.runtime.Composable
import com.mudita.mmd.ThemeMMD

/**
 * App-level theme wrapper using Mudita Mindful Design.
 * MMD automatically provides an E Ink-optimized monochrome color scheme
 * with disabled ripple effects and minimal animations.
 */
@Composable
fun AudiobookTheme(content: @Composable () -> Unit) {
    ThemeMMD {
        content()
    }
}
