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
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
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
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

// Layar login, Steam Guard, dan izin. Semua ukuran mengikuti file desain (lihat DesignScale.kt): posisi dan ukuran
// tiap elemen diukur dari file desain, dalam satuan desain.

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

internal object GlassColors {
    val BgTop = Color(0xFF203858)
    val BgBottom = Color(0xFF1B2838)
    val Olive = Color(0xCC8E8E4F)
    val CardStart = Color(0xFF3D2B76)
    val CardMid = Color(0xFF162331)
    val CardEnd = Color(0xFF192837)
    val BorderStart = Color(0xFF6341FF)
    val BorderEnd = Color(0xFF2D4A7A)
    val Blue = Color(0xFF1F63FF)
    val BlueIdle = Color(0xFF28414F)
    val BlueIdleText = Color(0xFF808080)
    val Purple = Color(0xFF6435FD)
    val PurpleIdle = Color(0xFF182034)
    val ToggleOn = Color(0xFF614AE3)
    val Error = Color(0xFFFF8A8A)
    val Red = Color(0xFFE62D3B)
}

/** Latar halaman: gradasi biru tua dengan cahaya zaitun di pojok kiri atas dan logo Steam kabur di kanan bawah. */
@Composable
fun GlassBackground(content: @Composable BoxScope.() -> Unit) {
    ProvideDu { du ->
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
            // Logo di desain: 940 satuan, mulai dari x=210.6, tepi atasnya di y=631.3 pada halaman setinggi 1440
            Image(
                painter = painterResource(R.drawable.steam_logo_blur),
                contentDescription = null,
                alpha = 0.5f,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = du.dp(341f), y = du.dp(131.8f))
                    .size(du.dp(940f))
            )
            content()
        }
    }
}

/** Kartu kaca: gradasi ungu ke biru tua dengan garis tepi ungu ke biru. */
@Composable
fun GlassPanel(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val du = LocalDu.current
    val shape = RoundedCornerShape(du.dp(16f))
    Box(
        modifier = modifier
            .shadow(du.dp(30f), shape, ambientColor = Color.Black, spotColor = Color.Black)
            .clip(shape)
            .background(
                Brush.linearGradient(
                    0f to GlassColors.CardStart,
                    0.45f to GlassColors.CardMid,
                    1f to GlassColors.CardEnd
                )
            )
            .border(
                du.dp(2.5f),
                Brush.linearGradient(listOf(GlassColors.BorderStart, GlassColors.BorderEnd)),
                shape
            ),
        content = content
    )
}

/** Kotak berisi gradasi ungu ke biru tua, dipakai untuk kolom isian dan kotak kode QR. */
private fun fieldSurface(du: Du, modifier: Modifier): Modifier {
    val shape = RoundedCornerShape(du.dp(20f))
    return modifier
        .clip(shape)
        .background(Brush.horizontalGradient(listOf(Color(0xFF38296E), Color(0xFF1A2A3E), Color(0xFF182737))))
        .border(du.dp(2f), Brush.horizontalGradient(listOf(Color(0xFF633CF6), Color(0xFF2D496C))), shape)
}

/** Tombol sesuai desain. Ukurannya diatur pemanggil lewat [modifier]. */
@Composable
private fun DesignButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier,
    textSize: Float,
    radius: Float,
    bold: Boolean,
    enabled: Boolean,
    loading: Boolean,
    activeColor: Color,
    idleColor: Color,
    idleTextColor: Color,
) {
    val du = LocalDu.current
    val active = enabled && !loading
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(du.dp(radius)))
            .background(if (enabled || loading) activeColor else idleColor)
            .clickable(enabled = active, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (loading) {
            CircularProgressIndicator(Modifier.size(du.dp(40f)), color = Color.White, strokeWidth = du.dp(4f))
        } else {
            DText(
                text,
                textSize,
                color = if (enabled) Color.White else idleTextColor,
                bold = bold,
                lineHeight = textSize * 1.3f
            )
        }
    }
}

