package com.metrolist.music.external

import android.service.notification.NotificationListenerService

/**
 * A dummy listener required by the Android system to grant the app 
 * Notification Access, which in turn allows MediaSessionManager to 
 * observe active external media sessions.
 */
class MetrolistNotificationListener : NotificationListenerService()
