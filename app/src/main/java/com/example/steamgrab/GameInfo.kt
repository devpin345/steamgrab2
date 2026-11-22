package com.example.steamgrab

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/** Keterangan tambahan sebuah game dari toko Steam, untuk jendela info game. Semua bagian boleh kosong. */
data class GameExtra(
    val developer: String?,
    val publisher: String?,
    // Nomor content descriptor Steam (1 konten seksual, 2 kekerasan, 3 khusus dewasa, 4 ketelanjangan, 5 konten dewasa)
    val descriptors: List<Int>,
    // Peringkat usia, misalnya "M" dan "17+" untuk ESRB
    val ageLabel: String?,
    val ageSub: String?,
    // Nilai ulasan pengguna dari 0 sampai 5
    val score: Float?,
)

/** Mengambil [GameExtra] dari API toko Steam yang terbuka, dan menyimpannya di memori selama aplikasi hidup. */
object GameInfoStore {
    private val cache = ConcurrentHashMap<Int, GameExtra>()

    // Data dari PICS (daftar app saat login). Dipakai kalau toko tidak punya halaman game itu, misalnya server uji.
    private class Pics(val developer: String?, val publisher: String?, val reviewPercent: Int?)

    private val pics = ConcurrentHashMap<Int, Pics>()

    /** Dicatat saat daftar game dimuat: pengembang, penerbit, dan persentase ulasan positif dari data app Steam. */
    fun recordPics(appId: Int, developer: String?, publisher: String?, reviewPercent: Int?) {
        val dev = developer?.trim()?.takeIf { it.isNotEmpty() }
        val pub = publisher?.trim()?.takeIf { it.isNotEmpty() }
        if (dev != null || pub != null || reviewPercent != null) pics[appId] = Pics(dev, pub, reviewPercent)
    }

    fun cached(appId: Int): GameExtra? = cache[appId]

    suspend fun load(appId: Int): GameExtra? = withContext(Dispatchers.IO) {
        cache[appId]?.let { return@withContext it }
        var developer: String? = null
        var publisher: String? = null
        var descriptors: List<Int> = emptyList()
        var ageLabel: String? = null
        var ageSub: String? = null
        var score: Float? = null
        var storeOk = false
        try {
            val details = get("https://store.steampowered.com/api/appdetails?appids=$appId&l=english")
            val data = details?.let { JSONObject(it).optJSONObject(appId.toString())?.optJSONObject("data") }
            if (data != null) {
                storeOk = true

                fun names(key: String): String? =
                    data.optJSONArray(key)?.let { arr -> (0 until arr.length()).map { arr.optString(it) } }
                        ?.filter { it.isNotBlank() }?.joinToString(", ")?.ifBlank { null }

                developer = names("developers")
                publisher = names("publishers")
                descriptors = data.optJSONObject("content_descriptors")?.optJSONArray("ids")
                    ?.let { arr -> (0 until arr.length()).map { arr.optInt(it) } } ?: emptyList()

                val ratings = data.optJSONObject("ratings")
                val esrb = ratings?.optJSONObject("esrb")?.optString("rating")?.lowercase()
                if (!esrb.isNullOrBlank()) {
                    when (esrb) {
                        "e" -> { ageLabel = "E"; ageSub = "ALL" }
                        "e10" -> { ageLabel = "E"; ageSub = "10+" }
                        "t" -> { ageLabel = "T"; ageSub = "13+" }
                        "m" -> { ageLabel = "M"; ageSub = "17+" }
                        "ao" -> { ageLabel = "AO"; ageSub = "18+" }
                        else -> { ageLabel = esrb.uppercase(); ageSub = null }
                    }
                } else {
                    val pegi = ratings?.optJSONObject("pegi")?.optString("rating")
                    if (!pegi.isNullOrBlank()) {
                        ageLabel = pegi
                        ageSub = "PEGI"
                    }
                }
            }
        } catch (e: Exception) {
            DebugLog.log("Info game $appId: data toko gagal dimuat: ${e.describe()}")
        }

        try {
            val reviews = get(
                "https://store.steampowered.com/appreviews/$appId?json=1&language=all&purchase_type=all&num_per_page=0"
            )
            if (reviews != null) {
                val summary = JSONObject(reviews).optJSONObject("query_summary")
                val positive = summary?.optInt("total_positive") ?: 0
                val negative = summary?.optInt("total_negative") ?: 0
                if (positive + negative > 0) score = 5f * positive / (positive + negative)
            }
        } catch (e: Exception) {
            DebugLog.log("Info game $appId: ulasan gagal dimuat: ${e.describe()}")
        }

        // Toko tidak punya datanya: pakai yang tercatat dari data app Steam
        val fallback = pics[appId]
        if (developer == null) developer = fallback?.developer
        if (publisher == null) publisher = fallback?.publisher
        if (score == null && fallback?.reviewPercent != null) score = 5f * fallback.reviewPercent / 100f

        DebugLog.log(
            "Info game $appId: toko=${if (storeOk) "ada" else "tidak ada"} pengembang=${developer ?: "-"} " +
                "penerbit=${publisher ?: "-"} usia=${ageLabel ?: "-"} skor=${score ?: "-"}"
        )
        val extra = GameExtra(developer, publisher, descriptors, ageLabel, ageSub, score)
        // Hanya disimpan kalau ada isinya, supaya gangguan jaringan sesaat tidak terbawa sampai aplikasi ditutup
        if (storeOk || developer != null || publisher != null || score != null) cache[appId] = extra
        extra
    }

    private fun get(url: String): String? {
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("Accept-Language", "en")
            if (conn.responseCode != 200) null else conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}
