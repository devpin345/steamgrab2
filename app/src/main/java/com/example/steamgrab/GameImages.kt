package com.example.steamgrab

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Gambar header game dari CDN Steam (publik, tanpa login). Disimpan di memori
 * supaya daftar game dan notifikasi tidak mengunduh ulang gambar yang sama.
 */
object GameImages {

    private const val CACHE_BYTES = 24 * 1024 * 1024

    private val cache = object : LruCache<Int, Bitmap>(CACHE_BYTES) {
        override fun sizeOf(key: Int, value: Bitmap): Int = value.byteCount
    }

    fun headerUrl(appId: Int): String =
        "https://cdn.akamai.steamstatic.com/steam/apps/$appId/header.jpg"

    /** Gambar yang sudah ada di memori, tanpa jaringan. */
    fun cached(appId: Int): Bitmap? = cache.get(appId)

    /** Mengambil gambar header. Mengembalikan null kalau gagal, supaya UI tetap tampil dengan placeholder. */
    suspend fun load(appId: Int): Bitmap? = withContext(Dispatchers.IO) {
        cache.get(appId)?.let { return@withContext it }
        val bitmap = try {
            val conn = URL(headerUrl(appId)).openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.inputStream.use { BitmapFactory.decodeStream(it) }
        } catch (e: IOException) {
            DebugLog.log("Gambar game $appId tidak bisa dimuat: ${e.message}")
            null
        }
        bitmap?.also { cache.put(appId, it) }
    }
}
