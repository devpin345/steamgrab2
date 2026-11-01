package com.example.steamgrab

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * Ukuran game. [download] = byte yang diunduh (terkompresi), [disk] = byte yang dipakai di penyimpanan.
 */
data class GameSize(val download: Long, val disk: Long)

/**
 * Menyimpan ukuran game ke file di HP, supaya tidak perlu mengambil manifest lagi
 * setiap kali aplikasi dibuka.
 */
object GameSizeStore {
    private const val FILE_NAME = "game_sizes.txt"
    private var file: File? = null

    private val _sizes = MutableStateFlow<Map<Int, GameSize>>(emptyMap())
    val sizes: StateFlow<Map<Int, GameSize>> = _sizes.asStateFlow()

    @Synchronized
    fun init(context: Context) {
        if (file != null) return
        val f = File(context.filesDir, FILE_NAME)
        file = f
        _sizes.value = runCatching { f.readLines() }
            .getOrDefault(emptyList())
            .mapNotNull { line ->
                val parts = line.split('\t')
                if (parts.size != 3) return@mapNotNull null
                val appId = parts[0].toIntOrNull() ?: return@mapNotNull null
                val download = parts[1].toLongOrNull() ?: return@mapNotNull null
                val disk = parts[2].toLongOrNull() ?: return@mapNotNull null
                appId to GameSize(download, disk)
            }
            .toMap()
    }

    fun get(appId: Int): GameSize? = _sizes.value[appId]

    @Synchronized
    fun put(appId: Int, size: GameSize) {
        val next = _sizes.value + (appId to size)
        _sizes.value = next
        runCatching {
            file?.writeText(
                next.entries.joinToString("\n") { "${it.key}\t${it.value.download}\t${it.value.disk}" } + "\n"
            )
        }
    }
}
