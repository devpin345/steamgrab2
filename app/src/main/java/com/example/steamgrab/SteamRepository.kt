package com.example.steamgrab

import android.content.Context
import `in`.dragonbra.javasteam.depotdownloader.DepotDownloader
import `in`.dragonbra.javasteam.depotdownloader.IDownloadListener
import `in`.dragonbra.javasteam.depotdownloader.data.AppItem
import `in`.dragonbra.javasteam.depotdownloader.data.DownloadItem
import `in`.dragonbra.javasteam.enums.EResult
import `in`.dragonbra.javasteam.steam.authentication.AuthSessionDetails
import `in`.dragonbra.javasteam.steam.authentication.IAuthenticator
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import `in`.dragonbra.javasteam.steam.cdn.Client as CdnClient
import `in`.dragonbra.javasteam.steam.handlers.steamcontent.SteamContent
import `in`.dragonbra.javasteam.steam.steamclient.AsyncJobFailedException
import `in`.dragonbra.javasteam.types.KeyValue
import java.util.concurrent.atomic.AtomicReference

data class OwnedGame(val appId: Int, val name: String)

enum class GuardType { DEVICE, EMAIL }

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
    @Volatile private var connected = false
    private var pumpStarted = false
    // Dikirim ke UI saat status koneksi berubah (misalnya sedang menyambung ulang saat menunggu kode)
    @Volatile private var onStatus: ((String) -> Unit)? = null

    private var loggedOn = CompletableDeferred<Unit>()
    private var licenseSignal = CompletableDeferred<List<License>>()

    init {
        manager.subscribe(ConnectedCallback::class.java) {
            connected = true
            DebugLog.log("Terhubung ke server Steam")
            scope.launch { startSession() }
        }

        manager.subscribe(DisconnectedCallback::class.java) { cb ->
            connected = false
            DebugLog.log("Koneksi ke Steam terputus (dibatalkan pengguna: ${cb.isUserInitiated})")
            if (!cb.isUserInitiated) {
                // Kode yang sedang ditunggu ikut batal. Setelah tersambung, Steam akan meminta kode lagi.
                pendingGuard?.cancel(false)
                pendingGuard = null
                onStatus?.invoke("Koneksi ke Steam terputus, menyambung ulang...")
                scope.launch {
                    delay(2000L)
                    client.connect()
                }
            }
        }

        manager.subscribe(LoggedOnCallback::class.java) { cb ->
            DebugLog.log("Steam membalas logOn: ${cb.result}")
            if (cb.result == EResult.OK) {
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

        DebugLog.log("login: mulai (sudah terhubung: $connected)")
        withContext(Dispatchers.IO) {
            ensurePump()
            if (connected) {
                scope.launch { startSession() }
            } else {
                client.connect()
            }
        }

        loggedOn.await()
        DebugLog.log("login: loggedOn diterima")
        licenseSignal.await()
        DebugLog.log("login: daftar lisensi diterima")
    }

    /** True jika library sedang menunggu kode Steam Guard dari pengguna. */
    val isWaitingForGuard: Boolean
        get() = pendingGuard != null

    /** Mengembalikan true jika kode dipakai untuk pertanyaan Steam Guard yang sedang menunggu. */
    fun submitGuardCode(code: String?): Boolean {
        val future = pendingGuard ?: return false
        if (code.isNullOrBlank()) return false
        pendingGuard = null
        DebugLog.log("Kode Steam Guard diteruskan ke library")
        future.complete(code.trim())
        return true
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

            // Argumen: maxDownloads = 50 chunk paralel, maxFileWrites = 20 penulisan file paralel
            val downloader = DepotDownloader(client, licenses, false, false, 50, 20)
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

                writeAppManifest(app, installDir, depots.mapValues { it.value.compressed to it.value.uncompressed })
                Storage.gameDir(installDir)
            } finally {
                downloader.close()
            }
        }

    /** Nama folder install game (config/installdir), atau null kalau info app belum ada. */
    fun installDirOf(appId: Int): String? =
        appInfos[appId]?.let { it["config"]["installdir"].asString() }

    /**
     * Menghitung ukuran game dari manifest tiap depot. Manifest itu kecil, jadi jauh lebih ringan
     * daripada mengunduh isi game. Pilihan depot sama dengan yang dipakai library saat download.
     */
    suspend fun computeDownloadSize(appId: Int): GameSize = withContext(Dispatchers.IO) {
        val kv = appInfos[appId] ?: error("Info app $appId belum dimuat")
        val steamContent = client.getHandler(SteamContent::class.java) ?: error("Sesi Steam belum siap")
        val depots = selectedDepots(kv)
        if (depots.isEmpty()) return@withContext GameSize(0L, 0L)

        val server = steamContent.getServersForSteamPipe(
            cellId = client.cellID ?: 0,
            parentScope = this
        ).await()
            .filter { (it.type == "SteamCache" || it.type == "CDN") && !it.useAsProxy }
            .filter { it.allowedAppIds.isEmpty() || it.allowedAppIds.contains(appId) }
            .minByOrNull { it.weightedLoad }
            ?: error("Tidak ada server CDN yang tersedia")

        val cdn = CdnClient(client)
        try {
            var download = 0L
            var disk = 0L
            for ((depotId, manifestId) in depots) {
                val code = steamContent
                    .getManifestRequestCode(depotId, appId, manifestId, "public", null, this)
                    .await()
                if (code == 0L) error("Steam tidak memberi izin untuk manifest depot $depotId")

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
        } finally {
            cdn.close()
        }
    }

    /** Depot untuk Windows 64-bit bahasa Inggris, beserta ID manifest publiknya. */
    private fun selectedDepots(kv: KeyValue): List<Pair<Int, Long>> =
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

            val manifestId = section["manifests"]["public"]["gid"].asString()?.toLongOrNull()
                ?: return@mapNotNull null
            depotId to manifestId
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
                val poll = session.pollingWaitForResult().get()
                DebugLog.log("Verifikasi selesai, token diterima")
                poll
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

        // false = tidak memakai konfirmasi di aplikasi Steam Mobile, langsung minta kode manual
        override fun acceptDeviceConfirmation(): CompletableFuture<Boolean> =
            CompletableFuture.completedFuture(false)
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

    /** Menulis appmanifest supaya Steam di PC menganggap game ini sudah terinstal. */
    private fun writeAppManifest(app: PICSProductInfo, installDir: String, depots: Map<Int, Pair<Long, Long>>) {
        val kv = app.keyValues
        val name = kv["common"]["name"].asString() ?: "App ${app.id}"
        val buildId = kv["depots"]["branches"]["public"]["buildid"].asString() ?: "0"
        val compressedTotal = depots.values.sumOf { it.first }
        val uncompressedTotal = depots.values.sumOf { it.second }

        val text = buildString {
            appendLine("\"AppState\"")
            appendLine("{")
            appendLine("\t\"appid\"\t\t\"${app.id}\"")
            appendLine("\t\"Universe\"\t\t\"1\"")
            appendLine("\t\"name\"\t\t\"${vdfEscape(name)}\"")
            appendLine("\t\"StateFlags\"\t\t\"4\"")
            appendLine("\t\"installdir\"\t\t\"${vdfEscape(installDir)}\"")
            appendLine("\t\"LastUpdated\"\t\t\"${System.currentTimeMillis() / 1000}\"")
            appendLine("\t\"SizeOnDisk\"\t\t\"$uncompressedTotal\"")
            appendLine("\t\"buildid\"\t\t\"$buildId\"")
            appendLine("\t\"BytesToDownload\"\t\t\"$compressedTotal\"")
            appendLine("\t\"BytesDownloaded\"\t\t\"$compressedTotal\"")
            appendLine("\t\"InstalledDepots\"")
            appendLine("\t{")
            for ((depotId, sizes) in depots) {
                val manifestId = kv["depots"][depotId.toString()]["manifests"]["public"]["gid"].asString() ?: "0"
                appendLine("\t\t\"$depotId\"")
                appendLine("\t\t{")
                appendLine("\t\t\t\"manifest\"\t\t\"$manifestId\"")
                appendLine("\t\t\t\"size\"\t\t\"${sizes.second}\"")
                appendLine("\t\t}")
            }
            appendLine("\t}")
            appendLine("}")
        }

        val file = Storage.manifestFile(app.id)
        file.parentFile?.mkdirs()
        file.writeText(text)
    }

    private fun vdfEscape(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"")

    companion object {
        private const val KEY_ACCOUNT = "account"
        private const val KEY_TOKEN = "refresh_token"

        @Volatile private var instance: SteamRepository? = null

        fun get(context: Context): SteamRepository =
            instance ?: synchronized(this) {
                instance ?: SteamRepository(context.applicationContext).also { instance = it }
            }
    }
}
