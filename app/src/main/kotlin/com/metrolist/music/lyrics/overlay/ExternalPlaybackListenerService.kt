package com.metrolist.music.lyrics.overlay

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import com.metrolist.music.lyrics.vault.DatabaseProvider
import com.metrolist.music.lyrics.vault.FileLogger
import com.metrolist.music.lyrics.vault.LyricsSyncManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * ExternalPlaybackListenerService monitors media playback events specifically from
 * app.morphe.android.apps.youtube.music. It extracts Title, Artist, and Album metadata,
 * queries the local vault database, and if a match is found, triggers the overlay module.
 *
 * Key Design & Robustness Features:
 * 1. Active State Expansion: Treats STATE_PLAYING, STATE_BUFFERING, and STATE_CONNECTING as active playback.
 * 2. Instant Metadata Retry: Handles early empty/null metadata events via a non-blocking retry polling loop.
 * 3. Single-Job Debouncing: Cancels ongoing playback jobs before starting a new state handler.
 * 4. High-Precision Interpolated Ticker: Smoothly advances lyric timeline while media is playing.
 * 5. State Filtering: Accurately handles PLAYING, PAUSED, and STOPPED states without flickering.
 * 6. Vault Retrieval Normalization: Cleans title and artist artifacts for reliable matching.
 * 7. Strict Zero-Download Constraint: Only plays if lyrics exist locally in the vault.
 */
class ExternalPlaybackListenerService : NotificationListenerService() {

    companion object {
        private const val TAG = "ExternalPlaybackListener"
        const val TARGET_PACKAGE = "app.morphe.android.apps.youtube.music"
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var playbackJob: Job? = null
    private var isExternalEnabled = true
    private var mediaSessionManager: MediaSessionManager? = null
    private val activeControllers = ConcurrentHashMap<MediaController, MediaController.Callback>()

    private val sessionListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        updateActiveSessions(controllers)
    }

    override fun onCreate() {
        super.onCreate()
        serviceScope.launch {
            OverlayLyricsPreferences.getExternalOverlayEnabled(applicationContext).collect { enabled ->
                isExternalEnabled = enabled
                if (!enabled) {
                    playbackJob?.cancel()
                    playbackJob = null
                    OverlayLyricsService.clearExternalPlayback()
                }
            }
        }
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
        playbackJob?.cancel()
        playbackJob = null
        unregisterAllControllers()
        try {
            mediaSessionManager?.removeOnActiveSessionsChangedListener(sessionListener)
        } catch (e: Exception) {
            FileLogger.e(TAG, "Error unregistering session listener", e)
        }
        OverlayLyricsService.clearExternalPlayback()
    }

    override fun onDestroy() {
        super.onDestroy()
        playbackJob?.cancel()
        playbackJob = null
        unregisterAllControllers()
        serviceScope.cancel()
        OverlayLyricsService.clearExternalPlayback()
    }

    private fun updateActiveSessions(controllers: List<MediaController>?) {
        unregisterAllControllers()
        if (controllers.isNullOrEmpty()) {
            playbackJob?.cancel()
            playbackJob = null
            OverlayLyricsService.clearExternalPlayback()
            return
        }

        var foundTarget = false
        for (controller in controllers) {
            if (controller.packageName == TARGET_PACKAGE) {
                registerController(controller)
                foundTarget = true
            }
        }

        if (!foundTarget) {
            playbackJob?.cancel()
            playbackJob = null
            OverlayLyricsService.clearExternalPlayback()
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
                playbackJob?.cancel()
                playbackJob = null
                OverlayLyricsService.clearExternalPlayback()
            }
        }

        controller.registerCallback(callback)
        activeControllers[controller] = callback

        // Process current state immediately
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

    private fun isPlaybackActive(state: Int): Boolean {
        return state == PlaybackState.STATE_PLAYING ||
                state == PlaybackState.STATE_BUFFERING ||
                state == PlaybackState.STATE_CONNECTING ||
                state == PlaybackState.STATE_FAST_FORWARDING ||
                state == PlaybackState.STATE_REWINDING
    }

