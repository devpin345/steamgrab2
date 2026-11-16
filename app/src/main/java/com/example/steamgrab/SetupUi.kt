package com.example.steamgrab

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

// Layar login, Steam Guard, dan izin. Gaya mengikuti desain: latar gradasi, kartu kaca ungu-biru, tombol biru.

/** Bahasa teks di layar setup dan login. Layar lain masih berbahasa Indonesia. */
object AppLanguage {
    private const val PREFS = "app_language"
    private const val KEY = "code"
    private var prefs: SharedPreferences? = null

    private val _code = MutableStateFlow(defaultCode())
    val code: StateFlow<String> = _code.asStateFlow()

    @Synchronized
    fun init(context: Context) {
        if (prefs != null) return
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        p.getString(KEY, null)?.let { _code.value = it }
    }

    fun set(code: String) {
        _code.value = code
        prefs?.edit()?.putString(KEY, code)?.apply()
    }

    // Bahasa ponsel Indonesia ("in" di Android lama, "id" di yang baru) memakai Indonesia, selain itu Inggris
    private fun defaultCode(): String {
        val lang = Locale.getDefault().language
        return if (lang == "id" || lang == "in") "id" else "en"
    }
}

/** Memilih teks sesuai bahasa yang dipilih. Ikut berubah saat bahasa diganti. */
@Composable
fun tr(id: String, en: String): String {
    val code by AppLanguage.code.collectAsState()
    return if (code == "id") id else en
}

private object GlassColors {
    val BgTop = Color(0xFF203858)
    val BgBottom = Color(0xFF1B2838)
    val Olive = Color(0xCC8E8E4F)
    val CardStart = Color(0xFF3C297A)
    val CardMid = Color(0xFF1B283B)
    val CardEnd = Color(0xFF152433)
    val BorderStart = Color(0xFF5B45D6)
    val BorderEnd = Color(0xFF2F4F93)
    val Blue = Color(0xFF1F63FF)
    val BlueIdle = Color(0xFF2B4254)
    val BlueIdleText = Color(0xFF8DA0AD)
    val Purple = Color(0xFF6435FD)
    val Error = Color(0xFFFF8A8A)
    val Red = Color(0xFFE62E3E)
}

/** Latar halaman: gradasi biru tua dengan cahaya zaitun di pojok kiri atas dan logo Steam kabur di kanan bawah. */
@Composable
fun GlassBackground(content: @Composable BoxScope.() -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(GlassColors.BgTop, GlassColors.BgBottom)))
            .drawBehind {
                drawRect(
                    Brush.radialGradient(
                        colors = listOf(GlassColors.Olive, Color.Transparent),
                        center = Offset.Zero,
                        radius = size.width * 1.1f
                    )
                )
            }
    ) {
        Image(
            painter = painterResource(R.drawable.steam_logo_blur),
            contentDescription = null,
            alpha = 0.5f,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .offset(x = 110.dp, y = 90.dp)
                .size(380.dp)
        )
        content()
    }
}

@Composable
fun GlassCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier = modifier
            .shadow(18.dp, shape, ambientColor = Color.Black, spotColor = Color.Black)
            .clip(shape)
            .background(
                Brush.linearGradient(
                    0f to GlassColors.CardStart,
                    0.45f to GlassColors.CardMid,
                    1f to GlassColors.CardEnd
                )
            )
            .border(1.dp, Brush.linearGradient(listOf(GlassColors.BorderStart, GlassColors.BorderEnd)), shape)
            .padding(22.dp),
        content = content
    )
}

/** Kotak berisi gradasi ungu ke biru tua, dipakai untuk kolom isian dan kotak logo. */
private fun fieldSurface(modifier: Modifier): Modifier {
    val shape = RoundedCornerShape(14.dp)
    return modifier
        .clip(shape)
        .background(Brush.horizontalGradient(listOf(Color(0xFF372869), Color(0xFF1A2A3E), Color(0xFF162636))))
        .border(1.5.dp, Brush.horizontalGradient(listOf(Color(0xFF6B4DEB), Color(0xFF34508F))), shape)
}

