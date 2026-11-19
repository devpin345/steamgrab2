package com.example.steamgrab

import android.os.Build
import android.os.Environment
import android.os.StatFs
import java.io.File
import kotlin.random.Random

/**
 * Hal yang berlaku untuk semua library: nilai StateFlags appmanifest, izin akses file, dan gabungan
 * status game dari semua library yang sedang tersambung. Isi satu library ada di [Library].
 */
object Storage {

    const val STATE_INSTALLED = 4
    const val STATE_UPDATE_REQUIRED = 2
    const val STATE_UPDATE_STARTED = 1024

    /** Download belum selesai: update diperlukan dan update sudah dimulai (kombinasi yang dipakai Steam untuk download yang dijeda). */
    const val STATE_PARTIAL = STATE_UPDATE_REQUIRED or STATE_UPDATE_STARTED

    /** Terinstal penuh: ada bit "installed" dan tidak ada bit update yang tersisa. */
    fun isComplete(flags: Int): Boolean =
        (flags and STATE_INSTALLED) != 0 &&
            (flags and (STATE_UPDATE_REQUIRED or STATE_UPDATE_STARTED)) == 0

    fun hasAllFilesAccess(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()

    /**
     * StateFlags tiap game dari semua library yang tersambung. Kuncinya app ID. Kalau satu game ada di dua library,
     * yang sudah selesai yang dipakai.
     */
    fun appStates(): Map<Int, Int> {
        val merged = HashMap<Int, Int>()
        for (ref in Libraries.available()) {
            for ((appId, flags) in ref.library.appStates()) {
                val old = merged[appId]
                if (old == null || (!isComplete(old) && isComplete(flags))) merged[appId] = flags
            }
        }
        return merged
    }
}

/** Satu game yang tercatat di appmanifest sebuah library. */
data class LibraryGame(
    val appId: Int,
    val name: String,
    val installDir: String,
    val flags: Int,
    val sizeOnDisk: Long,
) {
    val complete: Boolean get() = Storage.isComplete(flags)
}

/**
 * Satu library, meniru library Steam di PC. [root] adalah foldernya, isinya:
 *   <root>/libraryfolder.vdf
 *   <root>/steamapps/common/<installdir>/...
 *   <root>/steamapps/appmanifest_<appid>.acf
 *
 * appmanifest menyimpan keadaan tiap game lewat StateFlags: 4 berarti sudah terinstal penuh,
 * 1026 berarti download belum selesai. Tanpa file ini Steam menganggap game belum terinstal
 * dan mengunduh ulang seluruhnya.
 *
 * Di PC, tambahkan folder library ini di Steam (Settings > Storage), atau salin isi folder "steamapps" ke
 * steamapps Steam yang sudah ada. Gabungkan, jangan menimpa seluruh folder.
 */
class Library(val root: File) {

    fun steamLibraryDir(): File = File(root, "steamapps")

    fun commonDir(): File = File(steamLibraryDir(), "common")

    fun gameDir(installDir: String): File = File(commonDir(), installDir)

    fun manifestFile(appId: Int): File = File(steamLibraryDir(), "appmanifest_$appId.acf")

    fun libraryFolderFile(): File = File(root, "libraryfolder.vdf")

    /** Membuat folder library dan libraryfolder.vdf kalau belum ada, seperti library Steam asli. */
    fun ensureLibrary() {
        commonDir().mkdirs()
        val file = libraryFolderFile()
        if (!file.exists()) {
            // contentid hanya dibuat sekali, supaya tetap sama di pemakaian berikutnya
            file.writeText(
                "\"libraryfolder\"\n{\n" +
                    "\t\"contentid\"\t\t\"${Random.nextLong(1L, Long.MAX_VALUE)}\"\n" +
                    "\t\"label\"\t\t\"\"\n" +
                    "}\n"
            )
        }
    }

