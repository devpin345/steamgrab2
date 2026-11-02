package com.example.steamgrab

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
