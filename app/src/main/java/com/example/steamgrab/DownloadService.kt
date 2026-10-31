package com.example.steamgrab

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed class DownloadStatus {
    object Idle : DownloadStatus()
    data class Running(val appId: Int, val name: String, val progress: Float, val text: String) : DownloadStatus()
    data class Finished(val appId: Int, val path: String) : DownloadStatus()
    data class Failed(val appId: Int, val message: String) : DownloadStatus()
}

/**
 * Menjalankan download di foreground service supaya tetap jalan saat layar mati atau app di background.
 */
class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var lastNotifyMs = 0L

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Download game", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            if (job?.isActive == true) job?.cancel() else stopSelf()
            return START_NOT_STICKY
        }

        val appId = intent?.getIntExtra(EXTRA_APP_ID, -1) ?: -1
        val name = intent?.getStringExtra(EXTRA_NAME) ?: "App $appId"

        // Harus dipanggil segera setelah startForegroundService, walau request ini akan diabaikan.
        startForegroundCompat(buildNotification(name, "Menyiapkan download...", 0f))

        if (appId <= 0) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (job?.isActive == true) return START_NOT_STICKY // sudah ada download yang berjalan

        acquireLocks()
        _status.value = DownloadStatus.Running(appId, name, 0f, "Menyiapkan download...")

        job = scope.launch {
            try {
                val dir = SteamRepository.get(applicationContext).downloadGame(appId) { percent, text ->
                    publishProgress(appId, name, percent, text)
                }
                _status.value = DownloadStatus.Finished(appId, dir.absolutePath)
                postResult("Selesai: $name")
            } catch (e: CancellationException) {
                _status.value = DownloadStatus.Idle
                throw e
            } catch (e: Exception) {
                val msg = e.describe()
                _status.value = DownloadStatus.Failed(appId, msg)
                postResult("Gagal: ${msg.lineSequence().first()}")
            } finally {
                releaseLocks()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        job?.cancel()
        scope.cancel()
        releaseLocks()
        super.onDestroy()
    }

    private fun publishProgress(appId: Int, name: String, percent: Float, text: String) {
        _status.value = DownloadStatus.Running(appId, name, percent, text)

        // Notifikasi diperbarui maksimal sekali per detik supaya tidak membebani sistem
        val now = System.currentTimeMillis()
        if (now - lastNotifyMs < NOTIFY_INTERVAL_MS) return
        lastNotifyMs = now
        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_ID, buildNotification(name, text, percent))
    }

    private fun acquireLocks() {
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SteamGrab:download")
            .apply { acquire() }
        wifiLock = getSystemService(WifiManager::class.java)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "SteamGrab:wifi")
            .apply { acquire() }
    }

    private fun releaseLocks() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wifiLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        wifiLock = null
    }

    private fun startForegroundCompat(n: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun buildNotification(title: String, text: String, percent: Float): Notification {
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, DownloadService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(text)
            .setProgress(100, (percent * 100).toInt().coerceIn(0, 100), false)
            .setOngoing(true)
            .addAction(0, "Batal", stopIntent)
            .build()
    }

    private fun postResult(text: String) {
        val n = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("SteamGrab")
            .setContentText(text)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(NOTIF_DONE_ID, n)
    }

    companion object {
        const val ACTION_STOP = "com.example.steamgrab.STOP"
        const val EXTRA_APP_ID = "app_id"
        const val EXTRA_NAME = "name"

        private const val CHANNEL_ID = "download"
        private const val NOTIF_ID = 1
        private const val NOTIF_DONE_ID = 2
        private const val NOTIFY_INTERVAL_MS = 1000L

        private val _status = MutableStateFlow<DownloadStatus>(DownloadStatus.Idle)
        val status: StateFlow<DownloadStatus> = _status.asStateFlow()
    }
}
