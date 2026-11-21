package com.example.steamgrab

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File

/** Satu penyimpanan yang sedang tersambung: memori internal ponsel, kartu SD, atau flashdisk USB. */
data class StorageVolumeInfo(
    val id: String,
    // Nama dari sistem untuk penyimpanan eksternal. Kosong untuk memori internal, yang diberi nama oleh layar.
    val label: String,
    val root: File,
    val primary: Boolean,
    val removable: Boolean,
) {
    val totalBytes: Long get() = runCatching { StatFs(root.path).totalBytes }.getOrDefault(0L)
    val freeBytes: Long get() = runCatching { StatFs(root.path).availableBytes }.getOrDefault(0L)
}

/** Library di sebuah penyimpanan yang sedang tersambung. */
data class LibraryRef(val volume: StorageVolumeInfo, val library: Library)

/**
 * Library tiap penyimpanan. Memori internal punya library bawaan Download/SteamGames, jadi game yang sudah diunduh
 * sebelumnya tetap terbaca. Penyimpanan eksternal baru dipakai setelah pengguna memilih foldernya.
 * Library hanya dihitung selama penyimpanannya tersambung.
 */
object Libraries {
    private const val PRIMARY_ID = "primary"
    private const val KEY_SETUP_DONE = "setup_done"
    private const val KEY_PRIMARY_DISK = "primary_disk"

    private var appContext: Context? = null
    private var prefs: SharedPreferences? = null

    private val _version = MutableStateFlow(0)

    /** Berubah setiap ada perubahan library atau penyimpanan yang tersambung, supaya layar menghitung ulang. */
    val version: StateFlow<Int> = _version.asStateFlow()

