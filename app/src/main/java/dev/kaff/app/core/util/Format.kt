package dev.kaff.app.core.util

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.kaff.app.R
import java.text.NumberFormat
import java.util.Locale

/** Locale-aware number with at most [fractionDigits] decimals ("٣٫٢" in Arabic, "3.2" in English). */
fun formatNumber(value: Double, fractionDigits: Int = 1, locale: Locale = Locale.getDefault()): String =
    NumberFormat.getNumberInstance(locale).apply {
        maximumFractionDigits = fractionDigits
        minimumFractionDigits = 0
    }.format(value)

private const val KB = 1024.0
private const val MB = KB * 1024
private const val GB = MB * 1024

@Composable
fun fileSizeText(bytes: Long): String = when {
    bytes >= GB -> stringResource(R.string.size_gb, formatNumber(bytes / GB))
    bytes >= MB -> stringResource(R.string.size_mb, formatNumber(bytes / MB))
    bytes >= KB -> stringResource(R.string.size_kb, formatNumber(bytes / KB, 0))
    else -> stringResource(R.string.size_bytes, formatNumber(bytes.toDouble(), 0))
}

@Composable
fun speedText(bytesPerSecond: Double): String = stringResource(R.string.done_speed, fileSizeText(bytesPerSecond.toLong()))

@Composable
fun durationText(ms: Long): String = stringResource(R.string.duration_seconds, formatNumber(ms / 1000.0, if (ms < 10_000) 1 else 0))
