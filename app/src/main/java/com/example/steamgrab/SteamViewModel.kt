package com.example.steamgrab

import android.app.Application
import android.content.Intent
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

// Batas waktu supaya kalau macet, layar menampilkan langkah yang macet, bukan loading terus.
// Login dibuat longgar karena kadang menunggu kode Steam Guard dari email.
private const val LOGIN_TIMEOUT_MS = 180_000L
private const val GAMES_TIMEOUT_MS = 120_000L

data class GameInfo(val appId: Int, val name: String)

sealed class UiState {
    data class LoggedOut(
        val loading: Boolean = false,
        val error: String? = null,
        val needsGuardCode: Boolean = false,
        val status: String? = null
    ) : UiState()

    data class GameList(val games: List<GameInfo>) : UiState()

    data class Downloading(
        val appId: Int,
        val gameName: String,
        val progress: DownloadProgress,
        val speedBps: Long,
        val speedHistory: List<Long>,
    ) : UiState()

    data class Done(val savedPath: String) : UiState()
    data class Error(val message: String) : UiState()
}

class SteamViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = SteamRepository.get(app)

    private val _uiState = MutableStateFlow<UiState>(UiState.LoggedOut())
    val uiState: StateFlow<UiState> = _uiState

    private var ownedGames: List<GameInfo> = emptyList()
    private var watchingAppId: Int? = null

    init {
        // Status download datang dari DownloadService, UI hanya menampilkannya
        viewModelScope.launch {
            DownloadService.status.collect { onDownloadStatus(it) }
        }
    }

    fun login(username: String, password: String, guardCode: String?) {
        // Sedang menunggu kode Steam Guard: kirim kodenya ke sesi login yang sudah berjalan,
        // jangan memulai login baru.
        if (repo.isWaitingForGuard) {
            DebugLog.log("Tombol Kirim kode ditekan")
            _uiState.value = if (repo.submitGuardCode(guardCode)) {
                UiState.LoggedOut(loading = true, needsGuardCode = true, status = "Mengirim kode Steam Guard...")
            } else {
                UiState.LoggedOut(needsGuardCode = true, error = "Masukkan kode Steam Guard dulu")
            }
            return
        }

        DebugLog.log("Tombol Login ditekan")
        _uiState.value = UiState.LoggedOut(loading = true, status = "Menghubungkan ke Steam...")
        viewModelScope.launch {
            var step = "login ke Steam"
            try {
                withTimeout(LOGIN_TIMEOUT_MS) {
                    repo.login(username.trim(), password) { type, wrongCode ->
                        val where = if (type == GuardType.EMAIL) "email" else "aplikasi Steam Mobile"
                        _uiState.value = UiState.LoggedOut(
                            loading = false,
                            needsGuardCode = true,
                            error = if (wrongCode) "Kode salah, coba lagi" else "Masukkan kode Steam Guard dari $where"
                        )
                    }
                }

                DebugLog.log("Login berhasil, lanjut mengambil daftar game")
                step = "mengambil daftar game"
                _uiState.value = UiState.LoggedOut(loading = true, status = "Mengambil daftar game...")
                val owned = withTimeout(GAMES_TIMEOUT_MS) { repo.loadOwnedGames() }
                val games = owned.map { GameInfo(it.appId, it.name) }
                ownedGames = games
                watchingAppId = null
                _uiState.value = UiState.GameList(games)
            } catch (e: Exception) {
                DebugLog.log("ERROR saat $step: ${e.describe()}")
                Log.e("SteamGrab", "Gagal saat $step", e)
                _uiState.value = UiState.LoggedOut(error = "Gagal saat $step.\nDetail:\n${e.describe()}")
            }
        }
    }

    fun startDownload(appId: Int) {
        // Hanya satu download dalam satu waktu. Kalau masih ada yang berjalan, tampilkan progres itu.
        if (DownloadService.status.value is DownloadStatus.Running) {
            DebugLog.log("Masih ada download yang berjalan, menampilkan progresnya")
            openRunningDownload()
            return
        }

        val game = ownedGames.find { it.appId == appId } ?: return
        watchingAppId = appId
        _uiState.value = UiState.Downloading(appId, game.name, DownloadProgress(), 0L, emptyList())

        val ctx = getApplication<Application>()
        ctx.startForegroundService(
            Intent(ctx, DownloadService::class.java)
                .putExtra(DownloadService.EXTRA_APP_ID, appId)
                .putExtra(DownloadService.EXTRA_NAME, game.name)
        )
    }

    /** Membuka kembali halaman download yang sedang berjalan di latar belakang. */
    fun openRunningDownload() {
        val running = DownloadService.status.value as? DownloadStatus.Running ?: return
        watchingAppId = running.appId
        _uiState.value = running.toUi()
    }

    fun cancelDownload() {
        watchingAppId = null
        _uiState.value = UiState.GameList(ownedGames)

        val ctx = getApplication<Application>()
        ctx.startService(Intent(ctx, DownloadService::class.java).setAction(DownloadService.ACTION_STOP))
    }

    /** Kembali ke daftar. Download yang sedang berjalan tetap lanjut di latar belakang. */
    fun backToList() {
        watchingAppId = null
        _uiState.value = UiState.GameList(ownedGames)
    }

    private fun onDownloadStatus(status: DownloadStatus) {
        val watching = watchingAppId ?: return
        when (status) {
            is DownloadStatus.Running -> {
                if (status.appId == watching) {
                    _uiState.value = status.toUi()
                }
            }
            is DownloadStatus.Finished -> {
                if (status.appId == watching) {
                    watchingAppId = null
                    _uiState.value = UiState.Done(status.path)
                }
            }
            is DownloadStatus.Failed -> {
                if (status.appId == watching) {
                    watchingAppId = null
                    _uiState.value = UiState.Error(status.message)
                }
            }
            DownloadStatus.Idle -> Unit
        }
    }

    private fun DownloadStatus.Running.toUi(): UiState.Downloading =
        UiState.Downloading(appId, name, progress, speedBps, speedHistory)
}
