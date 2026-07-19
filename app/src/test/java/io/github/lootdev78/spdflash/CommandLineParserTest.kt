package io.github.lootdev78.spdflash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CommandLineParserTest {
    @Test fun parsesQuotedPathsAndEscapes() {
        assertEquals(
            listOf("write_part", "boot_a", "/tmp/My image.img"),
            CommandLineParser.parse("write_part boot_a \"/tmp/My image.img\""),
        )
        assertEquals(listOf("r", "all_lite"), CommandLineParser.parse("  r   all_lite "))
    }

    @Test fun rejectsUnclosedQuotes() {
        assertThrows(IllegalArgumentException::class.java) {
            CommandLineParser.parse("read_part boot 0 16m \"broken")
        }
    }
}
