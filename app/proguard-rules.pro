# Add project specific ProGuard rules here.
-keep class com.kompakt.audiobookplayer.data.** { *; }
-keep class * extends androidx.room.RoomDatabase
-dontwarn com.google.android.gms.**
