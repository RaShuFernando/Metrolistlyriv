package com.metrolist.music.lyrics.overlay

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import com.metrolist.music.utils.dataStore
import com.metrolist.music.utils.safeDataStoreEdit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

object OverlayLyricsPreferences {
    private val KEY_OVERLAY_ENABLED = booleanPreferencesKey("overlay_lyrics_enabled")
    private val KEY_OVERLAY_FONT_SIZE = floatPreferencesKey("overlay_lyrics_font_size")

    fun getOverlayEnabled(context: Context): Flow<Boolean> {
        return context.dataStore.data.map { prefs ->
            prefs[KEY_OVERLAY_ENABLED] ?: false
        }
    }

    suspend fun setOverlayEnabled(context: Context, enabled: Boolean) {
        context.safeDataStoreEdit { prefs ->
            prefs[KEY_OVERLAY_ENABLED] = enabled
        }
    }

    fun getOverlayFontSize(context: Context): Flow<Float> {
        return context.dataStore.data.map { prefs ->
            prefs[KEY_OVERLAY_FONT_SIZE] ?: 18f
        }
    }

    suspend fun setOverlayFontSize(context: Context, fontSize: Float) {
        context.safeDataStoreEdit { prefs ->
            prefs[KEY_OVERLAY_FONT_SIZE] = fontSize
        }
    }

    private val KEY_ENABLE_LYRICS_PREFETCH = booleanPreferencesKey("enable_lyrics_prefetch")
    private val KEY_LYRICS_PREFETCH_COUNT = androidx.datastore.preferences.core.intPreferencesKey("lyrics_prefetch_count")

    fun getLyricsPrefetchEnabled(context: Context): Flow<Boolean> {
        return context.dataStore.data.map { prefs ->
            prefs[KEY_ENABLE_LYRICS_PREFETCH] ?: false
        }
    }

    suspend fun setLyricsPrefetchEnabled(context: Context, enabled: Boolean) {
        context.safeDataStoreEdit { prefs ->
            prefs[KEY_ENABLE_LYRICS_PREFETCH] = enabled
        }
    }

    fun getLyricsPrefetchCount(context: Context): Flow<Int> {
        return context.dataStore.data.map { prefs ->
            prefs[KEY_LYRICS_PREFETCH_COUNT] ?: 1
        }
    }

    suspend fun setLyricsPrefetchCount(context: Context, count: Int) {
        context.safeDataStoreEdit { prefs ->
            prefs[KEY_LYRICS_PREFETCH_COUNT] = count.coerceIn(1, 20)
        }
    }
}
