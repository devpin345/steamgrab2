package com.example.steamgrab

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

// Layar Penyimpanan: library di tiap penyimpanan yang tersambung, dan pemilih folder bergaya Browse di Windows.

private val GAMES_COLOR = Color(0xFF614AE3)
private val OTHER_COLOR = Color(0xFFADFF09)
private val FREE_COLOR = Color(0xFFC6CFD6)
private val FOLDER_COLOR = Color(0xFFF5C451)

/**
 * Daftar penyimpanan (memori internal, kartu SD, flashdisk) dengan pemakaian, folder library, dan game di dalamnya.
 * Dipakai sekali di awal ([setup]) dan bisa dibuka lagi dari daftar game untuk mengatur lokasi.
 */
@Composable
fun StorageScreen(setup: Boolean, onDone: () -> Unit) {
    val context = LocalContext.current
    val version by Libraries.version.collectAsState()
    if (!setup) BackHandler(onBack = onDone)

    // Dicek ulang saat kembali ke layar ini dan saat flashdisk atau kartu SD dipasang atau dilepas
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

    val volumes = remember(version) { Libraries.volumes() }
    var browsing by remember { mutableStateOf<StorageVolumeInfo?>(null) }
    var blocked by remember { mutableStateOf(false) }
    var expandedId by remember { mutableStateOf<String?>(null) }

    GlassBackground {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(Modifier.widthIn(max = 480.dp).fillMaxWidth()) {
                Text(
                    tr("Tempat menyimpan game", "Where to keep your games"),
                    color = Color.White,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 4.dp)
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    tr(
                        "Tiap penyimpanan punya folder library sendiri. Game baru langsung disimpan ke penyimpanan " +
                            "yang tersambung. Kalau ada lebih dari satu, kamu ditanya setiap kali mulai mengunduh.",
                        "Each storage has its own library folder. New games are saved to the connected storage " +
                            "automatically. If there is more than one, you are asked each time you start a download."
                    ),
                    color = Color.White.copy(alpha = 0.8f),
                    fontSize = 14.sp,
                    modifier = Modifier.padding(start = 4.dp)
                )
                Spacer(Modifier.height(18.dp))

                volumes.forEach { volume ->
                    VolumeCard(
                        volume = volume,
                        version = version,
                        expanded = expandedId == volume.id,
                        onToggle = { expandedId = if (expandedId == volume.id) null else volume.id },
                        onPickFolder = { browsing = volume },
                        onChangeBlocked = { blocked = true }
                    )
                    Spacer(Modifier.height(16.dp))
                }
                if (volumes.none { !it.primary }) {
                    Text(
                        tr(
                            "Flashdisk atau kartu SD belum terdeteksi. Sambungkan, lalu daftarnya muncul di sini.",
                            "No USB drive or SD card detected. Connect one and it will appear here."
                        ),
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 13.sp,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                    Spacer(Modifier.height(16.dp))
                }

                GlassButton(
                    text = if (setup) tr("Lanjutkan", "Continue") else tr("Kembali", "Back"),
                    onClick = onDone
                )
            }
        }
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
                            "hilang dari daftar. Pemindahan game ke penyimpanan atau folder lain akan menyusul.",
                        "The library location cannot be changed while it still holds games, because they would " +
                            "disappear from the list. Moving games to another storage or folder is coming next."
                    )
                )
            },
            confirmButton = { TextButton(onClick = { blocked = false }) { Text(tr("Mengerti", "OK")) } }
        )
    }
}

