package com.example.steamgrab

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.math.roundToInt

// Efek kaca pop-up seperti di desain: yang diburamkan hanya bagian layar di belakang jendela pop-up itu sendiri,
// bukan seluruh layar. Compose belum punya blur latar, jadi layar difoto sebentar sebelum pop-up tampil, diperkecil,
// diburamkan, lalu digambar di dalam jendela pop-up (dipotong sesuai bentuknya).

/** Foto layar yang sudah diburamkan. [luminance] (0 sampai 1) dipakai menentukan segelap apa kaca perlu dibuat. */
class GlassBackdrop(
    val image: ImageBitmap,
    val windowWidth: Int,
    val windowHeight: Int,
    val luminance: Float,
)

/** Hasil pengambilan foto layar. [backdrop] null kalau gagal, dan pop-up memakai kaca gelap polos. */
class GlassState(val ready: Boolean, val backdrop: GlassBackdrop?)

/** Foto layar untuk pop-up yang sedang tampil. Null di luar pop-up. */
val LocalGlassBackdrop = compositionLocalOf<GlassBackdrop?> { null }

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private const val DOWNSCALE = 8
private const val BLUR_RADIUS = 3
private const val BLUR_PASSES = 3

object GlassCapture {

    /** Memfoto jendela aktivitas, mengecilkan, dan memburamkannya. Null kalau perangkat atau sistem menolak. */
    suspend fun capture(activity: Activity): GlassBackdrop? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
        val window = activity.window
        val width = window.decorView.width
        val height = window.decorView.height
        if (width <= 0 || height <= 0) return null

        val full = try {
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        } catch (e: OutOfMemoryError) {
            return null
        }
        val ok = suspendCancellableCoroutine<Boolean> { cont ->
            try {
                PixelCopy.request(window, full, { result ->
                    if (cont.isActive) cont.resume(result == PixelCopy.SUCCESS)
                }, Handler(Looper.getMainLooper()))
            } catch (e: IllegalArgumentException) {
                if (cont.isActive) cont.resume(false)
            }
        }
        if (!ok) {
            full.recycle()
            return null
        }

        return withContext(Dispatchers.Default) {
            val sw = (width / DOWNSCALE).coerceAtLeast(2)
            val sh = (height / DOWNSCALE).coerceAtLeast(2)
            val small = Bitmap.createScaledBitmap(full, sw, sh, true)
            val pixels = IntArray(sw * sh)
            small.getPixels(pixels, 0, sw, 0, 0, sw, sh)
            small.recycle()
            full.recycle()
            repeat(BLUR_PASSES) {
                blurPass(pixels, sw, sh, BLUR_RADIUS, horizontal = true)
                blurPass(pixels, sw, sh, BLUR_RADIUS, horizontal = false)
            }
            var sum = 0f
            for (c in pixels) {
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                sum += 0.299f * r + 0.587f * g + 0.114f * b
            }
            val luminance = (sum / pixels.size / 255f).coerceIn(0f, 1f)
            val blurred = Bitmap.createBitmap(pixels, sw, sh, Bitmap.Config.ARGB_8888)
            GlassBackdrop(blurred.asImageBitmap(), width, height, luminance)
        }
    }

    /** Satu putaran blur kotak dengan jendela bergeser, searah baris atau kolom. Tepi gambar diperpanjang. */
    private fun blurPass(pix: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
        val len = if (horizontal) w else h
        val lines = if (horizontal) h else w
        val line = IntArray(len)
        val out = IntArray(len)
        val div = 2 * r + 1
        for (l in 0 until lines) {
            for (i in 0 until len) line[i] = if (horizontal) pix[l * w + i] else pix[i * w + l]
            var sr = 0
            var sg = 0
            var sb = 0
            for (k in -r..r) {
                val c = line[k.coerceIn(0, len - 1)]
                sr += (c shr 16) and 0xFF
                sg += (c shr 8) and 0xFF
                sb += c and 0xFF
            }
            for (i in 0 until len) {
                out[i] = (0xFF shl 24) or ((sr / div) shl 16) or ((sg / div) shl 8) or (sb / div)
                val add = line[(i + r + 1).coerceAtMost(len - 1)]
                val rem = line[(i - r).coerceAtLeast(0)]
                sr += ((add shr 16) and 0xFF) - ((rem shr 16) and 0xFF)
                sg += ((add shr 8) and 0xFF) - ((rem shr 8) and 0xFF)
                sb += (add and 0xFF) - (rem and 0xFF)
            }
            for (i in 0 until len) {
                if (horizontal) pix[l * w + i] = out[i] else pix[i * w + l] = out[i]
            }
        }
    }
}

/**
 * Mengambil foto layar tepat sebelum pop-up tampil. [GlassState.ready] baru true setelah selesai (atau gagal), dan
 * pop-up baru digambar sesudahnya supaya tidak ikut terfoto. Dua frame ditunggu dulu supaya layar yang baru
 * berganti sudah benar-benar tergambar.
 */
@Composable
fun rememberGlass(): GlassState {
    val context = LocalContext.current
    var ready by remember { mutableStateOf(false) }
    var backdrop by remember { mutableStateOf<GlassBackdrop?>(null) }
    LaunchedEffect(Unit) {
        backdrop = try {
            withFrameNanos { }
            withFrameNanos { }
            context.findActivity()?.let { GlassCapture.capture(it) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            DebugLog.log("Foto layar untuk efek kaca gagal: ${e.describe()}")
            null
        }
        if (backdrop == null) DebugLog.log("Efek blur kaca tidak tersedia, memakai kaca gelap polos")
        ready = true
    }
    return GlassState(ready, backdrop)
}

/**
 * Jendela kaca: foto layar yang diburamkan dan digelapkan sebagai latar (atau gradasi gelap polos kalau foto tidak
 * ada), lalu isinya, lalu garis tepi paling atas supaya gambar di dalamnya tidak menutupi garis.
 */
@Composable
fun GlassBox(
    shape: Shape,
    borderWidth: Dp,
    borderBrush: Brush,
    modifier: Modifier = Modifier,
    backdrop: GlassBackdrop? = LocalGlassBackdrop.current,
    content: @Composable BoxScope.() -> Unit,
) {
    var origin by remember { mutableStateOf(Offset.Zero) }
    Box(
        modifier
            .onGloballyPositioned { origin = it.positionInWindow() }
            .clip(shape)
            .drawBehind {
                if (backdrop != null) {
                    drawImage(
                        image = backdrop.image,
                        srcOffset = IntOffset.Zero,
                        srcSize = IntSize(backdrop.image.width, backdrop.image.height),
                        dstOffset = IntOffset(-origin.x.roundToInt(), -origin.y.roundToInt()),
                        dstSize = IntSize(backdrop.windowWidth, backdrop.windowHeight),
                        filterQuality = FilterQuality.High
                    )
                    // Kecerahan dikurangi seperti di desain. Latar terang (layar daftar game) digelapkan lebih kuat.
                    val extra = ((backdrop.luminance - 0.25f) / 0.75f).coerceIn(0f, 1f)
                    val top = 0.32f + 0.42f * extra
                    val bottom = 0.54f + 0.40f * extra
                    drawRect(
                        Brush.verticalGradient(
                            listOf(Color.Black.copy(alpha = top), Color.Black.copy(alpha = bottom))
                        )
                    )
                } else {
                    drawRect(
                        Brush.verticalGradient(listOf(Color(0xF2172131), Color(0xFA0B121B)))
                    )
                }
            }
    ) {
        content()
        Box(Modifier.matchParentSize().border(borderWidth, borderBrush, shape))
    }
}
