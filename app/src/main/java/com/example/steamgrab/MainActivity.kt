package com.example.steamgrab

import android.Manifest
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.content.ClipData
import android.content.ClipboardManager
import android.provider.Settings
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val MENU_DEBUG = 1

// Perbandingan lebar : tinggi gambar header Steam (460 x 215)
private const val HEADER_RATIO = 460f / 215f

class MainActivity : ComponentActivity() {
    // Tombol Debug di pojok kanan atas (action bar)
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, MENU_DEBUG, 0, "Debug").setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == MENU_DEBUG) {
            DebugLog.setVisible(true)
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DebugLog.init(applicationContext)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SteamGrabApp()
                }
            }
        }
    }
}

@Composable
fun SteamGrabApp(vm: SteamViewModel = viewModel()) {
    val state by vm.uiState.collectAsState()
    val sizes by vm.sizes.collectAsState()
    val sizesLoading by vm.sizesLoading.collectAsState()
    val prices by vm.prices.collectAsState()
    val storageAlert by vm.storageAlert.collectAsState()

    // Notifikasi dibutuhkan agar progres download terlihat di Android 13 ke atas
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val showDebug by DebugLog.visible.collectAsState()
    if (showDebug) DebugDialog(onClose = { DebugLog.setVisible(false) })

    // Izin akses semua file diminta di awal, sebelum login dan sebelum daftar game muncul.
    // Statusnya dicek ulang setiap aplikasi kembali ke layar (setelah pengguna dari pengaturan),
    // jadi popup hilang sendiri begitu izin diberikan.
    val context = LocalContext.current
    var hasAccess by remember { mutableStateOf(Storage.hasAllFilesAccess()) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) hasAccess = Storage.hasAllFilesAccess()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    if (!hasAccess) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Izin penyimpanan dibutuhkan") },
            text = {
                Text(
                    "SteamGrab menyimpan game ke folder Download/SteamGames di penyimpanan ponsel " +
                        "supaya bisa dipindahkan ke PC lewat USB. Tanpa izin akses semua file, " +
                        "aplikasi tidak bisa menulis ke folder itu dan tidak bisa menghitung ruang " +
                        "yang dibutuhkan dengan benar.\n\n" +
                        "Tekan tombol di bawah, lalu aktifkan \"Izinkan akses untuk mengelola semua file\"."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        try {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                    Uri.parse("package:${context.packageName}")
                                )
                            )
                        } catch (e: Exception) {
                            // Sebagian ponsel tidak punya halaman izin per aplikasi, buka daftar umumnya
                            context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                        }
                    }
                }) { Text("Buka pengaturan izin") }
            }
        )
    }

    when (val s = state) {
        is UiState.LoggedOut -> LoginScreen(
            loading = s.loading,
            error = s.error,
            needsGuardCode = s.needsGuardCode,
            status = s.status,
            onLogin = { user, pass, guard -> vm.login(user, pass, guard) }
        )
        is UiState.GameList -> GameListScreen(
            games = s.games,
            sizes = sizes,
            sizesLoading = sizesLoading,
            free = prices,
            onSelect = { appId -> vm.requestDownload(appId) },
            onOpenDownload = { vm.openRunningDownload() }
        )
        is UiState.Downloading -> DownloadScreen(
            state = s,
            onPause = { vm.pauseDownload() },
            onResume = { appId -> vm.resumeDownload(appId) },
            onCancel = { vm.cancelDownload() },
            onBack = { vm.backToList() }
        )
        is UiState.Done -> DoneScreen(
            path = s.savedPath,
            onBack = { vm.backToList() }
        )
        is UiState.Error -> ErrorScreen(
            message = s.message,
            onBack = { vm.backToList() }
        )
    }

    // Popup saat memori tidak cukup untuk game yang dipilih
    storageAlert?.let { alert ->
        AlertDialog(
            onDismissRequest = { vm.dismissStorageAlert() },
            confirmButton = {
                TextButton(onClick = { vm.dismissStorageAlert() }) { Text("Mengerti") }
            },
            title = { Text("Memori penuh") },
            text = {
                Text(
                    "Game ${alert.gameName} butuh sekitar ${formatBytes(alert.neededBytes)} untuk disimpan, " +
                        "tetapi memori yang tersedia hanya ${formatBytes(alert.freeBytes)}.\n\n" +
                        "Kosongkan memori dulu, lalu coba lagi."
                )
            }
        )
    }
}

enum class LibraryFilter { ALL, INSTALLED, NOT_INSTALLED }

