package com.example.steamgrab

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

// Layar pilih penyimpanan utama dan halaman info penyimpanan, sesuai desain: kotak kaca di atas layar izin yang
// diburamkan. Pemilih folder di bawah bergaya Browse di Windows.

private val GAMES_COLOR = Color(0xFF614AE3)
private val OTHER_COLOR = Color(0xFFADFF09)
private val FREE_COLOR = Color(0xFFC6CFD6)
private val FOLDER_COLOR = Color(0xFFF5C451)
private val NEXT_ACTIVE = Color(0xFF6435FD)
private val NEXT_IDLE = Color(0xFF28414F)

// Kotak kaca di desain: 665,6 x 832,8 satuan, tepi atasnya di y=274,4 pada halaman setinggi 1440
private const val PANEL_X = 81.3f
private const val PANEL_Y = 274.4f
private const val PANEL_W = 665.6f
private const val PANEL_H = 832.8f

// Jendela info game: 771,9 x 452,5 satuan, tepi atasnya di y=457,2
private const val INFO_X = 21.4f
private const val INFO_Y = 457.2f
private const val INFO_W = 771.9f
private const val INFO_H = 452.5f

/** Daftar penyimpanan yang tersambung. Dicek ulang saat kembali ke layar ini dan saat flashdisk atau kartu SD dipasang. */
@Composable
private fun rememberVolumes(version: Int): List<StorageVolumeInfo> {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) Libraries.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                Libraries.refresh()
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_MEDIA_MOUNTED)
            addAction(Intent.ACTION_MEDIA_UNMOUNTED)
            addAction(Intent.ACTION_MEDIA_REMOVED)
            addAction(Intent.ACTION_MEDIA_EJECT)
            addDataScheme("file")
        }
        val registered = try {
            ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            true
        } catch (e: Exception) {
            DebugLog.log("Tidak bisa memantau pemasangan penyimpanan: ${e.describe()}")
            false
        }
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            if (registered) runCatching { context.unregisterReceiver(receiver) }
        }
    }
    return remember(version) {
        Libraries.volumes().also { list ->
            // Dicatat supaya kalau flashdisk tidak muncul, penyebabnya terlihat di log Debug
            DebugLog.log("Volume dari sistem: " + Libraries.rawReport())
            DebugLog.log(
                "Penyimpanan terdeteksi: " + list.joinToString("; ") {
                    "${it.id} ${it.root.path}${if (it.removable) " (lepasan)" else ""}"
                }
            )
        }
    }
}

/**
 * Lapisan untuk pop-up. Layar di belakang tidak diburamkan dan tidak digelapkan, seperti di desain: hanya jendela
 * pop-up yang berefek kaca. Lapisan ini menahan ketukan supaya tidak tembus, dan menunggu foto layar siap sebelum
 * jendelanya digambar.
 */
@Composable
private fun PopupOverlay(onOutsideTap: (() -> Unit)? = null, content: @Composable (Du) -> Unit) {
    val glass = rememberGlass()
    ProvideDu { du ->
        Box(
            Modifier
                .fillMaxSize()
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                    if (glass.ready) onOutsideTap?.invoke()
                },
            contentAlignment = Alignment.Center
        ) {
            if (glass.ready) {
                CompositionLocalProvider(LocalGlassBackdrop provides glass.backdrop) {
                    Box(Modifier.systemBarsPadding(), contentAlignment = Alignment.Center) { content(du) }
                }
            }
        }
    }
}

/** Gaya garis tepi jendela kaca, diambil dari warna piksel di PDF desain. */
private enum class SheetBorder { MAIN, FADE }

private val MAIN_BORDER = Brush.linearGradient(listOf(Color(0xFF653AFD), Color(0xFF2B4668)))
private val FADE_BORDER = Brush.verticalGradient(listOf(Color(0x52FFFFFF), Color(0x0DFFFFFF)))

/**
 * Jendela kaca. [SheetBorder.MAIN] (ungu ke biru) untuk jendela utama, [SheetBorder.FADE] (putih yang memudar ke
 * bawah) untuk jendela info game dan menu. Garis tepi digambar paling atas.
 */
@Composable
private fun GlassSheet(
    modifier: Modifier,
    radius: Float = 24f,
    border: SheetBorder = SheetBorder.MAIN,
    content: @Composable BoxScope.() -> Unit,
) {
    val du = LocalDu.current
    GlassBox(
        shape = RoundedCornerShape(du.dp(radius)),
        borderWidth = du.dp(if (border == SheetBorder.MAIN) 3f else 2.2f),
        borderBrush = if (border == SheetBorder.MAIN) MAIN_BORDER else FADE_BORDER,
        modifier = modifier,
        content = content
    )
}

@Composable
private fun NextButton(text: String, enabled: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val du = LocalDu.current
    Box(
        modifier
            .size(du.dp(148.7f), du.dp(50.4f))
            .clip(RoundedCornerShape(du.dp(25.2f)))
            .background(if (enabled) NEXT_ACTIVE else NEXT_IDLE)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        DText(
            text, 31.2f, bold = true, lineHeight = 40f,
            color = if (enabled) Color.White else Color(0xFF808080)
        )
    }
}

@Composable
private fun volumeName(volume: StorageVolumeInfo): String =
    if (volume.primary) tr("Ponsel Saya", "My Phone") else volume.label.ifBlank { "USB" }