@Composable
private fun GlassField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Next,
    onDone: () -> Unit = {},
    visualTransformation: VisualTransformation = VisualTransformation.None,
    enabled: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
) {
    Column(modifier) {
        Text(
            label,
            color = Color.White,
            fontSize = 16.sp,
            modifier = Modifier.padding(start = 4.dp, bottom = 6.dp)
        )
        Row(
            fieldSurface(Modifier.fillMaxWidth().height(54.dp)).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.weight(1f)) {
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    enabled = enabled,
                    singleLine = true,
                    textStyle = TextStyle(color = Color.White, fontSize = 16.sp),
                    cursorBrush = SolidColor(Color.White),
                    visualTransformation = visualTransformation,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        keyboardType = keyboardType,
                        imeAction = imeAction
                    ),
                    keyboardActions = KeyboardActions(onDone = { onDone() }),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            trailing?.invoke()
        }
    }
}

@Composable
private fun GlassButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    activeColor: Color = GlassColors.Blue,
    idleColor: Color = GlassColors.BlueIdle,
    idleTextColor: Color = GlassColors.BlueIdleText,
) {
    val active = enabled && !loading
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (enabled || loading) activeColor else idleColor)
            .clickable(enabled = active, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (loading) {
            CircularProgressIndicator(Modifier.size(24.dp), color = Color.White, strokeWidth = 2.5.dp)
        } else {
            Text(
                text,
                color = if (enabled) Color.White else idleTextColor,
                fontSize = 19.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

/** Bulatan centang "Remember me". */
@Composable
private fun RememberRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null
        ) { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically
    ) {
        val ring = Modifier.size(28.dp).clip(CircleShape)
        Box(
            if (checked) ring.background(GlassColors.Blue) else ring.border(2.dp, GlassColors.Blue, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            if (checked) {
                Canvas(Modifier.size(15.dp)) {
                    val tick = Path().apply {
                        moveTo(size.width * 0.1f, size.height * 0.55f)
                        lineTo(size.width * 0.4f, size.height * 0.85f)
                        lineTo(size.width * 0.92f, size.height * 0.18f)
                    }
                    drawPath(
                        tick, Color.White,
                        style = Stroke(width = size.width * 0.16f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                    )
                }
            }
        }
        Spacer(Modifier.width(10.dp))
        Text(label, color = Color.White, fontSize = 16.sp)
    }
}

/** Roda gigi kecil untuk tombol Language. Digambar sendiri karena ikon extended tidak dipakai di proyek ini. */
@Composable
private fun GearIcon(modifier: Modifier = Modifier, color: Color = Color.White) {
    Canvas(modifier.size(20.dp)) {
        val c = center
        val r = size.minDimension / 2f
        drawCircle(color, radius = r * 0.55f, center = c, style = Stroke(width = r * 0.34f))
        for (i in 0 until 8) {
            rotate(degrees = i * 45f, pivot = c) {
                drawRoundRect(
                    color,
                    topLeft = Offset(c.x - r * 0.17f, c.y - r),
                    size = Size(r * 0.34f, r * 0.42f),
                    cornerRadius = CornerRadius(r * 0.06f)
                )
            }
        }
    }
}

@Composable
private fun LanguageChip(modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Row(
        modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0x66101820))
            .clickable { open = true }
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Tulisan tetap "Language" di semua bahasa supaya mudah dicari
        Text("Language", color = Color.White, fontSize = 14.sp)
        Spacer(Modifier.width(6.dp))
        GearIcon()
    }
    if (open) {
        val current by AppLanguage.code.collectAsState()
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text("Language") },
            text = {
                Column {
                    listOf("id" to "Bahasa Indonesia", "en" to "English").forEach { (code, name) ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    AppLanguage.set(code)
                                    open = false
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = current == code, onClick = null)
                            Spacer(Modifier.width(12.dp))
                            Text(name)
                        }
                    }
                }
            },
            confirmButton = {}
        )
    }
}

