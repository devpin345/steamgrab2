package com.example.steamgrab

import android.content.Context
import `in`.dragonbra.javasteam.depotdownloader.DepotDownloader
import `in`.dragonbra.javasteam.depotdownloader.IDownloadListener
import `in`.dragonbra.javasteam.depotdownloader.data.AppItem
import `in`.dragonbra.javasteam.depotdownloader.data.DownloadItem
import `in`.dragonbra.javasteam.enums.EResult
import `in`.dragonbra.javasteam.steam.authentication.AuthSessionDetails
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
    // Sesi login yang sedang menunggu persetujuan di Steam Mobile. Kode juga bisa dikirim langsung ke sesi ini.
    @Volatile private var activeSession: CredentialsAuthSession? = null
    @Volatile private var awaitingApproval = false
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
                // Kode yang sedang ditunggu ikut batal. Setelah tersambung, Steam akan meminta kode lagi.
                pendingGuard?.cancel(false)
                pendingGuard = null
                awaitingApproval = false
                activeSession = null
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
            // Byte yang sudah masuk per depot, dalam bentuk yang dipakai appmanifest
            val snapshot = { depots.mapValues { it.value.compressed to it.value.uncompressed } }

            // Library dan appmanifest ditulis sejak awal. Selama download belum selesai, StateFlags bernilai
            // "belum selesai", jadi Steam di PC tahu game ini belum lengkap dan tidak menganggapnya terinstal.
            runCatching {
                Storage.ensureLibrary()
                writeAppManifest(appId, emptyMap(), complete = false)
            }.onFailure { DebugLog.log("Gagal menulis appmanifest awal: ${it.describe()}") }
            val failure = AtomicReference<Throwable?>(null)
            // Tahap yang sedang dikerjakan library sebelum chunk pertama masuk (manifest, alokasi, validasi)
            val phase = AtomicReference("Menyiapkan download...")
            val category = AtomicReference("")

            val listener = object : IDownloadListener {
                override fun onStatusUpdate(message: String) {
                    val (text, cat) = phaseOf(message)
                    phase.set(text)
                    // Hanya dicatat saat tahap berganti, supaya log tidak penuh oleh tiap file
                    if (category.getAndSet(cat) != cat) DebugLog.log("Tahap: $text")
                }

                override fun onItemAdded(item: DownloadItem) {
                    DebugLog.log("Library menerima permintaan download")
                }

                override fun onDownloadStarted(item: DownloadItem) {
                    DebugLog.log("Library mulai memproses download")
                }

                override fun onChunkCompleted(
                    depotId: Int,
                    depotPercentComplete: Float,
                    compressedBytes: Long,
                    uncompressedBytes: Long
                ) {
                    depots[depotId] = DepotProgress(compressedBytes, uncompressedBytes, depotPercentComplete)
                    onProgress(summarize(depots, phase.get(), GameSizeStore.get(appId)?.download))
                }

                override fun onFileCompleted(depotId: Int, fileName: String, depotPercentComplete: Float) {
                    // Untuk file kecil yang hanya terdiri dari satu chunk, persen hanya datang dari sini
                    val prev = depots[depotId]
                    depots[depotId] = DepotProgress(
                        prev?.compressed ?: 0L,
                        prev?.uncompressed ?: 0L,
                        depotPercentComplete
                    )
                    onProgress(summarize(depots, phase.get(), GameSizeStore.get(appId)?.download))
                }

                override fun onDepotCompleted(depotId: Int, compressedBytes: Long, uncompressedBytes: Long) {
                    depots[depotId] = DepotProgress(compressedBytes, uncompressedBytes, 1f)
                    DebugLog.log("Depot $depotId selesai (${formatBytes(compressedBytes)})")
                    onProgress(summarize(depots, phase.get(), GameSizeStore.get(appId)?.download))
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

                val app = fetchAppInfo(appId) ?: error("Info app $appId tidak ditemukan di Steam")
                val installDir = app.keyValues["config"]["installdir"].asString()
                    ?: error("Nama folder install untuk app $appId kosong")

                writeAppManifest(appId, snapshot(), complete = true, kv = app.keyValues)
                Storage.gameDir(installDir)
            } catch (e: Throwable) {
                // Dijeda, dibatalkan, atau gagal: manifest tetap bertanda belum selesai, dengan jumlah byte yang sudah masuk
                runCatching { writeAppManifest(appId, snapshot(), complete = false) }
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
     */
    private fun summarize(depots: Map<Int, DepotProgress>, phase: String, knownTotal: Long?): DownloadProgress {
        var done = 0.0
        var total = 0.0
        for (d in depots.values) {
            done += d.compressed
            if (d.percent > 0f) total += d.compressed / d.percent.toDouble()
        }
        // Kalau ukuran dari manifest sudah diketahui, pakai itu. Lebih akurat daripada perkiraan dari persen.
        if (knownTotal != null && knownTotal > 0L) total = knownTotal.toDouble()
        val percent = if (total > 0.0) (done / total).toFloat().coerceIn(0f, 1f) else 0f
        return DownloadProgress(
            doneBytes = done.toLong(),
            totalBytes = total.toLong(),
            percent = percent,
            phase = phase
        )
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

            val user = typedUser ?: error("Silakan login ulang")
            val pass = pendingPass ?: error("Silakan login ulang")

            val result = withContext(Dispatchers.IO) {
                val details = AuthSessionDetails().apply {
                    username = user
                    password = pass
                    deviceFriendlyName = "SteamGrab Android"
                    persistentSession = true
                    authenticator = guardAuthenticator
                }
                DebugLog.log("Mengirim login kredensial ke Steam...")
                val session = client.authentication.beginAuthSessionViaCredentials(details).get()
                DebugLog.log("Sesi login dibuat, menunggu verifikasi...")
                activeSession = session
                try {
                    val poll = session.pollingWaitForResult().get()
                    DebugLog.log("Verifikasi selesai, token diterima")
                    poll
                } finally {
                    awaitingApproval = false
                    activeSession = null
                }
            }

            prefs.edit()
                .putString(KEY_ACCOUNT, result.accountName)
                .putString(KEY_TOKEN, result.refreshToken)
                .apply()
            logOn(result.accountName, result.refreshToken)
        } catch (e: java.util.concurrent.CancellationException) {
            // Koneksi putus di tengah login membatalkan permintaan ke Steam. Login dimulai ulang
            // otomatis saat tersambung lagi, jadi error tidak ditampilkan dulu.
            DebugLog.log("Login terhenti karena koneksi putus, menunggu sambung ulang")
        } catch (e: Exception) {
            if (!connected || e.isConnectionLoss()) {
                // Koneksi putus di tengah login. Setelah tersambung lagi, startSession dijalankan ulang
                // dan layar login tetap menunggu, jadi error ini tidak dikirim ke UI.
                DebugLog.log("Login terputus di tengah jalan, menunggu sambung ulang: ${e.describe()}")
                return
            }
            DebugLog.log("ERROR di startSession: ${e.describe()}")
            loggedOn.completeExceptionally(e)
        }
    }

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
     * [depots] berisi (byte terkompresi, byte tidak terkompresi) yang sudah masuk per depot.
     */
    private fun writeAppManifest(
        appId: Int,
        depots: Map<Int, Pair<Long, Long>>,
        complete: Boolean,
        kv: KeyValue? = appInfos[appId],
    ) {
        if (kv == null) return
        val installDir = kv["config"]["installdir"].asString() ?: return
        val name = kv["common"]["name"].asString() ?: "App $appId"
        val buildId = kv["depots"]["branches"]["public"]["buildid"].asString() ?: "0"
        val doneCompressed = depots.values.sumOf { it.first }
        val doneUncompressed = depots.values.sumOf { it.second }
        val total = GameSizeStore.get(appId)
        // Kalau sudah selesai, yang sudah masuk itulah totalnya. Kalau belum, pakai ukuran penuh game.
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
                    append("\t\t\t\"size\"\t\t\"${sizes.second}\"\n")
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

        @Volatile private var instance: SteamRepository? = null

        fun get(context: Context): SteamRepository =
            instance ?: synchronized(this) {
                instance ?: SteamRepository(context.applicationContext).also { instance = it }
            }
    }
}
