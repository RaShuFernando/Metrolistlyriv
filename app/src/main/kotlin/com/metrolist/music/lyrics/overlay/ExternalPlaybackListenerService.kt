package com.metrolist.music.lyrics.overlay

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import androidx.core.content.ContextCompat
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
 * patched YouTube Music (app.morphe.android.apps.youtube.music).
 *
 * Responsibilities:
 * 1. Dynamically registers a BroadcastReceiver for Morphe and YouTube Music metadata changes.
 * 2. Monitors active MediaSessions, filtering strictly for app.morphe.android.apps.youtube.music in exclusive mode.
 * 3. Extracts track metadata and videoId, resolves info via InnerTube, and syncs lyrics to the local vault.
 * 4. Advances real-time interpolated playback position to OverlayLyricsService.
 */
class ExternalPlaybackListenerService : NotificationListenerService() {

    companion object {
        private const val TAG = "ExternalPlaybackListener"
        const val TARGET_PACKAGE = "app.morphe.android.apps.youtube.music"
        const val YTM_PACKAGE = "com.google.android.apps.youtube.music"
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var playbackJob: Job? = null
    private var isExternalEnabled = true
    private var isExclusiveEnabled = false
    private var mediaSessionManager: MediaSessionManager? = null
    private val activeControllers = ConcurrentHashMap<MediaController, MediaController.Callback>()
    
    @Volatile
    private var currentInterceptedVideoId: String? = null

    private val sessionListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        updateActiveSessions(controllers)
    }

    private val metadataReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            val action = intent.action
            FileLogger.d(TAG, "Received metadata broadcast action: $action")

            val videoId = intent.getStringExtra("videoId")
                ?: intent.getStringExtra("video_id")
                ?: intent.getStringExtra("id")