/** App ID game yang appmanifest-nya bertanda terinstal penuh (StateFlags 4), artinya sudah selesai diunduh. */
private fun installedAppIds(): Set<Int> =
    Storage.appStates().filterValues { Storage.isComplete(it) }.keys

/** App ID game yang appmanifest-nya ada tetapi download-nya belum selesai (dijeda, dibatalkan, atau gagal). */
private fun partialAppIds(): Set<Int> =
    Storage.appStates().filterValues { !Storage.isComplete(it) }.keys

/** Layar debug: log dengan jam, tombol Salin, Bersihkan, dan Tutup. Dibuka lewat dialog supaya isian login tidak hilang. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DebugDialog(onClose: () -> Unit) {
    val lines by DebugLog.lines.collectAsState()
    val context = LocalContext.current

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                Text("Debug", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(8.dp))
                Row {
                    Button(onClick = {
                        val cm = context.getSystemService(ClipboardManager::class.java)
                        cm.setPrimaryClip(ClipData.newPlainText("SteamGrab log", DebugLog.text()))
                        Toast.makeText(context, "Log disalin", Toast.LENGTH_SHORT).show()
                    }) { Text("Salin") }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = { DebugLog.clear() }) { Text("Bersihkan") }
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = onClose) { Text("Tutup") }
                }
                Spacer(Modifier.height(12.dp))
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                    SelectionContainer {
                        Text(
                            text = if (lines.isEmpty()) "(belum ada log)" else lines.joinToString("\n"),
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LoginScreen(
    loading: Boolean,
    error: String?,
    needsGuardCode: Boolean,
    status: String?,
    onLogin: (String, String, String?) -> Unit
) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var guardCode by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text("Login Steam", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))

        OutlinedTextField(
            value = username, onValueChange = { username = it },
            label = { Text("Username") }, modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = password, onValueChange = { password = it },
            label = { Text("Password") },
            visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { passwordVisible = !passwordVisible }) {
                    EyeIcon(visible = passwordVisible)
                }
            },
            modifier = Modifier.fillMaxWidth()
        )

        if (needsGuardCode) {
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = guardCode, onValueChange = { guardCode = it },
                label = { Text("Kode Steam Guard") },
                modifier = Modifier.fillMaxWidth()
            )
        }

        error?.let {
            Spacer(Modifier.height(8.dp))
            // SelectionContainer supaya teks error bisa disalin dan dikirim ke pengembang
            SelectionContainer {
                Text(it, color = MaterialTheme.colorScheme.error)
            }
        }

        Spacer(Modifier.height(16.dp))
        status?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
        }
        Button(
            onClick = { onLogin(username, password, guardCode.ifBlank { null }) },
            // Saat menunggu kode, tombol baru aktif setelah kode diisi. Saat menunggu persetujuan di Steam Mobile, tidak wajib.
            enabled = !loading && username.isNotBlank() && password.isNotBlank() &&
                (!needsGuardCode || guardCode.isNotBlank()),
            modifier = Modifier.fillMaxWidth()
        ) {
            if (loading) CircularProgressIndicator(modifier = Modifier.size(20.dp))
            else Text(if (needsGuardCode) "Kirim kode" else "Login")
        }
    }
}

/**
 * Ikon mata untuk tombol lihat/sembunyikan password. Digambar sendiri karena
 * ikon extended tidak dipakai di proyek ini. Mata dicoret kalau password sedang disembunyikan.
 */
@Composable
fun EyeIcon(visible: Boolean, modifier: Modifier = Modifier) {
    val color = LocalContentColor.current
    Canvas(modifier.size(24.dp)) {
        val w = size.width
        val h = size.height
        val eye = Path().apply {
            moveTo(w * 0.05f, h * 0.5f)
            quadraticBezierTo(w * 0.5f, h * -0.15f, w * 0.95f, h * 0.5f)
            quadraticBezierTo(w * 0.5f, h * 1.15f, w * 0.05f, h * 0.5f)
            close()
        }
        drawPath(eye, color, style = Stroke(width = w * 0.08f, join = StrokeJoin.Round))
        drawCircle(color, radius = w * 0.14f, center = Offset(w * 0.5f, h * 0.5f))
        if (!visible) {
            drawLine(
                color,
                start = Offset(w * 0.1f, h * 0.9f),
                end = Offset(w * 0.9f, h * 0.1f),
                strokeWidth = w * 0.08f,
                cap = StrokeCap.Round
            )
        }
    }
}

