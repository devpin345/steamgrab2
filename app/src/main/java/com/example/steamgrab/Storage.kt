package com.example.steamgrab

import android.os.Build
import android.os.Environment
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
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOAD),
        "SteamGames/steamapps"
    )

    fun commonDir(): File = File(steamLibraryDir(), "common")

    fun gameDir(installDir: String): File = File(commonDir(), installDir)

    fun manifestFile(appId: Int): File = File(steamLibraryDir(), "appmanifest_$appId.acf")

    fun hasAllFilesAccess(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()
}
