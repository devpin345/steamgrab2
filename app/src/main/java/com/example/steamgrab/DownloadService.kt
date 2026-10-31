package com.example.steamgrab

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

sealed class DownloadStatus {
    object Idle : DownloadStatus()
    data class Running(
        val appId: Int,
        val name: String,
        val progress: DownloadProgress,
        val speedBps: Long,
        val speedHistory: List<Long>,
    ) : DownloadStatus()
    data class Finished(val appId: Int, val path: String) : DownloadStatus()
    data class Failed(val appId: Int, val message: String) : DownloadStatus()
}

/**
 * Menjalankan download di foreground service supaya tetap jalan saat layar mati atau app di background.
 * Hanya satu download dalam satu waktu. Setiap detik status dan notifikasi diperbarui
 * (logo, persen, ukuran, dan kecepatan).
 */
class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private var currentAppId = -1
    private var currentName = ""
    @Volatile private var latest = DownloadProgress()
    @Volatile private var largeIcon: Bitmap? = null
    @Volatile private var publishing = false
    private val meter = SpeedMeter()

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

        // Harus dipanggil segera setelah startForegroundService, termasuk saat permintaan ini diabaikan.
        // Notifikasi download yang sedang berjalan dipertahankan, tidak diganti.
        if (job?.isActive == true) {
            startForegroundCompat(buildNotification(currentName, latest, meter.speedBps))
            return START_NOT_STICKY
        }

        val appId = intent?.getIntExtra(EXTRA_APP_ID, -1) ?: -1
        val name = intent?.getStringExtra(EXTRA_NAME) ?: "App $appId"
        startForegroundCompat(buildNotification(name, DownloadProgress(), 0L))

        if (appId <= 0) {
            stopSelf()
            return START_NOT_STICKY
        }

        acquireLocks()
        currentAppId = appId
        currentName = name
        latest = DownloadProgress()
        largeIcon = null
        meter.start(0L, System.currentTimeMillis())
        publishing = true
        publish()

        job = scope.launch {
            // Setiap detik: catat kecepatan lalu perbarui status dan notifikasi
            val ticker = launch {
                while (isActive) {
                    delay(TICK_MS)
                    meter.sample(latest.doneBytes, System.currentTimeMillis())
                    publish()
                }
            }
            try {
                largeIcon = GameImages.load(appId)
                val dir = SteamRepository.get(applicationContext).downloadGame(appId) { p -> latest = p }
                publishing = false
                _status.value = DownloadStatus.Finished(appId, dir.absolutePath)
                postResult("Selesai: $name")
            } catch (e: CancellationException) {
                publishing = false
                _status.value = DownloadStatus.Idle
                throw e
            } catch (e: Exception) {
                publishing = false
                val msg = e.describe()
                _status.value = DownloadStatus.Failed(appId, msg)
                postResult("Gagal: ${msg.lineSequence().first()}")
            } finally {
                publishing = false
                ticker.cancel()
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

    /** Mengirim keadaan terbaru ke UI dan notifikasi. Dipanggil tiap detik oleh ticker. */
    private fun publish() {
        if (!publishing) return
        val p = latest
        val speed = meter.speedBps
        _status.value = DownloadStatus.Running(currentAppId, currentName, p, speed, meter.history())
        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_ID, buildNotification(currentName, p, speed))
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

    /**
     * Notifikasi berisi logo game, judul dengan persen, bar progres, ukuran, dan kecepatan.
     * Ketuk notifikasi untuk membuka aplikasi.
     */
    private fun buildNotification(title: String, p: DownloadProgress, speedBps: Long): Notification {
        val pct = (p.percent * 100).toInt().coerceIn(0, 100)
        val known = p.totalBytes > 0L

        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, DownloadService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(if (known) "$title · $pct%" else title)
            .setContentText(
                if (known) "${formatBytes(p.doneBytes)} / ${formatBytes(p.totalBytes)} · ${formatSpeed(speedBps)}"
                else "Menyiapkan download..."
            )
            .setContentIntent(openIntent)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .addAction(0, "Batal", stopIntent)

        if (known) builder.setProgress(100, pct, false) else builder.setProgress(0, 0, true)
        largeIcon?.let { builder.setLargeIcon(it) }

        return builder.build()
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
        private const val TICK_MS = 1000L

        private val _status = MutableStateFlow<DownloadStatus>(DownloadStatus.Idle)
        val status: StateFlow<DownloadStatus> = _status.asStateFlow()
    }
}
