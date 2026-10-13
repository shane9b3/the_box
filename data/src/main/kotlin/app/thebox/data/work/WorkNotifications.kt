package app.thebox.data.work

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.ForegroundInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** The "Sync" notification channel and the dataSync [ForegroundInfo] used by every import worker (§3.10, B4). */
@Singleton
class WorkNotifications @Inject constructor(@param:ApplicationContext private val context: Context) {

    fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Sync", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Playlist and TV guide updates"
            setShowBadge(false)
        })
    }

    fun foregroundInfo(notificationId: Int, title: String, text: String? = null): ForegroundInfo {
        ensureChannel()
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context).setPriority(Notification.PRIORITY_LOW)
        }
        val notification = builder
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(0, 0, true)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(notificationId, notification)
        }
    }

    companion object {
        const val CHANNEL_ID = "sync"
        const val ID_PLAYLIST = 4101
        const val ID_EPG_IMPORT = 4102
        const val ID_EPG_PATCH = 4103
    }
}
