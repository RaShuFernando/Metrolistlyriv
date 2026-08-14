package com.metrolist.music.external

import android.content.ComponentName
import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import com.metrolist.music.lyrics.overlay.OverlayLyricsService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class ExternalPlayerWatcher(
    private val context: Context,
    private val coordinator: ExternalLyricsCoordinator,
    notificationListenerComponent: ComponentName? = null
) : MediaSessionManager.OnActiveSessionsChangedListener {

    private val mediaSessionManager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
    private var currentController: MediaController? = null
    
    init {
        try {
            val componentName = ComponentName(context, MetrolistNotificationListener::class.java)
            mediaSessionManager.addOnActiveSessionsChangedListener(this, componentName)
            onActiveSessionsChanged(mediaSessionManager.getActiveSessions(componentName))
        } catch (e: SecurityException) {
            // This will occur if the user hasn't granted Notification Access yet.
            e.printStackTrace()
        }
    }

    override fun onActiveSessionsChanged(controllers: MutableList<MediaController>?) {
        val ytMusicController = controllers?.find { it.packageName == "app.morphe.android.apps.youtube.music" }
        
        if (ytMusicController != null && currentController?.sessionToken != ytMusicController.sessionToken) {
            currentController?.unregisterCallback(callback)
            currentController = ytMusicController
            currentController?.registerCallback(callback)
            
            ytMusicController.metadata?.let { callback.onMetadataChanged(it) }
            ytMusicController.playbackState?.let { callback.onPlaybackStateChanged(it) }
        }
    }

    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: android.media.MediaMetadata?) {
            super.onMetadataChanged(metadata)
            CoroutineScope(Dispatchers.IO).launch {
                coordinator.onNewTrackDetected(context)
            }
        }

        override fun onPlaybackStateChanged(state: PlaybackState?) {
            super.onPlaybackStateChanged(state)
            state?.let {
                val isPlaying = it.state == PlaybackState.STATE_PLAYING
                val videoId = coordinator.currentVideoId ?: return
                OverlayLyricsService.updatePlaybackState(videoId, it.position, isPlaying)
            }
        }
    }
    
    fun stop() {
        mediaSessionManager.removeOnActiveSessionsChangedListener(this)
        currentController?.unregisterCallback(callback)
    }
}