@Composable
fun GameListScreen(
    games: List<GameInfo>,
    sizes: Map<Int, GameSize>,
    sizesLoading: Boolean,
    free: Map<Int, Boolean>,
    onSelect: (Int) -> Unit,
    onOpenDownload: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(LibraryFilter.ALL) }
    val installed = remember(games) { installedAppIds() }
    val partial = remember(games) { partialAppIds() }
    // Game yang diketuk padahal sudah selesai diunduh: dikenali, tidak langsung diunduh ulang
    var knownDone by remember { mutableStateOf<GameInfo?>(null) }
    val running by DownloadService.status.collectAsState()

    val shown = games.filter { game ->
        (query.isBlank() || game.name.contains(query, ignoreCase = true)) &&
            when (filter) {
                LibraryFilter.ALL -> true
                LibraryFilter.INSTALLED -> game.appId in installed
                LibraryFilter.NOT_INSTALLED -> game.appId !in installed
            }
    }

    knownDone?.let { game ->
        AlertDialog(
            onDismissRequest = { knownDone = null },
            title = { Text(game.name) },
            text = {
                Text(
                    "Game ini sudah selesai diunduh dan tersimpan di Download/SteamGames/steamapps/common. " +
                        "Tidak perlu diunduh lagi. Pilih Periksa ulang kalau ingin memastikan semua filenya lengkap."
                )
            },
            confirmButton = { TextButton(onClick = { knownDone = null }) { Text("Tutup") } },
            dismissButton = {
                TextButton(onClick = {
                    knownDone = null
                    onSelect(game.appId)
                }) { Text("Periksa ulang") }
            }
        )
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Pilih Game", style = MaterialTheme.typography.headlineSmall)

        // Download yang sedang berjalan atau dijeda. Ketuk untuk membuka halaman progresnya.
        val banner: Triple<String, DownloadProgress, String>? = when (val st = running) {
            is DownloadStatus.Running -> Triple(st.name, st.progress, "sedang diunduh")
            is DownloadStatus.Paused -> Triple(st.name, st.progress, "dijeda")
            else -> null
        }
        banner?.let { (name, progress, label) ->
            Spacer(Modifier.height(8.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().clickable(onClick = onOpenDownload).padding(12.dp)) {
                    Text("$name $label", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "${(progress.percent * 100).toInt()}% · ketuk untuk melihat",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Cari game") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = filter == LibraryFilter.ALL,
                onClick = { filter = LibraryFilter.ALL },
                label = { Text("Semua") }
            )
            FilterChip(
                selected = filter == LibraryFilter.INSTALLED,
                onClick = { filter = LibraryFilter.INSTALLED },
                label = { Text("Sudah diunduh") }
            )
            FilterChip(
                selected = filter == LibraryFilter.NOT_INSTALLED,
                onClick = { filter = LibraryFilter.NOT_INSTALLED },
                label = { Text("Belum") }
            )
        }
        Text(
            "${shown.size} dari ${games.size} game · semua dimiliki akun",
            style = MaterialTheme.typography.bodySmall
        )

        Spacer(Modifier.height(12.dp))
        LazyColumn {
            items(shown) { game ->
                val done = game.appId in installed
                ListItem(
                    leadingContent = { GameHeader(appId = game.appId, modifier = Modifier.width(96.dp)) },
                    headlineContent = { Text(game.name) },
                    supportingContent = {
                        val priceLabel = when (free[game.appId]) {
                            true -> "Gratis"
                            false -> "Berbayar"
                            null -> null
                        }
                        val ownLabel = when {
                            done -> "Sudah diunduh"
                            game.appId in partial -> "Belum selesai"
                            else -> "Dimiliki"
                        }
                        Text(listOfNotNull("App ID ${game.appId}", priceLabel, ownLabel).joinToString(" · "))
                    },
                    trailingContent = {
                        SizeLabel(size = sizes[game.appId], loading = sizesLoading)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            if (done) knownDone = game else onSelect(game.appId)
                        }
                )
                Divider()
            }
        }
    }
}

/** Ukuran game di sisi kanan daftar: ukuran unduh besar, ukuran di disk kecil di bawahnya. */
@Composable
fun SizeLabel(size: GameSize?, loading: Boolean) {
    Column(horizontalAlignment = Alignment.End) {
        if (size != null) {
            Text("Unduh ${formatBytes(size.download)}", style = MaterialTheme.typography.bodyMedium)
            Text("Disk ${formatBytes(size.disk)}", style = MaterialTheme.typography.labelSmall)
        } else {
            Text(
                if (loading) "Menghitung..." else "—",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

/**
 * Gambar header game. Tampil kotak abu-abu selama gambar belum siap atau gagal dimuat.
 */
@Composable
fun GameHeader(appId: Int, modifier: Modifier = Modifier) {
    val bitmap by produceState<Bitmap?>(initialValue = GameImages.cached(appId), key1 = appId) {
        value = GameImages.load(appId)
    }
    val frame = modifier
        .aspectRatio(HEADER_RATIO)
        .clip(RoundedCornerShape(8.dp))

    val b = bitmap
    if (b != null) {
        Image(
            bitmap = b.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = frame
        )
    } else {
        Box(modifier = frame.background(MaterialTheme.colorScheme.surfaceVariant))
    }
}

@Composable
fun DownloadScreen(
    state: UiState.Downloading,
    onPause: () -> Unit,
    onResume: (Int) -> Unit,
    onCancel: () -> Unit,
    onBack: () -> Unit
) {
    val p = state.progress
    val pct = (p.percent * 100).toInt().coerceIn(0, 100)
    val known = p.totalBytes > 0L

    // Baris keterangan: saat dijeda, saat ukuran sudah diketahui, atau tahap yang sedang berjalan
    val statusLine = when {
        state.paused -> "Dijeda. ${formatBytes(p.doneBytes)} sudah diunduh dan tidak hilang."
        p.finishing -> "Hampir selesai. Semua bagian sudah diunduh, sedang merapikan folder dan menyimpan catatan..."
        known -> "Sudah diunduh ${formatBytes(p.doneBytes)} dari ${formatBytes(p.totalBytes)}"
        else -> p.phase.ifBlank { "Menyiapkan download..." }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        // Gambar latar game tanpa judul, dengan candle kecepatan di atasnya
        DownloadBackdrop(
            appId = state.appId,
            networkSamples = state.speedHistory,
            diskSamples = state.diskHistory,
            networkBps = state.speedBps,
            diskBps = state.diskBps,
            showLegend = !state.paused,
        )

        // Bar progres tipis tepat di bawah gambar
        Box(
            Modifier
                .fillMaxWidth()
                .height(4.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(p.percent.coerceIn(0f, 1f))
                    .background(MaterialTheme.colorScheme.primary)
            )
        }

        Column(Modifier.fillMaxWidth().padding(20.dp)) {
            Text(
                state.gameName,
                style = MaterialTheme.typography.headlineSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text("$pct%", style = MaterialTheme.typography.headlineMedium)
                if (!state.paused) {
                    Spacer(Modifier.width(12.dp))
                    Text(
                        formatSpeed(state.speedBps),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(bottom = 3.dp)
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(statusLine, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(16.dp))

            ActivityPanel(paused = state.paused, progress = p, speedHistory = state.speedHistory)
            Spacer(Modifier.height(16.dp))

            if (!state.paused) {
                Text(
                    "Download tetap berjalan walau layar dikunci atau aplikasi ditutup. Pantau dari notifikasi.",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(16.dp))
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.paused) {
                    Button(onClick = { onResume(state.appId) }) { Text("Lanjutkan") }
                } else {
                    Button(onClick = onPause) { Text("Jeda") }
                }
                OutlinedButton(onClick = onCancel) { Text("Batal") }
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onBack) { Text("Ke daftar game") }
        }
    }
}

/** Jumlah baris catatan yang ditampilkan di panel detail, terbaru di atas. */
private const val ACTIVITY_LINES_SHOWN = 80

/** Berapa detik terakhir yang dirata-ratakan untuk estimasi sisa waktu. */
private const val ETA_WINDOW_SECONDS = 20

/**
 * Estimasi sisa waktu: sisa byte dibagi rata-rata kecepatan jaringan beberapa detik terakhir. Hanya perkiraan,
 * karena kecepatan berubah-ubah dan ada pekerjaan penutup (merapikan folder) setelah byte terakhir masuk.
 */
private fun estimateText(p: DownloadProgress, speedHistory: List<Long>): String {
    if (p.finishing) return "Estimasi: tinggal merapikan folder"
    if (p.totalBytes <= 0L) return "Estimasi: menghitung..."
    val remaining = (p.totalBytes - p.doneBytes).coerceAtLeast(0L)
    // Dihitung mulai dari detik pertama yang ada datanya, supaya detik-detik awal tanpa data (pemeriksaan file)
    // tidak menggelembungkan estimasi
    val window = speedHistory.takeLast(ETA_WINDOW_SECONDS).dropWhile { it == 0L }
    val average = if (window.isEmpty()) 0L else window.sum() / window.size
    if (average < 1024L) return "Estimasi: menunggu data masuk..."
    return "Estimasi sisa waktu: sekitar ${formatDuration(remaining / average)} (sisa ${formatBytes(remaining)})"
}

/**
 * Panel kegiatan: satu baris tentang yang sedang dikerjakan, dan tombol untuk membuka catatan lengkap berjam
 * beserta hitungan file. Beberapa tahap berjalan bersamaan, jadi baris ini mengikuti kejadian terbaru.
 */
@Composable
private fun ActivityPanel(paused: Boolean, progress: DownloadProgress, speedHistory: List<Long>) {
    val current by DownloadActivity.now.collectAsState()
    val entries by DownloadActivity.entries.collectAsState()
    val stats by DownloadActivity.stats.collectAsState()
    var expanded by remember { mutableStateOf(false) }

    val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text(
                if (paused) "Terakhir dikerjakan" else "Sedang dikerjakan",
                style = MaterialTheme.typography.labelMedium
            )
            Spacer(Modifier.height(4.dp))
            Text(
                current.text.ifBlank { "Menyiapkan..." },
                style = MaterialTheme.typography.bodyMedium,
                maxLines = if (expanded) 5 else 2,
                overflow = TextOverflow.Ellipsis
            )
            if (!paused) {
                Text(estimateText(progress, speedHistory), style = MaterialTheme.typography.bodySmall)
            }
            TextButton(
                onClick = { expanded = !expanded },
                contentPadding = PaddingValues(0.dp)
            ) {
                Text(if (expanded) "Sembunyikan detail" else "Tampilkan detail")
            }

            if (expanded) {
                val counts = buildList {
                    if (stats.depotsTotal > 0) add("Depot selesai: ${stats.depotsDone} dari ${stats.depotsTotal}")
                    if (stats.filesAllocated > 0) add("File disiapkan di penyimpanan: ${stats.filesAllocated}")
                    if (stats.filesChecked > 0) add("File diperiksa ulang: ${stats.filesChecked}")
                    if (stats.filesWritten > 0) add("File selesai ditulis: ${stats.filesWritten}")
                }
                counts.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                if (counts.isNotEmpty()) Spacer(Modifier.height(8.dp))

                Text(
                    "Beberapa tahap berjalan bersamaan. Data dari Steam berbentuk terkompresi, jadi tiap bagian " +
                        "didekompresi dulu sebelum ditulis ke file. Library tidak melaporkan dekompresi sebagai " +
                        "tahap terpisah, jadi ia ikut dihitung di baris mengunduh dan menulis.",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(8.dp))

                val shown = entries.asReversed().take(ACTIVITY_LINES_SHOWN)
                if (shown.isEmpty()) {
                    Text("Belum ada catatan.", style = MaterialTheme.typography.bodySmall)
                }
                shown.forEach { entry ->
                    Row(Modifier.padding(vertical = 2.dp)) {
                        Text(
                            timeFormat.format(Date(entry.timeMs)),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            entry.text,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                if (entries.size > ACTIVITY_LINES_SHOWN) {
                    Text(
                        "${entries.size - ACTIVITY_LINES_SHOWN} catatan lebih lama disembunyikan.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

/**
 * Gambar latar game (tanpa judul) dengan grafik di bagian bawahnya: candle kecepatan jaringan dan garis kecepatan
 * tulis ke disk yang saling menimpa. Selama gambar belum siap atau game tidak punya gambar latar, yang tampil warna polos.
 */
@Composable
private fun DownloadBackdrop(
    appId: Int,
    networkSamples: List<Long>,
    diskSamples: List<Long>,
    networkBps: Long,
    diskBps: Long,
    showLegend: Boolean,
) {
    val hero by produceState<Bitmap?>(initialValue = GameImages.cachedHero(appId), key1 = appId) {
        value = GameImages.loadHero(appId)
    }

    Box(
        Modifier
            .fillMaxWidth()
            .height(240.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        val b = hero
        if (b != null) {
            Image(
                bitmap = b.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize()
            )
        }
        // Bayangan tipis di atas dan bawah supaya keterangan dan candle tetap terbaca di gambar yang terang
        Box(
            Modifier
                .matchParentSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.35f),
                        0.3f to Color.Transparent,
                        0.6f to Color.Transparent,
                        1f to Color.Black.copy(alpha = 0.35f)
                    )
                )
        )
        SpeedChart(
            network = networkSamples,
            disk = diskSamples,
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(110.dp)
        )
        if (showLegend) {
            Column(
                Modifier.align(Alignment.TopEnd).padding(10.dp),
                horizontalAlignment = Alignment.End
            ) {
                LegendItem("Jaringan ${formatSpeed(networkBps)}") {
                    Box(Modifier.size(width = 6.dp, height = 10.dp).background(CANDLE_COLOR))
                }
                Spacer(Modifier.height(2.dp))
                LegendItem("Tulis ke disk ${formatSpeed(diskBps)}") {
                    Box(Modifier.size(width = 14.dp, height = 2.dp).background(DISK_LINE_COLOR))
                }
            }
        }
    }
}

// Candle jaringan: putih dengan opasitas 45%. Garis disk: biru muda, supaya tampak di atas candle putih.
private val CANDLE_COLOR = Color.White.copy(alpha = 0.45f)
private val DISK_LINE_COLOR = Color(0xFF66C0F4)

/** Satu baris keterangan grafik: penanda warna lalu teks, dengan bayangan supaya terbaca di atas gambar. */
@Composable
private fun LegendItem(text: String, swatch: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall.copy(
                color = Color.White,
                shadow = Shadow(Color.Black.copy(alpha = 0.7f), blurRadius = 4f)
            )
        )
        Spacer(Modifier.width(6.dp))
        swatch()
    }
}

/**
 * Grafik kecepatan: satu candle per detik untuk jaringan (terbaru di kanan, jarak antar batang sama dengan lebar
 * batang, jadi "l l l" bukan "lll"), dan di atasnya satu garis untuk kecepatan tulis ke disk. Keduanya memakai
 * skala yang sama, jadi terlihat langsung kalau disk tertinggal dari jaringan.
 */
@Composable
fun SpeedChart(network: List<Long>, disk: List<Long>, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        if (network.isEmpty() && disk.isEmpty()) return@Canvas

        val peak = maxOf(network.maxOrNull() ?: 0L, disk.maxOrNull() ?: 0L, 1L).toFloat()
        // Batang dan celah sama lebar: 60 batang dan 59 celah berarti 119 satuan lebar
        val unit = size.width / (2 * SPEED_HISTORY_SIZE - 1)
        val minHeight = 2.dp.toPx()

        val networkFirst = SPEED_HISTORY_SIZE - network.size
        network.forEachIndexed { i, v ->
            val h = (v / peak * size.height).coerceAtLeast(minHeight)
            val x = (networkFirst + i) * 2 * unit
            drawRect(color = CANDLE_COLOR, topLeft = Offset(x, size.height - h), size = Size(unit, h))
        }

        if (disk.isNotEmpty()) {
            val diskFirst = SPEED_HISTORY_SIZE - disk.size
            // Titik garis ada di tengah batang candle pada detik yang sama
            fun xAt(i: Int) = (diskFirst + i) * 2 * unit + unit / 2f
            fun yAt(v: Long) = size.height - (v / peak * size.height).coerceIn(0f, size.height)

            val line = Path()
            disk.forEachIndexed { i, v ->
                if (i == 0) line.moveTo(xAt(i), yAt(v)) else line.lineTo(xAt(i), yAt(v))
            }
            drawPath(
                path = line,
                color = DISK_LINE_COLOR,
                style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
            )
            if (disk.size == 1) {
                drawCircle(color = DISK_LINE_COLOR, radius = 2.dp.toPx(), center = Offset(xAt(0), yAt(disk[0])))
            }
        }
    }
}

@Composable
fun DoneScreen(path: String, onBack: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text("Download selesai!", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text("Tersimpan di:\n$path")
        Spacer(Modifier.height(16.dp))
        Text(
            "Hubungkan HP ke PC via USB dan salin folder SteamGames (di Download) ke PC. " +
                "Di Steam, buka Settings > Storage lalu tambahkan folder itu sebagai library. " +
                "Atau salin isi folder steamapps ke folder steamapps Steam yang sudah ada, " +
                "gabungkan dan jangan menimpa seluruh folder. " +
                "Kalau Steam masih menawarkan pembaruan, pilih Verify integrity pada game tersebut."
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onBack) { Text("Download game lain") }
    }
}

@Composable
fun ErrorScreen(message: String, onBack: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text("Terjadi kesalahan", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(message, color = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(16.dp))
        Button(onClick = onBack) { Text("Kembali ke daftar") }
    }
}