@Composable
private fun VolumeCard(
    volume: StorageVolumeInfo,
    version: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
    onPickFolder: () -> Unit,
    onChangeBlocked: () -> Unit,
) {
    val libDir = remember(version, volume.id) { Libraries.libraryDir(volume) }
    val games by produceState(emptyList<LibraryGame>(), libDir?.path, version) {
        value = withContext(Dispatchers.IO) { libDir?.let { Library(it).games() } ?: emptyList() }
    }
    val total = remember(version, volume.id) { volume.totalBytes }
    val free = remember(version, volume.id) { volume.freeBytes }
    val used = (total - free).coerceAtLeast(0L)
    val gamesBytes = games.sumOf { it.sizeOnDisk }.coerceAtMost(used)
    val other = (used - gamesBytes).coerceAtLeast(0L)

    GlassCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            VolumeIcon(removable = volume.removable)
            Spacer(Modifier.width(12.dp))
            Text(
                if (volume.primary) tr("Ponsel Kamu", "Your Phone") else volume.label,
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            Text(formatBytes(total), color = Color.White.copy(alpha = 0.85f), fontSize = 16.sp)
        }
        Spacer(Modifier.height(14.dp))
        UsageBar(other = other, games = gamesBytes, free = free)
        Spacer(Modifier.height(8.dp))
        Text(
            tr(
                "Terpakai : ${formatBytes(used)}/${formatBytes(total)}    Kosong : ${formatBytes(free)}",
                "Usage : ${formatBytes(used)}/${formatBytes(total)}    Free : ${formatBytes(free)}"
            ),
            color = Color.White,
            fontSize = 14.sp
        )
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            LegendDot(OTHER_COLOR, tr("File lain", "Other files"))
            Spacer(Modifier.width(12.dp))
            LegendDot(GAMES_COLOR, tr("Game SteamGrab", "SteamGrab games"))
            Spacer(Modifier.width(12.dp))
            LegendDot(FREE_COLOR, tr("Kosong", "Free"))
        }
        Spacer(Modifier.height(16.dp))

        if (libDir == null) {
            OutlinedButton(onClick = onPickFolder, modifier = Modifier.fillMaxWidth()) {
                Text(
                    "+ " + tr("Tambahkan alamat penyimpanan", "Add storage location"),
                    color = Color.White
                )
            }
        } else {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(onClick = onToggle)
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FolderIcon(Modifier.size(34.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(libDir.path, color = Color.White, fontSize = 15.sp)
                    Text("${games.size} Game", color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
                }
                Chevron(up = expanded)
            }
            if (expanded) {
                Spacer(Modifier.height(8.dp))
                if (games.isEmpty()) {
                    Text(
                        tr("Belum ada game di library ini.", "No games in this library yet."),
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 14.sp
                    )
                } else {
                    games.forEach { game -> LibraryGameRow(game) }
                }
            }
            TextButton(onClick = if (games.isEmpty()) onPickFolder else onChangeBlocked) {
                Text(tr("Ubah alamat", "Change location"), color = Color(0xFF8FB4FF))
            }
        }
    }
}

@Composable
private fun LibraryGameRow(game: LibraryGame) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        GameHeader(appId = game.appId, modifier = Modifier.width(76.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                game.name,
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            val platform = (PlatformStore.get(game.appId) ?: Platform.WINDOWS).label
            val status = if (game.complete) "" else " · " + tr("Belum selesai", "Incomplete")
            Text(platform + status, color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
        }
        Spacer(Modifier.width(8.dp))
        Text(formatBytes(game.sizeOnDisk), color = Color.White, fontSize = 14.sp)
    }
}

@Composable
private fun UsageBar(other: Long, games: Long, free: Long) {
    val total = (other + games + free).coerceAtLeast(1L).toFloat()
    Row(
        Modifier
            .fillMaxWidth()
            .height(14.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(FREE_COLOR)
    ) {
        if (other > 0L) Box(Modifier.weight((other / total).coerceAtLeast(0.001f)).fillMaxHeight().background(OTHER_COLOR))
        if (games > 0L) Box(Modifier.weight((games / total).coerceAtLeast(0.001f)).fillMaxHeight().background(GAMES_COLOR))
        if (free > 0L) Spacer(Modifier.weight((free / total).coerceAtLeast(0.001f)))
    }
}

@Composable
private fun LegendDot(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(9.dp).clip(RoundedCornerShape(5.dp)).background(color))
        Spacer(Modifier.width(5.dp))
        Text(text, color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp)
    }
}

/** Ikon ponsel (memori internal) atau flashdisk. Digambar sendiri karena ikon extended tidak dipakai di proyek ini. */
@Composable
private fun VolumeIcon(removable: Boolean, modifier: Modifier = Modifier) {
    Canvas(modifier.size(40.dp)) {
        val w = size.width
        val h = size.height
        val c = Color.White
        if (!removable) {
            drawRoundRect(
                c,
                topLeft = Offset(w * 0.27f, h * 0.04f),
                size = Size(w * 0.46f, h * 0.92f),
                cornerRadius = CornerRadius(w * 0.08f),
                style = Stroke(width = w * 0.07f)
            )
            drawCircle(c, radius = w * 0.035f, center = Offset(w * 0.5f, h * 0.86f))
        } else {
            drawRoundRect(
                c,
                topLeft = Offset(w * 0.3f, h * 0.32f),
                size = Size(w * 0.4f, h * 0.64f),
                cornerRadius = CornerRadius(w * 0.06f),
                style = Stroke(width = w * 0.07f)
            )
            drawRect(c, topLeft = Offset(w * 0.38f, h * 0.06f), size = Size(w * 0.24f, h * 0.26f))
        }
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

@Composable
private fun Chevron(up: Boolean) {
    Canvas(Modifier.size(20.dp)) {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            if (up) {
                moveTo(w * 0.2f, h * 0.65f)
                lineTo(w * 0.5f, h * 0.35f)
                lineTo(w * 0.8f, h * 0.65f)
            } else {
                moveTo(w * 0.2f, h * 0.35f)
                lineTo(w * 0.5f, h * 0.65f)
                lineTo(w * 0.8f, h * 0.35f)
            }
        }
        drawPath(path, Color.White, style = Stroke(width = w * 0.1f))
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
