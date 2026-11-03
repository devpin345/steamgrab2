package com.example.steamgrab

import android.content.Context
import `in`.dragonbra.javasteam.depotdownloader.DepotDownloader
import `in`.dragonbra.javasteam.depotdownloader.IDownloadListener
import `in`.dragonbra.javasteam.depotdownloader.data.AppItem
import `in`.dragonbra.javasteam.depotdownloader.data.DownloadItem
import `in`.dragonbra.javasteam.enums.EResult
import `in`.dragonbra.javasteam.steam.authentication.AuthPollResult
import `in`.dragonbra.javasteam.steam.authentication.AuthSessionDetails
import `in`.dragonbra.javasteam.steam.authentication.AuthenticationException
import `in`.dragonbra.javasteam.steam.authentication.IAuthenticator
import `in`.dragonbra.javasteam.steam.authentication.CredentialsAuthSession
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesAuthSteamclient.EAuthSessionGuardType
import `in`.dragonbra.javasteam.steam.handlers.steamapps.License
import `in`.dragonbra.javasteam.steam.handlers.steamapps.PICSProductInfo
import `in`.dragonbra.javasteam.steam.handlers.steamapps.PICSRequest
import `in`.dragonbra.javasteam.steam.handlers.steamapps.SteamApps
import `in`.dragonbra.javasteam.steam.handlers.steamapps.callback.LicenseListCallback
import `in`.dragonbra.javasteam.steam.handlers.steamuser.LogOnDetails
import `in`.dragonbra.javasteam.steam.handlers.steamuser.SteamUser
import `in`.dragonbra.javasteam.steam.handlers.steamuser.callback.LoggedOnCallback
import `in`.dragonbra.javasteam.steam.steamclient.SteamClient
import `in`.dragonbra.javasteam.steam.steamclient.callbackmgr.CallbackManager
import `in`.dragonbra.javasteam.steam.steamclient.callbacks.ConnectedCallback
import `in`.dragonbra.javasteam.steam.steamclient.callbacks.DisconnectedCallback
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import `in`.dragonbra.javasteam.steam.cdn.Client as CdnClient
import `in`.dragonbra.javasteam.steam.handlers.steamcontent.SteamContent
import `in`.dragonbra.javasteam.steam.steamclient.AsyncJobFailedException
import `in`.dragonbra.javasteam.types.KeyValue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

data class OwnedGame(val appId: Int, val name: String)

/** DEVICE = kode dari aplikasi authenticator, EMAIL = kode dari email, APPROVAL = menunggu persetujuan di Steam Mobile. */
enum class GuardType { DEVICE, EMAIL, APPROVAL }

/**
 * Depot yang dipilih untuk diunduh. [download] (terkompresi) dan [disk] (setelah dibuka) berasal dari info app
 * Steam dan null kalau info itu tidak memuatnya.
 */
data class DepotChoice(val depotId: Int, val manifestId: Long, val download: Long?, val disk: Long?)

/** Keadaan satu depot dari listener library: byte terkompresi, byte tidak terkompresi, dan persennya (0..1). */
internal class DepotProgress(val compressed: Long, val uncompressed: Long, val percent: Float)

/**
 * Satu sesi Steam per proses. Dipakai bersama oleh ViewModel dan DownloadService.
 * Alurnya mengikuti Sample 023 JavaSteam: login dengan kredensial sekali,
 * lalu refresh token disimpan dan dipakai untuk login berikutnya.
 */
class SteamRepository private constructor(context: Context) {