/** Satu penyimpanan sebagai baris pil: ikon, nama, dan pemakaiannya. Yang dipilih diberi bingkai terang. */
@Composable
private fun DiskPill(volume: StorageVolumeInfo, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val du = LocalDu.current
    val shape = RoundedCornerShape(du.dp(31.2f))
    val total = remember(volume.id) { volume.totalBytes }
    val used = (total - remember(volume.id) { volume.freeBytes }).coerceAtLeast(0L)
    Row(
        modifier
            .size(du.dp(553.7f), du.dp(62.4f))
            .clip(shape)
            .background(Color.White.copy(alpha = if (selected) 0.27f else 0.18f))
            .then(
                if (selected) {
                    Modifier.border(
                        du.dp(2f),
                        Brush.horizontalGradient(listOf(Color(0x33FFFFFF), Color.White, Color(0x33FFFFFF))),
                        shape
                    )
                } else {
                    Modifier
                }
            )
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Spacer(Modifier.width(du.dp(12.5f)))
        Box(Modifier.size(du.dp(46f)), contentAlignment = Alignment.Center) {
            if (volume.primary) {
                Image(
                    painter = painterResource(R.drawable.ic_disc),
                    contentDescription = null,
                    alpha = 0.85f,
                    modifier = Modifier.size(du.dp(46f))
                )
            } else {
                Image(
                    painter = painterResource(R.drawable.ic_usb),
                    contentDescription = null,
                    modifier = Modifier.size(du.dp(38f))
                )
            }
        }
        Spacer(Modifier.width(du.dp(10f)))
        DText(
            volumeName(volume), 21.7f, bold = true, lineHeight = 29f,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
        )
        DText(
            "${formatBytes(used)}/${formatBytes(total)}", 16.4f,
            color = Color.White.copy(alpha = 0.55f), lineHeight = 22f, maxLines = 1
        )
        Spacer(Modifier.width(du.dp(18f)))
    }
}

/**
 * Layar "Please select the primary disk": penyimpanan tempat game baru diunduh. Hanya ditampilkan kalau ada
 * penyimpanan eksternal. Tombol Next baru aktif setelah salah satu dipilih.
 */
@Composable
fun PrimaryDiskPopup(onNext: (String) -> Unit) {
    val version by Libraries.version.collectAsState()
    val volumes = rememberVolumes(version)
    var selected by remember { mutableStateOf<String?>(null) }
    PopupOverlay { du ->
        val o = PANEL_Y
        Box(Modifier.size(du.dp(810f), du.dp(PANEL_H))) {
            GlassSheet(Modifier.at(du, PANEL_X, 0f).size(du.dp(PANEL_W), du.dp(PANEL_H))) {}
            DTextAt(
                tr("Pilih penyimpanan utama", "Please select the primary disk"),
                x = PANEL_X + 52.8f, capTop = 316f, size = 42.3f, origin = o, width = 560f, bold = true,
                lineHeight = 59f, align = TextAlign.Center,
                shadow = Shadow(Color(0x66000000), Offset(0f, 3f), 8f)
            )
            Box(Modifier.at(du, 135.8f, 439.6f - o).size(du.dp(554.5f), du.dp(3f)).background(Color.White))
            volumes.forEachIndexed { i, v ->
                DiskPill(
                    volume = v,
                    selected = selected == v.id,
                    onClick = { selected = v.id },
                    modifier = Modifier.at(du, 134f, 526.6f + i * 85.6f - o)
                )
            }
            DTextAt(
                tr("Bisa diubah nanti di pengaturan", "You can change it later in settings"),
                x = 136f, capTop = 1035f, size = 16f, origin = o, width = 360f
            )
            NextButton(
                text = tr("Lanjut", "Next"),
                enabled = selected != null,
                onClick = { selected?.let(onNext) },
                modifier = Modifier.at(du, 517.2f, 1012.5f - o)
            )
        }
    }
}

private fun pathLabel(volume: StorageVolumeInfo, dir: File?): String {
    if (dir == null) return ""
    if (volume.primary) return dir.path.removePrefix("/storage/")
    val relative = runCatching { dir.relativeTo(volume.root).path }.getOrDefault(dir.path)
    return "${volume.label.ifBlank { "USB" }}/$relative"
}

