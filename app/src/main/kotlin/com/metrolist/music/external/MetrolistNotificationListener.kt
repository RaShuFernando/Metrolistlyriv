package com.metrolist.music.external

import android.service.notification.NotificationListenerService

class MetrolistNotificationListener : NotificationListenerService() {

    private var playerWatcher: ExternalPlayerWatcher? = null

    override fun onListenerConnected() {
        super.onListenerConnected()
        // The OS has connected the service. Start watching for media sessions!
        playerWatcher = ExternalPlayerWatcher(this, ExternalLyricsCoordinator)
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        // The OS disconnected the service. Clean up to prevent memory leaks.
        playerWatcher?.stop()
        playerWatcher = null
    }
}
