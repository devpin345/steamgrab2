package com.example.steamgrab

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * Gambar header game dari CDN Steam (publik, tanpa login). Disimpan di memori
 * supaya daftar game dan notifikasi tidak mengunduh ulang gambar yang sama.
 */
object GameImages {

    private const val CACHE_BYTES = 24 * 1024 * 1024

    private val cache = object : LruCache<Int, Bitmap>(CACHE_BYTES) {
        override fun sizeOf(key: Int, value: Bitmap): Int = value.byteCount
    }

    // Game yang tidak punya gambar header (misalnya tool) dicatat supaya tidak diminta lagi
    // setiap kali daftar digambar ulang. Gangguan jaringan tidak dicatat, jadi akan dicoba lagi.
    private val missing: MutableSet<Int> = ConcurrentHashMap.newKeySet()
    private val warned: MutableSet<Int> = ConcurrentHashMap.newKeySet()

    fun headerUrl(appId: Int): String =
        "https://cdn.akamai.steamstatic.com/steam/apps/$appId/header.jpg"

    /** Gambar yang sudah ada di memori, tanpa jaringan. */
    fun cached(appId: Int): Bitmap? = cache.get(appId)

    /** Mengambil gambar header. Mengembalikan null kalau gagal, supaya UI tetap tampil dengan placeholder. */
    suspend fun load(appId: Int): Bitmap? = withContext(Dispatchers.IO) {
        cache.get(appId)?.let { return@withContext it }
        if (appId in missing) return@withContext null

        val conn = URL(headerUrl(appId)).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            if (conn.responseCode == HttpURLConnection.HTTP_NOT_FOUND) {
                missing.add(appId)
                if (warned.add(appId)) DebugLog.log("Game $appId tidak punya gambar header, dilewati")
                return@withContext null
            }
            val bitmap = conn.inputStream.use { BitmapFactory.decodeStream(it) }
            bitmap?.also { cache.put(appId, it) }
        } catch (e: IOException) {
            if (warned.add(appId)) DebugLog.log("Gambar game $appId gagal dimuat: ${e.message}")
            null
        } finally {
            conn.disconnect()
        }
    }
}