    @Synchronized
    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        prefs = context.applicationContext.getSharedPreferences("libraries", Context.MODE_PRIVATE)
    }

    fun refresh() {
        _version.update { it + 1 }
    }

    /** Layar pilih penyimpanan di awal sudah pernah ditampilkan. */
    var setupDone: Boolean
        get() = prefs?.getBoolean(KEY_SETUP_DONE, false) ?: false
        set(value) {
            prefs?.edit()?.putBoolean(KEY_SETUP_DONE, value)?.apply()
        }

    /**
     * Penyimpanan utama yang dipilih pengguna: tempat game baru diunduh tanpa bertanya lagi. Kosong sampai
     * pengguna memilih, dan dianggap memori internal.
     */
    var primaryId: String
        get() = prefs?.getString(KEY_PRIMARY_DISK, null) ?: PRIMARY_ID
        set(value) {
            prefs?.edit()?.putString(KEY_PRIMARY_DISK, value)?.apply()
            refresh()
        }

    /** Pengguna sudah pernah memilih penyimpanan utama (layar "Please select the primary disk"). */
    val primaryChosen: Boolean get() = prefs?.contains(KEY_PRIMARY_DISK) == true

    /** Library di penyimpanan utama pilihan pengguna, selama penyimpanannya tersambung. Null kalau tidak tersambung. */
    fun primaryRef(): LibraryRef? {
        val id = primaryId
        val volume = volumes().firstOrNull { it.id == id } ?: return null
        return libraryDir(volume)?.let { LibraryRef(volume, Library(it)) }
    }

    private fun defaultPrimaryLibrary(): File =
        File(Environment.getExternalStoragePublicDirectory("Download"), "SteamGames")

    /** Semua volume yang dilaporkan sistem beserta keadaannya, untuk log kalau penyimpanan tidak terdeteksi. */
    fun rawReport(): String {
        val ctx = appContext ?: return "belum siap"
        val manager = ctx.getSystemService(StorageManager::class.java) ?: return "StorageManager tidak ada"
        return manager.storageVolumes.joinToString("; ") { v ->
            "${v.uuid ?: "primary"} state=${v.state} dir=${directoryOf(v)?.path} " +
                "primary=${v.isPrimary} lepasan=${v.isRemovable}"
        }.ifEmpty { "sistem tidak melaporkan volume" }
    }

    /** Penyimpanan yang tersambung dan bisa dibaca, memori internal paling atas. */
    fun volumes(): List<StorageVolumeInfo> {
        val ctx = appContext
        val found = mutableListOf<StorageVolumeInfo>()
        if (ctx != null) {
            val manager = ctx.getSystemService(StorageManager::class.java)
            for (v in manager?.storageVolumes.orEmpty()) {
                if (v.state != Environment.MEDIA_MOUNTED) continue
                val dir = directoryOf(v) ?: continue
                val primary = v.isPrimary
                found += StorageVolumeInfo(
                    id = if (primary) PRIMARY_ID else (v.uuid ?: dir.path),
                    label = if (primary) "" else (v.getDescription(ctx) ?: dir.name),
                    root = dir,
                    primary = primary,
                    removable = v.isRemovable,
                )
            }
        }
        if (found.none { it.primary }) {
            found.add(0, StorageVolumeInfo(PRIMARY_ID, "", Environment.getExternalStorageDirectory(), true, false))
        }
        return found.sortedByDescending { it.primary }
    }

    // StorageVolume.getDirectory() baru ada di Android 11. Di versi lama alamatnya diambil lewat getPath() yang tersembunyi.
    private fun directoryOf(volume: StorageVolume): File? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) return volume.directory
        return try {
            (StorageVolume::class.java.getMethod("getPath").invoke(volume) as? String)?.let { File(it) }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Folder library untuk penyimpanan ini: yang dipilih pengguna kalau ada. Selain itu memori internal memakai
     * Download/SteamGames (supaya game lama tetap terbaca) dan penyimpanan eksternal memakai SteamGrab/SteamGames
     * di akarnya, yang dibuat otomatis saat dipakai.
     */
    fun libraryDir(volume: StorageVolumeInfo): File? {
        val saved = prefs?.getString("lib_${volume.id}", null)?.let { File(it) }
        return saved ?: if (volume.primary) defaultPrimaryLibrary() else File(File(volume.root, "SteamGrab"), "SteamGames")
    }

    fun setLibraryDir(volume: StorageVolumeInfo, dir: File) {
        prefs?.edit()?.putString("lib_${volume.id}", dir.path)?.apply()
        refresh()
    }

    /** Library di semua penyimpanan yang tersambung dan sudah punya folder library. */
    fun available(): List<LibraryRef> =
        volumes().mapNotNull { v -> libraryDir(v)?.let { LibraryRef(v, Library(it)) } }

    /** Library bawaan di memori internal. Dipakai kalau tidak ada library lain yang cocok. */
    fun defaultRef(): LibraryRef {
        val v = volumes().first { it.primary }
        return LibraryRef(v, Library(libraryDir(v) ?: defaultPrimaryLibrary()))
    }

    fun refFor(path: String?): LibraryRef? =
        if (path == null) null else available().firstOrNull { it.library.root.path == path }

    /** Library yang sudah memuat game ini (selesai atau dijeda). Null kalau game belum ada di library mana pun. */
    fun locate(appId: Int): LibraryRef? =
        available().firstOrNull { it.library.manifestFile(appId).exists() }

    /** Library tujuan yang dipilih untuk download game ini, dicatat supaya Lanjutkan setelah jeda memakai yang sama. */
    fun rememberTarget(appId: Int, library: Library) {
        prefs?.edit()?.putString("target_$appId", library.root.path)?.apply()
    }

    /** Library untuk game ini: tempat game sudah berada, lalu pilihan terakhir, lalu library pertama yang tersambung. */
    fun resolveFor(appId: Int): LibraryRef =
        locate(appId)
            ?: refFor(prefs?.getString("target_$appId", null))
            ?: available().firstOrNull()
            ?: defaultRef()

    /** Apakah folder ini bisa ditulisi aplikasi. Dicoba dengan membuat file kecil lalu menghapusnya. */
    fun canWrite(dir: File): Boolean = try {
        val probe = File(dir, ".steamgrab_write_test_${System.nanoTime()}")
        probe.writeText("x")
        probe.delete()
        true
    } catch (e: Exception) {
        false
    }
}
