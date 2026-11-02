package com.example.steamgrab

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Satu baris catatan kegiatan, lengkap dengan jam kejadiannya. */
data class ActivityEntry(val timeMs: Long, val text: String)

/** Kegiatan yang sedang berjalan. [sinceMs] adalah kapan langkah ini dimulai, untuk menampilkan sudah berapa lama. */
data class ActivityNow(val text: String = "", val sinceMs: Long = 0L)

/** Hitungan kegiatan library. Beberapa tahap berjalan bersamaan, jadi angka-angka ini naik bersamaan juga. */
data class ActivityStats(
    val filesAllocated: Int = 0,
    val filesChecked: Int = 0,
    val filesWritten: Int = 0,
    val depotsDone: Int = 0,
    val depotsTotal: Int = 0,
)

/**
 * Catatan apa yang sedang dikerjakan aplikasi selama download, untuk panel detail di layar download.
 * Diisi dari ViewModel (cek ruang), SteamRepository (folder, manifest, alokasi, pemeriksaan, penulisan, penyelesaian),
 * dan dibaca oleh UI. Hanya menampung yang benar-benar dilaporkan library: pendekompresan tidak punya
 * laporan sendiri, jadi disebut sebagai bagian dari "mengunduh dan menulis".
 */
object DownloadActivity {
    private const val MAX_ENTRIES = 300

    private val _now = MutableStateFlow(ActivityNow())
    private val _entries = MutableStateFlow<List<ActivityEntry>>(emptyList())
    private val _stats = MutableStateFlow(ActivityStats())

    val now: StateFlow<ActivityNow> = _now.asStateFlow()
    val entries: StateFlow<List<ActivityEntry>> = _entries.asStateFlow()
    val stats: StateFlow<ActivityStats> = _stats.asStateFlow()

    /** Mengosongkan catatan untuk download baru. */
    fun reset() {
        _now.value = ActivityNow()
        _entries.value = emptyList()
        _stats.value = ActivityStats()
    }

    /**
     * Langkah baru: masuk ke catatan, jadi kegiatan saat ini, dan dicatat juga di log debug.
     * Dipakai untuk kejadian yang jumlahnya sedikit, bukan untuk tiap file.
     */
    fun step(text: String) {
        val t = System.currentTimeMillis()
        _now.value = ActivityNow(text, t)
        _entries.update { (it + ActivityEntry(t, text)).takeLast(MAX_ENTRIES) }
        DebugLog.log("Aktivitas: $text")
    }

    /** Mengganti teks kegiatan saat ini tanpa masuk catatan (misalnya nama file yang sedang diperiksa). */
    fun detail(text: String) {
        _now.update {
            if (it.text == text) it else ActivityNow(text, if (it.sinceMs == 0L) System.currentTimeMillis() else it.sinceMs)
        }
    }

    fun setDepotsTotal(total: Int) {
        _stats.update { it.copy(depotsTotal = total) }
    }

    fun fileAllocated() {
        _stats.update { it.copy(filesAllocated = it.filesAllocated + 1) }
    }

    fun fileChecked() {
        _stats.update { it.copy(filesChecked = it.filesChecked + 1) }
    }

    fun fileWritten() {
        _stats.update { it.copy(filesWritten = it.filesWritten + 1) }
    }

    fun depotDone() {
        _stats.update { it.copy(depotsDone = it.depotsDone + 1) }
    }
}

/** Nama file tanpa folder induknya yang panjang, supaya muat di satu baris. */
fun shortFileName(path: String): String {
    val clean = path.replace('\\', '/').trimEnd('/')
    val parts = clean.split('/')
    return if (parts.size <= 2) clean else ".../" + parts.takeLast(2).joinToString("/")
}

/** Contoh: 75 -> "1m 15d", 8 -> "8d". */
fun formatElapsed(seconds: Long): String {
    val s = seconds.coerceAtLeast(0L)
    return if (s < 60L) "${s}d" else "${s / 60L}m ${s % 60L}d"
}