    private fun processMediaUpdate(
        controller: MediaController,
        metadata: MediaMetadata?,
        playbackState: PlaybackState?
    ) {
        if (controller.packageName != TARGET_PACKAGE || !isExternalEnabled) {
            return
        }

        // Cancel previous coroutine job to prevent race conditions on rapid state toggles
        playbackJob?.cancel()

        if (playbackState == null) {
            OverlayLyricsService.updatePlaybackState("", 0L, false, isExternal = true)
            return
        }

        val state = playbackState.state
        val isPlaying = isPlaybackActive(state)
        val isPaused = state == PlaybackState.STATE_PAUSED

        // If neither active nor paused (e.g. stopped, none, error), update overlay to inactive
        if (!isPlaying && !isPaused) {
            OverlayLyricsService.updatePlaybackState(
                videoId = OverlayLyricsService.currentVideoId.value,
                positionMs = playbackState.position,
                playing = false,
                isExternal = true
            )
            return
        }

        val basePositionMs = playbackState.position
        val lastUpdateTime = playbackState.lastPositionUpdateTime
        val speed = playbackState.playbackSpeed.takeIf { it > 0f } ?: 1.0f

        playbackJob = serviceScope.launch {
            // Retrieve metadata, with retry mechanism if initially null or empty strings
            var currentMetadata = metadata ?: controller.metadata
            var rawTitle = currentMetadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
            var rawArtist = currentMetadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)

            var retries = 0
            val maxRetries = 10 // 10 * 80ms = 800ms retry window for initial metadata publication
            while ((rawTitle.isNullOrBlank() || rawArtist.isNullOrBlank()) && retries < maxRetries && isActive) {
                delay(80L)
                retries++
                currentMetadata = controller.metadata
                rawTitle = currentMetadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
                rawArtist = currentMetadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            }

            if (rawTitle.isNullOrBlank() || rawArtist.isNullOrBlank()) {
                // Metadata still unavailable after retries
                return@launch
            }

            val rawAlbum = currentMetadata?.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: ""

            handlePlaybackEvent(
                rawTitle = rawTitle,
                rawArtist = rawArtist,
                rawAlbum = rawAlbum,
                basePositionMs = basePositionMs,
                lastUpdateTime = lastUpdateTime,
                speed = speed,
                isPlaying = isPlaying
            )
        }
    }

    private fun cleanMetadata(text: String): String {
        return text.replace(Regex("""[\u200B-\u200D\uFEFF\u200E\u200F]"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private suspend fun CoroutineScope.handlePlaybackEvent(
        rawTitle: String,
        rawArtist: String,
        rawAlbum: String,
        basePositionMs: Long,
        lastUpdateTime: Long,
        speed: Float,
        isPlaying: Boolean
    ) {
        try {
            val title = cleanMetadata(rawTitle)
            val artist = cleanMetadata(rawArtist)
            val album = cleanMetadata(rawAlbum)

            val masterPath = LyricsSyncManager.getMasterFolderPath()
            val db = DatabaseProvider.getDatabase(applicationContext, masterPath)
            val dao = db.libraryDao()

            // 1. Check local Vault database (song_index first, then songs entity table)
            var songIndex = dao.getSongIndexByTitleAndArtist(title, artist)
            var songEntity = if (songIndex == null) dao.checkSongExists(title, artist) else null

            // Secondary lookup: try without common trailing tags if initial match fails
            if (songIndex == null && songEntity == null) {
                val simplifiedTitle = title.replace(
                    Regex("""(?i)\s*[\(\[](?:official\s*(?:video|audio|music\s*video|lyric\s*video)?|feat\.?|ft\.?).*?[\)\]]"""),
                    ""
                ).trim()
                if (simplifiedTitle.isNotEmpty() && simplifiedTitle != title) {
                    songIndex = dao.getSongIndexByTitleAndArtist(simplifiedTitle, artist)
                    songEntity = if (songIndex == null) dao.checkSongExists(simplifiedTitle, artist) else null
                }
            }

            val matchedVideoId = songIndex?.videoId ?: songEntity?.youtubeVideoId
            val hasLocalLyrics = songIndex != null || songEntity?.hasLyricsAvailable == true

            if (matchedVideoId != null && hasLocalLyrics) {
                FileLogger.d(TAG, "Found local lyrics in vault for \"$title\" by \"$artist\" ($matchedVideoId). Displaying overlay.")

                withContext(Dispatchers.Main) {
                    OverlayLyricsService.start(applicationContext)
                }

                if (isPlaying) {
                    // Start high-precision ticker loop to smoothly advance lyrics in real time
                    while (isActive) {
                        val timeDelta = (SystemClock.elapsedRealtime() - lastUpdateTime).coerceAtLeast(0L)
                        val interpolatedPos = basePositionMs + (timeDelta * speed).toLong()

                        OverlayLyricsService.updatePlaybackState(
                            videoId = matchedVideoId,
                            positionMs = interpolatedPos,
                            playing = true,
                            isExternal = true
                        )
                        delay(100L)
                    }
                } else {
                    // Paused state: send single update with current position and playing = false
                    OverlayLyricsService.updatePlaybackState(
                        videoId = matchedVideoId,
                        positionMs = basePositionMs,
                        playing = false,
                        isExternal = true
                    )
                }
            } else {
                // Crucial constraint: NO DOWNLOADING. Silently ignore if not found in local vault.
                FileLogger.d(TAG, "No local lyrics found in vault for \"$title\" by \"$artist\". Silently ignoring event.")
                OverlayLyricsService.updatePlaybackState("", 0L, false, isExternal = true)
            }
        } catch (e: Exception) {
            FileLogger.e(TAG, "Error checking vault database for \"$rawTitle\" by \"$rawArtist\"", e)
        }
    }
}
