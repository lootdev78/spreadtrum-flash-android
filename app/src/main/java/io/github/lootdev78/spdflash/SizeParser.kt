package io.github.lootdev78.spdflash

object SizeParser {
    private val pattern = Regex("^\\s*(0x[0-9a-fA-F]+|[0-9]+(?:\\.[0-9]+)?)\\s*([kmgt]?i?b?|s)?\\s*$", RegexOption.IGNORE_CASE)

    fun parse(value: String): Long? {
        val normalized = value.trim().let { text ->
            if (text.startsWith("ubi", ignoreCase = true)) text.drop(3) else text
        }
        val match = pattern.matchEntire(normalized) ?: return null
        val numberText = match.groupValues[1]
        val suffix = match.groupValues[2].lowercase()
        if (numberText.startsWith("0x", ignoreCase = true)) {
            return numberText.substring(2).toLongOrNull(16)
        }
        val number = numberText.toDoubleOrNull() ?: return null
        val multiplier = when (suffix) {
            "", "b" -> 1.0
            "k", "kb", "kib" -> 1024.0
            "m", "mb", "mib" -> 1024.0 * 1024.0
            "g", "gb", "gib" -> 1024.0 * 1024.0 * 1024.0
            "t", "tb", "tib" -> 1024.0 * 1024.0 * 1024.0 * 1024.0
            "s" -> 512.0
            else -> return null
        }
        val result = number * multiplier
        return if (result.isFinite() && result >= 0 && result <= Long.MAX_VALUE) result.toLong() else null
    }

    fun human(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val units = arrayOf("KiB", "MiB", "GiB", "TiB")
        var value = bytes.toDouble()
        var index = -1
        while (value >= 1024 && index < units.lastIndex) {
            value /= 1024
            index++
        }
        return "%.2f %s".format(value, units[index])
    }
}
