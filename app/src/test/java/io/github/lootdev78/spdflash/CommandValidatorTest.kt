package io.github.lootdev78.spdflash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CommandValidatorTest {
    @Test fun validatesTypedArguments() {
        val spec = requireNotNull(CommandCatalog.find("read_part"))
        val result = CommandValidator.validate(spec, listOf("boot", "0", "64m", "boot.img"))
        assertTrue(result.valid)
        assertEquals(64L * 1024 * 1024, result.expectedBytes)
    }

    @Test fun rejectsUnsafeOutputName() {
        val spec = requireNotNull(CommandCatalog.find("read_mem"))
        val result = CommandValidator.validate(spec, listOf("0x1000", "4k", "../dump.bin"))
        assertFalse(result.valid)
    }

    @Test fun detectsOversizedPartitionImage() {
        val image = File.createTempFile("spd-test", ".img").apply { writeBytes(ByteArray(16)) }
        try {
            val spec = requireNotNull(CommandCatalog.find("write_part"))
            val result = CommandValidator.validate(spec, listOf("boot", image.absolutePath), mapOf("boot" to 8L))
            assertTrue(result.warning.orEmpty().contains("larger"))
        } finally {
            image.delete()
        }
    }
}
