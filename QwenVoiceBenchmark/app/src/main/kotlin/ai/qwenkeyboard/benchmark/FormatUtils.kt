package ai.qwenkeyboard.benchmark

fun formatBytes(bytes: Long): String {
    val b = bytes.coerceAtLeast(0L).toDouble()
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var value = b
    var unit = 0
    while (value >= 1024.0 && unit < units.lastIndex) {
        value /= 1024.0
        unit++
    }
    return if (unit == 0) "${bytes.coerceAtLeast(0L)} ${units[unit]}" else String.format("%.1f %s", value, units[unit])
}
