# Kompakt Audiobook Player

A simple, E Ink-optimized audiobook player for the **Mudita Kompakt**, built with Kotlin, Jetpack Compose, and the [Mudita Mindful Design (MMD)](https://github.com/mudita/MMD) component library.

## Features

- **Library management** — Import audiobooks by selecting folders containing audio files
- **Persistent progress** — Automatically saves your position; resume exactly where you left off
- **Chapter navigation** — Browse and jump between chapters
- **Playback speed** — Cycle through 0.75×, 1.0×, 1.25×, 1.5×, 1.75×, 2.0×
- **Sleep timer** — Auto-pause after 15, 30, 45, or 60 minutes
- **Bookmarks** — Save and return to any position
- **Background playback** — Continues playing with notification controls when the screen is off
- **E Ink optimized** — High contrast monochrome UI, no animations, large tap targets, minimal screen refreshes

## Supported Formats

MP3, M4A, M4B, OGG, Opus, FLAC, WAV, AAC

## Architecture

```
com.kompakt.audiobookplayer/
├── AudiobookApp.kt                  # Application class
├── data/                            # Room database layer
│   ├── AppDatabase.kt
│   ├── Audiobook.kt                 # Entity
│   ├── AudiobookWithChapters.kt     # Relation
│   ├── Bookmark.kt                  # Entity
│   ├── Chapter.kt                   # Entity
│   ├── AudiobookDao.kt
│   ├── BookmarkDao.kt
│   └── ChapterDao.kt
├── playback/                        # Media3 audio playback
│   ├── AudiobookPlaybackService.kt  # Foreground service
│   ├── PlaybackController.kt        # UI-facing controller
│   └── PlaybackState.kt             # State model
└── ui/                              # Jetpack Compose UI (MMD)
    ├── MainActivity.kt              # Entry point + navigation
    ├── theme/Theme.kt               # MMD theme wrapper
    ├── util/FormatUtils.kt
    ├── library/
    │   ├── LibraryScreen.kt
    │   └── LibraryViewModel.kt
    └── player/
        ├── PlayerScreen.kt          # Player + Chapter list
        └── PlayerViewModel.kt
```

## Building

Requires Android Studio Ladybug (2024.2) or later with:
- Kotlin 2.1+
- AGP 8.7+
- JDK 17

```bash
./gradlew assembleRelease
```

## Installing on Kompakt

1. Build the APK: `./gradlew assembleRelease`
2. Connect Kompakt via USB-C
3. Use **Mudita Center** to sideload the APK, or:
   ```bash
   adb install app/build/outputs/apk/release/app-release.apk
   ```

## How to Use

1. Launch the app on Kompakt
2. Tap **"+ Add Audiobook"** to select a folder containing audio files (one folder = one audiobook)
3. Tap an audiobook in the library to start playback
4. Use the player controls: skip ±30s/15s, change chapters, adjust speed, set sleep timer, or add bookmarks

## License

Apache 2.0
