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
import androidx.compose.ui.graphics.Path
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

/** App ID game yang appmanifest-nya ada di folder steamapps, artinya sudah diunduh. */
private fun installedAppIds(): Set<Int> =
    Storage.steamLibraryDir().listFiles()
        ?.mapNotNull { f ->
            if (f.name.startsWith("appmanifest_") && f.name.endsWith(".acf")) {
                f.name.removePrefix("appmanifest_").removeSuffix(".acf").toIntOrNull()
            } else {
                null
            }
        }
        ?.toSet()
        ?: emptySet()

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
    val running by DownloadService.status.collectAsState()

    val shown = games.filter { game ->
        (query.isBlank() || game.name.contains(query, ignoreCase = true)) &&
            when (filter) {
                LibraryFilter.ALL -> true
                LibraryFilter.INSTALLED -> game.appId in installed
                LibraryFilter.NOT_INSTALLED -> game.appId !in installed
            }
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
                        val ownLabel = if (done) "Sudah diunduh" else "Dimiliki"
                        Text(listOfNotNull("App ID ${game.appId}", priceLabel, ownLabel).joinToString(" · "))
                    },
                    trailingContent = {
                        SizeLabel(size = sizes[game.appId], loading = sizesLoading)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(game.appId) }
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
    val barColor = MaterialTheme.colorScheme.primary

    // Baris keterangan: saat dijeda, saat ukuran sudah diketahui, atau tahap yang sedang berjalan
    val statusLine = when {
        state.paused -> "Dijeda. ${formatBytes(p.doneBytes)} sudah diunduh dan tidak hilang."
        known -> "Sudah diunduh ${formatBytes(p.doneBytes)} dari ${formatBytes(p.totalBytes)}"
        else -> p.phase.ifBlank { "Menyiapkan download..." }
    }

    Column(Modifier.fillMaxSize().padding(20.dp)) {
        GameHeader(appId = state.appId, modifier = Modifier.width(220.dp))
        Spacer(Modifier.height(12.dp))
        Text(
            state.gameName,
            style = MaterialTheme.typography.headlineSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(4.dp))
        Text(statusLine, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(16.dp))

        Row(Modifier.fillMaxWidth().height(220.dp)) {
            // Kiri: bar persentase
            Column(
                modifier = Modifier.width(72.dp).fillMaxHeight(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("$pct%", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .width(36.dp)
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .fillMaxHeight(p.percent.coerceIn(0f, 1f))
                            .background(barColor)
                    )
                }
            }

            Spacer(Modifier.width(16.dp))

            // Kanan: kecepatan per detik dengan grafiknya
            Column(Modifier.weight(1f).fillMaxHeight()) {
                Text("Kecepatan", style = MaterialTheme.typography.labelMedium)
                Text(formatSpeed(state.speedBps), style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(8.dp))
                SpeedGraph(samples = state.speedHistory, modifier = Modifier.fillMaxWidth().weight(1f))
            }
        }

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

/** Grafik kecepatan: garis dari kanan (terbaru) ke kiri, skala mengikuti nilai tertinggi. */
@Composable
fun SpeedGraph(samples: List<Long>, modifier: Modifier = Modifier) {
    val lineColor = MaterialTheme.colorScheme.primary
    val baseColor = MaterialTheme.colorScheme.outlineVariant

    Canvas(modifier = modifier) {
        drawLine(
            color = baseColor,
            start = Offset(0f, size.height),
            end = Offset(size.width, size.height),
            strokeWidth = 1.dp.toPx()
        )
        if (samples.size < 2) return@Canvas

        val peak = (samples.maxOrNull() ?: 0L).coerceAtLeast(1L).toFloat()
        val stepX = size.width / (SPEED_HISTORY_SIZE - 1)
        val path = Path()
        samples.forEachIndexed { i, v ->
            val x = size.width - (samples.size - 1 - i) * stepX
            val y = size.height - (v / peak) * size.height
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(
            path = path,
            color = lineColor,
            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
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
            "Hubungkan HP ke PC via USB. Salin isi folder steamapps (di Download/SteamGames/steamapps) " +
                "ke folder steamapps Steam di PC. Gabungkan dengan yang sudah ada, jangan menimpa seluruh folder. " +
                "Lalu buka Steam dan pilih Verify integrity pada game tersebut."
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