/**
 * Halaman info satu penyimpanan: nama, folder library, pemakaian, dan game di dalamnya. Penyimpanan lain tampil
 * sebagai baris di bawah daftar game dan tetap terlihat walau daftarnya panjang. [setup] true saat dipakai sekali
 * di awal (tombol Next), dan false saat dibuka dari daftar game (tombol tutup).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun StoragePopup(onDone: () -> Unit, setup: Boolean = false) {
    val version by Libraries.version.collectAsState()
    val volumes = rememberVolumes(version)
    BackHandler(enabled = !setup, onBack = onDone)

    var shownId by remember { mutableStateOf(Libraries.primaryId) }
    val shown = volumes.firstOrNull { it.id == shownId } ?: volumes.first()
    val others = volumes.filter { it.id != shown.id }

    var browsing by remember { mutableStateOf<StorageVolumeInfo?>(null) }
    var blocked by remember { mutableStateOf(false) }
    var infoGame by remember { mutableStateOf<LibraryGame?>(null) }
    var deleteTarget by remember { mutableStateOf<LibraryGame?>(null) }
    var moveTarget by remember { mutableStateOf<LibraryGame?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    val moveState by GameMover.state.collectAsState()
    val scope = rememberCoroutineScope()

    val libDir = remember(version, shown.id) { Libraries.libraryDir(shown) }
    val games by produceState(emptyList<LibraryGame>(), libDir?.path, version) {
        value = withContext(Dispatchers.IO) { libDir?.let { Library(it).games() } ?: emptyList() }
    }
    val total = remember(version, shown.id) { shown.totalBytes }
    val free = remember(version, shown.id) { shown.freeBytes }
    val used = (total - free).coerceAtLeast(0L)
    val gamesBytes = games.sumOf { it.sizeOnDisk }.coerceAtMost(used)
    val otherBytes = (used - gamesBytes).coerceAtLeast(0L)

    // Game yang sedang diunduh atau dijeda tidak boleh dihapus atau dipindah
    fun isBusy(game: LibraryGame): Boolean {
        val st = DownloadService.status.value
        return (st is DownloadStatus.Running && st.appId == game.appId) ||
            (st is DownloadStatus.Paused && st.appId == game.appId)
    }
    val busyText = tr(
        "Game ini sedang diunduh atau dijeda. Selesaikan atau batalkan unduhannya dulu.",
        "This game is downloading or paused. Finish or cancel the download first."
    )

    PopupOverlay { du ->
        val o = PANEL_Y
        Box(Modifier.size(du.dp(810f), du.dp(PANEL_H))) {
            GlassSheet(Modifier.at(du, PANEL_X, 0f).size(du.dp(PANEL_W), du.dp(PANEL_H))) {}

            DTextAt(
                if (shown.primary) "Disc" else volumeName(shown),
                x = 134f, capTop = 330f, size = 42.3f, origin = o, width = 420f, bold = true,
                lineHeight = 57f, maxLines = 1,
                shadow = Shadow(Color(0x66000000), Offset(0f, 3f), 8f)
            )
            if (shown.removable) {
                Image(
                    painter = painterResource(R.drawable.ic_usb),
                    contentDescription = null,
                    alpha = 0.62f,
                    modifier = Modifier.at(du, 562.2f, 319.8f - o).size(du.dp(103.8f))
                )
            } else {
                Image(
                    painter = painterResource(R.drawable.ic_disc),
                    contentDescription = null,
                    alpha = 0.62f,
                    modifier = Modifier.at(du, 562.2f, 308.9f - o).size(du.dp(117.8f), du.dp(115f))
                )
            }

            // Mengetuk alamat library membuka pemilih folder, kecuali di library itu sudah ada game
            Box(
                Modifier
                    .at(du, 130f, 400f - o)
                    .size(du.dp(430f), du.dp(40f))
                    .clickable { if (games.isEmpty()) browsing = shown else blocked = true }
            )
            DTextAt(
                pathLabel(shown, libDir), x = 140f, capTop = 410f, size = 21.7f, origin = o, width = 420f, maxLines = 1
            )

            Box(Modifier.at(du, 137.1f, 449.1f - o).width(du.dp(554.5f))) {
                UsageBar(other = otherBytes, games = gamesBytes, free = free)
            }
            DTextAt(
                tr("Terpakai :${formatBytes(used)}/${formatBytes(total)}", "Usage :${formatBytes(used)}/${formatBytes(total)}"),
                x = 137f, capTop = 477f, size = 20.8f, origin = o, width = 320f, maxLines = 1
            )
            DTextAt(
                tr("Kosong: ${formatBytes(free)}", "Free: ${formatBytes(free)}"),
                x = 469f, capTop = 477f, size = 20.8f, origin = o, width = 220f, align = TextAlign.End, maxLines = 1
            )
            Box(Modifier.at(du, 137.4f, 507.7f - o).size(du.dp(554.7f), du.dp(3f)).background(Color.White))

            // Daftar game bisa digulir. Baris penyimpanan lain di bawahnya tetap terlihat, dan game yang bergulir ke
            // arahnya memudar.
            Column(
                Modifier
                    .at(du, 137.1f, 538.9f - o)
                    .width(du.dp(553.7f))
                    .heightIn(max = du.dp(451f))
            ) {
                val scroll = rememberScrollState()
                Box(
                    Modifier
                        .weight(1f, fill = false)
                        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                        .drawWithContent {
                            drawContent()
                            if (scroll.canScrollForward) {
                                val fade = size.height.coerceAtMost(46.dp.toPx())
                                drawRect(
                                    brush = Brush.verticalGradient(
                                        colors = listOf(Color.Black, Color.Transparent),
                                        startY = size.height - fade,
                                        endY = size.height
                                    ),
                                    topLeft = Offset(0f, size.height - fade),
                                    size = Size(size.width, fade),
                                    blendMode = BlendMode.DstIn
                                )
                            }
                        }
                        .verticalScroll(scroll)
                ) {
                    Column {
                        if (games.isEmpty()) {
                            Box(
                                Modifier.fillMaxWidth().height(du.dp(127.6f)),
                                contentAlignment = Alignment.TopCenter
                            ) {
                                DText(
                                    tr("Kosong", "Empty"), 25f,
                                    modifier = Modifier.padding(top = du.dp(30f)),
                                    color = Color.White.copy(alpha = 0.5f), lineHeight = 34f
                                )
                            }
                        } else {
                            games.forEachIndexed { i, game ->
                                if (i > 0) Spacer(Modifier.height(du.dp(15f)))
                                GameRow(
                                    game = game,
                                    canMove = others.isNotEmpty(),
                                    onInfo = { infoGame = game },
                                    onDelete = { if (isBusy(game)) notice = busyText else deleteTarget = game },
                                    onMove = { if (isBusy(game)) notice = busyText else moveTarget = game }
                                )
                            }
                        }
                    }
                }
                if (others.isNotEmpty()) {
                    Spacer(Modifier.height(du.dp(if (games.isEmpty()) 0f else 35f)))
                    Box(Modifier.fillMaxWidth().height(du.dp(3f)).background(Color.White))
                    Spacer(Modifier.height(du.dp(18f)))
                    others.forEachIndexed { i, v ->
                        if (i > 0) Spacer(Modifier.height(du.dp(12f)))
                        DiskPill(volume = v, selected = false, onClick = { shownId = v.id }, modifier = Modifier)
                    }
                }
            }

            NextButton(
                text = if (setup) tr("Lanjut", "Next") else tr("Tutup", "Close"),
                enabled = true,
                onClick = onDone,
                modifier = Modifier.at(du, 517.2f, 1012.5f - o)
            )
        }
    }

    infoGame?.let { game ->
        GameInfoPopup(game = game, volume = shown, libDir = libDir, onClose = { infoGame = null })
    }

    deleteTarget?.let { game ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(tr("Hapus game?", "Delete game?")) },
            text = {
                Text(
                    tr(
                        "${game.name} dan semua filenya (${formatBytes(game.sizeOnDisk)}) akan dihapus dari penyimpanan ini. " +
                            "Tindakan ini tidak bisa dibatalkan.",
                        "${game.name} and all its files (${formatBytes(game.sizeOnDisk)}) will be deleted from this " +
                            "storage. This cannot be undone."
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    deleteTarget = null
                    val dir = libDir
                    if (dir != null) {
                        scope.launch(Dispatchers.IO) {
                            val ok = Library(dir).deleteGame(game)
                            DebugLog.log("Hapus ${game.name}: ${if (ok) "selesai" else "ada file yang gagal dihapus"}")
                            Libraries.refresh()
                            if (!ok) notice = "Sebagian file ${game.name} gagal dihapus."
                        }
                    }
                }) { Text(tr("Hapus", "Delete")) }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text(tr("Batal", "Cancel")) } }
        )
    }

    moveTarget?.let { game ->
        AlertDialog(
            onDismissRequest = { moveTarget = null },
            title = { Text(tr("Pindahkan ke mana?", "Move to where?")) },
            text = {
                Column {
                    Text(
                        tr(
                            "${game.name} (${formatBytes(game.sizeOnDisk)}) disalin dulu seluruhnya, baru yang lama dihapus. " +
                                "Biarkan aplikasi terbuka sampai selesai.",
                            "${game.name} (${formatBytes(game.sizeOnDisk)}) is copied completely first, then the old " +
                                "copy is deleted. Keep the app open until it finishes."
                        ),
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(12.dp))
                    others.forEach { target ->
                        OutlinedButton(
                            onClick = {
                                moveTarget = null
                                val from = libDir
                                val to = Libraries.libraryDir(target)
                                if (from != null && to != null) {
                                    GameMover.start(game, Library(from), Library(to), volumeNameText(target))
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(volumeName(target)) }
                        Spacer(Modifier.height(8.dp))
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { moveTarget = null }) { Text(tr("Batal", "Cancel")) } }
        )
    }

    moveState?.let { m ->
        val done = m.finished || m.error != null
        AlertDialog(
            onDismissRequest = { if (done) GameMover.dismiss() },
            title = {
                Text(
                    when {
                        m.error != null -> tr("Pemindahan gagal", "Move failed")
                        m.finished -> tr("Selesai dipindahkan", "Move finished")
                        else -> tr("Memindahkan game", "Moving game")
                    }
                )
            },
            text = {
                Column {
                    Text(m.name, style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                    when {
                        m.error != null -> Text(m.error, style = MaterialTheme.typography.bodySmall)
                        m.finished -> Text(
                            tr("Game sekarang ada di ${m.targetLabel}.", "The game is now on ${m.targetLabel}."),
                            style = MaterialTheme.typography.bodySmall
                        )
                        else -> {
                            val fraction = if (m.totalBytes > 0L) (m.copiedBytes.toFloat() / m.totalBytes).coerceIn(0f, 1f) else 0f
                            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "${formatBytes(m.copiedBytes)} / ${formatBytes(m.totalBytes)} → ${m.targetLabel}",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            },
            confirmButton = {
                if (done) TextButton(onClick = { GameMover.dismiss() }) { Text("OK") }
            },
            dismissButton = {
                if (!done) TextButton(onClick = { GameMover.cancel() }) { Text(tr("Batal", "Cancel")) }
            }
        )
    }

    notice?.let { text ->
        AlertDialog(
            onDismissRequest = { notice = null },
            confirmButton = { TextButton(onClick = { notice = null }) { Text(tr("Mengerti", "OK")) } },
            text = { Text(text) }
        )
    }

    browsing?.let { volume ->
        val current = Libraries.libraryDir(volume)
        // Mulai dari folder induk library yang sekarang, atau dari akar penyimpanan kalau belum ada
        val start = current?.parentFile?.takeIf { it.path.startsWith(volume.root.path) && it.exists() } ?: volume.root
        FolderBrowserDialog(
            volume = volume,
            start = start,
            onPick = { dir ->
                runCatching { Library(dir).ensureLibrary() }
                    .onFailure { DebugLog.log("Gagal menyiapkan library di ${dir.path}: ${it.describe()}") }
                Libraries.setLibraryDir(volume, dir)
                DebugLog.log("Library ${volume.id} diatur ke ${dir.path}")
                browsing = null
            },
            onDismiss = { browsing = null }
        )
    }

    if (blocked) {
        AlertDialog(
            onDismissRequest = { blocked = false },
            title = { Text(tr("Library masih berisi game", "The library still has games")) },
            text = {
                Text(
                    tr(
                        "Alamat library tidak bisa diubah selama masih ada game di dalamnya, karena game itu akan " +
                            "hilang dari daftar. Pindahkan atau hapus gamenya dulu.",
                        "The library location cannot be changed while it still holds games, because they would " +
                            "disappear from the list. Move or delete the games first."
                    )
                )
            },
            confirmButton = { TextButton(onClick = { blocked = false }) { Text(tr("Mengerti", "OK")) } }
        )
    }
}

/** Nama penyimpanan sebagai teks biasa, untuk dipakai di luar komposisi (pesan pemindahan). */
private fun volumeNameText(volume: StorageVolumeInfo): String =
    if (volume.primary) "My Phone" else volume.label.ifBlank { "USB" }

