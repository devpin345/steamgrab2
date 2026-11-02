package com.example.steamgrab

import android.os.Build
import android.os.Environment
import android.os.StatFs
import java.io.File
import kotlin.random.Random

/**
 * Struktur folder meniru library Steam di PC:
 *   Download/SteamGames/libraryfolder.vdf
 *   Download/SteamGames/steamapps/common/<installdir>/...
 *   Download/SteamGames/steamapps/appmanifest_<appid>.acf
 *
 * appmanifest menyimpan keadaan tiap game lewat StateFlags: 4 berarti sudah terinstal penuh,
 * 1026 berarti download belum selesai. Tanpa file ini Steam menganggap game belum terinstal
 * dan mengunduh ulang seluruhnya.
 *
 * Di PC, tambahkan folder "SteamGames" sebagai library Steam (Settings > Storage), atau salin
 * isi folder "steamapps" ke steamapps Steam yang sudah ada. Gabungkan, jangan menimpa seluruh folder.
 */
object Storage {

    const val STATE_INSTALLED = 4
    const val STATE_UPDATE_REQUIRED = 2
    const val STATE_UPDATE_STARTED = 1024

    /** Download belum selesai: update diperlukan dan update sudah dimulai (kombinasi yang dipakai Steam untuk download yang dijeda). */
    const val STATE_PARTIAL = STATE_UPDATE_REQUIRED or STATE_UPDATE_STARTED

    fun libraryRootDir(): File = File(
        Environment.getExternalStoragePublicDirectory("Download"),
        "SteamGames"
    )

    fun steamLibraryDir(): File = File(libraryRootDir(), "steamapps")

    fun commonDir(): File = File(steamLibraryDir(), "common")

    fun gameDir(installDir: String): File = File(commonDir(), installDir)

    fun manifestFile(appId: Int): File = File(steamLibraryDir(), "appmanifest_$appId.acf")

    fun libraryFolderFile(): File = File(libraryRootDir(), "libraryfolder.vdf")

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

    private val stateFlagsRegex = Regex("\"StateFlags\"\\s+\"(\\d+)\"")

    /** StateFlags dari tiap appmanifest di library. Kuncinya app ID. */
    fun appStates(): Map<Int, Int> {
        val files = steamLibraryDir().listFiles() ?: return emptyMap()
        val result = HashMap<Int, Int>()
        for (file in files) {
            val name = file.name
            if (!name.startsWith("appmanifest_") || !name.endsWith(".acf")) continue
            val appId = name.removePrefix("appmanifest_").removeSuffix(".acf").toIntOrNull() ?: continue
            val flags = runCatching {
                stateFlagsRegex.find(file.readText())?.groupValues?.get(1)?.toIntOrNull()
            }.getOrNull()
            // Manifest yang StateFlags-nya tidak terbaca dianggap belum selesai, bukan sudah terinstal
            result[appId] = flags ?: STATE_PARTIAL
        }
        return result
    }

    private val bytesDownloadedRegex = Regex("\"BytesDownloaded\"\\s+\"(\\d+)\"")

    /**
     * Byte (terkompresi) yang sudah diunduh pada sesi sebelumnya, menurut appmanifest. Game yang sudah selesai
     * dihitung penuh ([totalDownload]). Hasilnya 0 kalau belum ada appmanifest atau tidak terbaca.
     * Ini dipakai untuk melanjutkan progres dan menghitung sisa ruang yang dibutuhkan.
     */
    fun downloadedBefore(appId: Int, totalDownload: Long): Long {
        val file = manifestFile(appId)
        val text = runCatching { if (file.exists()) file.readText() else null }.getOrNull() ?: return 0L
        val flags = stateFlagsRegex.find(text)?.groupValues?.get(1)?.toIntOrNull() ?: return 0L
        if (isComplete(flags)) return totalDownload
        val saved = bytesDownloadedRegex.find(text)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        return if (totalDownload > 0L) saved.coerceIn(0L, totalDownload) else saved.coerceAtLeast(0L)
    }

    /** Terinstal penuh: ada bit "installed" dan tidak ada bit update yang tersisa. */
    fun isComplete(flags: Int): Boolean =
        (flags and STATE_INSTALLED) != 0 &&
            (flags and (STATE_UPDATE_REQUIRED or STATE_UPDATE_STARTED)) == 0

    fun hasAllFilesAccess(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()

    /** Ruang kosong di penyimpanan tempat download disimpan. */
    fun freeBytes(): Long {
        var dir: File? = steamLibraryDir()
        while (dir != null && !dir.exists()) dir = dir.parentFile
        val path = (dir ?: Environment.getExternalStorageDirectory()).path
        return StatFs(path).availableBytes
    }

    /** Ukuran file yang sudah ada di folder game. */
    fun existingBytes(installDir: String): Long =
        gameDir(installDir).walkTopDown().filter { it.isFile }.sumOf { it.length() }
}