/** Tombol biasa untuk layar lain yang memakai gaya kaca (pemilih folder). */
@Composable
internal fun GlassButton(
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
                fontFamily = OpenSans,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

/** Bulatan centang "Remember me". */
@Composable
private fun RememberCircle(checked: Boolean, modifier: Modifier) {
    val du = LocalDu.current
    Canvas(modifier) {
        val ring = du.dp(4f).toPx()
        if (checked) {
            drawCircle(GlassColors.Blue)
            val tick = Path().apply {
                moveTo(size.width * 0.27f, size.height * 0.52f)
                lineTo(size.width * 0.44f, size.height * 0.68f)
                lineTo(size.width * 0.74f, size.height * 0.32f)
            }
            drawPath(
                tick, Color.White,
                style = Stroke(width = size.width * 0.09f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            )
        } else {
            drawCircle(GlassColors.Blue, radius = (size.minDimension - ring) / 2f, style = Stroke(width = ring))
        }
    }
}

/** Roda gigi kecil untuk tombol Language. Digambar sendiri karena ikon extended tidak dipakai di proyek ini. */
@Composable
private fun GearIcon(modifier: Modifier = Modifier, color: Color = Color.White) {
    Canvas(modifier) {
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
    val du = LocalDu.current
    var open by remember { mutableStateOf(false) }
    Row(
        modifier
            .height(du.dp(33.4f))
            .clip(RoundedCornerShape(du.dp(4f)))
            .background(Color(0x44000000))
            .clickable { open = true }
            .padding(start = du.dp(12f), end = du.dp(9.1f)),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Tulisan tetap "Language" di semua bahasa supaya mudah dicari
        DText("Language", 24.2f, lineHeight = 28f)
        Spacer(Modifier.width(du.dp(13.6f)))
        GearIcon(Modifier.size(du.dp(29.2f)))
    }
    if (open) LanguagePopup(onDismiss = { open = false })
}

/**
 * Pilihan bahasa: kotak kecil di tengah layar dengan latar kaca gelap (efek blur dan gelap 50% di desain). Baris bahasa
 * yang dipakai diberi sorotan.
 */
@Composable
private fun LanguagePopup(onDismiss: () -> Unit) {
    val du = LocalDu.current
    val current by AppLanguage.code.collectAsState()
    val center = remember {
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize,
            ): IntOffset = IntOffset(
                (windowSize.width - popupContentSize.width) / 2,
                (windowSize.height - popupContentSize.height) / 2
            )
        }
    }
    Popup(
        popupPositionProvider = center,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true)
    ) {
        val shape = RoundedCornerShape(du.dp(22f))
        Box(
            Modifier
                .size(du.dp(355.6f), du.dp(158f))
                .clip(shape)
                .background(Color(0xE60B1119))
                .border(du.dp(1.5f), Color(0x33FFFFFF), shape)
        ) {
            DText(
                "Language", 24.2f,
                modifier = Modifier.at(du, 0f, 11f).width(du.dp(355.6f)),
                lineHeight = 30f, align = TextAlign.Center
            )
            Box(
                Modifier
                    .at(du, 25.9f, 41.1f)
                    .size(du.dp(305.6f), du.dp(3f))
                    .background(Color(0x99FFFFFF))
            )
            listOf("en" to "English", "id" to "Indonesia").forEachIndexed { index, (code, name) ->
                val top = 56.3f + index * 40f
                val selected = current == code
                Box(
                    Modifier
                        .at(du, 14.9f, top)
                        .size(du.dp(322.8f), du.dp(41f))
                        .clip(RoundedCornerShape(du.dp(12f)))
                        .background(if (selected) Color(0x55FFFFFF) else Color.Transparent)
                        .clickable {
                            AppLanguage.set(code)
                            onDismiss()
                        }
                        .padding(start = du.dp(10.7f)),
                    contentAlignment = Alignment.CenterStart
                ) {
                    DText(name, 24.2f, lineHeight = 30f)
                }
            }
        }
    }
}