/** Satu game di daftar. Ketuk membuka info, tahan membuka menu Delete, Move, dan Info. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GameRow(
    game: LibraryGame,
    canMove: Boolean,
    onInfo: () -> Unit,
    onDelete: () -> Unit,
    onMove: () -> Unit,
) {
    val du = LocalDu.current
    val haptic = LocalHapticFeedback.current
    var menu by remember { mutableStateOf(false) }
    val platform = PlatformStore.get(game.appId) ?: Platform.WINDOWS
    val platformIcon = when (platform) {
        Platform.LINUX -> R.drawable.ic_linux
        Platform.WINDOWS -> R.drawable.ic_windows
        else -> null
    }
    Box(Modifier.fillMaxWidth().height(du.dp(62.4f))) {
        Row(
            Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(du.dp(12f)))
                .background(Color(0x40000000))
                .combinedClickable(
                    onClick = onInfo,
                    onLongClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        menu = true
                    }
                )
                .padding(start = du.dp(9.4f), end = du.dp(11.5f)),
            verticalAlignment = Alignment.CenterVertically
        ) {
            GameIcon(game.appId, Modifier.size(du.dp(44.9f)))
            Spacer(Modifier.width(du.dp(8f)))
            Column(Modifier.weight(1f)) {
                DText(
                    game.name, 21.7f, bold = true, lineHeight = 29f,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                if (!game.complete) {
                    DText(tr("Belum selesai", "Incomplete"), 15f, color = Color(0xFFFFC46B), lineHeight = 20f)
                }
            }
            Spacer(Modifier.width(du.dp(8f)))
            Column(horizontalAlignment = Alignment.End) {
                if (platformIcon != null) {
                    Image(
                        painter = painterResource(platformIcon),
                        contentDescription = null,
                        alpha = 0.6f,
                        modifier = Modifier.size(du.dp(19.6f))
                    )
                }
                DText(
                    formatBytes(game.sizeOnDisk), 16.4f,
                    color = Color.White.copy(alpha = 0.6f), lineHeight = 22f, maxLines = 1
                )
            }
        }
        if (menu) {
            val overhang = with(LocalDensity.current) { du.dp(3.5f).roundToPx() }
            Popup(
                alignment = Alignment.TopEnd,
                offset = IntOffset(overhang, 0),
                onDismissRequest = { menu = false },
                properties = PopupProperties(focusable = true)
            ) {
                GameMenu(
                    canMove = canMove,
                    onDelete = { menu = false; onDelete() },
                    onMove = { menu = false; onMove() },
                    onInfo = { menu = false; onInfo() }
                )
            }
        }
    }
}

/** Menu tahan di baris game: Delete, Move (hanya kalau ada penyimpanan lain), dan Info. */
@Composable
private fun GameMenu(canMove: Boolean, onDelete: () -> Unit, onMove: () -> Unit, onInfo: () -> Unit) {
    val du = LocalDu.current
    val shape = RoundedCornerShape(du.dp(24f))
    val kinds = buildList {
        add(0)
        if (canMove) add(1)
        add(2)
    }
    Column(
        Modifier
            .width(du.dp(168.3f))
            .clip(shape)
            // Hampir tidak tembus pandang seperti di desain, supaya tulisan di baris game di belakangnya tidak terlihat
            .background(Color(0xF50A1016))
            .border(du.dp(2.2f), FADE_BORDER, shape)
            .padding(vertical = du.dp(12f))
    ) {
        kinds.forEachIndexed { index, kind ->
            if (index > 0) {
                Box(
                    Modifier
                        .padding(start = du.dp(56.5f))
                        .width(du.dp(88.8f))
                        .height(du.dp(2.2f))
                        .background(Color.White)
                )
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(du.dp(37.9f))
                    .clickable {
                        when (kind) {
                            0 -> onDelete()
                            1 -> onMove()
                            else -> onInfo()
                        }
                    },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Spacer(Modifier.width(du.dp(21.2f)))
                MenuIcon(kind, Modifier.size(du.dp(28f)))
                Spacer(Modifier.width(du.dp(12f)))
                DText(
                    when (kind) {
                        0 -> tr("Hapus", "Delete")
                        1 -> tr("Pindah", "Move")
                        else -> "Info"
                    },
                    19f, lineHeight = 26f
                )
            }
        }
    }
}

