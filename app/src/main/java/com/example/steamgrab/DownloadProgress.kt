package com.example.steamgrab

import android.net.TrafficStats
import android.os.Process
import java.io.File
import java.util.Locale

/** Jumlah sampel kecepatan yang disimpan untuk grafik (satu sampel per detik). */
const val SPEED_HISTORY_SIZE = 60

/**
 * Progres download. Byte dihitung dari yang lewat jaringan (terkompresi).
 * [doneBytes] sudah termasuk yang diunduh pada sesi sebelumnya (setelah dijeda atau dibatalkan),
 * jadi tidak kembali ke nol saat dilanjutkan. [totalBytes] bernilai 0 kalau total belum diketahui.
 */
data class DownloadProgress(
    val doneBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val percent: Float = 0f,
    // Tahap yang sedang dikerjakan library sebelum byte game mulai masuk (manifest, alokasi, validasi)
    val phase: String = "",
    // Byte yang benar-benar lewat jaringan sejak sesi ini dimulai. Dipakai untuk menghitung kecepatan,
    // karena doneBytes bisa melompat saat sesi sebelumnya dihitung masuk.
    val runBytes: Long = 0L,
    // Semua depot sudah melapor selesai, tinggal pekerjaan penutup library (menghapus file usang, menyimpan catatan)
    val finishing: Boolean = false,
    // Byte data game setelah dekompresi yang sudah selesai ditulis library pada sesi ini. Cadangan untuk mengukur
    // kecepatan tulis kalau /proc/self/io tidak bisa dibaca.
    val writtenBytes: Long = 0L,
)

/** Menghitung kecepatan per detik dari selisih byte, dan menyimpan riwayatnya untuk grafik. */
class SpeedMeter {
    private var lastBytes = 0L
    private var lastMs = 0L
    private val samples = ArrayDeque<Long>()

    var speedBps: Long = 0L
        private set

    fun start(bytes: Long, nowMs: Long) {
        lastBytes = bytes
        lastMs = nowMs
        speedBps = 0L
        samples.clear()
    }

    fun sample(bytes: Long, nowMs: Long) {
        val elapsed = nowMs - lastMs
        if (elapsed <= 0L) return
        speedBps = (bytes - lastBytes).coerceAtLeast(0L) * 1000L / elapsed
        samples.addLast(speedBps)
        while (samples.size > SPEED_HISTORY_SIZE) samples.removeFirst()
        lastBytes = bytes
        lastMs = nowMs
    }

    fun history(): List<Long> = samples.toList()
}

/**
 * Kecepatan jaringan per detik untuk candle. Sumber utama: byte masuk milik aplikasi ini dari TrafficStats, yang
 * dihitung sistem Android per paket, jadi alirannya halus dan setara dengan yang ditunjukkan aplikasi monitor jaringan.
 *
 * Hitungan library sengaja bukan sumber utama: library baru menghitung sebuah chunk setelah chunk itu selesai
 * diunduh, didekompresi, dan ditulis. Beberapa chunk yang diunduh paralel selesai hampir bersamaan, sehingga
 * hasilnya lonjakan tiap beberapa detik dengan nol di antaranya, padahal jaringan terus mengalir. Hitungan library
 * dipakai hanya kalau TrafficStats tidak didukung atau tidak bergerak.
 */
class NetworkMeter {
    private val meter = SpeedMeter()
    private var useTraffic = false
    private var lastRx = 0L
    private var lastLibrary = 0L
    private var stuckTicks = 0

    val speedBps: Long
        get() = meter.speedBps

    /** Sumber yang dipakai, untuk dicatat di log. */
    val sourceName: String
        get() = if (useTraffic) "TrafficStats (byte masuk aplikasi)" else "hitungan library"

    fun start(nowMs: Long) {
        val rx = readRx()
        useTraffic = rx >= 0L
        lastRx = rx
        lastLibrary = 0L
        stuckTicks = 0
        meter.start(if (useTraffic) rx else 0L, nowMs)
    }

    fun sample(libraryBytes: Long, nowMs: Long) {
        if (useTraffic) {
            val rx = readRx()
            if (rx >= 0L) {
                // Angka sistem tidak bergerak padahal library melaporkan data masuk: perangkat ini tidak
                // menyediakannya dengan benar, jadi beralih ke hitungan library.
                if (rx == lastRx && libraryBytes > lastLibrary) stuckTicks++ else if (rx != lastRx) stuckTicks = 0
                lastRx = rx
            }
            lastLibrary = libraryBytes
            if (stuckTicks >= STUCK_LIMIT) {
                useTraffic = false
                meter.start(libraryBytes, nowMs)
                return
            }
            meter.sample(lastRx, nowMs)
        } else {
            meter.sample(libraryBytes, nowMs)
        }
    }

