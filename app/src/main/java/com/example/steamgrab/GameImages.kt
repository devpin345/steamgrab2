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

    // Gambar latar tanpa judul untuk layar download. Aslinya sangat lebar (sekitar 3840 px), jadi diperkecil
    // saat dibaca supaya satu gambar tidak memakai belasan MB memori.
    private const val HERO_CACHE_BYTES = 16 * 1024 * 1024
    private const val HERO_TARGET_WIDTH = 1080

    private val heroCache = object : LruCache<Int, Bitmap>(HERO_CACHE_BYTES) {
        override fun sizeOf(key: Int, value: Bitmap): Int = value.byteCount
    }
    private val heroMissing: MutableSet<Int> = ConcurrentHashMap.newKeySet()

    /** Urutan percobaan: hero (tanpa judul), lalu latar buram bawaan toko. Header sengaja tidak dipakai karena memuat judul. */
    private fun heroUrls(appId: Int): List<String> = listOf(
        "https://cdn.akamai.steamstatic.com/steam/apps/$appId/library_hero.jpg",
        "https://cdn.akamai.steamstatic.com/steam/apps/$appId/page_bg_generated_v6b.jpg",
    )

    fun cachedHero(appId: Int): Bitmap? = heroCache.get(appId)

    /** Mengambil gambar latar tanpa judul. Null kalau game tidak punya atau jaringan gagal, UI memakai warna polos. */
    suspend fun loadHero(appId: Int): Bitmap? = withContext(Dispatchers.IO) {
        heroCache.get(appId)?.let { return@withContext it }
        if (appId in heroMissing) return@withContext null

        var networkProblem = false
        for (url in heroUrls(appId)) {
            try {
                val bytes = fetchBytes(url) ?: continue
                val bitmap = decodeScaled(bytes, HERO_TARGET_WIDTH) ?: continue
                heroCache.put(appId, bitmap)
                return@withContext bitmap
            } catch (e: IOException) {
                networkProblem = true
                if (warned.add(appId)) DebugLog.log("Gambar latar game $appId gagal dimuat: ${e.message}")
            }
        }
        // Gangguan jaringan tidak dicatat sebagai "tidak ada", supaya dicoba lagi lain kali
        if (!networkProblem && heroMissing.add(appId)) DebugLog.log("Game $appId tidak punya gambar latar")
        null
    }

    /** Isi file dari URL, atau null kalau server menjawab selain 200 (misalnya 404 karena game tidak punya gambar itu). */
    private fun fetchBytes(url: String): ByteArray? {
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            if (conn.responseCode != HttpURLConnection.HTTP_OK) null else conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }

    private fun decodeScaled(bytes: ByteArray, targetWidth: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= targetWidth) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

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
