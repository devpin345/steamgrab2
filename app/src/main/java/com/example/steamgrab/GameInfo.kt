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

    fun cached(appId: Int): GameExtra? = cache[appId]

    suspend fun load(appId: Int): GameExtra? = withContext(Dispatchers.IO) {
        cache[appId]?.let { return@withContext it }
        try {
            val details = get("https://store.steampowered.com/api/appdetails?appids=$appId&l=english")
                ?: return@withContext null
            val data = JSONObject(details).optJSONObject(appId.toString())?.optJSONObject("data")
                ?: return@withContext null

            fun names(key: String): String? =
                data.optJSONArray(key)?.let { arr -> (0 until arr.length()).map { arr.optString(it) } }
                    ?.filter { it.isNotBlank() }?.joinToString(", ")?.ifBlank { null }

            val ids = data.optJSONObject("content_descriptors")?.optJSONArray("ids")
                ?.let { arr -> (0 until arr.length()).map { arr.optInt(it) } } ?: emptyList()

            var ageLabel: String? = null
            var ageSub: String? = null
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

            var score: Float? = null
            val reviews = get(
                "https://store.steampowered.com/appreviews/$appId?json=1&language=all&purchase_type=all&num_per_page=0"
            )
            if (reviews != null) {
                val summary = JSONObject(reviews).optJSONObject("query_summary")
                val positive = summary?.optInt("total_positive") ?: 0
                val negative = summary?.optInt("total_negative") ?: 0
                if (positive + negative > 0) score = 5f * positive / (positive + negative)
            }

            val extra = GameExtra(names("developers"), names("publishers"), ids, ageLabel, ageSub, score)
            cache[appId] = extra
            extra
        } catch (e: Exception) {
            DebugLog.log("Info game $appId gagal dimuat: ${e.describe()}")
            null
        }
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