@Composable
fun LoginScreen(
    loading: Boolean,
    error: String?,
    status: String?,
    username: String,
    onUsername: (String) -> Unit,
    password: String,
    onPassword: (String) -> Unit,
    remember: Boolean,
    onRemember: (Boolean) -> Unit,
    onLogin: () -> Unit,
) {
    var passwordVisible by remember { mutableStateOf(false) }
    val canLogin = !loading && username.isNotBlank() && password.isNotBlank()

    GlassBackground {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Column(Modifier.widthIn(max = 420.dp).fillMaxWidth()) {
                Text(
                    tr("Login ke akun Steam kamu", "Login to your Steam account"),
                    color = Color.White,
                    fontSize = 27.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 4.dp)
                )
                Spacer(Modifier.height(20.dp))
                GlassCard(Modifier.fillMaxWidth()) {
                    Box(
                        fieldSurface(Modifier.align(Alignment.CenterHorizontally).size(width = 118.dp, height = 112.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            painter = painterResource(R.drawable.steam_logo),
                            contentDescription = null,
                            modifier = Modifier.size(72.dp)
                        )
                    }
                    Spacer(Modifier.height(18.dp))
                    GlassField(
                        label = "Nickname",
                        value = username,
                        onValueChange = onUsername,
                        enabled = !loading
                    )
                    Spacer(Modifier.height(14.dp))
                    GlassField(
                        label = "Password",
                        value = password,
                        onValueChange = onPassword,
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                        onDone = { if (canLogin) onLogin() },
                        visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        enabled = !loading,
                        trailing = {
                            Box(
                                Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .clickable { passwordVisible = !passwordVisible },
                                contentAlignment = Alignment.Center
                            ) {
                                CompositionLocalProvider(LocalContentColor provides Color.White) {
                                    EyeIcon(visible = passwordVisible)
                                }
                            }
                        }
                    )
                    Spacer(Modifier.height(16.dp))
                    RememberRow("Remember me", remember, onRemember)

                    error?.let {
                        Spacer(Modifier.height(12.dp))
                        // SelectionContainer supaya teks error bisa disalin dan dikirim ke pengembang
                        SelectionContainer {
                            Text(it, color = GlassColors.Error, fontSize = 13.sp)
                        }
                    }
                    status?.let {
                        Spacer(Modifier.height(12.dp))
                        Text(it, color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp)
                    }

                    Spacer(Modifier.height(18.dp))
                    GlassButton(
                        text = tr("Kirim", "Send"),
                        onClick = onLogin,
                        enabled = canLogin,
                        loading = loading
                    )
                }
            }
        }
        LanguageChip(Modifier.align(Alignment.BottomEnd).padding(16.dp))
    }
}

private const val CODE_LENGTH = 5
private const val GUARD_WAIT_SECONDS = 180L

