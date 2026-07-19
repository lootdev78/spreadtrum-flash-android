package io.github.lootdev78.spdflash

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

object HashUtils {
    fun sha256(file: File): String = file.inputStream().buffered().use(::sha256)

    fun sha256(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(1024 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
