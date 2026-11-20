package com.example.steamgrab

import android.content.Context
import android.content.SharedPreferences
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

    // --- Ikon asli game (aset Steam "Client Icon" dan "Icon"), bukan potongan gambar header ---

    private const val ICON_CACHE_BYTES = 6 * 1024 * 1024
    private const val ICON_TARGET_SIZE = 192
    private const val ICON_HOSTS = "steamcommunity/public/images/apps"

    private val iconCache = object : LruCache<Int, Bitmap>(ICON_CACHE_BYTES) {
        override fun sizeOf(key: Int, value: Bitmap): Int = value.byteCount
    }
    private var iconPrefs: SharedPreferences? = null

    @Synchronized
    fun init(context: Context) {
        if (iconPrefs == null) iconPrefs = context.getSharedPreferences("game_icons", Context.MODE_PRIVATE)
    }

    /**
     * Mencatat nama file ikon dari info app Steam: [clientIcon] untuk ikon besar (.ico) dan [icon] untuk ikon kecil
     * 32 px (.jpg). Disimpan supaya ikon tetap bisa ditampilkan di layar penyimpanan tanpa mengambil ulang info app.
     */
    fun recordIcons(appId: Int, clientIcon: String?, icon: String?) {
        val ci = clientIcon?.trim().orEmpty()
        val ic = icon?.trim().orEmpty()
        if (ci.isEmpty() && ic.isEmpty()) return
        val value = "$ci|$ic"
        val prefs = iconPrefs ?: return
        if (prefs.getString(appId.toString(), null) != value) {
            prefs.edit().putString(appId.toString(), value).apply()
        }
    }

    private fun iconUrls(appId: Int): List<String> {
        val parts = iconPrefs?.getString(appId.toString(), null)?.split('|') ?: return emptyList()
        val clientIcon = parts.getOrNull(0).orEmpty()
        val icon = parts.getOrNull(1).orEmpty()
        val urls = ArrayList<String>()
        if (clientIcon.isNotEmpty()) {
            urls += "https://cdn.cloudflare.steamstatic.com/$ICON_HOSTS/$appId/$clientIcon.ico"
            urls += "https://media.steampowered.com/$ICON_HOSTS/$appId/$clientIcon.ico"
        }
        if (icon.isNotEmpty()) {
            urls += "https://cdn.cloudflare.steamstatic.com/$ICON_HOSTS/$appId/$icon.jpg"
            urls += "https://media.steampowered.com/$ICON_HOSTS/$appId/$icon.jpg"
        }
        return urls
    }

    fun cachedIcon(appId: Int): Bitmap? = iconCache.get(appId)

    /**
     * Mengambil ikon asli game: Client Icon (.ico, ukuran terbesar di dalamnya), lalu Icon 32 px kalau yang besar
     * tidak ada. Null kalau game belum dikenal ikonnya atau jaringan gagal; UI menampilkan kotak polos.
     */
    suspend fun loadIcon(appId: Int): Bitmap? = withContext(Dispatchers.IO) {
        iconCache.get(appId)?.let { return@withContext it }
        for (url in iconUrls(appId)) {
            try {
                val bytes = fetchBytes(url) ?: continue
                val bitmap = (if (url.endsWith(".ico")) decodeIco(bytes) else decodeScaled(bytes, ICON_TARGET_SIZE))
                    ?: continue
                iconCache.put(appId, bitmap)
                return@withContext bitmap
            } catch (e: IOException) {
                if (warned.add(-appId)) DebugLog.log("Ikon game $appId gagal dimuat: ${e.message}")
            }
        }
        null
    }

    /**
     * Membaca file .ico milik Steam. Isinya beberapa ukuran; yang diambil yang terbesar. Gambar PNG di dalam .ico
     * dibaca langsung (cara yang sama di semua versi Android), dan kalau bukan PNG diserahkan ke decoder sistem.
     */
    private fun decodeIco(bytes: ByteArray): Bitmap? {
        if (bytes.size >= 22 && bytes[0].toInt() == 0 && bytes[1].toInt() == 0 && bytes[2].toInt() == 1 && bytes[3].toInt() == 0) {
            val count = (bytes[4].toInt() and 0xFF) or ((bytes[5].toInt() and 0xFF) shl 8)
            var bestWidth = -1
            var bestOffset = -1
            var bestSize = 0
            for (i in 0 until count) {
                val e = 6 + i * 16
                if (e + 16 > bytes.size) break
                val width = (bytes[e].toInt() and 0xFF).let { if (it == 0) 256 else it }
                val size = u32(bytes, e + 8)
                val offset = u32(bytes, e + 12)
                if (offset < 0 || size <= 8 || offset + size > bytes.size) continue
                val isPng = bytes[offset].toInt() and 0xFF == 0x89 && bytes[offset + 1].toInt() == 'P'.code &&
                    bytes[offset + 2].toInt() == 'N'.code && bytes[offset + 3].toInt() == 'G'.code
                if (isPng && width > bestWidth) {
                    bestWidth = width
                    bestOffset = offset
                    bestSize = size
                }
            }
            if (bestOffset >= 0) {
                val png = bytes.copyOfRange(bestOffset, bestOffset + bestSize)
                decodeScaled(png, ICON_TARGET_SIZE)?.let { return it }
            }
        }
        return decodeScaled(bytes, ICON_TARGET_SIZE)
    }

    private fun u32(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8) or
            ((b[at + 2].toInt() and 0xFF) shl 16) or ((b[at + 3].toInt() and 0xFF) shl 24)

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