/** Kotak-kotak kode Steam Guard. Satu kolom teks tak terlihat menampung ketikan, kotaknya hanya menampilkan. */
@Composable
private fun CodeBoxes(code: String, enabled: Boolean, hasError: Boolean, onCodeChange: (String) -> Unit) {
    val focus = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(enabled) {
        if (enabled) runCatching { focus.requestFocus() }
    }
    BasicTextField(
        value = code,
        onValueChange = { raw ->
            onCodeChange(raw.filter { it.isLetterOrDigit() }.uppercase(Locale.US).take(CODE_LENGTH))
        },
        enabled = enabled,
        singleLine = true,
        textStyle = TextStyle(color = Color.Transparent),
        cursorBrush = SolidColor(Color.Transparent),
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Characters,
            keyboardType = KeyboardType.Ascii,
            imeAction = ImeAction.Done
        ),
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focus)
            .onFocusChanged { focused = it.isFocused },
        decorationBox = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val shape = RoundedCornerShape(14.dp)
                repeat(CODE_LENGTH) { i ->
                    val active = focused && enabled && i == code.length.coerceAtMost(CODE_LENGTH - 1)
                    val borderColor = when {
                        hasError -> GlassColors.Red
                        active -> GlassColors.Purple
                        else -> Color(0x33FFFFFF)
                    }
                    Box(
                        Modifier
                            .weight(1f)
                            .aspectRatio(0.85f)
                            .clip(shape)
                            .background(Brush.verticalGradient(listOf(Color(0xFF1D2A46), Color(0xFF2A2563))))
                            .border(if (active) 2.dp else 1.dp, borderColor, shape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            code.getOrNull(i)?.toString() ?: "",
                            color = Color.White,
                            fontSize = 26.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    )
}

/**
 * Layar Steam Guard: kode diketik di kotak (terkirim otomatis begitu lengkap), atau login disetujui di aplikasi
 * Steam Mobile. Tombol kirim ulang aktif setelah 3 menit atau saat waktu menunggu habis, dan baru saat ditekan
 * Steam diminta membuat persetujuan baru.
 */
@Composable
fun GuardScreen(
    state: UiState.LoggedOut,
    onSubmit: (String) -> Unit,
    onResend: () -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)

    var code by remember { mutableStateOf("") }
    // Kode yang ditolak Steam dikosongkan supaya bisa langsung diketik ulang
    val rejected = state.wrongCode && !state.loading
    LaunchedEffect(rejected) { if (rejected) code = "" }

    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.guardSince) {
        while (true) {
            now = System.currentTimeMillis()
            delay(500L)
        }
    }
    val remaining = if (state.guardSince == 0L) GUARD_WAIT_SECONDS
    else (GUARD_WAIT_SECONDS - (now - state.guardSince) / 1000L).coerceIn(0L, GUARD_WAIT_SECONDS)
    val canResend = !state.loading && (state.guardExpired || remaining <= 0L)
    val inputEnabled = !state.loading && !state.guardExpired
    val countdown = String.format(Locale.US, "%d:%02d", remaining / 60L, remaining % 60L)

    val hint = when {
        state.guardExpired -> tr(
            "Waktu habis. Kirim ulang untuk meminta konfirmasi baru.",
            "Time ran out. Resend to request a new confirmation."
        )
        state.guardType == GuardType.EMAIL -> tr(
            "Masukkan kode Steam Guard yang dikirim ke email kamu.",
            "Enter the Steam Guard code sent to your email."
        )
        state.guardType == GuardType.DEVICE -> tr(
            "Masukkan kode dari aplikasi Steam Mobile.",
            "Enter the code from the Steam Mobile app."
        )
        else -> tr(
            "Masukkan kode Steam Guard atau buka aplikasi Steam untuk konfirmasi login.",
            "Enter the Steam Guard code or open the Steam app to confirm login."
        )
    }
    val wrongText = tr("Kode salah, coba lagi.", "Wrong code, try again.")
    val resendText = if (canResend) tr("Kirim ulang", "Resend")
    else tr("Kirim ulang kode dalam $countdown", "Resend code in $countdown")

    GlassBackground {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Image(
                painter = painterResource(R.drawable.steam_shield),
                contentDescription = null,
                modifier = Modifier.size(width = 124.dp, height = 144.dp)
            )
            Spacer(Modifier.height(24.dp))
            GlassCard(Modifier.widthIn(max = 420.dp).fillMaxWidth()) {
                CodeBoxes(
                    code = code,
                    enabled = inputEnabled,
                    hasError = state.wrongCode,
                    onCodeChange = { c ->
                        code = c
                        if (c.length == CODE_LENGTH) onSubmit(c)
                    }
                )
                Spacer(Modifier.height(18.dp))
                Text(
                    hint,
                    color = Color.White,
                    fontSize = 16.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                if (state.wrongCode && !state.loading) {
                    Spacer(Modifier.height(8.dp))
                    Text(wrongText, color = GlassColors.Error, fontSize = 14.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
                }
                state.error?.let {
                    Spacer(Modifier.height(8.dp))
                    SelectionContainer {
                        Text(it, color = GlassColors.Error, fontSize = 13.sp)
                    }
                }
                state.status?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        it,
                        color = Color.White.copy(alpha = 0.85f),
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Spacer(Modifier.height(18.dp))
                GlassButton(
                    text = resendText,
                    onClick = onResend,
                    enabled = canResend,
                    activeColor = GlassColors.Purple,
                    idleColor = Color(0xFF16202D),
                    idleTextColor = Color.White.copy(alpha = 0.9f)
                )
            }
        }
    }
}

