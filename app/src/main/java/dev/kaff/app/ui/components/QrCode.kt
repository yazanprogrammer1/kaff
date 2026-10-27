package dev.kaff.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.WriterException
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import dev.kaff.app.ui.theme.KaffShapes

/** A QR code drawn as rounded modules, dark on white so every camera reads it in both themes. */
@Composable
fun QrCode(content: String, description: String, modifier: Modifier = Modifier, size: Dp = 168.dp) {
    val matrix = remember(content) { encode(content) } ?: return
    Box(
        modifier
            .clip(KaffShapes.medium)
            .background(Color.White)
            .padding(12.dp)
            .semantics { contentDescription = description },
    ) {
        Canvas(Modifier.size(size)) {
            val cell = this.size.width / matrix.width
            val ink = Color(0xFF07100C)
            for (y in 0 until matrix.height) {
                for (x in 0 until matrix.width) {
                    if (matrix[x, y]) {
                        drawRoundRect(
                            ink,
                            topLeft = Offset(x * cell, y * cell),
                            size = Size(cell * 1.02f, cell * 1.02f),
                            cornerRadius = CornerRadius(cell * 0.28f),
                        )
                    }
                }
            }
        }
    }
}

private fun encode(content: String): BitMatrix? = try {
    QRCodeWriter().encode(
        content,
        BarcodeFormat.QR_CODE,
        0,
        0,
        mapOf(EncodeHintType.MARGIN to 0, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.CHARACTER_SET to "UTF-8"),
    )
} catch (e: WriterException) {
    null
}

/** Wi-Fi join payload understood by Android and iOS cameras. */
fun wifiQrPayload(ssid: String, passphrase: String): String {
    fun esc(s: String) = buildString { s.forEach { ch -> if (ch in "\\;,:\"") append('\\'); append(ch) } }
    return "WIFI:T:WPA;S:${esc(ssid)};P:${esc(passphrase)};;"
}
