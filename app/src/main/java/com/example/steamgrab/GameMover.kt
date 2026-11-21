package com.example.steamgrab

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/** Keadaan pemindahan satu game antar penyimpanan. */
data class MoveState(
    val appId: Int,
    val name: String,
    val targetLabel: String,
    val copiedBytes: Long,
    val totalBytes: Long,
    val finished: Boolean = false,
    val error: String? = null,
)

/**
 * Memindahkan satu game ke library di penyimpanan lain: disalin dulu seluruhnya, dicek ukurannya, baru yang lama
 * dihapus. Kalau gagal atau dibatalkan, salinan yang setengah jadi dihapus dan game di tempat asal tidak diubah.
 */
object GameMover {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    private val _state = MutableStateFlow<MoveState?>(null)
    val state: StateFlow<MoveState?> = _state.asStateFlow()

    val running: Boolean get() = job?.isActive == true

    fun start(game: LibraryGame, from: Library, to: Library, targetLabel: String) {
        if (running) return
        _state.value = MoveState(game.appId, game.name, targetLabel, 0L, game.sizeOnDisk)
        job = scope.launch {
            if (game.installDir.isBlank()) {
                _state.value = _state.value?.copy(error = "Folder game tidak tercatat di appmanifest")
                return@launch
            }
            val src = from.gameDir(game.installDir)
            val dst = to.gameDir(game.installDir)
            // Hanya salinan yang dibuat di sini yang boleh dihapus kalau gagal, bukan folder yang sudah ada sebelumnya
            var started = false
            try {
                to.ensureLibrary()
                val files = src.walkTopDown().filter { it.isFile }.toList()
                val total = files.sumOf { it.length() }
                if (dst.exists() && (dst.list()?.isNotEmpty() == true)) {
                    throw IllegalStateException("Folder ${game.installDir} sudah ada di tujuan")
                }
                if (to.freeBytes() < total + 64L * 1024 * 1024) {
                    throw IllegalStateException(
                        "Ruang di tujuan tidak cukup: butuh ${formatBytes(total)}, tersedia ${formatBytes(to.freeBytes())}"
                    )
                }
                DebugLog.log("Memindahkan ${game.name} (${formatBytes(total)}) ke ${to.root.path}")
                _state.value = _state.value?.copy(totalBytes = total)

                started = true
                var copied = 0L
                var lastPublish = 0L
                val buffer = ByteArray(1 shl 20)
                for (file in files) {
                    ensureActive()
                    val out = File(dst, file.relativeTo(src).path)
                    out.parentFile?.mkdirs()
                    file.inputStream().use { input ->
                        out.outputStream().use { output ->
                            while (true) {
                                ensureActive()
                                val n = input.read(buffer)
                                if (n < 0) break
                                output.write(buffer, 0, n)
                                copied += n
                                val now = System.currentTimeMillis()
                                if (now - lastPublish > 250L) {
                                    lastPublish = now
                                    _state.value = _state.value?.copy(copiedBytes = copied)
                                }
                            }
                        }
                    }
                    if (out.length() != file.length()) {
                        throw IllegalStateException("Ukuran salinan ${file.name} tidak sama dengan aslinya")
                    }
                }
                to.manifestFile(game.appId).also { it.parentFile?.mkdirs() }
                    .writeText(from.manifestFile(game.appId).readText())

                // Salinan lengkap dan cocok, baru yang lama dihapus
                from.deleteGame(game)
                Libraries.refresh()
                DebugLog.log("Pemindahan ${game.name} selesai")
                _state.value = _state.value?.copy(copiedBytes = total, finished = true)
            } catch (e: CancellationException) {
                if (started) cleanup(dst, to, game)
                DebugLog.log("Pemindahan ${game.name} dibatalkan")
                _state.value = null
                throw e
            } catch (e: Exception) {
                if (started) cleanup(dst, to, game)
                DebugLog.log("Pemindahan ${game.name} gagal: ${e.describe()}")
                val hint = if (e.message?.contains("large", ignoreCase = true) == true) {
                    "\nPenyimpanan tujuan kemungkinan berformat FAT32 yang tidak menerima file di atas 4 GB."
                } else {
                    ""
                }
                _state.value = _state.value?.copy(error = (e.message ?: e.describe()) + hint)
            }
        }
    }

    private fun cleanup(dst: File, to: Library, game: LibraryGame) {
        runCatching { dst.deleteRecursively() }
        runCatching { to.manifestFile(game.appId).delete() }
    }

    fun cancel() {
        job?.cancel()
    }

    fun dismiss() {
        if (!running) _state.value = null
    }
}