/** Ikon menu digambar sendiri karena ikon extended tidak dipakai di proyek ini: tempat sampah, folder berpanah, info. */
@Composable
private fun MenuIcon(kind: Int, modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val sw = w * 0.085f
        val stroke = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round)
        when (kind) {
            0 -> {
                drawLine(Color.White, Offset(w * 0.16f, h * 0.27f), Offset(w * 0.84f, h * 0.27f), sw, StrokeCap.Round)
                drawPath(
                    Path().apply {
                        moveTo(w * 0.38f, h * 0.27f)
                        lineTo(w * 0.38f, h * 0.14f)
                        lineTo(w * 0.62f, h * 0.14f)
                        lineTo(w * 0.62f, h * 0.27f)
                    },
                    Color.White, style = stroke
                )
                drawPath(
                    Path().apply {
                        moveTo(w * 0.25f, h * 0.27f)
                        lineTo(w * 0.30f, h * 0.88f)
                        lineTo(w * 0.70f, h * 0.88f)
                        lineTo(w * 0.75f, h * 0.27f)
                    },
                    Color.White, style = stroke
                )
                drawLine(Color.White, Offset(w * 0.43f, h * 0.42f), Offset(w * 0.43f, h * 0.74f), sw, StrokeCap.Round)
                drawLine(Color.White, Offset(w * 0.57f, h * 0.42f), Offset(w * 0.57f, h * 0.74f), sw, StrokeCap.Round)
            }
            1 -> {
                drawPath(
                    Path().apply {
                        moveTo(w * 0.08f, h * 0.80f)
                        lineTo(w * 0.08f, h * 0.22f)
                        lineTo(w * 0.36f, h * 0.22f)
                        lineTo(w * 0.44f, h * 0.34f)
                        lineTo(w * 0.92f, h * 0.34f)
                        lineTo(w * 0.92f, h * 0.80f)
                        close()
                    },
                    Color.White, style = stroke
                )
                drawLine(Color.White, Offset(w * 0.34f, h * 0.58f), Offset(w * 0.68f, h * 0.58f), sw, StrokeCap.Round)
                drawPath(
                    Path().apply {
                        moveTo(w * 0.58f, h * 0.46f)
                        lineTo(w * 0.70f, h * 0.58f)
                        lineTo(w * 0.58f, h * 0.70f)
                    },
                    Color.White, style = stroke
                )
            }
            else -> {
                drawCircle(Color.White, radius = w * 0.42f, style = stroke)
                drawCircle(Color.White, radius = sw * 0.75f, center = Offset(w / 2f, h * 0.31f))
                drawLine(Color.White, Offset(w / 2f, h * 0.45f), Offset(w / 2f, h * 0.72f), sw, StrokeCap.Round)
            }
        }
    }
}