/** Membuka halaman izin "akses semua file" milik aplikasi ini di pengaturan. */
fun openAllFilesAccessSettings(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
    try {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))
        )
    } catch (e: Exception) {
        // Sebagian ponsel tidak punya halaman izin per aplikasi, buka daftar umumnya
        try {
            context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        } catch (e2: Exception) {
            DebugLog.log("Tidak bisa membuka pengaturan akses file: ${e2.describe()}")
        }
    }
}

private fun openNotificationSettings(context: Context) {
    try {
        context.startActivity(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        )
    } catch (e: Exception) {
        DebugLog.log("Tidak bisa membuka pengaturan notifikasi: ${e.describe()}")
    }
}

@Composable
private fun PermissionRow(text: String, checked: Boolean, onToggle: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text, color = Color.White, fontSize = 17.sp, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(14.dp))
        Switch(
            checked = checked,
            onCheckedChange = { onToggle() },
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color(0xFFF4F4F8),
                checkedTrackColor = Color(0xFF614AE3),
                checkedBorderColor = Color.Transparent,
                uncheckedThumbColor = Color(0xFFF8F9FB),
                uncheckedTrackColor = GlassColors.Red,
                uncheckedBorderColor = Color.Transparent
            )
        )
    }
}

/**
 * Layar izin di awal. Saklar penyimpanan mengikuti izin "akses semua file" yang sebenarnya, dan membuka
 * pengaturan saat diketuk. Notifikasi bersifat opsional. Tombol Lanjutkan muncul setelah izin penyimpanan aktif.
 */
@Composable
fun PermissionScreen(storageGranted: Boolean, onContinue: () -> Unit) {
    val context = LocalContext.current
    var notifOn by remember { mutableStateOf(NotificationManagerCompat.from(context).areNotificationsEnabled()) }
    var notifAsked by remember { mutableStateOf(false) }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        notifOn = NotificationManagerCompat.from(context).areNotificationsEnabled()
    }
    // Dicek ulang setiap kembali dari pengaturan
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                notifOn = NotificationManagerCompat.from(context).areNotificationsEnabled()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    GlassBackground {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Image(
                painter = painterResource(R.drawable.ic_user_key),
                contentDescription = null,
                modifier = Modifier.size(width = 120.dp, height = 128.dp)
            )
            Spacer(Modifier.height(24.dp))
            GlassCard(Modifier.widthIn(max = 420.dp).fillMaxWidth()) {
                Text(
                    tr("Izinkan", "Allow"),
                    color = Color.White,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(14.dp))
                PermissionRow(
                    text = tr(
                        "Beri izin aplikasi mengakses penyimpanan untuk menulis dan membaca data game",
                        "Allow the app to access storage to write and read game data"
                    ),
                    checked = storageGranted,
                    onToggle = { if (!storageGranted) openAllFilesAccessSettings(context) }
                )
                Spacer(Modifier.height(14.dp))
                Box(Modifier.fillMaxWidth().height(2.dp).background(Color.White.copy(alpha = 0.9f)))
                Spacer(Modifier.height(14.dp))
                PermissionRow(
                    text = tr("Berikan akses notifikasi (Opsional)", "Allow notification access (Optional)"),
                    checked = notifOn,
                    onToggle = {
                        if (!notifOn) {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !notifAsked) {
                                notifAsked = true
                                notifLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                // Sistem tidak menampilkan dialog lagi setelah ditolak, jadi buka pengaturannya
                                openNotificationSettings(context)
                            }
                        }
                    }
                )
            }
            if (storageGranted) {
                Spacer(Modifier.height(20.dp))
                GlassButton(
                    text = tr("Lanjutkan", "Continue"),
                    onClick = onContinue,
                    modifier = Modifier.widthIn(max = 420.dp)
                )
            }
        }
        LanguageChip(Modifier.align(Alignment.BottomEnd).padding(16.dp))
    }
}
