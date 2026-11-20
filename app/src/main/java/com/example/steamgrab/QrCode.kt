package com.example.steamgrab

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** Menggambar [content] sebagai kode QR hitam di atas latar yang disediakan pemanggil (tanpa pinggiran putih sendiri). */
@Composable
fun QrCodeView(content: String, modifier: Modifier = Modifier) {
    val matrix: BitMatrix? = remember(content) {
        try {
            QRCodeWriter().encode(
                content,
                BarcodeFormat.QR_CODE,
                0,
                0,
                mapOf(
                    EncodeHintType.MARGIN to 0,
                    EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M
                )
            )
        } catch (e: Exception) {
            DebugLog.log("Gagal membuat kode QR: ${e.describe()}")
            null
        }
    }
    Canvas(modifier) {
        val m = matrix ?: return@Canvas
        val modules = m.width
        if (modules <= 0) return@Canvas
        val cell = size.minDimension / modules
        // Sedikit tumpang tindih supaya tidak ada garis tipis di antara kotak yang bersebelahan
        val cellSize = Size(cell + 0.6f, cell + 0.6f)
        for (y in 0 until m.height) {
            for (x in 0 until modules) {
                if (m.get(x, y)) {
                    drawRect(Color.Black, topLeft = Offset(x * cell, y * cell), size = cellSize)
                }
            }
        }
    }
}
