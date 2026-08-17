package com.metrolist.music.lyrics.overlay

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.service.notification.NotificationListenerService
import com.metrolist.music.lyrics.vault.DatabaseProvider
import com.metrolist.music.lyrics.vault.FileLogger
import com.metrolist.music.lyrics.vault.LyricsSyncManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * ExternalPlaybackListenerService monitors media playback events specifically from
 * app.morphe.android.apps.youtube.music. It extracts Title, Artist, and Album metadata,
 * queries the local vault database, and if a match is found, triggers the overlay module.
 *
 * Crucial Constraints Followed:
 * 1. No Downloading: If lyrics are not in the local database, it silently ignores the event.
 * 2. Strict Modularity: Fully decoupled and append-only architecture.
 */
class ExternalPlaybackListenerService : NotificationListenerService() {

    companion object {
        private const val TAG = "ExternalPlaybackListener"
        const val TARGET_PACKAGE = "app.morphe.android.apps.youtube.music"
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var mediaSessionManager: MediaSessionManager? = null
    private val activeControllers = ConcurrentHashMap<MediaController, MediaController.Callback>()

    private val sessionListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        updateActiveSessions(controllers)
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        FileLogger.d(TAG, "NotificationListener connected. Monitoring media sessions for $TARGET_PACKAGE")
        try {
            mediaSessionManager = getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
            val component = ComponentName(this, ExternalPlaybackListenerService::class.java)
            mediaSessionManager?.addOnActiveSessionsChangedListener(sessionListener, component)
            val controllers = mediaSessionManager?.getActiveSessions(component)
            updateActiveSessions(controllers)
        } catch (e: Exception) {
            FileLogger.e(TAG, "Error connecting to MediaSessionManager", e)
        }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        unregisterAllControllers()
        try {
            mediaSessionManager?.removeOnActiveSessionsChangedListener(sessionListener)
        } catch (e: Exception) {
            FileLogger.e(TAG, "Error unregistering session listener", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterAllControllers()
        serviceScope.cancel()
    }

    private fun updateActiveSessions(controllers: List<MediaController>?) {
        unregisterAllControllers()
        if (controllers.isNullOrEmpty()) return

        for (controller in controllers) {
            if (controller.packageName == TARGET_PACKAGE) {
                registerController(controller)
            }
        }
    }

    private fun registerController(controller: MediaController) {
        val callback = object : MediaController.Callback() {
            override fun onMetadataChanged(metadata: MediaMetadata?) {
                super.onMetadataChanged(metadata)
                processMediaUpdate(controller, metadata, controller.playbackState)
            }

            override fun onPlaybackStateChanged(state: PlaybackState?) {
                super.onPlaybackStateChanged(state)
                processMediaUpdate(controller, controller.metadata, state)
            }

            override fun onSessionDestroyed() {
                super.onSessionDestroyed()
                activeControllers.remove(controller)
            }
        }

        controller.registerCallback(callback)
        activeControllers[controller] = callback

        // Process current playback state immediately upon binding
        processMediaUpdate(controller, controller.metadata, controller.playbackState)
    }

    private fun unregisterAllControllers() {
        for ((controller, callback) in activeControllers) {
            try {
                controller.unregisterCallback(callback)
            } catch (e: Exception) {
                FileLogger.e(TAG, "Error unregistering callback", e)
            }
        }
        activeControllers.clear()
    }

    private fun processMediaUpdate(
        controller: MediaController,
        metadata: MediaMetadata?,
        playbackState: PlaybackState?
    ) {
        if (controller.packageName != TARGET_PACKAGE || metadata == null) {
            return
        }

        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
        val album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: ""

        if (title.isNullOrBlank() || artist.isNullOrBlank()) {
            return
        }

        val isPlaying = playbackState?.state == PlaybackState.STATE_PLAYING
        val positionMs = playbackState?.position ?: 0L

        serviceScope.launch {
            handlePlaybackEvent(
                title = title.trim(),
                artist = artist.trim(),
                album = album.trim(),
                positionMs = positionMs,
                isPlaying = isPlaying
            )
        }
    }

    private suspend fun handlePlaybackEvent(
        title: String,
        artist: String,
        album: String,
        positionMs: Long,
        isPlaying: Boolean
    ) {
        try {
            val masterPath = LyricsSyncManager.getMasterFolderPath()
            val db = DatabaseProvider.getDatabase(applicationContext, masterPath)
            val dao = db.libraryDao()

            // 1. Query local vault database by Title and Artist
            val songIndex = dao.getSongIndexByTitleAndArtist(title, artist)
            val songEntity = if (songIndex == null) dao.checkSongExists(title, artist) else null

            val matchedVideoId = songIndex?.videoId ?: songEntity?.youtubeVideoId
            val hasLocalLyrics = songIndex != null || songEntity?.hasLyricsAvailable == true

            if (matchedVideoId != null && hasLocalLyrics) {
                FileLogger.d(TAG, "Found local lyrics in vault for \"$title\" by \"$artist\" ($matchedVideoId). Activating overlay.")

                // 2. Trigger overlay module to display existing local lyrics
                OverlayLyricsService.start(applicationContext)
                OverlayLyricsService.updatePlaybackState(
                    videoId = matchedVideoId,
                    positionMs = positionMs,
                    playing = isPlaying
                )
            } else {
                // Crucial constraint: NO DOWNLOADING. Silently ignore missing local lyrics.
                FileLogger.d(TAG, "No local lyrics found in vault for \"$title\" by \"$artist\". Silently ignoring event.")
            }
        } catch (e: Exception) {
            FileLogger.e(TAG, "Error checking vault database for \"$title\" by \"$artist\"", e)
        }
    }
}
