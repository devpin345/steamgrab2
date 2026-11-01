package com.example.steamgrab

import android.os.Build
import android.os.Environment
import android.os.StatFs
import java.io.File

/**
 * Struktur folder meniru library Steam di PC:
 *   Download/SteamGames/steamapps/common/<installdir>/...
 *   Download/SteamGames/steamapps/appmanifest_<appid>.acf
 *
 * Di PC, salin isi folder "steamapps" ini ke folder steamapps Steam.
 * Gabungkan dengan yang sudah ada, jangan menimpa seluruh folder steamapps.
 */
object Storage {

    fun steamLibraryDir(): File = File(
        Environment.getExternalStoragePublicDirectory("Download"),
        "SteamGames/steamapps"
    )

    fun commonDir(): File = File(steamLibraryDir(), "common")

    fun gameDir(installDir: String): File = File(commonDir(), installDir)

    fun manifestFile(appId: Int): File = File(steamLibraryDir(), "appmanifest_$appId.acf")

    fun hasAllFilesAccess(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()

    /** Ruang kosong di penyimpanan tempat download disimpan. */
    fun freeBytes(): Long {
        var dir: File? = steamLibraryDir()
        while (dir != null && !dir.exists()) dir = dir.parentFile
        val path = (dir ?: Environment.getExternalStorageDirectory()).path
        return StatFs(path).availableBytes
    }

    /** Ukuran file yang sudah ada di folder game, dipakai untuk menghitung sisa yang perlu diunduh. */
    fun existingBytes(installDir: String): Long =
        gameDir(installDir).walkTopDown().filter { it.isFile }.sumOf { it.length() }
}
