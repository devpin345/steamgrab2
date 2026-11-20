package com.example.steamgrab

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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

// Info penyimpanan sebagai pop-up (sesuai desain): tiap penyimpanan yang tersambung dengan pemakaiannya, folder
// library, dan game di dalamnya. Pemilih folder di bawah bergaya Browse di Windows.

private val GAMES_COLOR = Color(0xFF614AE3)
private val OTHER_COLOR = Color(0xFFADFF09)
private val FREE_COLOR = Color(0xFFC6CFD6)
private val FOLDER_COLOR = Color(0xFFF5C451)

/**
 * Pop-up info penyimpanan di atas layar yang sedang tampil. Ketuk di luar kotak atau tombol kembali untuk menutup.
 * [setup] true saat dipakai sekali setelah izin diberikan, supaya petunjuknya menyebut "lanjut".
 */
@Composable
fun StoragePopup(onDone: () -> Unit, setup: Boolean = false) {
    val context = LocalContext.current
    val version by Libraries.version.collectAsState()
    BackHandler(onBack = onDone)

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

    val volumes = remember(version) {
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
    var browsing by remember { mutableStateOf<StorageVolumeInfo?>(null) }
    var blocked by remember { mutableStateOf(false) }
    var expandedId by remember { mutableStateOf<String?>(null) }

    ProvideDu { du ->
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0x66000000))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDone),
            contentAlignment = Alignment.Center
        ) {
            val maxCardHeight = (LocalConfiguration.current.screenHeightDp * 0.8f).dp
            Column(Modifier.systemBarsPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
                PopupPanel(
                    Modifier
                        .width(du.dp(665.6f))
                        .heightIn(max = maxCardHeight)
                        // Ketukan di dalam kotak tidak menutup pop-up
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                ) {
                    Column(
                        Modifier
                            .verticalScroll(rememberScrollState())
                            .padding(start = du.dp(55.5f), end = du.dp(55.5f), top = du.dp(45.4f), bottom = du.dp(52f))
                    ) {
                        volumes.forEachIndexed { index, volume ->
                            VolumeSection(
                                volume = volume,
                                version = version,
                                expanded = expandedId == volume.id,
                                last = index == volumes.lastIndex,
                                onToggle = { expandedId = if (expandedId == volume.id) null else volume.id },
                                onPickFolder = { browsing = volume },
                                onChangeBlocked = { blocked = true }
                            )
                        }
                    }
                }
                Spacer(Modifier.height(du.dp(26f)))
                val closeHint = if (setup) {
                    tr("Ketuk di luar kotak untuk lanjut.", "Tap outside the box to continue.")
                } else {
                    tr("Ketuk di luar kotak untuk menutup.", "Tap outside the box to close.")
                }
                val noRemovable = volumes.none { it.removable }
                DText(
                    if (noRemovable) {
                        tr(
                            "Flashdisk atau kartu SD belum terdeteksi. ",
                            "No USB drive or SD card detected. "
                        ) + closeHint
                    } else {
                        closeHint
                    },
                    21f,
                    modifier = Modifier.width(du.dp(560f)),
                    color = Color.White.copy(alpha = 0.75f),
                    lineHeight = 30f,
                    align = TextAlign.Center
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

/** Kotak pop-up: kaca gelap dengan garis tepi putih di atas yang memudar ke ungu di bawah. */
@Composable
private fun PopupPanel(modifier: Modifier, content: @Composable BoxScope.() -> Unit) {
    val du = LocalDu.current
    val shape = RoundedCornerShape(du.dp(24f))
    Box(
        modifier = modifier
            .clip(shape)
            .background(Brush.verticalGradient(listOf(Color(0xEB1C2A40), Color(0xF0101A24))))
            .border(
                du.dp(2.5f),
                Brush.verticalGradient(listOf(Color(0xFFE8E8F0), Color(0xFF5B45D6))),
                shape
            ),
        content = content
    )
}

@Composable
private fun PopupDivider() {
    val du = LocalDu.current
    Box(Modifier.fillMaxWidth().height(du.dp(3f)).background(Color.White))
}

/** Satu penyimpanan: judul dan ikon, pemakaian, lalu folder library (atau ajakan menambahkannya) dan isinya. */
@Composable
private fun VolumeSection(
    volume: StorageVolumeInfo,
    version: Int,
    expanded: Boolean,
    last: Boolean,
    onToggle: () -> Unit,
    onPickFolder: () -> Unit,
    onChangeBlocked: () -> Unit,
) {
    val du = LocalDu.current
    val libDir = remember(version, volume.id) { Libraries.libraryDir(volume) }
    val games by produceState(emptyList<LibraryGame>(), libDir?.path, version) {
        value = withContext(Dispatchers.IO) { libDir?.let { Library(it).games() } ?: emptyList() }
    }
    val total = remember(version, volume.id) { volume.totalBytes }
    val free = remember(version, volume.id) { volume.freeBytes }
    val used = (total - free).coerceAtLeast(0L)
    val gamesBytes = games.sumOf { it.sizeOnDisk }.coerceAtMost(used)
    val other = (used - gamesBytes).coerceAtLeast(0L)

    val isSdCard = volume.label.contains("sd", ignoreCase = true)
    val title = when {
        volume.primary -> "Disc"
        isSdCard -> "SD Card"
        else -> "USB Drive"
    }
    val subtitle = if (volume.primary) tr("Ponsel Kamu", "Your Phone") else "${volume.label} ${formatBytes(total)}".trim()

    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(du.dp(57.1f))) {
            DText(title, 42.3f, bold = true, lineHeight = 57.1f, maxLines = 1)
            Image(
                painter = painterResource(if (volume.removable) R.drawable.ic_usb else R.drawable.ic_disc),
                contentDescription = null,
                alpha = if (volume.removable) 0.8f else 0.58f,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = -du.dp(13.4f), y = -du.dp(4f))
                    .requiredSize(du.dp(if (volume.removable) 110f else 116f))
            )
        }
        Spacer(Modifier.height(du.dp(17.2f)))
        DText(subtitle, 26.1f, lineHeight = 36.5f, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(du.dp(9.5f)))
        UsageBar(other = other, games = gamesBytes, free = free)
        Spacer(Modifier.height(du.dp(3.6f)))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            DText(
                tr(
                    "Terpakai :${formatBytes(used)}/${formatBytes(total)}",
                    "Usage :${formatBytes(used)}/${formatBytes(total)}"
                ),
                20.8f, lineHeight = 29.1f
            )
            DText(tr("Kosong: ${formatBytes(free)}", "Free: ${formatBytes(free)}"), 20.8f, lineHeight = 29.1f)
        }
        Spacer(Modifier.height(du.dp(13.4f)))
        PopupDivider()

        if (libDir == null) {
            // Penyimpanan eksternal yang belum punya folder library
            Column(
                Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onPickFolder)
                    .padding(top = du.dp(42f)),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                PlusIcon(Modifier.size(du.dp(48f)))
                Spacer(Modifier.height(du.dp(11f)))
                DText(
                    tr("Tambahkan alamat penyimpanan", "Add storage location"),
                    26.1f, lineHeight = 36.5f, align = TextAlign.Center
                )
            }
        } else {
            Spacer(Modifier.height(du.dp(23.5f)))
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(du.dp(123f))
                    .clickable(onClick = onToggle),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_folder),
                    contentDescription = null,
                    modifier = Modifier.padding(start = du.dp(3f)).size(du.dp(123f))
                )
                Spacer(Modifier.width(du.dp(40f)))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                    DText(
                        libDir.path.removePrefix("/storage/"),
                        26.1f, lineHeight = 34f, maxLines = 2, overflow = TextOverflow.Ellipsis
                    )
                    DText(
                        tr("${games.size} Game", "${games.size} Games"),
                        22f, color = Color.White.copy(alpha = 0.8f), lineHeight = 30f
                    )
                }
            }
            if (expanded) {
                Spacer(Modifier.height(du.dp(15f)))
                if (games.isEmpty()) {
                    DText(
                        tr("Belum ada game di library ini.", "No games in this library yet."),
                        22f, color = Color.White.copy(alpha = 0.7f), lineHeight = 30f
                    )
                } else {
                    games.forEach { game ->
                        GameRow(game)
                        Spacer(Modifier.height(du.dp(15f)))
                    }
                }
                DText(
                    tr("Ubah alamat", "Change location"),
                    22f,
                    modifier = Modifier
                        .clickable(onClick = if (games.isEmpty()) onPickFolder else onChangeBlocked)
                        .padding(vertical = du.dp(8f)),
                    color = Color(0xFF8FB4FF),
                    lineHeight = 30f
                )
            }
        }
        if (!last) {
            Spacer(Modifier.height(du.dp(31.2f)))
            PopupDivider()
            Spacer(Modifier.height(du.dp(15f)))
        }
    }
}

