package io.github.lootdev78.spdflash

/** Stable, user-facing descriptions for Android JNI bridge result codes. */
object NativeResult {
    fun describe(code: Int): String = when (code) {
        0 -> "Completed successfully"
        -200 -> "The native engine is already in use by another session"
        -201 -> "Could not duplicate the USB file descriptor"
        -202, -203, -204 -> "Could not prepare native command arguments"
        -205 -> "Working directory is unavailable"
        -206, -207, -208 -> "Could not initialize the JNI callback interface"
        -209, -210, -211, -212 -> "Could not initialize native log forwarding"
        -213 -> "The upstream core terminated the operation because of an error"
        -214 -> "Cancelled safely"
        -215 -> "Invalid native session parameters"
        -998 -> "Android execution failed with an exception"
        -999 -> "Native engine was not started"
        else -> if (code < 0) "Native engine reported error code $code" else "Upstream process exited with code $code"
    }
}

/** Names and concise German explanations for the BSL response values used by spreadtrum_flash. */
object BslResponse {
    private val names = mapOf(
        0x80 to "ACK",
        0x81 to "Version",
        0x82 to "Invalid command",
        0x83 to "Unknown command",
        0x84 to "Operation failed",
        0x85 to "Baud rate not supported",
        0x86 to "Download not started",
        0x87 to "Download already started",
        0x88 to "Download ended too early",
        0x89 to "Invalid download target",
        0x8A to "Invalid download size",
        0x8B to "Verifikationsfehler",
        0x8C to "Not verified",
        0x8D to "Not enough device memory",
        0x8E to "Device timeout",
        0x8F to "Device operation successful",
        0x96 to "Inkompatible Partition / Authentifizierungsdaten",
        0x97 to "Unknown device",
        0x98 to "Invalid device size",
        0x99 to "Invalid SDRAM",
        0x9A to "Falsche SDRAM-Parameter",
        0xA0 to "Checksum error",
        0xA1 to "Checksum mismatch",
        0xA2 to "Write error",
        0xA3 to "Chip ID mismatch",
        0xA4 to "Flash configuration error",
        0xA5 to "STL size error",
        0xAA to "Secure verification error",
        0xAC to "Flash writing is not enabled",
        0xAD to "Could not enable secure boot",
        0xB3 to "Flash is write-protected",
        0xB4 to "Flash initialization failed",
        0xB6 to "DDR check failed",
        0xB7 to "Self-refresh failed",
        0xB9 to "Random data/handshake error",
        0xBF to "Partition is not supported",
        0xD0 to "Invalid magic value",
        0xD1 to "Repartition failed",
        0xD2 to "Flash read error",
        0xD3 to "Device could not allocate memory",
        0xFE to "Command is not supported by the FDL",
        0xFF to "FDL protocol message",
    )

    fun describe(code: Int): String = names[code] ?: "Unknown BSL response"

    fun describeHex(token: String): String? {
        val normalized = token.removePrefix("0x").removePrefix("0X")
        val code = normalized.toIntOrNull(16) ?: return null
        return "0x%04X · %s".format(code, describe(code))
    }
}
