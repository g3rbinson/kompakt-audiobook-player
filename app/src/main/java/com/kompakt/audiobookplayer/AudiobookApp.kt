package com.kompakt.audiobookplayer

import android.app.Application
import com.kompakt.audiobookplayer.data.AppDatabase

/**
 * Application class — initializes the database eagerly so it's ready
 * when the app starts.
 */
class AudiobookApp : Application() {

    lateinit var database: AppDatabase
        private set

    override fun onCreate() {
        super.onCreate()
        database = AppDatabase.getInstance(this)
    }
}
