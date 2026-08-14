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

    // Line Animations: 1=Fade, 2=SlideUp, 3=Scale, 4=3D Flip (RotateX), 5=Blur Reveal.
    private val KEY_LINE_ANIMATION = androidx.datastore.preferences.core.intPreferencesKey("overlay_line_animation")
    // Word Animations: 1=None, 2=Scale, 3=Glow, 4=Jump/Lift (TranslateY), 5=Elastic Pop, 6=Karaoke Fill.
    private val KEY_WORD_ANIMATION = androidx.datastore.preferences.core.intPreferencesKey("overlay_word_animation")
    private val KEY_ACTIVE_WORD_COLOR = androidx.datastore.preferences.core.intPreferencesKey("overlay_active_word_color")
    private val KEY_ACTIVE_LINE_COLOR = androidx.datastore.preferences.core.intPreferencesKey("overlay_active_line_color")
    private val KEY_INACTIVE_LINE_COLOR = androidx.datastore.preferences.core.intPreferencesKey("overlay_inactive_line_color")

    fun getLineAnimation(context: Context): Flow<Int> {
        return context.dataStore.data.map { prefs ->
            prefs[KEY_LINE_ANIMATION] ?: 2 // Default: Slide Up
        }
    }

    suspend fun setLineAnimation(context: Context, value: Int) {
        context.safeDataStoreEdit { prefs ->
            prefs[KEY_LINE_ANIMATION] = value
        }
    }

    fun getWordAnimation(context: Context): Flow<Int> {
        return context.dataStore.data.map { prefs ->
            prefs[KEY_WORD_ANIMATION] ?: 2 // Default: Spring Scale
        }
    }

    suspend fun setWordAnimation(context: Context, value: Int) {
        context.safeDataStoreEdit { prefs ->
            prefs[KEY_WORD_ANIMATION] = value
        }
    }

    fun getActiveWordColor(context: Context): Flow<Int> {
        return context.dataStore.data.map { prefs ->
            prefs[KEY_ACTIVE_WORD_COLOR] ?: android.graphics.Color.parseColor("#FFD700")
        }
    }

    suspend fun setActiveWordColor(context: Context, color: Int) {
        context.safeDataStoreEdit { prefs ->
            prefs[KEY_ACTIVE_WORD_COLOR] = color
        }
    }

    fun getActiveLineColor(context: Context): Flow<Int> {
        return context.dataStore.data.map { prefs ->
            prefs[KEY_ACTIVE_LINE_COLOR] ?: android.graphics.Color.WHITE
        }
    }

    suspend fun setActiveLineColor(context: Context, color: Int) {
        context.safeDataStoreEdit { prefs ->
            prefs[KEY_ACTIVE_LINE_COLOR] = color
        }
    }

    fun getInactiveLineColor(context: Context): Flow<Int> {
        return context.dataStore.data.map { prefs ->
            prefs[KEY_INACTIVE_LINE_COLOR] ?: android.graphics.Color.parseColor("#A6FFFFFF") // ~65% white
        }
    }

    suspend fun setInactiveLineColor(context: Context, color: Int) {
        context.safeDataStoreEdit { prefs ->
            prefs[KEY_INACTIVE_LINE_COLOR] = color
        }
    }

    private val KEY_BACKGROUND_COLOR = androidx.datastore.preferences.core.intPreferencesKey("overlay_background_color")

    fun getBackgroundColor(context: Context): Flow<Int> {
        return context.dataStore.data.map { prefs ->
            prefs[KEY_BACKGROUND_COLOR] ?: android.graphics.Color.TRANSPARENT
        }
    }

    suspend fun setBackgroundColor(context: Context, color: Int) {
        context.safeDataStoreEdit { prefs ->
            prefs[KEY_BACKGROUND_COLOR] = color
        }
    }

    private val KEY_SHOW_ROMANIZED = booleanPreferencesKey("show_romanized_lyrics")
    private val KEY_SHOW_TRANSLATED = booleanPreferencesKey("show_translated_lyrics")

    fun getShowRomanizedLyrics(context: Context): Flow<Boolean> {
        return context.dataStore.data.map { prefs ->
            prefs[KEY_SHOW_ROMANIZED] ?: true
        }
    }

    suspend fun setShowRomanizedLyrics(context: Context, enabled: Boolean) {
        context.safeDataStoreEdit { prefs ->
            prefs[KEY_SHOW_ROMANIZED] = enabled
        }
    }

    fun getShowTranslatedLyrics(context: Context): Flow<Boolean> {
        return context.dataStore.data.map { prefs ->
            prefs[KEY_SHOW_TRANSLATED] ?: true
        }
    }

    suspend fun setShowTranslatedLyrics(context: Context, enabled: Boolean) {
        context.safeDataStoreEdit { prefs ->
            prefs[KEY_SHOW_TRANSLATED] = enabled
        }
    }
}
