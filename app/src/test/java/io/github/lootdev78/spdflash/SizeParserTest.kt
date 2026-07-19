package io.github.lootdev78.spdflash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SizeParserTest {
    @Test fun parsesHexAndUnits() {
        assertEquals(4096L, SizeParser.parse("0x1000"))
        assertEquals(16L * 1024 * 1024, SizeParser.parse("16m"))
        assertEquals(40L * 1024 * 1024, SizeParser.parse("ubi40m"))
    }

    @Test fun rejectsInvalidAndOverflowingInput() {
        assertNull(SizeParser.parse("twelve"))
        assertNull(SizeParser.parse("999999999999999999999t"))
    }
}
