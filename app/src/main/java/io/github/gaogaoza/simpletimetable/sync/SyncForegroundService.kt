package io.github.gaogaoza.simpletimetable.sync

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/** Keeps the user-started Bluetooth session visible while its page is active. */
class SyncForegroundService : Service() {
    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("watch-sync", "手表同步", NotificationManager.IMPORTANCE_LOW))
        val notification = Notification.Builder(this, "watch-sync").setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("简单课表 · 手表同步").setContentText("蓝牙同步进行中；可在应用中结束").setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(41, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE) else startForeground(41, notification)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_NOT_STICKY
    override fun onBind(intent: Intent?): IBinder? = null
}
