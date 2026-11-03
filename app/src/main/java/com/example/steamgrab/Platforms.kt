package com.example.steamgrab

import android.content.Context
import android.content.SharedPreferences

/**
 * Versi game yang bisa diunduh. [steamName] adalah nama yang dipakai Steam di daftar OS tiap depot
 * (config/oslist), dan sama dengan yang dicocokkan library saat memilih depot.
 */
enum class Platform(val steamName: String, val label: String) {
    WINDOWS("windows", "Windows"),
    MACOS("macos", "macOS"),
    LINUX("linux", "Linux");

    companion object {
        fun fromSteamName(name: String?): Platform = values().firstOrNull { it.steamName == name } ?: WINDOWS
    }
}

/** Satu pilihan di popup pilih versi. [size] null kalau info app tidak memuat ukuran depotnya. */
data class PlatformOption(val platform: Platform, val size: GameSize?)

/** Permintaan memilih versi untuk satu game, ditampilkan sebagai popup. */
data class PlatformChoice(val appId: Int, val gameName: String, val options: List<PlatformOption>)

/**
 * Versi yang terakhir dipilih per game. Dipakai saat download dilanjutkan dari jeda, supaya tidak berpindah versi
 * diam-diam. appmanifest buatan Steam tidak mencatat OS, jadi catatan ini disimpan di sini.
 */
object PlatformStore {
    private var prefs: SharedPreferences? = null

    @Synchronized
    fun init(context: Context) {
        if (prefs == null) prefs = context.getSharedPreferences("platform_choice", Context.MODE_PRIVATE)
    }

    fun get(appId: Int): Platform? =
        prefs?.getString(appId.toString(), null)?.let { name -> Platform.values().firstOrNull { it.steamName == name } }

    fun put(appId: Int, platform: Platform) {
        prefs?.edit()?.putString(appId.toString(), platform.steamName)?.apply()
    }
}
