package com.example.aiagenttestapp.ui.components

import kotlin.math.roundToInt

private const val KB = 1024.0
private const val MB = KB * 1024
private const val GB = MB * 1024

/** "2.4 GB", "557 MB". Base-1024, which is what a phone's storage screen shows. */
fun formatBytes(bytes: Long): String = when {
    bytes >= GB -> String.format("%.1f GB", bytes / GB)
    bytes >= MB -> "${(bytes / MB).roundToInt()} MB"
    bytes >= KB -> "${(bytes / KB).roundToInt()} KB"
    else -> "$bytes B"
}

fun formatBytesPerSecond(bytesPerSecond: Long): String =
    if (bytesPerSecond <= 0) "" else "${formatBytes(bytesPerSecond)}/s"

/** "3m 20s left", "45s left". Null input means we genuinely do not know yet, so say nothing. */
fun formatEta(secondsRemaining: Long?): String {
    if (secondsRemaining == null || secondsRemaining <= 0) return ""
    val minutes = secondsRemaining / 60
    val seconds = secondsRemaining % 60
    return when {
        minutes >= 60 -> "${minutes / 60}h ${minutes % 60}m left"
        minutes > 0 -> "${minutes}m ${seconds}s left"
        else -> "${seconds}s left"
    }
}

/** "3.9B", "820M" -- the headline parameter budget. */
fun formatParams(billions: Double): String = when {
    billions <= 0 -> "—"
    billions < 1.0 -> "${(billions * 1000).roundToInt()}M"
    else -> String.format("%.1fB", billions)
}
