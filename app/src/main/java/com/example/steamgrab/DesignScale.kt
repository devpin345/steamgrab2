package com.example.steamgrab

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp

// Skala desain. Halaman di file desain lebarnya 810 satuan. Semua ukuran dan posisi di layar login, Steam Guard,
// izin, dan pop-up penyimpanan ditulis dalam satuan itu, lalu diubah ke dp menurut lebar layar, jadi proporsinya
// sama dengan desain di ponsel berapa pun.

/** Font desain (Open Sans). */
val OpenSans = FontFamily(
    Font(R.font.opensans_regular, FontWeight.Normal),
    Font(R.font.opensans_bold, FontWeight.Bold),
)

private const val DESIGN_WIDTH = 810f

// Di tablet atau layar sangat lebar, skala dibatasi supaya kartu tidak membesar berlebihan
private const val MAX_SCALE = 0.62f

/** Pengubah satuan desain ke dp dan sp. Ukuran huruf tidak ikut pengaturan ukuran font sistem, seperti di desain. */
@Immutable
class Du(val u: Float, private val density: Density) {
    fun dp(v: Float): Dp = (v * u).dp

    fun sp(v: Float): TextUnit = with(density) { (v * u).dp.toSp() }

    /**
     * Posisi atas kotak teks (satuan desain) supaya tepi atas huruf besar berada di [capTop]. File desain mencatat
     * posisi teks dari tepi atas huruf, sedangkan kotak teks di layar dimulai dari tinggi baris.
     */
    fun boxTop(capTop: Float, size: Float, lineHeight: Float): Float = capTop - (lineHeight / 2f - 0.319f * size)
}

val LocalDu = staticCompositionLocalOf<Du> { error("LocalDu belum disediakan") }

/** Menyediakan [Du] sesuai lebar area ini, dan mengisi seluruh area. */
@Composable
fun ProvideDu(content: @Composable (Du) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val u = (maxWidth.value / DESIGN_WIDTH).coerceAtMost(MAX_SCALE)
        val du = remember(u, density) { Du(u, density) }
        CompositionLocalProvider(LocalDu provides du) { content(du) }
    }
}

/** Menempatkan elemen di (x, y) satuan desain dari pojok kiri atas induknya. */
fun Modifier.at(du: Du, x: Float, y: Float): Modifier = offset(du.dp(x), du.dp(y))

private val CenteredLine = LineHeightStyle(
    alignment = LineHeightStyle.Alignment.Center,
    trim = LineHeightStyle.Trim.None
)

/** Teks Open Sans dengan ukuran dan tinggi baris dalam satuan desain. */
@OptIn(ExperimentalTextApi::class)
@Composable
fun DText(
    text: String,
    size: Float,
    modifier: Modifier = Modifier,
    color: Color = Color.White,
    bold: Boolean = false,
    lineHeight: Float = size * 1.4f,
    align: TextAlign? = null,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    shadow: Shadow? = null,
) {
    val du = LocalDu.current
    Text(
        text = text,
        modifier = modifier,
        color = color,
        fontSize = du.sp(size),
        fontFamily = OpenSans,
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        lineHeight = du.sp(lineHeight),
        textAlign = align,
        maxLines = maxLines,
        overflow = overflow,
        style = TextStyle(lineHeightStyle = CenteredLine, shadow = shadow)
    )
}

/**
 * Teks yang tepi atas hurufnya ada di ([x], [capTop]) satuan desain, relatif terhadap [origin] (tepi atas area
 * penampung dalam satuan desain). [width] membatasi lebar supaya pembungkusan baris sama dengan desain.
 */
@Composable
fun DTextAt(
    text: String,
    x: Float,
    capTop: Float,
    size: Float,
    origin: Float,
    modifier: Modifier = Modifier,
    width: Float? = null,
    color: Color = Color.White,
    bold: Boolean = false,
    lineHeight: Float = size * 1.4f,
    align: TextAlign? = null,
    maxLines: Int = Int.MAX_VALUE,
    shadow: Shadow? = null,
) {
    val du = LocalDu.current
    var m = modifier.at(du, x, du.boxTop(capTop, size, lineHeight) - origin)
    if (width != null) m = m.width(du.dp(width))
    DText(text, size, m, color, bold, lineHeight, align, maxLines, TextOverflow.Clip, shadow)
}
