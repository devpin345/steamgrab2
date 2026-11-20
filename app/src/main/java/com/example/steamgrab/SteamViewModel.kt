package com.example.steamgrab

import android.app.Application
import android.content.Intent
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

// Batas waktu supaya kalau macet, layar menampilkan langkah yang macet, bukan loading terus.
// Login dibuat longgar karena kadang menunggu kode Steam Guard dari email.
private const val LOGIN_TIMEOUT_MS = 180_000L
private const val GAMES_TIMEOUT_MS = 120_000L
// Steam menutup sesi QR setelah beberapa menit tanpa dipindai, lalu QR baru dibuat. Batas ini hanya jaring pengaman.
private const val QR_TIMEOUT_MS = 600_000L
private const val QR_MAX_FAILURES = 3

data class GameInfo(val appId: Int, val name: String)

/** Pesan untuk popup memori penuh. Ukuran dalam byte. */
data class StorageAlert(val gameName: String, val neededBytes: Long, val freeBytes: Long)

/** Satu tujuan di popup pilih lokasi simpan. [path] adalah folder library-nya. */
data class LocationOption(val path: String, val label: String, val freeBytes: Long)

/** Permintaan memilih lokasi simpan, hanya muncul kalau lebih dari satu penyimpanan punya library. */
data class LocationChoice(val appId: Int, val gameName: String, val platform: Platform, val options: List<LocationOption>)

sealed class UiState {
    data class LoggedOut(
        val loading: Boolean = false,
        val error: String? = null,
        val needsGuardCode: Boolean = false,
        val status: String? = null,
        // Kapan layar Steam Guard pertama muncul pada percobaan login ini (0 = belum). Dasar hitung mundur kirim ulang.
        val guardSince: Long = 0L,
        // Jenis verifikasi yang diminta Steam, untuk teks petunjuk di layar Steam Guard
        val guardType: GuardType? = null,
        // Kode yang baru dikirim ditolak Steam
        val wrongCode: Boolean = false,
        // Waktu menunggu habis, kode atau persetujuan baru harus diminta lewat tombol Kirim ulang
        val guardExpired: Boolean = false,
    ) : UiState()

    data class GameList(val games: List<GameInfo>) : UiState()