/** Satu game di library: ikon persegi, nama, sistem operasi, dan ukurannya di disk. */
@Composable
private fun GameRow(game: LibraryGame) {
    val du = LocalDu.current
    val platform = PlatformStore.get(game.appId) ?: Platform.WINDOWS
    val platformIcon = when (platform) {
        Platform.LINUX -> R.drawable.ic_linux
        Platform.WINDOWS -> R.drawable.ic_windows
        else -> null
    }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = du.dp(62.3f))
            .clip(RoundedCornerShape(du.dp(12f)))
            .background(Color(0x4D000000))
            .padding(horizontal = du.dp(9f), vertical = du.dp(7f)),
        verticalAlignment = Alignment.CenterVertically
    ) {
        GameIcon(game.appId, Modifier.size(du.dp(46.5f)))
        Spacer(Modifier.width(du.dp(8f)))
        Column(Modifier.weight(1f)) {
            DText(
                game.name, 21.7f, bold = true, lineHeight = 29f,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            if (!game.complete) {
                DText(
                    tr("Belum selesai", "Incomplete"),
                    15f, color = Color(0xFFFFC46B), lineHeight = 20f
                )
            }
        }
        Spacer(Modifier.width(du.dp(8f)))
        Column(horizontalAlignment = Alignment.End) {
            if (platformIcon != null) {
                Image(
                    painter = painterResource(platformIcon),
                    contentDescription = null,
                    alpha = 0.55f,
                    modifier = Modifier.size(du.dp(21f))
                )
            }
            DText(
                formatBytes(game.sizeOnDisk), 16.4f,
                color = Color.White.copy(alpha = 0.6f), lineHeight = 22f
            )
        }
    }
}

/** Ikon game: bagian tengah gambar header Steam dipotong jadi persegi. */
@Composable
private fun GameIcon(appId: Int, modifier: Modifier) {
    val du = LocalDu.current
    val bitmap by produceState<Bitmap?>(initialValue = GameImages.cached(appId), key1 = appId) {
        value = GameImages.load(appId)
    }
    val shape = RoundedCornerShape(du.dp(7f))
    val b = bitmap
    if (b != null) {
        Image(
            bitmap = b.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
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
private fun PlusIcon(modifier: Modifier) {
    Canvas(modifier) {
        val t = size.minDimension * 0.19f
        val r = CornerRadius(t * 0.3f)
        drawRoundRect(Color.White, Offset((size.width - t) / 2f, 0f), Size(t, size.height), r)
        drawRoundRect(Color.White, Offset(0f, (size.height - t) / 2f), Size(size.width, t), r)
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
