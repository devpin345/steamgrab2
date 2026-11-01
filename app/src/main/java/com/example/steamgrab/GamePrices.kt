package com.example.steamgrab

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Menyimpan status gratis per game (true = gratis, false = berbayar).
 * Data PICS tidak memuat harga, jadi statusnya diambil dari Steam Store lalu disimpan ke file
 * supaya tidak diambil ulang setiap aplikasi dibuka.
 */
object GamePriceStore {
    private const val FILE_NAME = "game_free.txt"
    private var file: File? = null

    private val _prices = MutableStateFlow<Map<Int, Boolean>>(emptyMap())
    val prices: StateFlow<Map<Int, Boolean>> = _prices.asStateFlow()

    @Synchronized
    fun init(context: Context) {
        if (file != null) return
        val f = File(context.filesDir, FILE_NAME)
        file = f
        _prices.value = runCatching { f.readLines() }
            .getOrDefault(emptyList())
            .mapNotNull { line ->
                val parts = line.split('\t')
                if (parts.size != 2) return@mapNotNull null
                val appId = parts[0].toIntOrNull() ?: return@mapNotNull null
                appId to (parts[1] == "1")
            }
            .toMap()
    }

    fun get(appId: Int): Boolean? = _prices.value[appId]

    @Synchronized
    fun put(appId: Int, free: Boolean) {
        val next = _prices.value + (appId to free)
        _prices.value = next
        runCatching {
            file?.writeText(
                next.entries.joinToString("\n") { "${it.key}\t${if (it.value) "1" else "0"}" } + "\n"
            )
        }
    }
}

/**
 * Mengambil status gratis dari Steam Store (endpoint publik, tanpa login).
 * Mengembalikan null kalau tidak bisa diketahui, misalnya karena jaringan atau batas permintaan Steam.
 */
object StorePrices {
    suspend fun isFree(appId: Int): Boolean? = withContext<Boolean?>(Dispatchers.IO) {
        val conn = URL("https://store.steampowered.com/api/appdetails?appids=$appId&filters=basic")
            .openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            if (conn.responseCode != 200) return@withContext null

            val root = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val entry = root.optJSONObject(appId.toString()) ?: return@withContext null
            if (!entry.optBoolean("success", false)) return@withContext null
            val data = entry.optJSONObject("data") ?: return@withContext null
            if (data.has("is_free")) data.getBoolean("is_free") else null
        } finally {
            conn.disconnect()
        }
    }
}
