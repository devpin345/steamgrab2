package com.example.steamgrab

import android.app.Application
import android.content.Intent
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

// Batas waktu supaya kalau macet, layar menampilkan langkah yang macet, bukan loading terus.
// Login dibuat longgar karena kadang menunggu kode Steam Guard dari email.
private const val LOGIN_TIMEOUT_MS = 180_000L
private const val GAMES_TIMEOUT_MS = 120_000L

data class GameInfo(val appId: Int, val name: String)

/** Pesan untuk popup memori penuh. Ukuran dalam byte. */
data class StorageAlert(val gameName: String, val neededBytes: Long, val freeBytes: Long)

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
        val paused: Boolean = false,
    ) : UiState()

    data class Done(val savedPath: String) : UiState()
    data class Error(val message: String) : UiState()
}

class SteamViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = SteamRepository.get(app)

    private val _uiState = MutableStateFlow<UiState>(UiState.LoggedOut())
    val uiState: StateFlow<UiState> = _uiState

    /** Ukuran game yang sudah diketahui (dari penyimpanan atau hasil hitungan). */
    val sizes: StateFlow<Map<Int, GameSize>> = GameSizeStore.sizes

    /** Status gratis per game (true = gratis). Game yang belum diketahui tidak ada di map ini. */
    val prices: StateFlow<Map<Int, Boolean>> = GamePriceStore.prices

    private val _sizesLoading = MutableStateFlow(false)
    val sizesLoading: StateFlow<Boolean> = _sizesLoading.asStateFlow()

    private val _storageAlert = MutableStateFlow<StorageAlert?>(null)
    val storageAlert: StateFlow<StorageAlert?> = _storageAlert.asStateFlow()

    private var ownedGames: List<GameInfo> = emptyList()
    private var watchingAppId: Int? = null
    private var sizeJob: Job? = null
    private var loginJob: Job? = null
    private var priceJob: Job? = null

    init {
        GamePriceStore.init(app)
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

        // Login sebelumnya masih berjalan (misalnya menunggu koneksi kembali). Jangan mulai yang kedua,
        // karena dua login bersamaan akan saling menimpa status.
        if (loginJob?.isActive == true) {
            DebugLog.log("Tombol Login diabaikan, login sebelumnya masih berjalan")
            val current = _uiState.value
            if (current is UiState.LoggedOut) {
                _uiState.value = current.copy(error = "Login masih berjalan, tunggu sebentar")
            }
            return
        }

        DebugLog.log("Tombol Login ditekan")
        _uiState.value = UiState.LoggedOut(loading = true, status = "Menghubungkan ke Steam...")
        loginJob = viewModelScope.launch {
            var step = "login ke Steam"
            try {
                withTimeout(LOGIN_TIMEOUT_MS) {
                    repo.login(
                        username = username.trim(),
                        password = password,
                        onGuardCodeNeeded = { type, wrongCode ->
                            _uiState.value = if (type == GuardType.APPROVAL) {
                                // Belum ada yang salah: cukup setujui di Steam Mobile. Kode juga bisa diketik.
                                UiState.LoggedOut(
                                    loading = false,
                                    needsGuardCode = true,
                                    status = "Setujui login di aplikasi Steam Mobile, atau ketik kode Steam Guard lalu tekan Kirim kode."
                                )
                            } else {
                                val where = if (type == GuardType.EMAIL) "email" else "aplikasi Steam Mobile"
                                UiState.LoggedOut(
                                    loading = false,
                                    needsGuardCode = true,
                                    error = if (wrongCode) "Kode salah, coba lagi" else "Masukkan kode Steam Guard dari $where"
                                )
                            }
                        },
                        onStatus = { message ->
                            // Hanya di layar login. Kolom kode tetap tampil supaya kode bisa diketik lagi
                            // setelah Steam meminta kode baru.
                            val current = _uiState.value
                            if (current is UiState.LoggedOut) {
                                _uiState.value = UiState.LoggedOut(
                                    loading = true,
                                    needsGuardCode = current.needsGuardCode,
                                    status = message
                                )
                            }
                        }
                    )
                }

                DebugLog.log("Login berhasil, lanjut mengambil daftar game")
                step = "mengambil daftar game"
                _uiState.value = UiState.LoggedOut(loading = true, status = "Mengambil daftar game...")
                val owned = withTimeout(GAMES_TIMEOUT_MS) { repo.loadOwnedGames() }
                val games = owned.map { GameInfo(it.appId, it.name) }
                ownedGames = games
                watchingAppId = null
                _uiState.value = UiState.GameList(games)
                loadSizesInBackground(games)
                loadPricesInBackground(games)
            } catch (e: Exception) {
                DebugLog.log("ERROR saat $step: ${e.describe()}")
                Log.e("SteamGrab", "Gagal saat $step", e)
                _uiState.value = UiState.LoggedOut(error = "Gagal saat $step.\nDetail:\n${e.describe()}")
            }
        }
    }

    /**
     * Dipanggil saat game diketuk. Memeriksa ruang penyimpanan dulu.
     * Kalau tidak cukup, popup memori penuh muncul dan download tidak dimulai.
     */
    fun requestDownload(appId: Int) {
        if (DownloadService.status.value is DownloadStatus.Running) {
            openRunningDownload()
            return
        }
        val game = ownedGames.find { it.appId == appId } ?: return
        viewModelScope.launch {
            val check = withContext(Dispatchers.IO) { checkStorage(appId) }
            if (check != null && check.first > check.second) {
                DebugLog.log("Memori tidak cukup untuk ${game.name}")
                _storageAlert.value = StorageAlert(game.name, check.first, check.second)
            } else {
                startDownload(appId)
            }
        }
    }

    fun dismissStorageAlert() {
        _storageAlert.value = null
    }

    /**
     * Mengembalikan (butuh, tersedia) dalam byte. Null kalau ukuran game belum bisa dihitung,
     * dan download tetap dicoba.
     */
    private suspend fun checkStorage(appId: Int): Pair<Long, Long>? {
        val size = GameSizeStore.get(appId) ?: try {
            repo.computeDownloadSize(appId).also { GameSizeStore.put(appId, it) }
        } catch (e: Exception) {
            DebugLog.log("Ukuran game belum diketahui, download tetap dicoba: ${e.describe()}")
            return null
        }
        // Selalu pakai ukuran penuh. Library mengalokasikan panjang file di awal sebelum datanya tiba,
        // jadi ukuran file di folder tidak menunjukkan berapa yang sudah benar-benar terunduh.
        // Mengurangi ukuran itu membuat kebutuhan terhitung 0 B pada percobaan kedua.
        val needed = size.disk
        val free = Storage.freeBytes()
        DebugLog.log("Cek ruang: butuh ${formatBytes(needed)}, tersedia ${formatBytes(free)}")
        return needed to free
    }

    /** Menghitung ukuran semua game di latar belakang, satu per satu. Hasilnya disimpan, jadi tidak dihitung ulang. */
    private fun loadSizesInBackground(games: List<GameInfo>) {
        if (sizeJob?.isActive == true) return
        sizeJob = viewModelScope.launch(Dispatchers.IO) {
            _sizesLoading.value = true
            try {
                for (game in games) {
                    if (GameSizeStore.get(game.appId) != null) continue
                    try {
                        GameSizeStore.put(game.appId, repo.computeDownloadSize(game.appId))
                    } catch (e: Exception) {
                        if (!isActive) throw e
                        DebugLog.log("Ukuran ${game.name} belum bisa dihitung: ${e.describe()}")
                    }
                }
            } finally {
                _sizesLoading.value = false
            }
        }
    }

    /**
     * Mengambil status gratis dari Steam Store satu per satu. Ada jeda antar permintaan
     * supaya tidak kena batas permintaan Steam. Yang sudah diketahui tidak diambil lagi.
     */
    private fun loadPricesInBackground(games: List<GameInfo>) {
        if (priceJob?.isActive == true) return
        priceJob = viewModelScope.launch(Dispatchers.IO) {
            for (game in games) {
                if (GamePriceStore.get(game.appId) != null) continue
                try {
                    StorePrices.isFree(game.appId)?.let { GamePriceStore.put(game.appId, it) }
                } catch (e: Exception) {
                    if (!isActive) throw e
                    DebugLog.log("Status gratis ${game.name} belum diketahui: ${e.describe()}")
                }
                delay(1_500L)
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

    /** Membuka kembali halaman download yang sedang berjalan atau dijeda di latar belakang. */
    fun openRunningDownload() {
        when (val st = DownloadService.status.value) {
            is DownloadStatus.Running -> {
                watchingAppId = st.appId
                _uiState.value = st.toUi()
            }
            is DownloadStatus.Paused -> {
                watchingAppId = st.appId
                _uiState.value = st.toUi()
            }
            else -> Unit
        }
    }

    fun pauseDownload() {
        DebugLog.log("Tombol Jeda ditekan")
        val ctx = getApplication<Application>()
        ctx.startService(Intent(ctx, DownloadService::class.java).setAction(DownloadService.ACTION_PAUSE))
    }

    /** Melanjutkan download yang dijeda. File yang sudah ada dicek ulang, lalu hanya bagian yang belum ada diunduh. */
    fun resumeDownload(appId: Int) {
        DebugLog.log("Tombol Lanjutkan ditekan")
        startDownload(appId)
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
            is DownloadStatus.Paused -> {
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

    private fun DownloadStatus.Paused.toUi(): UiState.Downloading =
        UiState.Downloading(appId, name, progress, 0L, emptyList(), paused = true)
}