    fun history(): List<Long> = meter.history()

    // -1 (UNSUPPORTED) kalau perangkat tidak menyediakan statistik per aplikasi
    private fun readRx(): Long = TrafficStats.getUidRxBytes(Process.myUid())

    private companion object {
        const val STUCK_LIMIT = 8
    }
}

/**
 * Kecepatan tulis ke penyimpanan per detik, beserta riwayatnya untuk garis di grafik.
 * Sumber utama adalah wchar di /proc/self/io: jumlah byte yang diserahkan aplikasi ke sistem lewat write(),
 * termasuk semua penulisan file game. Kalau file itu tidak bisa dibaca di perangkat ini, dipakai hitungan library
 * (byte data game setelah dekompresi yang sudah selesai ditulis), yang tidak menghitung bagian terakhir tiap file.
 */
class DiskMeter {
    private var useProc = false
    private var lastBytes = 0L
    private var lastMs = 0L
    private val samples = ArrayDeque<Long>()

    // Kecepatan yang ditampilkan: rata-rata beberapa detik terakhir. Library baru menulis sebuah chunk setelah seluruhnya
    // terunduh, dan beberapa chunk selesai bersamaan, jadi tulisan per detik berupa lonjakan dengan nol di antaranya.
    var speedBps: Long = 0L
        private set

    /** Sumber yang dipakai, untuk dicatat di log. */
    val sourceName: String
        get() = if (useProc) "/proc/self/io" else "hitungan library"

    fun start(libraryBytes: Long, nowMs: Long) {
        val proc = readWriteChars()
        useProc = proc != null
        lastBytes = proc ?: libraryBytes
        lastMs = nowMs
        speedBps = 0L
        samples.clear()
    }

    fun sample(libraryBytes: Long, nowMs: Long) {
        val elapsed = nowMs - lastMs
        if (elapsed <= 0L) return
        val current = if (useProc) readWriteChars() ?: lastBytes else libraryBytes
        samples.addLast((current - lastBytes).coerceAtLeast(0L) * 1000L / elapsed)
        while (samples.size > SPEED_HISTORY_SIZE) samples.removeFirst()
        speedBps = samples.toList().takeLast(SMOOTH_SECONDS).average().toLong()
        lastBytes = current
        lastMs = nowMs
    }

    /** Riwayat untuk garis: tiap titik adalah rata-rata [SMOOTH_SECONDS] detik sampai detik itu. */
    fun history(): List<Long> {
        val raw = samples.toList()
        return raw.indices.map { i ->
            raw.subList(maxOf(0, i - SMOOTH_SECONDS + 1), i + 1).average().toLong()
        }
    }

    private companion object {
        const val SMOOTH_SECONDS = 5
    }

    private fun readWriteChars(): Long? = try {
        File("/proc/self/io").useLines { lines ->
            lines.firstOrNull { it.startsWith("wchar:") }?.substringAfter(':')?.trim()?.toLongOrNull()
        }
    } catch (e: Exception) {
        null
    }
}

private val LOCALE_ID: Locale = Locale.forLanguageTag("id-ID")

/** Contoh: 1536 -> "1,5 KB", 3650722201 -> "3,4 GB". */
fun formatBytes(bytes: Long): String {
    if (bytes < 1024L) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes / 1024.0
    var i = 0
    while (value >= 1024.0 && i < units.lastIndex) {
        value /= 1024.0
        i++
    }
    return String.format(LOCALE_ID, "%.1f %s", value, units[i])
}

fun formatSpeed(bytesPerSecond: Long): String = formatBytes(bytesPerSecond) + "/dtk"

/** Contoh: 8 -> "8 dtk", 75 -> "1 mnt 15 dtk", 3725 -> "1 jam 2 mnt". */
fun formatDuration(seconds: Long): String {
    val s = seconds.coerceAtLeast(0L)
    return when {
        s < 60L -> "$s dtk"
        s < 3600L -> "${s / 60L} mnt ${s % 60L} dtk"
        else -> "${s / 3600L} jam ${(s % 3600L) / 60L} mnt"
    }
}
