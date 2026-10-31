package com.example.steamgrab

import android.app.Application
import android.content.Intent
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class GameInfo(val appId: Int, val name: String)

sealed class UiState {
    data class LoggedOut(
        val loading: Boolean = false,
        val error: String? = null,
        val needsGuardCode: Boolean = false
    ) : UiState()

    data class GameList(val games: List<GameInfo>) : UiState()
    data class Downloading(val progress: Float, val gameName: String, val status: String) : UiState()
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
            _uiState.value = if (repo.submitGuardCode(guardCode)) {
                UiState.LoggedOut(loading = true)
            } else {
                UiState.LoggedOut(needsGuardCode = true, error = "Masukkan kode Steam Guard dulu")
            }
            return
        }

        _uiState.value = UiState.LoggedOut(loading = true)
        viewModelScope.launch {
            try {
                repo.login(username, password) { type, wrongCode ->
                    val where = if (type == GuardType.EMAIL) "email" else "aplikasi Steam Mobile"
                    _uiState.value = UiState.LoggedOut(
                        loading = false,
                        needsGuardCode = true,
                        error = if (wrongCode) "Kode salah, coba lagi" else "Masukkan kode Steam Guard dari $where"
                    )
                }
                val games = repo.loadOwnedGames().map { GameInfo(it.appId, it.name) }
                ownedGames = games
                watchingAppId = null
                _uiState.value = UiState.GameList(games)
            } catch (e: Exception) {
                Log.e("SteamGrab", "Login gagal", e)
                _uiState.value = UiState.LoggedOut(error = "Login gagal. Detail:\n${e.describe()}")
            }
        }
    }

    fun startDownload(appId: Int) {
        val game = ownedGames.find { it.appId == appId } ?: return
        watchingAppId = appId
        _uiState.value = UiState.Downloading(0f, game.name, "Memulai...")

        val ctx = getApplication<Application>()
        ctx.startForegroundService(
            Intent(ctx, DownloadService::class.java)
                .putExtra(DownloadService.EXTRA_APP_ID, appId)
                .putExtra(DownloadService.EXTRA_NAME, game.name)
        )
    }

    fun cancelDownload() {
        watchingAppId = null
        _uiState.value = UiState.GameList(ownedGames)

        val ctx = getApplication<Application>()
        ctx.startService(Intent(ctx, DownloadService::class.java).setAction(DownloadService.ACTION_STOP))
    }

    fun backToList() {
        watchingAppId = null
        _uiState.value = UiState.GameList(ownedGames)
    }

    private fun onDownloadStatus(status: DownloadStatus) {
        val watching = watchingAppId ?: return
        when (status) {
            is DownloadStatus.Running -> {
                if (status.appId == watching) {
                    _uiState.value = UiState.Downloading(status.progress, status.name, status.text)
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
}