// Area desain layar login: dari tepi atas judul sampai ruang pesan di bawah kartu
private const val LOGIN_TOP = 168f
private const val LOGIN_CARD_BOTTOM = 1225.7f

/** Satu kolom isian beserta labelnya, di posisi desain. */
@Composable
private fun DesignField(
    du: Du,
    origin: Float,
    label: String,
    labelCapTop: Float,
    fieldTop: Float,
    value: String,
    onValueChange: (String) -> Unit,
    keyboardType: KeyboardType,
    imeAction: ImeAction,
    onDone: () -> Unit,
    visualTransformation: VisualTransformation,
    enabled: Boolean,
) {
    DTextAt(label, x = 128.7f, capTop = labelCapTop, size = 28.5f, origin = origin)
    Box(
        fieldSurface(du, Modifier.at(du, 123.7f, fieldTop - origin).size(du.dp(566.3f), du.dp(87f)))
            .padding(start = du.dp(29.8f), end = du.dp(76f)),
        contentAlignment = Alignment.CenterStart
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            singleLine = true,
            textStyle = TextStyle(color = Color.White, fontSize = du.sp(28.5f), fontFamily = OpenSans),
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
}

/** Isi kotak QR di kartu login: kode QR Steam, atau tanda menunggu, atau ajakan mencoba lagi. */
@Composable
private fun QrArea(du: Du, loading: Boolean, qrUrl: String?, qrFailed: Boolean, onRetry: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when {
            loading -> CircularProgressIndicator(Modifier.size(du.dp(70f)), color = Color.White, strokeWidth = du.dp(5f))
            qrUrl != null -> Box(
                Modifier
                    .size(du.dp(245f))
                    .clip(RoundedCornerShape(du.dp(14f)))
                    .background(Color.White)
                    .padding(du.dp(16f))
            ) {
                QrCodeView(qrUrl, Modifier.fillMaxSize())
            }
            qrFailed -> Column(
                Modifier.clickable(onClick = onRetry).padding(du.dp(16f)),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Image(
                    painter = painterResource(R.drawable.steam_logo),
                    contentDescription = null,
                    alpha = 0.45f,
                    modifier = Modifier.size(du.dp(110f))
                )
                Spacer(Modifier.height(du.dp(8f)))
                DText(
                    tr("Ketuk untuk memuat ulang", "Tap to reload"),
                    21f,
                    color = Color.White.copy(alpha = 0.8f),
                    align = TextAlign.Center
                )
            }
            else -> CircularProgressIndicator(Modifier.size(du.dp(70f)), color = Color.White, strokeWidth = du.dp(5f))
        }
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
    qrUrl: String?,
    qrFailed: Boolean,
    onRetryQr: () -> Unit,
) {
    var passwordVisible by remember { mutableStateOf(false) }
    val canLogin = !loading && username.isNotBlank() && password.isNotBlank()
    val t = LOGIN_TOP

    GlassBackground {
        val du = LocalDu.current
        Column(
            Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(Modifier.size(du.dp(810f), du.dp(LOGIN_CARD_BOTTOM + 150f - t))) {
                DTextAt(
                    tr("Login ke akun Steam kamu", "Login to your Steam account"),
                    x = 91.2f, capTop = 176.6f, size = 42.3f, origin = t, width = 700f, bold = true,
                    shadow = Shadow(Color(0x66000000), Offset(0f, 3f), 10f)
                )
                GlassPanel(Modifier.at(du, 81.6f, 243f - t).size(du.dp(647.8f), du.dp(982.7f))) {}

                Box(
                    fieldSurface(du, Modifier.at(du, 258.3f, 274.8f - t).size(du.dp(298.1f), du.dp(281f)))
                ) {
                    QrArea(du, loading, qrUrl, qrFailed, onRetryQr)
                }
                DTextAt(
                    tr("Pindai kode QR dengan SteamApp", "Scan QR code with SteamApp"),
                    x = 81.6f, capTop = 576f, size = 19f, origin = t, width = 647.8f,
                    color = Color.White.copy(alpha = 0.85f), align = TextAlign.Center
                )
                // Garis pemisah dengan tulisan "Or" di tengah
                Box(Modifier.at(du, 129.3f, 635.5f - t).size(du.dp(253.4f), du.dp(3f)).background(Color(0x66FFFFFF)))
                Box(Modifier.at(du, 431.3f, 635.5f - t).size(du.dp(259.1f), du.dp(3f)).background(Color(0x66FFFFFF)))
                DTextAt(
                    tr("Atau", "Or"),
                    x = 81.6f, capTop = 629f, size = 18f, origin = t, width = 647.8f,
                    color = Color.White.copy(alpha = 0.5f), align = TextAlign.Center
                )

                DesignField(
                    du, t, "Nickname", 687.2f, 724f, username, onUsername,
                    KeyboardType.Text, ImeAction.Next, {}, VisualTransformation.None, !loading
                )
                DesignField(
                    du, t, "Password", 846.2f, 883.6f, password, onPassword,
                    KeyboardType.Password, ImeAction.Done, { if (canLogin) onLogin() },
                    if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    !loading
                )
                Box(
                    Modifier
                        .at(du, 610.9f, 901.1f - t)
                        .size(du.dp(53f))
                        .clip(CircleShape)
                        .clickable { passwordVisible = !passwordVisible },
                    contentAlignment = Alignment.Center
                ) {
                    CompositionLocalProvider(LocalContentColor provides Color.White) {
                        EyeIcon(visible = passwordVisible, modifier = Modifier.size(du.dp(40f)))
                    }
                }

                val rememberTop = 993f
                Box(
                    Modifier
                        .at(du, 128.7f, rememberTop - t)
                        .size(du.dp(320f), du.dp(50f))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { onRemember(!remember) }
                ) {
                    RememberCircle(remember, Modifier.at(du, 0f, 4.6f).size(du.dp(40.6f)))
                    DTextAt("Remember me", x = 49.6f, capTop = 1007.8f, size = 28.5f, origin = rememberTop)
                }

                DesignButton(
                    text = tr("Kirim", "Send"),
                    onClick = onLogin,
                    modifier = Modifier.at(du, 123.7f, 1081.2f - t).size(du.dp(566.3f), du.dp(80.7f)),
                    textSize = 36.3f, radius = 20f, bold = true,
                    enabled = canLogin, loading = loading,
                    activeColor = GlassColors.Blue, idleColor = GlassColors.BlueIdle, idleTextColor = GlassColors.BlueIdleText
                )

                Column(Modifier.at(du, 81f, LOGIN_CARD_BOTTOM + 24f - t).width(du.dp(648f))) {
                    error?.let {
                        // SelectionContainer supaya teks error bisa disalin dan dikirim ke pengembang
                        SelectionContainer {
                            DText(it, 22f, color = GlassColors.Error, lineHeight = 30f)
                        }
                    }
                    status?.let {
                        DText(it, 22f, color = Color.White.copy(alpha = 0.85f), lineHeight = 30f)
                    }
                }
            }
        }
        LanguageChip(
            Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(end = du.dp(81f), bottom = du.dp(88.9f))
        )
    }
}

private const val CODE_LENGTH = 5
private const val GUARD_WAIT_SECONDS = 180L

/** Kotak-kotak kode Steam Guard. Satu kolom teks tak terlihat menampung ketikan, kotaknya hanya menampilkan. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun CodeBoxes(
    du: Du,
    code: String,
    enabled: Boolean,
    hasError: Boolean,
    modifier: Modifier,
    onCodeChange: (String) -> Unit,
    onDone: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(enabled) {
        if (enabled) {
            runCatching { focus.requestFocus() }
            delay(150L)
            keyboard?.show()
        }
    }
    // Pegangan kursor sistem disembunyikan, supaya tidak muncul bulatan abu-abu di atas kotak
    CompositionLocalProvider(
        LocalTextSelectionColors provides TextSelectionColors(
            handleColor = Color.Transparent,
            backgroundColor = Color.Transparent
        )
    ) {
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
            keyboardActions = KeyboardActions(onDone = { onDone() }),
            modifier = modifier
                .focusRequester(focus)
                .onFocusChanged { focused = it.isFocused },
            decorationBox = {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(du.dp(11.76f))) {
                    val shape = RoundedCornerShape(du.dp(18f))
                    repeat(CODE_LENGTH) { i ->
                        val active = focused && enabled && i == code.length.coerceAtMost(CODE_LENGTH - 1)
                        val borderColor = when {
                            hasError -> GlassColors.Red
                            active -> GlassColors.Purple
                            else -> Color.Transparent
                        }
                        Box(
                            Modifier
                                .size(du.dp(75.8f), du.dp(83f))
                                .clip(shape)
                                .background(Brush.verticalGradient(listOf(Color(0xFF19273A), Color(0xFF342B68))))
                                .border(du.dp(2.5f), borderColor, shape),
                            contentAlignment = Alignment.Center
                        ) {
                            RollingChar(code.getOrNull(i), du)
                        }
                    }
                }
            }
        )
    }
}

/**
 * Satu karakter kode yang berputar seperti mesin slot saat diketik: deretan karakter acak bergulir ke atas dan
 * berhenti di karakter yang diketik.
 */
@Composable
private fun RollingChar(ch: Char?, du: Du) {
    if (ch == null) return
    val cell = du.dp(83f)
    val cellPx = with(LocalDensity.current) { cell.toPx() }
    val progress = remember(ch) { Animatable(0f) }
    LaunchedEffect(ch) { progress.animateTo(1f, tween(durationMillis = 520, easing = FastOutSlowInEasing)) }
    val strip = remember(ch) {
        val rnd = java.util.Random(ch.code * 31L + 7L)
        List(ROLL_STEPS) { ROLL_ALPHABET[rnd.nextInt(ROLL_ALPHABET.length)] } + ch
    }
    Box(Modifier.fillMaxSize().clipToBounds()) {
        Column(
            Modifier
                .fillMaxWidth()
                .wrapContentHeight(align = Alignment.Top, unbounded = true)
                .offset { IntOffset(0, (-(strip.size - 1) * cellPx * progress.value).toInt()) }
        ) {
            strip.forEach { c ->
                Box(Modifier.fillMaxWidth().height(cell), contentAlignment = Alignment.Center) {
                    DText(c.toString(), 48.6f, bold = true, lineHeight = 62f, align = TextAlign.Center)
                }
            }
        }
    }
}

private const val ROLL_STEPS = 5
private const val ROLL_ALPHABET = "0123456789ABCDEFGHJKMNPQRTVWXY"

// Area desain layar Steam Guard: dari tepi atas perisai sampai ruang pesan di bawah kartu
private const val GUARD_TOP = 279f
private const val GUARD_CARD_BOTTOM = 985.3f

/**
 * Layar Steam Guard: kode diketik di kotak lalu dikirim dengan tombol Kirim, atau login disetujui di aplikasi
 * Steam Mobile. Selama kotak belum penuh, tombol menjadi hitung mundur kirim ulang. Baru saat tombol kirim ulang
 * ditekan Steam diminta membuat persetujuan baru.
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
    val full = code.length == CODE_LENGTH && inputEnabled

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
    val buttonText = when {
        full -> tr("Kirim", "Send")
        canResend -> tr("Kirim ulang", "Resend")
        else -> tr("Kirim ulang kode dalam $countdown", "Resend code in $countdown")
    }
    val t = GUARD_TOP

    GlassBackground {
        val du = LocalDu.current
        Column(
            Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(Modifier.size(du.dp(810f), du.dp(GUARD_CARD_BOTTOM + 150f - t))) {
                Image(
                    painter = painterResource(R.drawable.steam_shield),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.at(du, 304.3f, 290.7f - t).size(du.dp(202f), du.dp(244.1f))
                )
                GlassPanel(Modifier.at(du, 81.6f, 567.4f - t).size(du.dp(647.8f), du.dp(417.9f))) {}

                // Lima kotak (kode Steam Guard 5 karakter), ukuran tiap kotak sama dengan desain dan rata tengah kartu
                CodeBoxes(
                    du = du,
                    code = code,
                    enabled = inputEnabled,
                    hasError = state.wrongCode,
                    modifier = Modifier.at(du, 192.5f, 623.5f - t).width(du.dp(426f)),
                    onCodeChange = { code = it },
                    onDone = { if (full) onSubmit(code) }
                )
                DTextAt(
                    hint, x = 145.5f, capTop = 744f, size = 26.2f, origin = t, width = 520f,
                    lineHeight = 36.6f, align = TextAlign.Center, maxLines = 3
                )
                DesignButton(
                    text = buttonText,
                    onClick = { if (full) onSubmit(code) else onResend() },
                    modifier = Modifier.at(du, 157f, 876.6f - t).size(du.dp(496.9f), du.dp(70.9f)),
                    textSize = 26.1f, radius = 22f, bold = false,
                    enabled = full || canResend, loading = state.loading,
                    activeColor = GlassColors.Purple, idleColor = GlassColors.PurpleIdle,
                    idleTextColor = Color.White
                )

                Column(
                    Modifier.at(du, 81f, GUARD_CARD_BOTTOM + 24f - t).width(du.dp(648f)),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (state.wrongCode && !state.loading) {
                        DText(wrongText, 24f, color = GlassColors.Error, lineHeight = 32f)
                    }
                    state.error?.let {
                        SelectionContainer {
                            DText(it, 22f, color = GlassColors.Error, lineHeight = 30f)
                        }
                    }
                    // Teks status ("Koneksi ke Steam terputus, menyambung ulang..." dan sejenisnya) sengaja tidak
                    // ditampilkan di sini, sesuai permintaan. Kesalahan tetap tampil di atas.
                }
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

/** Saklar sesuai desain: jalur 85,5 x 53,4 dengan tombol bulat. Area ketuknya setinggi kotak 85,5 di desain. */
@Composable
private fun DesignToggle(checked: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val du = LocalDu.current
    Box(
        modifier
            .size(du.dp(85.5f), du.dp(85.3f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier
                .size(du.dp(85.5f), du.dp(53.4f))
                .clip(CircleShape)
                .background(if (checked) GlassColors.ToggleOn else GlassColors.Red)
        ) {
            Box(
                Modifier
                    .offset(x = du.dp(if (checked) 85.5f - 5.3f - 42.8f else 5.3f), y = du.dp(5.3f))
                    .size(du.dp(42.8f))
                    .clip(CircleShape)
                    .background(Color(0xFFF9F9F9))
            )
        }
    }
}

// Area desain layar izin: dari tepi atas ikon sampai di bawah kartu
private const val PERM_TOP = 358.7f

/**
 * Layar izin, setelah kode Steam Guard. Kedua saklar harus menyala supaya bisa lanjut: penyimpanan mengikuti izin
 * "akses semua file" yang sebenarnya, notifikasi mengikuti izin notifikasi. Saklar yang mati membuka dialog atau
 * pengaturannya. Setelah keduanya menyala, pop-up penyimpanan muncul di atas layar ini.
 */
@Composable
fun PermissionScreen(
    storageGranted: Boolean,
    notificationGranted: Boolean,
    onNotificationChanged: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    var notifAsked by remember { mutableStateOf(false) }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        onNotificationChanged(NotificationManagerCompat.from(context).areNotificationsEnabled())
    }
    val t = PERM_TOP

    Box {
        GlassBackground {
            val du = LocalDu.current
            Column(
                Modifier
                    .fillMaxSize()
                    .systemBarsPadding()
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Box(Modifier.size(du.dp(810f), du.dp(970.5f + 40f - t))) {
                    Image(
                        painter = painterResource(R.drawable.ic_user_key),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.at(du, 354.4f, 369f - t).size(du.dp(154.3f), du.dp(165.5f))
                    )
                    GlassPanel(Modifier.at(du, 81.6f, 567.4f - t).size(du.dp(647.8f), du.dp(403.1f))) {}

                    DTextAt(
                        tr("Izinkan", "Allow"),
                        x = 137f, capTop = 608f, size = 42.3f, origin = t, width = 400f, bold = true,
                        shadow = Shadow(Color(0x66000000), Offset(0f, 3f), 8f)
                    )
                    DTextAt(
                        tr(
                            "Beri izin aplikasi mengakses penyimpanan untuk menulis dan membaca data game.",
                            "Give the app permission to access storage to write and read game data."
                        ),
                        x = 133f, capTop = 679f, size = 26.1f, origin = t, width = 376f, lineHeight = 36f
                    )
                    DesignToggle(
                        checked = storageGranted,
                        onClick = { if (!storageGranted) openAllFilesAccessSettings(context) },
                        modifier = Modifier.at(du, 593f, 681.3f - t)
                    )
                    Box(
                        Modifier
                            .at(du, 133.6f, 809f - t)
                            .size(du.dp(549.6f), du.dp(3f))
                            .background(Color.White)
                    )
                    DTextAt(
                        tr("Berikan akses notifikasi", "Grant notification access"),
                        x = 133f, capTop = 830f, size = 26.1f, origin = t, width = 376f, lineHeight = 36f
                    )
                    DesignToggle(
                        checked = notificationGranted,
                        onClick = {
                            if (!notificationGranted) {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !notifAsked) {
                                    notifAsked = true
                                    notifLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                                } else {
                                    // Sistem tidak menampilkan dialog lagi setelah ditolak, jadi buka pengaturannya
                                    openNotificationSettings(context)
                                }
                            }
                        },
                        modifier = Modifier.at(du, 593f, 824f - t)
                    )
                }
            }
        }
    }
}

/** Layar tunggu saat masuk otomatis dengan akun yang tersimpan. "Ganti akun" membatalkan dan melupakan akun itu. */
@Composable
fun AutoLoginScreen(status: String?, onSwitchAccount: () -> Unit) {
    GlassBackground {
        val du = LocalDu.current
        Column(
            Modifier.fillMaxSize().systemBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Image(
                painter = painterResource(R.drawable.steam_logo),
                contentDescription = null,
                modifier = Modifier.size(du.dp(170f))
            )
            Spacer(Modifier.height(du.dp(56f)))
            CircularProgressIndicator(
                color = Color.White,
                strokeWidth = 3.dp,
                modifier = Modifier.size(du.dp(60f))
            )
            Spacer(Modifier.height(du.dp(36f)))
            DText(
                status ?: tr("Masuk ke Steam...", "Signing in to Steam..."),
                28f,
                modifier = Modifier.padding(horizontal = du.dp(60f)),
                align = TextAlign.Center
            )
        }
        DText(
            tr("Ganti akun", "Switch account"),
            26f,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = du.dp(70f))
                .clickable(onClick = onSwitchAccount)
                .padding(du.dp(16f)),
            color = Color(0xFF8FB4FF)
        )
    }
}
