package com.imamjesly.fliptunes

import android.service.notification.NotificationListenerService

/**
 * Android only lets an app read the "now playing" media sessions if the user
 * grants it Notification access. This empty listener is what that permission
 * is attached to. We don't read any notifications.
 */
class MediaListenerService : NotificationListenerService() {
    override fun onListenerConnected() {
        super.onListenerConnected()
        Bridge.watcher?.refresh()
    }
}