    private val prefs = context.getSharedPreferences("steam_session", Context.MODE_PRIVATE)
    private val client = SteamClient()
    // Klien CDN untuk menghitung ukuran. JANGAN di-close: Client.close() mematikan executor HTTP yang
    // dipakai bersama oleh semua klien CDN di sesi ini, sehingga download berikutnya gagal dengan "executor rejected".
    private val sizeCdn: CdnClient by lazy { CdnClient(client) }
    private val manager = CallbackManager(client)
    // Error yang tidak tertangkap di coroutine latar belakang dicatat, bukan membuat aplikasi tertutup
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e ->
            DebugLog.log("ERROR latar belakang: ${e.describe()}")
        }
    )

    private var licenses: List<License> = emptyList()

    // Info app dari PICS (depot, manifest, nama folder). Diisi saat daftar game diambil,
    // dipakai untuk menghitung ukuran tanpa meminta info yang sama lagi.
    private val appInfos = ConcurrentHashMap<Int, KeyValue>()

    init {
        GameSizeStore.init(context)
    }
    private var pendingUser: String? = null
    private var pendingPass: String? = null
    private var onGuardNeeded: ((GuardType, Boolean) -> Unit)? = null
    @Volatile private var pendingGuard: CompletableFuture<String>? = null
    // Sesi login yang sudah dibuat di Steam dan belum selesai (menunggu persetujuan di Steam Mobile atau kode).
    // Kode juga bisa dikirim langsung ke sesi ini. Sesi ini DIPERTAHANKAN saat koneksi putus, misalnya ketika pengguna
    // berpindah ke aplikasi Steam Mobile untuk menyetujui. Setelah tersambung lagi, sesi yang sama dilanjutkan.
    // Membuat sesi baru akan mengirim permintaan persetujuan baru ke Steam Mobile (dan email kode baru).
    @Volatile private var activeSession: CredentialsAuthSession? = null
    @Volatile private var awaitingApproval = false
    // Nomor percobaan login. Naik setiap login baru dari tombol Login, sehingga sesi dan loop tunggu milik percobaan
    // lama berhenti sendiri. Sambung ulang otomatis tidak menaikkannya, jadi sesi yang sama dilanjutkan.
    private val authGeneration = AtomicInteger(0)
    private val authLock = Any()
    // Nomor percobaan yang sedang menjalankan loop tunggu persetujuan, -1 kalau tidak ada
    private var authRunningGen = -1
    @Volatile private var connected = false
    // True dari balasan logOn OK sampai koneksi putus lagi. Dipakai untuk menunggu sesi pulih setelah sambung ulang.
    @Volatile private var sessionActive = false
    // Mencegah dua pemanggilan client.connect() bersamaan. connect() di library memakai assert, jadi sambung ulang
    // otomatis yang bertabrakan dengan tombol Login menutup aplikasi di build debug.
    private val connectLock = Any()
    @Volatile private var connecting = false
    @Volatile private var connectingSince = 0L
    private var pumpStarted = false
    // Dikirim ke UI saat status koneksi berubah (misalnya sedang menyambung ulang saat menunggu kode)
    @Volatile private var onStatus: ((String) -> Unit)? = null

    private var loggedOn = CompletableDeferred<Unit>()
    private var licenseSignal = CompletableDeferred<List<License>>()

    init {
        manager.subscribe(ConnectedCallback::class.java) {
            connected = true
            connecting = false
            DebugLog.log("Terhubung ke server Steam")
            scope.launch { startSession() }
        }

        manager.subscribe(DisconnectedCallback::class.java) { cb ->
            connected = false
            sessionActive = false
            DebugLog.log("Koneksi ke Steam terputus (dibatalkan pengguna: ${cb.isUserInitiated})")
            if (!cb.isUserInitiated) {
                connecting = false
                // Kode yang sedang ditunggu ikut batal. Setelah tersambung, kode diminta lagi dari sesi yang sama.
                // activeSession dan awaitingApproval sengaja dipertahankan: loop tunggu melanjutkan sesi itu,
                // bukan membuat sesi baru yang memunculkan permintaan persetujuan kedua di Steam Mobile.
                pendingGuard?.cancel(false)
                pendingGuard = null
                onStatus?.invoke("Koneksi ke Steam terputus, menyambung ulang...")
                scope.launch {
                    delay(2000L)
                    connectIfIdle()
                }
            }
        }

        manager.subscribe(LoggedOnCallback::class.java) { cb ->
            DebugLog.log("Steam membalas logOn: ${cb.result}")
            if (cb.result == EResult.OK) {
                sessionActive = true
                loggedOn.complete(Unit)
            } else {
                // Token mungkin sudah tidak valid, hapus supaya login berikutnya minta kredensial lagi
                prefs.edit().remove(KEY_TOKEN).remove(KEY_ACCOUNT).apply()
                loggedOn.completeExceptionally(IllegalStateException("Login gagal: ${cb.result}"))
            }
        }

        manager.subscribe(LicenseListCallback::class.java) { cb ->
            DebugLog.log("Daftar lisensi diterima: ${cb.licenseList.size} item (${cb.result})")
            if (cb.result == EResult.OK) {
                licenses = cb.licenseList
                licenseSignal.complete(cb.licenseList)
            } else {
                licenseSignal.completeExceptionally(IllegalStateException("Gagal mengambil daftar lisensi: ${cb.result}"))
            }
        }
    }

    /**
     * Login ke Steam. Jika akun butuh Steam Guard, [onGuardCodeNeeded] dipanggil
     * dan UI harus memanggil [submitGuardCode] dengan kode yang diketik pengguna.
     */
    suspend fun login(
        username: String,
        password: String,
        onGuardCodeNeeded: (GuardType, Boolean) -> Unit,
        onStatus: (String) -> Unit = {},
    ) {
        // Login baru dari tombol Login: sisa sesi dari percobaan sebelumnya dibuang. Hanya sambung ulang otomatis
        // yang melanjutkan sesi lama.
        authGeneration.incrementAndGet()
        activeSession = null
        awaitingApproval = false
        pendingGuard?.cancel(false)
        pendingGuard = null

        pendingUser = username
        pendingPass = password
        onGuardNeeded = onGuardCodeNeeded
        this.onStatus = onStatus
        loggedOn = CompletableDeferred()
        licenseSignal = CompletableDeferred()
        sessionActive = false

        DebugLog.log("login: mulai (sudah terhubung: $connected, sedang menyambung: $connecting)")
        withContext(Dispatchers.IO) {
            ensurePump()
            if (connected) {
                scope.launch { startSession() }
            } else {
                // Kalau sambung ulang otomatis sedang berjalan, tidak menyambung lagi. Saat tersambung,
                // ConnectedCallback menjalankan startSession dengan kredensial yang sudah disimpan di atas.
                connectIfIdle()
            }
        }

        loggedOn.await()
        DebugLog.log("login: loggedOn diterima")
        licenseSignal.await()
        DebugLog.log("login: daftar lisensi diterima")
    }

    /** Menyambung ke Steam kalau belum tersambung dan belum ada percobaan sambung yang berjalan. */
    private fun connectIfIdle() {
        synchronized(connectLock) {
            val stale = System.currentTimeMillis() - connectingSince > CONNECT_STALE_MS
            if (connected || (connecting && !stale)) {
                DebugLog.log("Sambungan ke Steam sudah ada atau sedang dibuat, tidak menyambung lagi")
                return
            }
            connecting = true
            connectingSince = System.currentTimeMillis()
        }
        try {
            client.connect()
        } catch (e: Throwable) {
            // AssertionError dari library adalah Error, bukan Exception, jadi harus ditangkap di sini
            connecting = false
            DebugLog.log("ERROR saat menyambung ke Steam: ${e.describe()}")
            throw IllegalStateException("Gagal menyambung ke Steam: ${e.message ?: e.javaClass.simpleName}", e)
        }
    }

    /**
     * Menunggu sampai login ke Steam aktif lagi, misalnya setelah koneksi putus dan tersambung ulang.
     * Status koneksi milik library ikut diperiksa: pekerjaan yang sedang berjalan gagal lebih dulu daripada
     * callback "terputus" diproses, jadi sessionActive saja bisa masih bernilai true padahal sudah putus.
     */
    private suspend fun awaitSession(timeoutMs: Long): Boolean =
        withTimeoutOrNull(timeoutMs) {
            while (!(sessionActive && client.isConnected)) delay(300L)
            true
        } ?: false

    /**
     * Seperti [loadOwnedGames], tapi kalau koneksi putus di tengah jalan (misalnya karena pengguna berpindah
     * aplikasi untuk menyetujui login), menunggu sampai tersambung lagi lalu mengulang, bukan langsung gagal.
     */
    suspend fun loadOwnedGamesResilient(): List<OwnedGame> {
        var attempt = 0
        while (true) {
            try {
                return loadOwnedGames()
            } catch (e: Exception) {
                // Kalau coroutine ini sendiri yang dibatalkan, jangan diulang
                currentCoroutineContext().ensureActive()
                if (attempt >= MAX_RECONNECT_RETRIES || !e.isConnectionLoss()) throw e
                attempt++
                DebugLog.log("Koneksi putus saat mengambil daftar game, menunggu sambung ulang (percobaan $attempt)")
                onStatus?.invoke("Koneksi ke Steam terputus, menyambung ulang...")
                if (!awaitSession(RECONNECT_WAIT_MS)) throw e
                onStatus?.invoke("Mengambil daftar game...")
            }
        }
    }

    /** True jika library sedang menunggu kode Steam Guard dari pengguna. */
    val isWaitingForGuard: Boolean
        get() = pendingGuard != null || awaitingApproval

    /** Mengembalikan true jika kode dipakai untuk pertanyaan Steam Guard yang sedang menunggu. */
    fun submitGuardCode(code: String?): Boolean {
        if (code.isNullOrBlank()) return false

        val future = pendingGuard
        if (future != null) {
            pendingGuard = null
            DebugLog.log("Kode Steam Guard diteruskan ke library")
            future.complete(code.trim())
            return true
        }

        val session = activeSession
        if (awaitingApproval && session != null) {
            // Sesi masih menunggu persetujuan di Steam Mobile. Kode dikirim langsung ke sesi itu,
            // bukan memulai login baru (login baru akan memunculkan permintaan baru di Steam Mobile).
            DebugLog.log("Kode Steam Guard dikirim ke sesi yang menunggu persetujuan")
            scope.launch {
                try {
                    session.sendSteamGuardCode(
                        code.trim(),
                        EAuthSessionGuardType.k_EAuthSessionGuardType_DeviceCode,
                        scope
                    ).get()
                    DebugLog.log("Kode diterima Steam, menunggu hasil login")
                } catch (e: Exception) {
                    DebugLog.log("Kode ditolak Steam: ${e.describe()}")
                    if (!e.isConnectionLoss()) onGuardNeeded?.invoke(GuardType.DEVICE, true)
                }
            }
            return true
        }
        return false
    }

    /** Daftar game yang dimiliki akun, nama diambil dari info app Steam. */
    suspend fun loadOwnedGames(): List<OwnedGame> = withContext(Dispatchers.IO) {
        val steamApps = client.getHandler(SteamApps::class.java) ?: error("Sesi Steam belum siap")
        DebugLog.log("Mengambil daftar game dari Steam...")

        // License menunjuk ke package, lalu package berisi daftar app ID
        val packageInfo = steamApps.picsGetProductInfo(
            emptyList<PICSRequest>(),
            licenses.map { PICSRequest(it.packageID, it.accessToken) }
        ).await()

        val appIds = packageInfo.results
            .flatMap { it.packages.values }
            .flatMap { pkg -> pkg.keyValues["appids"].children.map { it.asInteger() } }
            .distinct()

        val tokens = steamApps.picsGetAccessTokens(appIds, emptyList<Int>()).await().appTokens
        val names = HashMap<Int, String>()
        val types = HashMap<Int, String>()

        appIds.chunked(200).forEach { chunk ->
            val res = steamApps.picsGetProductInfo(
                chunk.map { PICSRequest(it, tokens[it] ?: 0L) },
                emptyList<PICSRequest>()
            ).await()
            res.results.flatMap { it.apps.values }.forEach { app ->
                app.keyValues["common"]["name"].asString()
                    ?.takeIf { it.isNotBlank() }
                    ?.let { names[app.id] = it }
                app.keyValues["common"]["type"].asString()?.let { types[app.id] = it.lowercase() }
                appInfos[app.id] = app.keyValues
            }
        }

        DebugLog.log("Daftar app: ${appIds.size} ID, ${types.size} punya tipe")

        appIds
            // Hanya game. DLC, demo, dan tool disembunyikan. Tipe yang tidak diketahui tetap ditampilkan.
            .filter { types[it] == null || types[it] == "game" }
            // Game tanpa depot untuk Windows 64-bit (misalnya server dedicated) tidak bisa diunduh, jadi disembunyikan
            .filter { appInfos[it]?.let { kv -> selectedDepots(kv).isNotEmpty() } ?: true }
            .map { OwnedGame(it, names[it] ?: "App $it") }
            .sortedBy { it.name.lowercase() }
    }

    /**
     * Mengunduh satu game ke Download/SteamGames/steamapps/common/<installdir>,
     * lalu menulis appmanifest_<appid>.acf. Melempar exception jika gagal atau dibatalkan.
     * Pembatalan dilakukan dengan membatalkan coroutine pemanggilnya.
     * [onProgress] dipanggil setiap ada chunk yang selesai, dengan progres semua depot digabung.
     */
    suspend fun downloadGame(appId: Int, onProgress: (DownloadProgress) -> Unit): File =
        withContext(Dispatchers.IO) {
            DebugLog.log("Download game $appId dimulai")
            val depots = ConcurrentHashMap<Int, DepotProgress>()
            // Byte yang sudah masuk per depot pada sesi ini, dalam bentuk yang dipakai appmanifest
            val snapshot = { depots.mapValues { it.value.compressed to it.value.uncompressed } }

            // Yang sudah diunduh pada sesi sebelumnya (dijeda atau dibatalkan) dibaca dari appmanifest sebelum
            // appmanifest ditulis ulang di bawah. Library memakai ulang file yang sudah valid, jadi hitungan
            // kita harus ikut melanjutkan dari situ, bukan mulai dari nol.
            val knownTotal = GameSizeStore.get(appId)?.download ?: 0L
            val previous = Storage.downloadedBefore(appId, knownTotal)
            // Game yang sudah tercatat selesai lalu diperiksa ulang tidak boleh langsung tampil 100%: yang terjadi
            // adalah pemeriksaan file, dan angka sebenarnya baru datang dari library sesudah itu.
            val installedBefore = Storage.appStates()[appId]?.let { Storage.isComplete(it) } == true
            val prior = if (installedBefore) 0L else previous
            // Selama pemeriksaan ulang, catatan di appmanifest tidak boleh menyusut kalau pemeriksaan dihentikan
            val persistFloor = if (installedBefore) previous else 0L
            // Ukuran tiap depot (tidak terkompresi) dari info app, untuk membobot persen dari library
            val depotDisk: Map<Int, Long> = appInfos[appId]
                ?.let { kv -> selectedDepots(kv).mapNotNull { d -> d.disk?.let { d.depotId to it } }.toMap() }
                ?: emptyMap()
            // Progres tertinggi yang pernah ditampilkan. Sebelum semua depot melapor tidak boleh mundur.
            val lastDone = AtomicLong(prior)
            // Baru true setelah library dan penulisan appmanifest benar-benar selesai. Sebelum itu persen dibatasi 99%.
            val completed = AtomicBoolean(false)
            val finishedDepots: MutableSet<Int> = ConcurrentHashMap.newKeySet()
            // Tahap yang sudah diumumkan di panel detail, supaya tiap tahap hanya dicatat sekali
            val announced: MutableSet<String> = ConcurrentHashMap.newKeySet()

            DownloadActivity.setDepotsTotal(depotDisk.size)
            if (installedBefore) {
                DownloadActivity.step("Game ini sudah tercatat selesai. Semua file akan diperiksa ulang")
            } else if (prior > 0L) {
                DownloadActivity.step("Melanjutkan. Catatan sesi sebelumnya: ${formatBytes(prior)} sudah masuk")
            }

            // Library dan appmanifest ditulis sejak awal. Selama download belum selesai, StateFlags bernilai
            // "belum selesai", jadi Steam di PC tahu game ini belum lengkap dan tidak menganggapnya terinstal.
            DownloadActivity.step("Membuat folder library dan catatan awal (appmanifest)")
            runCatching {
                Storage.ensureLibrary()
                writeAppManifest(appId, emptyMap(), complete = false, downloaded = maxOf(prior, persistFloor))
            }.onSuccess {
                DownloadActivity.step("Folder siap: Download/SteamGames/steamapps (common, libraryfolder.vdf, appmanifest_$appId.acf)")
            }.onFailure {
                DebugLog.log("Gagal menulis appmanifest awal: ${it.describe()}")
                DownloadActivity.step("Gagal membuat folder atau appmanifest awal: ${it.describe().lineSequence().first()}")
            }
            val failure = AtomicReference<Throwable?>(null)
            // Tahap yang sedang dikerjakan library sebelum chunk pertama masuk (manifest, alokasi, validasi)
            val phase = AtomicReference("Menyiapkan download...")

            val reportProgress = {
                val s = summarize(depots, GameSizeStore.get(appId)?.download, prior, depotDisk)
                val isCompleted = completed.get()
                // 100% hanya setelah semuanya selesai. Sebelum itu ada pekerjaan penutup (menunggu penulisan terakhir,
                // menghapus file lama, menulis appmanifest) yang bisa memakan waktu lama.
                val cap = if (isCompleted) 1.0 else MAX_FRACTION_BEFORE_DONE
                val rawDone = (minOf(s.fraction, cap) * s.total).toLong()
                // Sebelum semua depot melapor, persen dari library belum lengkap dan angkanya bisa turun sesaat,
                // jadi yang ditampilkan hanya boleh naik. Sesudah semua melapor, angka library sudah lengkap dan
                // boleh mengoreksi perkiraan yang terlalu tinggi.
                val done = if (s.exact) {
                    lastDone.set(rawDone)
                    rawDone
                } else {
                    lastDone.accumulateAndGet(rawDone) { a, b -> if (a > b) a else b }
                }
                val percent = when {
                    isCompleted -> 1f
                    s.total > 0.0 -> (done.toDouble() / s.total).toFloat().coerceIn(0f, MAX_FRACTION_BEFORE_DONE.toFloat())
                    else -> 0f
                }
                val finishing = !isCompleted && depotDisk.isNotEmpty() && depotDisk.keys.all { it in finishedDepots }
                onProgress(
                    DownloadProgress(
                        doneBytes = if (isCompleted && s.total > 0.0) s.total.toLong() else done,
                        totalBytes = s.total.toLong(),
                        percent = percent,
                        phase = phase.get(),
                        runBytes = s.thisRun.toLong(),
                        finishing = finishing,
                        writtenBytes = s.written.toLong(),
                    )
                )
            }
            // Tampilkan hasil sesi sebelumnya segera, sebelum library selesai memeriksa file yang ada
            reportProgress()

            val listener = object : IDownloadListener {
                override fun onStatusUpdate(message: String) {
                    val (text, cat) = phaseOf(message)
                    phase.set(text)
                    val fileName = message.substringAfter(": ", "")
                    when (cat) {
                        "manifest" -> {
                            // Satu pesan per depot, jumlahnya sedikit, jadi semuanya dicatat
                            val depotId = message.removePrefix("Downloading manifest for depot ").trim()
                            DownloadActivity.step("Mengunduh daftar file dari Steam (manifest depot $depotId)")
                        }
                        "alokasi" -> {
                            DownloadActivity.fileAllocated()
                            if (announced.add(cat)) {
                                DownloadActivity.step("Menyiapkan file di penyimpanan (mengalokasikan ruang untuk tiap file)")
                            }
                            DownloadActivity.detail("Menyiapkan file: ${shortFileName(fileName)}")
                        }
                        "validasi" -> {
                            DownloadActivity.fileChecked()
                            if (announced.add(cat)) {
                                DownloadActivity.step("Memeriksa ulang file yang sudah ada (cek checksum tiap bagian)")
                            }
                            DownloadActivity.detail("Memeriksa file: ${shortFileName(fileName)}")
                        }
                        else -> DownloadActivity.detail(text)
                    }
                }

                override fun onItemAdded(item: DownloadItem) {
                    DownloadActivity.step("Permintaan download diterima library")
                }

                override fun onDownloadStarted(item: DownloadItem) {
                    DownloadActivity.step("Library mulai memproses: memilih depot dan mengambil daftar file")
                }

                override fun onChunkCompleted(
                    depotId: Int,
                    depotPercentComplete: Float,
                    compressedBytes: Long,
                    uncompressedBytes: Long
                ) {
                    depots[depotId] = DepotProgress(compressedBytes, uncompressedBytes, depotPercentComplete)
                    markDataActivity(announced)
                    reportProgress()
                }

                override fun onFileCompleted(depotId: Int, fileName: String, depotPercentComplete: Float) {
                    // Untuk file kecil yang hanya terdiri dari satu chunk, persen hanya datang dari sini
                    val prev = depots[depotId]
                    depots[depotId] = DepotProgress(
                        prev?.compressed ?: 0L,
                        prev?.uncompressed ?: 0L,
                        depotPercentComplete
                    )
                    DownloadActivity.fileWritten()
                    markDataActivity(announced)
                    DownloadActivity.detail("$DATA_ACTIVITY (file terakhir selesai: ${shortFileName(fileName)})")
                    reportProgress()
                }

                override fun onDepotCompleted(depotId: Int, compressedBytes: Long, uncompressedBytes: Long) {
                    depots[depotId] = DepotProgress(compressedBytes, uncompressedBytes, 1f)
                    finishedDepots.add(depotId)
                    DownloadActivity.depotDone()
                    DownloadActivity.step("Depot $depotId selesai (${formatBytes(compressedBytes)} terunduh)")
                    if (depotDisk.isNotEmpty() && depotDisk.keys.all { it in finishedDepots }) {
                        DownloadActivity.step(
                            "Semua depot selesai. Library merapikan folder: menunggu penulisan terakhir, " +
                                "menghapus file lama, menyimpan catatan. Tahap ini bisa memakan waktu"
                        )
                    }
                    reportProgress()
                }

                override fun onDownloadFailed(item: DownloadItem, error: Throwable) {
                    DebugLog.log("Library gagal: ${error.describe()}")
                    failure.set(error)
                }
            }

            // Urutan parameter di JavaSteam 1.8.0: maxDownloads, maxDecompress, maxFileWrites.
            // Angka sebelumnya (50 dan 20) membuat memori aplikasi habis: tiap thread yang mendekompresi
            // chunk menyimpan buffer 8 MB sendiri (VZipUtil), sedangkan batas memori aplikasi hanya 256 MB.
            // Kecepatan tetap dibatasi jaringan, jadi angka kecil sudah cukup.
            val downloader = DepotDownloader(
                client, licenses, false, false,
                maxDownloads = 6,
                maxDecompress = 2,
                maxFileWrites = 2
            )
            try {
                DownloadActivity.step("Menghubungi Steam untuk memulai download")
                downloader.addListener(listener)
                downloader.add(
                    AppItem(
                        appId = appId,
                        installToGameNameDirectory = true,
                        installDirectory = Storage.commonDir().absolutePath,
                        branch = "public",
                        os = "windows",
                        osArch = "64",
                        language = "english",
                    )
                )
                downloader.finishAdding()

                // Memakai delay (bisa dibatalkan), bukan awaitCompletion() yang memblokir
                val completion = downloader.getCompletion()
                // Berhenti juga kalau library sudah melaporkan kegagalan, supaya tidak menunggu selamanya
                while (!completion.isDone && failure.get() == null) {
                    delay(500L)
                }
                failure.get()?.let { throw friendlyError(it) }
                completion.get()

                DownloadActivity.step("Library selesai. Mengambil info app untuk menulis appmanifest")
                val app = fetchAppInfo(appId) ?: error("Info app $appId tidak ditemukan di Steam")
                val installDir = app.keyValues["config"]["installdir"].asString()
                    ?: error("Nama folder install untuk app $appId kosong")

                DownloadActivity.step("Menulis appmanifest_$appId.acf sebagai game terinstal (StateFlags 4)")
                writeAppManifest(appId, snapshot(), complete = true, kv = app.keyValues)
                completed.set(true)
                reportProgress()
                DownloadActivity.step("Selesai")
                Storage.gameDir(installDir)
            } catch (e: Throwable) {
                // Dijeda, dibatalkan, atau gagal: manifest tetap bertanda belum selesai, dengan jumlah byte yang sudah masuk
                val stopped = if (e is java.util.concurrent.CancellationException) "Dihentikan (jeda atau batal)" else "Gagal"
                DownloadActivity.step("$stopped. Menyimpan catatan progres ke appmanifest")
                runCatching {
                    writeAppManifest(appId, snapshot(), complete = false, downloaded = maxOf(lastDone.get(), persistFloor))
                }
                throw e
            } finally {
                downloader.close()
            }
        }

    /** Nama folder install game (config/installdir), atau null kalau info app belum ada. */
    fun installDirOf(appId: Int): String? =
        appInfos[appId]?.let { it["config"]["installdir"].asString() }

    /**
     * Menghitung ukuran game. Cara cepat: info app dari Steam biasanya sudah memuat ukuran tiap depot
     * (manifests/public/download dan size), jadi tidak ada yang perlu diunduh. Kalau ada depot yang
     * tidak menyertakan ukuran, jatuh ke cara lama: mengunduh manifest depot itu.
     * Pilihan depot sama dengan yang dipakai library saat download.
     */
    suspend fun computeDownloadSize(appId: Int): GameSize = withContext(Dispatchers.IO) {
        val kv = appInfos[appId] ?: error("Info app $appId belum dimuat")
        val depots = selectedDepots(kv)
        if (depots.isEmpty()) return@withContext GameSize(0L, 0L)

        if (depots.all { it.download != null && it.disk != null }) {
            val quick = GameSize(depots.sumOf { it.download ?: 0L }, depots.sumOf { it.disk ?: 0L })
            DebugLog.log(
                "Ukuran app $appId dari info app: unduh ${formatBytes(quick.download)}, disk ${formatBytes(quick.disk)}"
            )
            return@withContext quick
        }
        DebugLog.log("Info app $appId tidak memuat ukuran semua depot, mengunduh manifest")

        val steamContent = client.getHandler(SteamContent::class.java) ?: error("Sesi Steam belum siap")

        val server = steamContent.getServersForSteamPipe(
            cellId = client.cellID ?: 0,
            parentScope = this
        ).await()
            .filter { (it.type == "SteamCache" || it.type == "CDN") && !it.useAsProxy }
            .filter { it.allowedAppIds.isEmpty() || it.allowedAppIds.contains(appId) }
            .minByOrNull { it.weightedLoad }
            ?: error("Tidak ada server CDN yang tersedia")

        val cdn = sizeCdn
        var download = 0L
        var disk = 0L
        for ((depotId, manifestId) in depots) {
            // Kode 0 bukan error. Library juga memakai URL tanpa kode dalam kasus itu.
            val code = steamContent
                .getManifestRequestCode(depotId, appId, manifestId, "public", null, this)
                .await()

            // Coba tanpa token dulu. Token baru diminta kalau server menolak, sama seperti yang dilakukan library.
            val manifest = try {
                cdn.downloadManifest(
                    depotId = depotId,
                    manifestId = manifestId,
                    manifestRequestCode = code.toULong(),
                    server = server,
                )
            } catch (e: Exception) {
                if (!isActive) throw e
                DebugLog.log("Manifest depot $depotId ditolak tanpa token, mencoba dengan token: ${e.describe()}")
                val auth = steamContent.getCDNAuthToken(appId, depotId, server.host!!, this).await()
                if (auth.result != EResult.OK) error("Token CDN depot $depotId ditolak: ${auth.result}")
                cdn.downloadManifest(
                    depotId = depotId,
                    manifestId = manifestId,
                    manifestRequestCode = code.toULong(),
                    server = server,
                    cdnAuthToken = auth.token,
                )
            }
            download += manifest.totalCompressedSize
            disk += manifest.totalUncompressedSize
        }
        GameSize(download, disk)
    }

    /** Depot untuk Windows 64-bit bahasa Inggris, beserta ID manifest publiknya. */
    private fun selectedDepots(kv: KeyValue): List<DepotChoice> =
        kv["depots"].children.mapNotNull { section ->
            val depotId = section.name?.toIntOrNull() ?: return@mapNotNull null
            if (section.children.isEmpty()) return@mapNotNull null

            val cfg = section["config"]
            if (cfg != KeyValue.INVALID) {
                val osList = cfg["oslist"].value
                if (!osList.isNullOrBlank() && "windows" !in osList.split(",")) return@mapNotNull null
                val osArch = cfg["osarch"].value
                if (!osArch.isNullOrBlank() && osArch != "64") return@mapNotNull null
                val language = cfg["language"].value
                if (!language.isNullOrBlank() && language != "english") return@mapNotNull null
                if (cfg["lowviolence"].asBoolean()) return@mapNotNull null
            }

            val manifest = section["manifests"]["public"]
            val manifestId = manifest["gid"].asString()?.toLongOrNull()
                ?: return@mapNotNull null
            DepotChoice(
                depotId = depotId,
                manifestId = manifestId,
                download = manifest["download"].asString()?.toLongOrNull(),
                disk = manifest["size"].asString()?.toLongOrNull()
            )
        }

    // ---- helper ----

    /**
     * Menggabungkan progres semua depot. Total diperkirakan dari persen tiap depot,
     * jadi masih perkiraan sampai ukuran dari manifest tersedia.
     * [Summary.exact] true kalau semua depot sudah melapor: persen dari library lalu lengkap dan dipakai apa adanya.
     */
    private fun summarize(
        depots: Map<Int, DepotProgress>,
        knownTotal: Long?,
        prior: Long,
        depotDisk: Map<Int, Long>,
    ): Summary {
        var thisRun = 0.0
        var written = 0.0
        var estimatedTotal = 0.0
        for (d in depots.values) {
            thisRun += d.compressed
            written += d.uncompressed
            if (d.percent > 0f) estimatedTotal += d.compressed / d.percent.toDouble()
        }
        // Kalau ukuran dari manifest sudah diketahui, pakai itu. Lebih akurat daripada perkiraan dari persen.
        val total = if (knownTotal != null && knownTotal > 0L) knownTotal.toDouble() else estimatedTotal
        if (total <= 0.0) return Summary(fraction = 0.0, total = 0.0, thisRun = thisRun, written = written, exact = false)

        // Sesi sebelumnya ditambah yang masuk sekarang. Library hanya mengunduh chunk yang belum ada,
        // jadi byte yang dilaporkannya hanya dari sesi ini.
        val byBytes = ((prior + thisRun) / total).coerceIn(0.0, 1.0)

        // Persen dari library ikut menghitung data yang sudah ada di folder, jadi lebih benar untuk folder yang
        // sudah terisi (misalnya dari sebelum ada catatan di appmanifest).
        val diskTotal = depotDisk.values.sum()
        if (diskTotal <= 0L) return Summary(fraction = byBytes, total = total, thisRun = thisRun, written = written, exact = false)

        var weighted = 0.0
        for ((depotId, disk) in depotDisk) {
            weighted += (depots[depotId]?.percent ?: 0f).coerceIn(0f, 1f) * disk.toDouble()
        }
        val byLibrary = (weighted / diskTotal).coerceIn(0.0, 1.0)

        // Setelah semua depot melapor, angka library lengkap. Itu yang paling dekat dengan kenyataan, termasuk
        // kalau perkiraan dari byte terkompresi terlalu tinggi (ukuran unduhan dari info app bisa meleset).
        val allReported = depotDisk.keys.all { depots.containsKey(it) }
        return if (allReported) {
            Summary(fraction = byLibrary, total = total, thisRun = thisRun, written = written, exact = true)
        } else {
            Summary(fraction = maxOf(byBytes, byLibrary), total = total, thisRun = thisRun, written = written, exact = false)
        }
    }

    /** Hasil penggabungan progres: [fraction] 0..1 dari [total] byte, [thisRun] byte terkompresi yang masuk sesi ini. */
    private class Summary(
        val fraction: Double,
        val total: Double,
        val thisRun: Double,
        val written: Double,
        val exact: Boolean,
    )

    /** Dipanggil tiap ada data yang masuk. Mengumumkan tahap data satu kali dan menjaga baris kegiatan saat ini. */
    private fun markDataActivity(announced: MutableSet<String>) {
        if (announced.add("data")) {
            DownloadActivity.step(
                "Mulai mengunduh data game. Tiap bagian diunduh, didekompresi, lalu ditulis ke file, " +
                    "semuanya berjalan bersamaan"
            )
        }
        if (!DownloadActivity.now.value.text.startsWith(DATA_ACTIVITY)) DownloadActivity.detail(DATA_ACTIVITY)
    }

    /** Menerjemahkan pesan tahap dari library ke teks Indonesia, plus kategorinya untuk keperluan log. */
    private fun phaseOf(message: String): Pair<String, String> = when {
        message.startsWith("Downloading manifest") -> "Mengunduh daftar file dari Steam..." to "manifest"
        message.startsWith("Allocating file") -> "Menyiapkan file di penyimpanan..." to "alokasi"
        message.startsWith("Validating") -> "Memeriksa file yang sudah ada..." to "validasi"
        else -> "Menyiapkan download..." to "lain"
    }

    /** Kegagalan karena koneksi putus (termasuk di dalam cause), bukan karena password atau kode salah. */
    private fun Throwable.isConnectionLoss(): Boolean =
        generateSequence(this) { it.cause }.any {
            it is AsyncJobFailedException || it is java.util.concurrent.CancellationException
        }

    /** Pesan yang lebih jelas untuk error dari library. */
    private fun friendlyError(e: Throwable): Throwable =
        if (generateSequence(e) { it.cause }.any { it.message?.contains("find any depots") == true }) {
            IllegalStateException("Game ini tidak punya file untuk Windows 64-bit")
        } else {
            e
        }

    private fun ensurePump() {
        if (pumpStarted) return
        pumpStarted = true
        // Callback harus diproses terus-menerus. Handler callback tidak boleh memblokir
        // (itu sebabnya proses login dijalankan di scope terpisah).
        scope.launch {
            while (isActive) {
                try {
                    manager.runWaitCallbacks(1000L)
                } catch (e: Exception) {
                    // Satu callback yang gagal tidak boleh menghentikan seluruh pemrosesan pesan Steam
                    DebugLog.log("ERROR di pemroses pesan Steam: ${e.describe()}")
                }
            }
        }
    }

    private suspend fun startSession() {
        val gen = authGeneration.get()
        try {
            val storedAccount = prefs.getString(KEY_ACCOUNT, null)
            val storedToken = prefs.getString(KEY_TOKEN, null)
            val typedUser = pendingUser

            if (storedAccount != null && storedToken != null &&
                (typedUser == null || typedUser.equals(storedAccount, ignoreCase = true))
            ) {
                logOn(storedAccount, storedToken)
                return
            }

            // Satu loop login per percobaan. Panggilan kedua (misalnya dari ConnectedCallback saat loop yang sama
            // masih menunggu sambungan pulih) tidak boleh membuat sesi baru.
            synchronized(authLock) {
                if (authRunningGen == gen) {
                    DebugLog.log("Login masih berjalan, tidak membuat sesi baru")
                    return
                }
                authRunningGen = gen
            }
            try {
                val existing = activeSession
                val session = if (existing != null) {
                    DebugLog.log("Melanjutkan sesi login yang sama setelah tersambung lagi (tanpa permintaan baru)")
                    existing
                } else {
                    val user = typedUser ?: error("Silakan login ulang")
                    val pass = pendingPass ?: error("Silakan login ulang")
                    val created = withContext(Dispatchers.IO) {
                        val details = AuthSessionDetails().apply {
                            username = user
                            password = pass
                            deviceFriendlyName = "SteamGrab Android"
                            persistentSession = true
                            authenticator = guardAuthenticator
                        }
                        DebugLog.log("Mengirim login kredensial ke Steam...")
                        client.authentication.beginAuthSessionViaCredentials(details).get()
                    }
                    DebugLog.log("Sesi login dibuat, menunggu verifikasi...")
                    if (gen != authGeneration.get()) throw StaleLoginException()
                    activeSession = created
                    created
                }

                val result = awaitAuthResult(session, gen)
                if (gen != authGeneration.get()) throw StaleLoginException()
                DebugLog.log("Verifikasi selesai, token diterima")

                awaitingApproval = false
                activeSession = null
                prefs.edit()
                    .putString(KEY_ACCOUNT, result.accountName)
                    .putString(KEY_TOKEN, result.refreshToken)
                    .apply()
                logOn(result.accountName, result.refreshToken)
            } finally {
                synchronized(authLock) { if (authRunningGen == gen) authRunningGen = -1 }
            }
        } catch (e: StaleLoginException) {
            DebugLog.log("Login lama dihentikan karena login baru dimulai")
        } catch (e: java.util.concurrent.CancellationException) {
            // Koneksi putus saat membuat sesi membatalkan permintaan ke Steam. Dicoba lagi otomatis
            // saat tersambung kembali, jadi error tidak ditampilkan dulu.
            DebugLog.log("Login terhenti karena koneksi putus, menunggu sambung ulang")
        } catch (e: Exception) {
            if (!connected || e.isConnectionLoss()) {
                // Koneksi putus di tengah login. Setelah tersambung lagi, startSession dijalankan ulang
                // dan layar login tetap menunggu, jadi error ini tidak dikirim ke UI.
                DebugLog.log("Login terputus di tengah jalan, menunggu sambung ulang: ${e.describe()}")
                return
            }
            DebugLog.log("ERROR di startSession: ${e.describe()}")
            if (gen == authGeneration.get()) {
                // Gagal sungguhan (bukan koneksi): sesi ini tidak bisa dilanjutkan lagi
                activeSession = null
                awaitingApproval = false
                val shown: Throwable = if (e.authRejection()?.result == EResult.Expired) {
                    IllegalStateException(
                        "Permintaan persetujuan di Steam Mobile sudah kedaluwarsa. Tekan Login untuk meminta yang baru.",
                        e
                    )
                } else {
                    e
                }
                loggedOn.completeExceptionally(shown)
            }
        }
    }

    /**
     * Menunggu sesi login disetujui (di Steam Mobile atau dengan kode). Koneksi yang putus di tengah jalan tidak
     * mengakhiri penantian: loop menunggu sambungan pulih lalu memeriksa sesi yang sama lagi. Pengguna bisa berpindah
     * ke aplikasi Steam Mobile dan kembali tanpa membuat permintaan persetujuan baru.
     */
    private suspend fun awaitAuthResult(session: CredentialsAuthSession, gen: Int): AuthPollResult {
        val byApproval = session.allowedConfirmations.firstOrNull()?.confirmationType ==
            EAuthSessionGuardType.k_EAuthSessionGuardType_DeviceConfirmation
        var transientFailures = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            if (gen != authGeneration.get()) throw StaleLoginException()
            if (!client.isConnected) {
                delay(500L)
                continue
            }
            try {
                return if (byApproval) {
                    pollForApproval(session, gen)
                } else {
                    // Kode dari authenticator atau email: library yang mengurus, dan meminta kode lewat guardAuthenticator
                    withContext(Dispatchers.IO) { session.pollingWaitForResult().get() }
                }
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                if (e is StaleLoginException) throw e
                if (e.isConnectionLoss()) {
                    DebugLog.log("Koneksi putus saat menunggu persetujuan, sesi yang sama dilanjutkan setelah tersambung lagi")
                    delay(1000L)
                    continue
                }
                // Ditolak Steam (misalnya kedaluwarsa) tidak bisa dilanjutkan. Gangguan sesaat dicoba lagi beberapa kali.
                if (e.authRejection() != null || ++transientFailures > MAX_POLL_FAILURES) throw e
                DebugLog.log("Gangguan sesaat saat menunggu persetujuan ($transientFailures): ${e.describe()}")
                delay(2000L)
            }
        }
    }

    /**
     * Memeriksa sesi secara berkala sampai disetujui di Steam Mobile. Dipakai menggantikan penantian bawaan library:
     * di JavaSteam 1.8.0 interval dari Steam (dalam detik) dibaca sebagai milidetik, sehingga permintaan dikirim
     * beruntun tanpa jeda berarti. Di sini jedanya mengikuti interval dari Steam.
     */
    private suspend fun pollForApproval(session: CredentialsAuthSession, gen: Int): AuthPollResult {
        awaitingApproval = true
        DebugLog.log("Menunggu persetujuan di Steam Mobile")
        // Dipanggil juga setiap kali penantian dilanjutkan, supaya layar kembali menampilkan petunjuk persetujuan
        // setelah sebelumnya menampilkan "menyambung ulang".
        onGuardNeeded?.invoke(GuardType.APPROVAL, false)
        val intervalMs = (session.pollingInterval * 1000f).toLong().coerceIn(2_000L, 5_000L)
        while (true) {
            currentCoroutineContext().ensureActive()
            if (gen != authGeneration.get()) throw StaleLoginException()
            val result = withContext(Dispatchers.IO) { session.pollAuthSessionStatus(scope).get() }
            if (result != null) return result
            delay(intervalMs)
        }
    }

    /** Login lama berhenti karena pengguna sudah memulai login baru. Bukan error yang perlu ditampilkan. */
    private class StaleLoginException : Exception("Login digantikan oleh percobaan baru")

    /** Penolakan dari Steam (misalnya sesi kedaluwarsa) di dalam rantai penyebab, atau null kalau bukan itu. */
    private fun Throwable.authRejection(): AuthenticationException? =
        generateSequence(this) { it.cause?.takeIf { c -> c !== it } }
            .filterIsInstance<AuthenticationException>()
            .firstOrNull()

    private fun logOn(account: String, refreshToken: String) {
        val details = LogOnDetails().apply {
            username = account
            accessToken = refreshToken
            shouldRememberPassword = true
            loginID = 149
        }
        DebugLog.log("Mengirim logOn ke Steam...")
        client.getHandler(SteamUser::class.java)?.logOn(details)
    }

    private val guardAuthenticator = object : IAuthenticator {
        override fun getDeviceCode(previousCodeWasIncorrect: Boolean): CompletableFuture<String> =
            askGuardCode(GuardType.DEVICE, previousCodeWasIncorrect)

        override fun getEmailCode(email: String?, previousCodeWasIncorrect: Boolean): CompletableFuture<String> =
            askGuardCode(GuardType.EMAIL, previousCodeWasIncorrect)

        // true = tunggu persetujuan di aplikasi Steam Mobile. Library memantau sendiri sampai disetujui.
        // Kode tetap bisa dikirim lewat submitGuardCode selama sesi menunggu.
        override fun acceptDeviceConfirmation(): CompletableFuture<Boolean> {
            awaitingApproval = true
            DebugLog.log("Menunggu persetujuan di Steam Mobile")
            onGuardNeeded?.invoke(GuardType.APPROVAL, false)
            return CompletableFuture.completedFuture(true)
        }
    }

    private fun askGuardCode(type: GuardType, wrongCode: Boolean): CompletableFuture<String> {
        val future = CompletableFuture<String>()
        pendingGuard = future
        DebugLog.log("Steam meminta kode Steam Guard (${type.name.lowercase()}, kode sebelumnya salah: $wrongCode)")
        onGuardNeeded?.invoke(type, wrongCode)
        return future
    }

    private suspend fun fetchAppInfo(appId: Int): PICSProductInfo? {
        val steamApps = client.getHandler(SteamApps::class.java) ?: return null
        val token = steamApps.picsGetAccessTokens(listOf(appId), emptyList<Int>())
            .await().appTokens[appId] ?: 0L
        val res = steamApps.picsGetProductInfo(
            listOf(PICSRequest(appId, token)),
            emptyList<PICSRequest>()
        ).await()
        return res.results.flatMap { it.apps.values }.firstOrNull { it.id == appId }
    }

    /**
     * Menulis appmanifest dengan susunan seperti yang dibuat Steam di PC. [complete] = true menandai game
     * terinstal penuh (StateFlags 4). Kalau false, game ditandai belum selesai (1026) beserta jumlah byte yang
     * sudah masuk, supaya Steam di PC tahu harus melanjutkan dan tidak menganggap game ini lengkap.
     * [depots] berisi (byte terkompresi, byte tidak terkompresi) yang masuk per depot pada sesi ini saja.
     * [downloaded] adalah total byte terkompresi yang sudah masuk termasuk sesi sebelumnya (untuk game belum selesai).
     */
    private fun writeAppManifest(
        appId: Int,
        depots: Map<Int, Pair<Long, Long>>,
        complete: Boolean,
        kv: KeyValue? = appInfos[appId],
        downloaded: Long? = null,
    ) {
        if (kv == null) return
        val installDir = kv["config"]["installdir"].asString() ?: return
        val name = kv["common"]["name"].asString() ?: "App $appId"
        val buildId = kv["depots"]["branches"]["public"]["buildid"].asString() ?: "0"
        val total = GameSizeStore.get(appId)
        val runCompressed = depots.values.sumOf { it.first }
        val runUncompressed = depots.values.sumOf { it.second }

        // Sesi yang dilanjutkan hanya melaporkan chunk yang baru diunduh, jadi untuk game yang sudah selesai
        // ukuran penuh dari manifest yang dipakai, bukan jumlah sesi terakhir.
        val doneCompressed = if (complete) (total?.download ?: runCompressed) else (downloaded ?: runCompressed)
        val fraction = if (total != null && total.download > 0L) {
            (doneCompressed.toDouble() / total.download).coerceIn(0.0, 1.0)
        } else {
            0.0
        }
        val doneUncompressed = when {
            complete -> total?.disk ?: runUncompressed
            total != null -> (total.disk * fraction).toLong()
            else -> runUncompressed
        }
        val toDownload = if (complete) doneCompressed else (total?.download ?: 0L)
        val toStage = if (complete) doneUncompressed else (total?.disk ?: 0L)
        val owner = client.steamID?.convertToUInt64() ?: 0L
        val state = if (complete) Storage.STATE_INSTALLED else Storage.STATE_PARTIAL

        fun line(key: String, value: Any) = "\t\"$key\"\t\t\"$value\"\n"

        val text = buildString {
            append("\"AppState\"\n{\n")
            append(line("appid", appId))
            append(line("Universe", 1))
            append(line("name", vdfEscape(name)))
            append(line("StateFlags", state))
            append(line("installdir", vdfEscape(installDir)))
            append(line("LastUpdated", System.currentTimeMillis() / 1000))
            append(line("LastPlayed", 0))
            append(line("SizeOnDisk", doneUncompressed))
            append(line("StagingSize", 0))
            // Game yang belum selesai belum punya build terpasang. Build tujuannya ada di TargetBuildID.
            append(line("buildid", if (complete) buildId else "0"))
            if (owner != 0L) append(line("LastOwner", owner))
            append(line("UpdateResult", 0))
            append(line("BytesToDownload", toDownload))
            append(line("BytesDownloaded", doneCompressed))
            append(line("BytesToStage", toStage))
            append(line("BytesStaged", doneUncompressed))
            append(line("TargetBuildID", buildId))
            append(line("AutoUpdateBehavior", 0))
            append(line("AllowOtherDownloadsWhileRunning", 0))
            append(line("ScheduledAutoUpdate", 0))
            if (complete) {
                append("\t\"InstalledDepots\"\n\t{\n")
                for ((depotId, sizes) in depots) {
                    val manifestId = kv["depots"][depotId.toString()]["manifests"]["public"]["gid"].asString() ?: "0"
                    append("\t\t\"$depotId\"\n\t\t{\n")
                    append("\t\t\t\"manifest\"\t\t\"$manifestId\"\n")
                    // Ukuran depot penuh dari info app. Jumlah dari sesi terakhir saja terlalu kecil setelah resume.
                    val depotSize = kv["depots"][depotId.toString()]["manifests"]["public"]["size"]
                        .asString()?.toLongOrNull() ?: sizes.second
                    append("\t\t\t\"size\"\t\t\"$depotSize\"\n")
                    append("\t\t}\n")
                }
                append("\t}\n")
            }
            append("\t\"UserConfig\"\n\t{\n\t\t\"language\"\t\t\"english\"\n\t}\n")
            append("\t\"MountedConfig\"\n\t{\n\t\t\"language\"\t\t\"english\"\n\t}\n")
            append("}\n")
        }

        val file = Storage.manifestFile(appId)
        file.parentFile?.mkdirs()
        file.writeText(text)
    }

    private fun vdfEscape(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"")

    companion object {
        private const val KEY_ACCOUNT = "account"
        private const val KEY_TOKEN = "refresh_token"
        private const val MAX_RECONNECT_RETRIES = 3
        private const val RECONNECT_WAIT_MS = 45_000L
        // Percobaan sambung yang tidak dijawab selama ini dianggap macet, jadi boleh dicoba lagi
        private const val CONNECT_STALE_MS = 30_000L
        // Gangguan sesaat (bukan penolakan Steam) berturut-turut yang ditoleransi saat menunggu persetujuan
        private const val MAX_POLL_FAILURES = 3
        // Persen yang ditampilkan tidak melewati ini sebelum download benar-benar selesai
        private const val MAX_FRACTION_BEFORE_DONE = 0.99
        private const val DATA_ACTIVITY = "Mengunduh, mendekompresi, dan menulis data game"

        @Volatile private var instance: SteamRepository? = null

        fun get(context: Context): SteamRepository =
            instance ?: synchronized(this) {
                instance ?: SteamRepository(context.applicationContext).also { instance = it }
            }
    }
}
