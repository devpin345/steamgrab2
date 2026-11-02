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
    data class Paused(val appId: Int, val name: String, val progress: DownloadProgress) : DownloadStatus()
    data class Finished(val appId: Int, val path: String) : DownloadStatus()
    data class Failed(val appId: Int, val message: String) : DownloadStatus()
}

/**
 * Menjalankan download di foreground service supaya tetap jalan saat layar mati atau app di background.
 * Hanya satu download dalam satu waktu. Setiap detik status dan notifikasi diperbarui.
 *
 * Jeda dilakukan dengan membatalkan download. Byte yang sudah tersimpan tetap di disk,
 * dan saat dilanjutkan library memeriksa file yang ada lalu hanya mengunduh bagian yang belum ada.
 */
class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var job: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    // Nomor setiap download dimulai. Sisa proses download lama tidak boleh mengganggu download baru.
    @Volatile private var generation = 0
    @Volatile private var finished = false
    @Volatile private var pauseRequested = false

    private var currentAppId = -1
    private var currentName = ""
    @Volatile private var latest = DownloadProgress()
    @Volatile private var largeIcon: Bitmap? = null
    private val meter = SpeedMeter()

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Download game", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_STOP || action == ACTION_PAUSE) {
            if (job?.isActive == true) {
                pauseRequested = action == ACTION_PAUSE
                job?.cancel()
            } else {
                stopSelf()
            }
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

        val gen = ++generation
        acquireLocks()
        finished = false
        pauseRequested = false
        currentAppId = appId
        currentName = name
        latest = DownloadProgress()
        largeIcon = null
        meter.start(0L, System.currentTimeMillis())
        publish(gen)

        job = scope.launch {
            // Setiap detik: catat kecepatan lalu perbarui status dan notifikasi
            val ticker = launch {
                while (isActive) {
                    delay(TICK_MS)
                    // runBytes, bukan doneBytes: doneBytes ikut menghitung sesi sebelumnya dan bisa melompat
                    meter.sample(latest.runBytes, System.currentTimeMillis())
                    publish(gen)
                }
            }
            try {
                // Logo notifikasi dimuat sambil jalan, supaya jaringan yang lambat tidak menunda mulainya download.
                // Dijalankan di scope service, bukan anak job ini, supaya job download tidak ikut menunggunya.
                scope.launch { largeIcon = GameImages.load(appId) }
                val dir =SteamRepository.get(applicationContext).downloadGame(appId) { p -> latest = p }
                finished = true
                _status.value = DownloadStatus.Finished(appId, dir.absolutePath)
                postResult("Selesai: $name")
            } catch (e: CancellationException) {
                finished = true
                if (pauseRequested) {
                    DebugLog.log("Download dijeda")
                    _status.value = DownloadStatus.Paused(appId, name, latest)
                    postResult("Dijeda: $name. Buka aplikasi untuk melanjutkan.")
                } else {
                    DebugLog.log("Download dibatalkan")
                    _status.value = DownloadStatus.Idle
                }
                throw e
            } catch (e: Throwable) {
                // Throwable, bukan Exception: kelas yang hilang (NoClassDefFoundError) adalah Error.
                // Kalau hanya Exception yang ditangkap, aplikasi langsung berhenti tanpa pesan.
                finished = true
                val msg = e.describe()
                DebugLog.log("Download gagal: $msg")
                _status.value = DownloadStatus.Failed(appId, msg)
                postResult("Gagal: ${msg.lineSequence().first()}")
            } finally {
                ticker.cancel()
                // Kalau pengguna sudah memulai download lain, service itu tidak boleh dimatikan di sini
                if (gen == generation) {
                    releaseLocks()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
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

    /** Mengirim keadaan terbaru ke UI dan notifikasi. Dipanggil saat mulai dan tiap detik oleh ticker. */
    private fun publish(gen: Int) {
        if (gen != generation || finished) return
        val p = latest
        val speed = meter.speedBps
        _status.value = DownloadStatus.Running(currentAppId, currentName, p, speed, meter.history())
        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_ID, buildNotification(currentName, p, speed))
    }

    private fun acquireLocks() {
        releaseLocks()
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
     * Sebelum byte game masuk, teks menampilkan tahap yang sedang dikerjakan.
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
            .setContentTitle("$title · $pct%")
            .setContentText(
                when {
                    p.finishing -> "Menyelesaikan: merapikan folder dan menyimpan catatan..."
                    known -> "${formatBytes(p.doneBytes)} / ${formatBytes(p.totalBytes)} · ${formatSpeed(speedBps)}"
                    else -> p.phase.ifBlank { "Menyiapkan download..." }
                }
            )
            .setContentIntent(openIntent)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .addAction(0, "Jeda", pauseIntent())
            .addAction(0, "Batal", stopIntent)

        if (known) builder.setProgress(100, pct, false) else builder.setProgress(0, 0, true)
        largeIcon?.let { builder.setLargeIcon(it) }

        return builder.build()
    }

    private fun pauseIntent(): PendingIntent = PendingIntent.getService(
        this, 1,
        Intent(this, DownloadService::class.java).setAction(ACTION_PAUSE),
        PendingIntent.FLAG_IMMUTABLE
    )

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
        const val ACTION_PAUSE = "com.example.steamgrab.PAUSE"
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