            if (!videoId.isNullOrBlank()) {
                FileLogger.d(TAG, "Intercepted videoId: $videoId from broadcast ($action)")
                currentInterceptedVideoId = videoId
                serviceScope.launch {
                    val hasLyrics = ExternalLyricsSyncManager.ensureLyricsForVideo(applicationContext, videoId)
                    if (hasLyrics) {
                        withContext(Dispatchers.Main) {
                            OverlayLyricsService.start(applicationContext)
                        }
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()

        // Register dynamic BroadcastReceiver for Morphe & YTM metadata events
        val filter = IntentFilter().apply {
            addAction("app.morphe.music.METADATA_CHANGED")
            addAction("com.google.android.apps.youtube.music.METADATA_CHANGED")
            addAction("app.morphe.patches.music.VIDEO_ID_CHANGED")
        }
        try {
            ContextCompat.registerReceiver(
                this,
                metadataReceiver,
                filter,
                ContextCompat.RECEIVER_EXPORTED
            )
            FileLogger.d(TAG, "Dynamically registered Morphe/YTM metadata BroadcastReceiver")
        } catch (e: Exception) {
            FileLogger.e(TAG, "Error registering metadata BroadcastReceiver", e)
        }

        // Collect preferences
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

        serviceScope.launch {
            OverlayLyricsPreferences.getExclusiveExternalLyricsEnabled(applicationContext).collect { exclusive ->
                isExclusiveEnabled = exclusive
                val component = ComponentName(this@ExternalPlaybackListenerService, ExternalPlaybackListenerService::class.java)
                val controllers = try {
                    mediaSessionManager?.getActiveSessions(component)
                } catch (e: Exception) {
                    null
                }
                updateActiveSessions(controllers)
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
        try {
            unregisterReceiver(metadataReceiver)
        } catch (e: Exception) {
            FileLogger.e(TAG, "Error unregistering metadata receiver", e)
        }
        playbackJob?.cancel()
        playbackJob = null
        unregisterAllControllers()
        serviceScope.cancel()
        OverlayLyricsService.clearExternalPlayback()
    }

    private fun isTargetPackage(packageName: String?): Boolean {
        if (packageName == null) return false
        return if (isExclusiveEnabled) {
            packageName == TARGET_PACKAGE
        } else {
            packageName == TARGET_PACKAGE || packageName == YTM_PACKAGE
        }
    }

    private fun updateActiveSessions(controllers: List<MediaController>?) {
        unregisterAllControllers()
        if (controllers.isNullOrEmpty() || !isExternalEnabled) {
            playbackJob?.cancel()
            playbackJob = null
            OverlayLyricsService.clearExternalPlayback()
            return
        }

        var foundTarget = false
        for (controller in controllers) {
            if (isTargetPackage(controller.packageName)) {
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
        if (!isTargetPackage(controller.packageName) || !isExternalEnabled) {
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

            // Determine target videoId from intercepted broadcast or local cache
            val targetVideoId = currentInterceptedVideoId ?: run {
                if (!rawTitle.isNullOrBlank() && !rawArtist.isNullOrBlank()) {
                    ExternalLyricsSyncManager.findCachedVideoId(applicationContext, rawTitle, rawArtist)
                } else null
            }

            if (targetVideoId != null) {
                val hasLyrics = ExternalLyricsSyncManager.ensureLyricsForVideo(applicationContext, targetVideoId)
                if (hasLyrics) {
                    FileLogger.d(TAG, "Lyrics active for videoId: $targetVideoId (isPlaying=$isPlaying, pos=$basePositionMs)")
                    withContext(Dispatchers.Main) {
                        OverlayLyricsService.start(applicationContext)
                    }

                    if (isPlaying) {
                        while (isActive) {
                            val timeDelta = (SystemClock.elapsedRealtime() - lastUpdateTime).coerceAtLeast(0L)
                            val interpolatedPos = basePositionMs + (timeDelta * speed).toLong()

                            OverlayLyricsService.updatePlaybackState(
                                videoId = targetVideoId,
                                positionMs = interpolatedPos,
                                playing = true,
                                isExternal = true
                            )
                            delay(100L)
                        }
                    } else {
                        OverlayLyricsService.updatePlaybackState(
                            videoId = targetVideoId,
                            positionMs = basePositionMs,
                            playing = false,
                            isExternal = true
                        )
                    }
                } else {
                    OverlayLyricsService.updatePlaybackState("", 0L, false, isExternal = true)
                }
            } else if (!rawTitle.isNullOrBlank() && !rawArtist.isNullOrBlank()) {
                val rawAlbum = currentMetadata?.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: ""
                handlePlaybackEventByMetadata(
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
    }

    private suspend fun CoroutineScope.handlePlaybackEventByMetadata(
        rawTitle: String,
        rawArtist: String,
        rawAlbum: String,
        basePositionMs: Long,
        lastUpdateTime: Long,
        speed: Float,
        isPlaying: Boolean
    ) {
        try {
            val matchedVideoId = ExternalLyricsSyncManager.findCachedVideoId(applicationContext, rawTitle, rawArtist)
            if (matchedVideoId != null) {
                val hasLyrics = ExternalLyricsSyncManager.ensureLyricsForVideo(applicationContext, matchedVideoId)
                if (hasLyrics) {
                    withContext(Dispatchers.Main) {
                        OverlayLyricsService.start(applicationContext)
                    }

                    if (isPlaying) {
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
                        OverlayLyricsService.updatePlaybackState(
                            videoId = matchedVideoId,
                            positionMs = basePositionMs,
                            playing = false,
                            isExternal = true
                        )
                    }
                } else {
                    OverlayLyricsService.updatePlaybackState("", 0L, false, isExternal = true)
                }
            } else {
                FileLogger.d(TAG, "No videoId or local lyrics found for \"$rawTitle\" by \"$rawArtist\".")
                OverlayLyricsService.updatePlaybackState("", 0L, false, isExternal = true)
            }
        } catch (e: Exception) {
            FileLogger.e(TAG, "Error checking database for \"$rawTitle\" by \"$rawArtist\"", e)
        }
    }
}