@Composable
private fun descriptorLabel(id: Int): String? = when (id) {
    1 -> tr("Konten Seksual", "Sexual Content")
    2 -> tr("Kekerasan", "Violence")
    3 -> tr("Khusus Dewasa", "Adults Only")
    4 -> tr("Ketelanjangan", "Nudity")
    5 -> tr("Konten Dewasa", "Mature Content")
    else -> null
}

/**
 * Jendela info game: gambar header sebagai latar, nama, pengembang, peringkat usia dan nilai ulasan (dari toko
 * Steam, kalau berhasil diambil), lalu folder, versi, ukuran, dan status unduhannya.
 */
@Composable
private fun GameInfoPopup(game: LibraryGame, volume: StorageVolumeInfo, libDir: File?, onClose: () -> Unit) {
    val extra by produceState<GameExtra?>(GameInfoStore.cached(game.appId), game.appId) {
        value = GameInfoStore.load(game.appId) ?: value
    }
    // Gambar hero tanpa judul seperti di desain. Kalau game tidak punya, dipakai gambar header toko.
    val hero by produceState<Bitmap?>(GameImages.cachedHero(game.appId), game.appId) {
        value = GameImages.loadHero(game.appId) ?: value
    }
    val header by produceState<Bitmap?>(GameImages.cached(game.appId), game.appId) {
        if (hero == null) value = GameImages.load(game.appId) ?: value
    }
    val banner = hero ?: header
    val platform = PlatformStore.get(game.appId) ?: Platform.WINDOWS
    val platformIcon = when (platform) {
        Platform.LINUX -> R.drawable.ic_linux
        Platform.WINDOWS -> R.drawable.ic_windows
        else -> null
    }
    PopupOverlay(onOutsideTap = onClose) { du ->
        val o = INFO_Y
        Box(
            Modifier
                .size(du.dp(INFO_W), du.dp(INFO_H))
                // Ketukan di dalam jendela tidak menutupnya
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
        ) {
            GlassSheet(Modifier.fillMaxSize(), radius = 40f, border = SheetBorder.FADE) {

            DTextAt(
                tr("Informasi game", "Information about games"),
                x = 55f - INFO_X, capTop = 490f, size = 21.7f, origin = o, width = 560f, maxLines = 1
            )
            Box(
                Modifier
                    .at(du, 670f, 18f)
                    .size(du.dp(56f))
                    .clip(CircleShape)
                    .clickable(onClick = onClose),
                contentAlignment = Alignment.Center
            ) {
                Canvas(Modifier.size(du.dp(26f))) {
                    val s = size.width
                    drawLine(Color.White, Offset(0f, 0f), Offset(s, s), s * 0.16f, StrokeCap.Round)
                    drawLine(Color.White, Offset(s, 0f), Offset(0f, s), s * 0.16f, StrokeCap.Round)
                }
            }

            Box(Modifier.at(du, 0f, 76.7f).size(du.dp(INFO_W), du.dp(249.7f)).clipToBounds()) {
                banner?.let {
                    Image(
                        bitmap = it.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                Box(
                    Modifier.fillMaxSize()
                        .background(Brush.horizontalGradient(listOf(Color(0xB3000000), Color(0x00000000))))
                )
                Box(
                    Modifier.fillMaxSize()
                        .background(Brush.verticalGradient(listOf(Color(0x00000000), Color(0x99000000))))
                )
            }

            DTextAt(
                game.name, x = 39f - INFO_X, capTop = 571f, size = 42.3f, origin = o, width = 640f, bold = true,
                lineHeight = 58f, maxLines = 1,
                shadow = Shadow(Color(0x99000000), Offset(0f, 3f), 8f)
            )
            // Pengembang selalu ditampilkan begitu data selesai dimuat, "-" kalau Steam tidak punya datanya
            val maker = extra?.publisher ?: extra?.developer ?: if (extra != null) "-" else null
            if (maker != null) {
                DTextAt(
                    maker, x = 41f - INFO_X, capTop = 622f, size = 25f, origin = o, width = 600f,
                    color = Color.White.copy(alpha = 0.85f), maxLines = 1
                )
            }
            val chips = extra?.descriptors.orEmpty().distinct().mapNotNull { descriptorLabel(it) }
            if (chips.isNotEmpty()) {
                Row(
                    Modifier.at(du, 19.6f, 655f - o),
                    horizontalArrangement = Arrangement.spacedBy(du.dp(13f))
                ) {
                    chips.forEach { text ->
                        Box(
                            Modifier
                                .height(du.dp(43f))
                                .clip(RoundedCornerShape(du.dp(21.5f)))
                                .background(Color(0x80704A42))
                                .padding(horizontal = du.dp(16f)),
                            contentAlignment = Alignment.Center
                        ) { DText(text, 14.6f, lineHeight = 20f, maxLines = 1) }
                    }
                }
            }
            extra?.ageLabel?.let { label ->
                Column(
                    Modifier
                        .at(du, 22.7f, 711.2f - o)
                        .size(du.dp(50.5f), du.dp(70.1f))
                        .clip(RoundedCornerShape(du.dp(5f)))
                        .background(Color.White),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    DText(label, 34f, color = Color.Black, bold = true, lineHeight = 40f)
                    extra?.ageSub?.let { DText(it, 13f, color = Color.Black, bold = true, lineHeight = 16f) }
                }
            }
            extra?.score?.let { score ->
                val text = String.format(Locale.US, "%.1f", score)
                DTextAt(
                    tr(text.replace('.', ','), text),
                    x = 633.6f, capTop = 737f, size = 42.3f, origin = o, width = 100f, bold = true,
                    align = TextAlign.End, shadow = Shadow(Color(0x99000000), Offset(0f, 3f), 8f)
                )
            }

            DTextAt(
                pathLabel(volume, libDir), x = 41f - INFO_X, capTop = 800f, size = 25f, origin = o, width = 640f,
                maxLines = 1
            )
            val build = game.buildId.takeIf { it.isNotBlank() && it != "0" } ?: "-"
            DTextAt(
                tr(
                    "Versi: $build  Ukuran: ${formatBytes(game.sizeOnDisk)}",
                    "Version: $build  Size: ${formatBytes(game.sizeOnDisk)}"
                ),
                x = 41f - INFO_X, capTop = 831f, size = 20f, origin = o, width = 640f, maxLines = 1
            )
            DTextAt(
                if (game.complete) tr("Lengkap", "Complete") else tr("Belum selesai", "Incomplete"),
                x = 41f - INFO_X, capTop = 863f, size = 18f, origin = o, width = 300f,
                color = if (game.complete) OTHER_COLOR else Color(0xFFFFC46B)
            )
            if (platformIcon != null) {
                Image(
                    painter = painterResource(platformIcon),
                    contentDescription = null,
                    alpha = 0.9f,
                    modifier = Modifier.at(du, 702.8f - INFO_X, 804.1f - o).size(du.dp(53.3f))
                )
            }
            }
        }
    }
}

/** Ikon asli game dari Steam (Client Icon). Kotak polos selama belum ada atau kalau game tidak punya ikon. */
@Composable
private fun GameIcon(appId: Int, modifier: Modifier) {
    val du = LocalDu.current
    val bitmap by produceState<Bitmap?>(initialValue = GameImages.cachedIcon(appId), key1 = appId) {
        value = GameImages.loadIcon(appId)
    }
    val shape = RoundedCornerShape(du.dp(7f))
    val b = bitmap
    if (b != null) {
        Image(
            bitmap = b.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = modifier.clip(shape)
        )
    } else {
        Box(modifier.clip(shape).background(Color(0x33FFFFFF)))
    }
}

@Composable
private fun UsageBar(other: Long, games: Long, free: Long) {
    val du = LocalDu.current
    val total = (other + games + free).coerceAtLeast(1L).toFloat()
    Row(
        Modifier
            .fillMaxWidth()
            .height(du.dp(15.6f))
            .clip(RoundedCornerShape(du.dp(8f)))
            .background(FREE_COLOR)
    ) {
        if (other > 0L) Box(Modifier.weight((other / total).coerceAtLeast(0.001f)).fillMaxHeight().background(OTHER_COLOR))
        if (games > 0L) Box(Modifier.weight((games / total).coerceAtLeast(0.001f)).fillMaxHeight().background(GAMES_COLOR))
        if (free > 0L) Spacer(Modifier.weight((free / total).coerceAtLeast(0.001f)))
    }
}

@Composable
private fun FolderIcon(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        drawRoundRect(
            FOLDER_COLOR,
            topLeft = Offset(0f, h * 0.14f),
            size = Size(w * 0.44f, h * 0.26f),
            cornerRadius = CornerRadius(w * 0.06f)
        )
        drawRoundRect(
            FOLDER_COLOR,
            topLeft = Offset(0f, h * 0.28f),
            size = Size(w, h * 0.6f),
            cornerRadius = CornerRadius(w * 0.08f)
        )
    }
}

/**
 * Pemilih folder seperti "Browse for folder" di Windows: masuk ke subfolder, naik satu tingkat, membuat folder baru,
 * lalu memilih folder yang sedang terbuka. Tidak bisa naik melewati akar penyimpanan.
 */
@Composable
private fun FolderBrowserDialog(
    volume: StorageVolumeInfo,
    start: File,
    onPick: (File) -> Unit,
    onDismiss: () -> Unit,
) {
    var current by remember { mutableStateOf(start) }
    var refresh by remember { mutableStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }

    val listing = remember(current, refresh) { current.listFiles() }
    val children = remember(listing) {
        (listing?.filter { it.isDirectory && !it.name.startsWith(".") } ?: emptyList()).sortedBy { it.name.lowercase() }
    }
    val atRoot = current.path == volume.root.path

    val rootName = if (volume.primary) tr("Ponsel Kamu", "Your Phone") else volume.label
    val crumbs = remember(current) {
        try {
            current.relativeTo(volume.root).path.split('/').filter { it.isNotEmpty() && it != "." }
        } catch (e: IllegalArgumentException) {
            listOf(current.path)
        }
    }
    val sameFolderMessage = tr("Folder ini tidak bisa dibuka.", "This folder cannot be opened.")
    val notWritableMessage = tr("Folder ini tidak bisa ditulisi.", "This folder is not writable.")

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        GlassBackground {
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                Text(
                    tr("Pilih folder library", "Choose library folder"),
                    color = Color.White,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    (listOf(rootName) + crumbs).joinToString("  ›  "),
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 14.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0x66101820))
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                )
                Spacer(Modifier.height(10.dp))

                LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                    if (!atRoot) {
                        item {
                            FolderRow(name = "..", hint = tr("Naik satu tingkat", "Up one level")) {
                                current = current.parentFile ?: current
                                message = null
                            }
                        }
                    }
                    items(children, key = { it.path }) { dir ->
                        FolderRow(name = dir.name) {
                            current = dir
                            message = null
                        }
                    }
                    if (listing == null) {
                        item { Text(sameFolderMessage, color = Color(0xFFFF8A8A), modifier = Modifier.padding(12.dp)) }
                    } else if (children.isEmpty()) {
                        item {
                            Text(
                                tr("Tidak ada subfolder di sini.", "No subfolders here."),
                                color = Color.White.copy(alpha = 0.7f),
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    }
                }

                message?.let {
                    Text(it, color = Color(0xFFFF8A8A), fontSize = 14.sp, modifier = Modifier.padding(vertical = 6.dp))
                }
                Spacer(Modifier.height(8.dp))
                GlassButton(
                    text = tr("Pilih folder ini", "Select this folder"),
                    onClick = {
                        if (listing == null) message = sameFolderMessage
                        else if (!Libraries.canWrite(current)) message = notWritableMessage
                        else onPick(current)
                    }
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = { creating = true }, modifier = Modifier.weight(1f)) {
                        Text(tr("Folder baru", "New folder"), color = Color.White)
                    }
                    OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                        Text(tr("Batal", "Cancel"), color = Color.White)
                    }
                }
            }
        }
    }

    if (creating) {
        var name by remember { mutableStateOf("") }
        val failText = tr("Gagal membuat folder.", "Could not create the folder.")
        AlertDialog(
            onDismissRequest = { creating = false },
            title = { Text(tr("Folder baru", "New folder")) },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text(tr("Nama folder", "Folder name")) }
                )
            },
            confirmButton = {
                TextButton(
                    enabled = name.isNotBlank() && !name.contains('/'),
                    onClick = {
                        val dir = File(current, name.trim())
                        if (dir.exists() || dir.mkdirs()) {
                            current = dir
                            refresh++
                            message = null
                        } else {
                            message = failText
                        }
                        creating = false
                    }
                ) { Text(tr("Buat", "Create")) }
            },
            dismissButton = { TextButton(onClick = { creating = false }) { Text(tr("Batal", "Cancel")) } }
        )
    }
}

@Composable
private fun FolderRow(name: String, hint: String? = null, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FolderIcon(Modifier.size(30.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(name, color = Color.White, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            hint?.let { Text(it, color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp) }
        }
    }
}