    private fun manifestFiles(): List<Pair<Int, File>> {
        val files = steamLibraryDir().listFiles() ?: return emptyList()
        return files.mapNotNull { file ->
            val name = file.name
            if (!name.startsWith("appmanifest_") || !name.endsWith(".acf")) return@mapNotNull null
            val appId = name.removePrefix("appmanifest_").removeSuffix(".acf").toIntOrNull() ?: return@mapNotNull null
            appId to file
        }
    }

    /** StateFlags dari tiap appmanifest di library ini. Kuncinya app ID. */
    fun appStates(): Map<Int, Int> {
        val result = HashMap<Int, Int>()
        for ((appId, file) in manifestFiles()) {
            val flags = runCatching {
                stateFlagsRegex.find(file.readText())?.groupValues?.get(1)?.toIntOrNull()
            }.getOrNull()
            // Manifest yang StateFlags-nya tidak terbaca dianggap belum selesai, bukan sudah terinstal
            result[appId] = flags ?: Storage.STATE_PARTIAL
        }
        return result
    }

    /** Daftar game di library ini, diurutkan menurut nama. Dibaca dari appmanifest, jadi cepat. */
    fun games(): List<LibraryGame> =
        manifestFiles().mapNotNull { (appId, file) ->
            val text = runCatching { file.readText() }.getOrNull() ?: return@mapNotNull null
            LibraryGame(
                appId = appId,
                name = vdfValue(text, "name") ?: "App $appId",
                installDir = vdfValue(text, "installdir") ?: "",
                flags = stateFlagsRegex.find(text)?.groupValues?.get(1)?.toIntOrNull() ?: Storage.STATE_PARTIAL,
                sizeOnDisk = vdfValue(text, "SizeOnDisk")?.toLongOrNull() ?: 0L,
            )
        }.sortedBy { it.name.lowercase() }

    /**
     * Byte (terkompresi) yang sudah diunduh pada sesi sebelumnya, menurut appmanifest. Game yang sudah selesai
     * dihitung penuh ([totalDownload]). Hasilnya 0 kalau belum ada appmanifest atau tidak terbaca.
     * Ini dipakai untuk melanjutkan progres dan menghitung sisa ruang yang dibutuhkan.
     */
    fun downloadedBefore(appId: Int, totalDownload: Long): Long {
        val file = manifestFile(appId)
        val text = runCatching { if (file.exists()) file.readText() else null }.getOrNull() ?: return 0L
        val flags = stateFlagsRegex.find(text)?.groupValues?.get(1)?.toIntOrNull() ?: return 0L
        if (Storage.isComplete(flags)) return totalDownload
        val saved = bytesDownloadedRegex.find(text)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        return if (totalDownload > 0L) saved.coerceIn(0L, totalDownload) else saved.coerceAtLeast(0L)
    }

    /** Ruang kosong di penyimpanan tempat library ini berada. */
    fun freeBytes(): Long {
        var dir: File? = root
        while (dir != null && !dir.exists()) dir = dir.parentFile
        val path = (dir ?: Environment.getExternalStorageDirectory()).path
        return StatFs(path).availableBytes
    }

    /** Ukuran file yang sudah ada di folder game. */
    fun existingBytes(installDir: String): Long =
        gameDir(installDir).walkTopDown().filter { it.isFile }.sumOf { it.length() }

    private companion object {
        val stateFlagsRegex = Regex("\"StateFlags\"\\s+\"(\\d+)\"")
        val bytesDownloadedRegex = Regex("\"BytesDownloaded\"\\s+\"(\\d+)\"")

        /** Nilai satu kunci tingkat atas di appmanifest, dengan escape VDF dikembalikan. */
        fun vdfValue(text: String, key: String): String? {
            val match = Regex("\"" + Regex.escape(key) + "\"\\s+\"((?:[^\"\\\\]|\\\\.)*)\"").find(text) ?: return null
            return match.groupValues[1].replace("\\\"", "\"").replace("\\\\", "\\")
        }
    }
}