    data class Downloading(
        val appId: Int,
        val gameName: String,
        val progress: DownloadProgress,
        val speedBps: Long,
        val speedHistory: List<Long>,
        val paused: Boolean = false,
        val diskBps: Long = 0L,
        val diskHistory: List<Long> = emptyList(),
        val platform: Platform = Platform.WINDOWS,
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

    /** Popup pilih versi (Windows, macOS, Linux). Hanya muncul kalau game punya lebih dari satu versi. */
    private val _platformChoice = MutableStateFlow<PlatformChoice?>(null)
    val platformChoice: StateFlow<PlatformChoice?> = _platformChoice.asStateFlow()

    private val _locationChoice = MutableStateFlow<LocationChoice?>(null)
    val locationChoice: StateFlow<LocationChoice?> = _locationChoice.asStateFlow()

    // Kode QR di layar login. Null selama belum siap. qrFailed: gagal beberapa kali, pengguna bisa mencoba lagi.
    private val _qrUrl = MutableStateFlow<String?>(null)
    val qrUrl: StateFlow<String?> = _qrUrl.asStateFlow()

    private val _qrFailed = MutableStateFlow(false)
    val qrFailed: StateFlow<Boolean> = _qrFailed.asStateFlow()

    private var qrJob: Job? = null

    private var ownedGames: List<GameInfo> = emptyList()
    private var watchingAppId: Int? = null
    private var sizeJob: Job? = null
    private var loginJob: Job? = null
    private var priceJob: Job? = null

    // Kredensial login terakhir, hanya di memori. Dipakai tombol Kirim ulang di layar Steam Guard.
    private var lastUser = ""
    private var lastPass = ""
    private var lastRemember = true
    @Volatile private var guardSince = 0L

    init {
        GamePriceStore.init(app)
        PlatformStore.init(app)
        GameImages.init(app)
        Libraries.init(app)
        // Status download datang dari DownloadService, UI hanya menampilkannya
        viewModelScope.launch {
            DownloadService.status.collect { onDownloadStatus(it) }
        }
    }

    fun savedAccount(): String? = repo.savedAccount()

    fun rememberChoice(): Boolean = repo.rememberChoice()

    /** Keadaan layar Steam Guard. [guardSince] ikut dibawa supaya hitung mundur kirim ulang tidak mulai dari awal. */
    private fun guardState(
        loading: Boolean = false,
        error: String? = null,
        status: String? = null,
        type: GuardType? = null,
        wrongCode: Boolean = false,
    ) = UiState.LoggedOut(
        loading = loading,
        error = error,
        needsGuardCode = true,
        status = status,
        guardSince = guardSince,
        guardType = type,
        wrongCode = wrongCode,
    )

    fun login(username: String, password: String, guardCode: String?, remember: Boolean = true) {
        // Sedang menunggu kode Steam Guard: kirim kodenya ke sesi login yang sudah berjalan,
        // jangan memulai login baru.
        if (repo.isWaitingForGuard) {
            DebugLog.log("Kode Steam Guard dikirim dari layar")
            val type = (_uiState.value as? UiState.LoggedOut)?.guardType
            _uiState.value = if (repo.submitGuardCode(guardCode)) {
                guardState(loading = true, status = "Mengirim kode Steam Guard...", type = type)
            } else {
                guardState(error = "Masukkan kode Steam Guard dulu", type = type)
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

        startLogin(username, password, remember)
    }

    /**
     * Tombol Kirim ulang di layar Steam Guard: sesi login yang lama dibuang, lalu Steam diminta mengirim
     * persetujuan atau kode yang baru. Hanya dijalankan dari tombol itu, tidak pernah otomatis.
     */
    fun resendGuard() {
        if (lastUser.isBlank() || lastPass.isEmpty()) {
            _uiState.value = UiState.LoggedOut(error = "Masukkan nickname dan password lagi")
            return
        }
        DebugLog.log("Tombol Kirim ulang ditekan")
        loginJob?.cancel()
        loginJob = null
        startLogin(lastUser, lastPass, lastRemember)
    }

    /** Kembali dari layar Steam Guard ke layar login, dan menghentikan sesi login yang sedang menunggu. */
    fun cancelLogin() {
        DebugLog.log("Login dibatalkan dari layar Steam Guard")
        loginJob?.cancel()
        loginJob = null
        repo.abortLogin()
        guardSince = 0L
        _uiState.value = UiState.LoggedOut()
    }

    /**
     * Menyiapkan kode QR untuk layar login, kalau belum ada yang berjalan dan tidak sedang login dengan kredensial.
     * [remember] dibaca saat QR disetujui, supaya pilihan "Remember me" terakhir yang dipakai.
     */
    fun ensureQr(remember: () -> Boolean) {
        if (qrJob?.isActive == true || loginJob?.isActive == true) return
        runQr(remember)
    }

    /** Memuat ulang kode QR setelah gagal. */
    fun retryQr(remember: () -> Boolean) {
        qrJob?.cancel()
        runQr(remember)
    }

    private fun runQr(remember: () -> Boolean) {
        _qrFailed.value = false
        _qrUrl.value = null
        qrJob = viewModelScope.launch {
            var failures = 0
            while (isActive) {
                try {
                    withTimeout(QR_TIMEOUT_MS) {
                        repo.loginWithQr(onChallenge = { _qrUrl.value = it }, remember = remember())
                    }
                } catch (e: Exception) {
                    // Dibatalkan pengguna (login dengan kredensial menggantikan QR): bukan error
                    if (e is CancellationException && e !is TimeoutCancellationException) throw e
                    if (e is QrExpiredException || e is TimeoutCancellationException) {
                        DebugLog.log("Kode QR kedaluwarsa, membuat yang baru")
                        _qrUrl.value = null
                        continue
                    }
                    failures++
                    DebugLog.log("ERROR login QR ($failures): ${e.describe()}")
                    if (failures >= QR_MAX_FAILURES) {
                        _qrUrl.value = null
                        _qrFailed.value = true
                        return@launch
                    }
                    _qrUrl.value = null
                    delay(2000L)
                    continue
                }

                // QR dipindai dan disetujui di Steam Mobile
                DebugLog.log("Login QR berhasil, lanjut mengambil daftar game")
                _qrUrl.value = null
                try {
                    openGameList(fromGuard = false)
                } catch (e: Exception) {
                    if (e is CancellationException && e !is TimeoutCancellationException) throw e
                    DebugLog.log("ERROR saat mengambil daftar game: ${e.describe()}")
                    _qrFailed.value = true
                    _uiState.value = UiState.LoggedOut(error = "Gagal saat mengambil daftar game.\nDetail:\n${e.describe()}")
                }
                return@launch
            }
        }
    }

    /** Mengambil daftar game setelah login berhasil, lalu membuka daftarnya. Layar login atau Steam Guard menunggu selama itu. */
    private suspend fun openGameList(fromGuard: Boolean) {
        _uiState.value = if (fromGuard) {
            guardState(loading = true, status = "Mengambil daftar game...")
        } else {
            UiState.LoggedOut(loading = true, status = "Mengambil daftar game...")
        }
        val owned = withTimeout(GAMES_TIMEOUT_MS) { repo.loadOwnedGamesResilient() }
        val games = owned.map { GameInfo(it.appId, it.name) }
        ownedGames = games
        watchingAppId = null
        _uiState.value = UiState.GameList(games)
        loadSizesInBackground(games)
        loadPricesInBackground(games)
    }

    private fun startLogin(username: String, password: String, remember: Boolean) {
        // Login dengan kredensial menggantikan QR yang sedang menunggu
        qrJob?.cancel()
        qrJob = null
        _qrUrl.value = null
        lastUser = username.trim()
        lastPass = password
        lastRemember = remember
        guardSince = 0L

        DebugLog.log("Tombol Login ditekan")
        _uiState.value = UiState.LoggedOut(loading = true, status = "Menghubungkan ke Steam...")
        loginJob = viewModelScope.launch {
            var step = "login ke Steam"
            try {
                withTimeout(LOGIN_TIMEOUT_MS) {
                    repo.login(
                        username = lastUser,
                        password = password,
                        onGuardCodeNeeded = { type, wrongCode ->
                            if (guardSince == 0L) guardSince = System.currentTimeMillis()
                            _uiState.value = if (type == GuardType.APPROVAL) {
                                // Belum ada yang salah: cukup setujui di Steam Mobile. Kode juga bisa diketik.
                                guardState(type = type)
                            } else {
                                guardState(type = type, wrongCode = wrongCode)
                            }
                        },
                        onStatus = { message ->
                            // Hanya di layar login. Layar Steam Guard tetap tampil supaya kode bisa diketik lagi
                            // setelah Steam meminta kode baru.
                            val current = _uiState.value
                            if (current is UiState.LoggedOut) {
                                _uiState.value = current.copy(loading = true, error = null, status = message)
                            }
                        },
                        remember = remember,
                    )
                }

                DebugLog.log("Login berhasil, lanjut mengambil daftar game")
                step = "mengambil daftar game"
                // Kalau login lewat layar Steam Guard, layarnya dipertahankan sampai daftar game siap
                openGameList(fromGuard = guardSince != 0L)
            } catch (e: Exception) {
                // Dibatalkan pengguna (Kirim ulang atau kembali): bukan error, dan layarnya sudah diatur oleh yang membatalkan
                if (e is CancellationException && e !is TimeoutCancellationException) throw e
                val current = _uiState.value
                if (e is TimeoutCancellationException && step == "login ke Steam" &&
                    current is UiState.LoggedOut && current.needsGuardCode
                ) {
                    // Waktu menunggu persetujuan atau kode habis: tetap di layar Steam Guard dan buka tombol Kirim ulang
                    DebugLog.log("Waktu menunggu Steam Guard habis")
                    repo.abortLogin()
                    _uiState.value = current.copy(loading = false, error = null, status = null, wrongCode = false, guardExpired = true)
                    return@launch
                }
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
        // Versi yang tidak tersedia untuk game ini tidak ditawarkan. Kalau hanya ada satu, langsung diunduh.
        val platforms = repo.availablePlatforms(appId).ifEmpty { listOf(Platform.WINDOWS) }
        if (platforms.size > 1) {
            _platformChoice.value = PlatformChoice(
                appId, game.name, platforms.map { PlatformOption(it, repo.knownSize(appId, it)) }
            )
            return
        }
        requestDownload(appId, platforms.first())
    }

    /** Pengguna memilih versi di popup. Lanjut ke pengecekan ruang dan download. */
    fun choosePlatform(appId: Int, platform: Platform) {
        _platformChoice.value = null
        requestDownload(appId, platform)
    }

    fun dismissPlatformChoice() {
        _platformChoice.value = null
    }

    private fun requestDownload(appId: Int, platform: Platform) {
        val game = ownedGames.find { it.appId == appId } ?: return
        // Game yang sudah ada (selesai atau dijeda) tetap di library tempatnya berada. Untuk game baru, pilihan
        // lokasi hanya ditanyakan kalau ada lebih dari satu penyimpanan dengan library. Kalau cuma satu, langsung dipakai.
        val existing = Libraries.locate(appId)
        if (existing != null) {
            beginDownload(appId, platform, existing.library)
            return
        }
        val targets = Libraries.available()
        if (targets.size > 1) {
            _locationChoice.value = LocationChoice(
                appId, game.name, platform,
                targets.map { ref ->
                    LocationOption(
                        path = ref.library.root.path,
                        label = if (ref.volume.primary) "Memori internal" else ref.volume.label,
                        freeBytes = ref.volume.freeBytes,
                    )
                }
            )
            return
        }
        beginDownload(appId, platform, (targets.firstOrNull() ?: Libraries.defaultRef()).library)
    }

    /** Pengguna memilih lokasi simpan di popup. Lanjut ke pengecekan ruang dan download. */
    fun chooseLocation(choice: LocationChoice, option: LocationOption) {
        _locationChoice.value = null
        val ref = Libraries.refFor(option.path)
        if (ref == null) {
            DebugLog.log("Lokasi ${option.path} tidak lagi tersambung")
            return
        }
        beginDownload(choice.appId, choice.platform, ref.library)
    }

    fun dismissLocationChoice() {
        _locationChoice.value = null
    }

    private fun beginDownload(appId: Int, platform: Platform, library: Library) {
        val game = ownedGames.find { it.appId == appId } ?: return
        // Dicatat supaya Lanjutkan setelah jeda memakai library yang sama
        Libraries.rememberTarget(appId, library)
        viewModelScope.launch {
            // Catatan kegiatan untuk panel detail di layar download dimulai dari pengecekan ruang
            DownloadActivity.reset()
            DownloadActivity.step("Mengecek ruang penyimpanan untuk versi ${platform.label}")
            val check = withContext(Dispatchers.IO) { checkStorage(appId, platform, library) }
            if (check != null && check.first > check.second) {
                DebugLog.log("Memori tidak cukup untuk ${game.name}")
                _storageAlert.value = StorageAlert(game.name, check.first, check.second)
            } else {
                if (check != null) {
                    DownloadActivity.step(
                        "Ruang cukup: butuh ${formatBytes(check.first)}, tersedia ${formatBytes(check.second)}"
                    )
                } else {
                    DownloadActivity.step("Ukuran game belum diketahui, download tetap dimulai")
                }
                startDownload(appId, platform)
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
    private suspend fun checkStorage(appId: Int, platform: Platform, library: Library): Pair<Long, Long>? {
        val size = repo.knownSize(appId, platform) ?: try {
            DownloadActivity.step("Menghitung ukuran game dari info Steam")
            repo.computeDownloadSize(appId, platform).also {
                // Hanya ukuran versi bawaan yang masuk ke penyimpanan dan daftar game
                if (platform == repo.defaultPlatform(appId)) GameSizeStore.put(appId, it)
            }
        } catch (e: Exception) {
            DebugLog.log("Ukuran game belum diketahui, download tetap dicoba: ${e.describe()}")
            return null
        }
        // Library mengalokasikan panjang file di awal sebelum datanya tiba, jadi ukuran file di folder tidak
        // menunjukkan berapa yang sudah benar-benar terunduh (itu yang dulu membuat kebutuhan terhitung 0 B).
        // Yang dipakai adalah catatan byte terunduh di appmanifest. Game yang sudah selesai butuh 0 B tambahan,
        // game yang dijeda di 70% hanya butuh sisanya, bukan ukuran penuh lagi.
        val before = library.downloadedBefore(appId, size.download)
        val fraction = if (size.download > 0L) (before.toDouble() / size.download).coerceIn(0.0, 1.0) else 0.0
        val needed = (size.disk * (1.0 - fraction)).toLong()
        val free = library.freeBytes()
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
                    // Jangan menghitung ukuran saat download berjalan. Unduh manifest besar ikut memakai
                    // memori dan jaringan, dan di log terbukti membuatnya timeout.
                    while (DownloadService.status.value is DownloadStatus.Running) delay(2_000L)
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

    fun startDownload(appId: Int, platform: Platform = PlatformStore.get(appId) ?: repo.defaultPlatform(appId)) {
        // Hanya satu download dalam satu waktu. Kalau masih ada yang berjalan, tampilkan progres itu.
        if (DownloadService.status.value is DownloadStatus.Running) {
            DebugLog.log("Masih ada download yang berjalan, menampilkan progresnya")
            openRunningDownload()
            return
        }

        val game = ownedGames.find { it.appId == appId } ?: return
        watchingAppId = appId
        // Dicatat supaya Lanjutkan setelah jeda memakai versi yang sama
        PlatformStore.put(appId, platform)
        _uiState.value = UiState.Downloading(appId, game.name, DownloadProgress(), 0L, emptyList(), platform = platform)

        val ctx = getApplication<Application>()
        ctx.startForegroundService(
            Intent(ctx, DownloadService::class.java)
                .putExtra(DownloadService.EXTRA_APP_ID, appId)
                .putExtra(DownloadService.EXTRA_NAME, game.name)
                .putExtra(DownloadService.EXTRA_OS, platform.steamName)
                .putExtra(DownloadService.EXTRA_LIBRARY, Libraries.resolveFor(appId).library.root.path)
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
        DownloadActivity.reset()
        DownloadActivity.step("Melanjutkan download yang dijeda")
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
        UiState.Downloading(
            appId, name, progress, speedBps, speedHistory,
            diskBps = diskBps, diskHistory = diskHistory,
            platform = PlatformStore.get(appId) ?: Platform.WINDOWS
        )

    private fun DownloadStatus.Paused.toUi(): UiState.Downloading =
        UiState.Downloading(
            appId, name, progress, 0L, emptyList(), paused = true,
            platform = PlatformStore.get(appId) ?: Platform.WINDOWS
        )
}
