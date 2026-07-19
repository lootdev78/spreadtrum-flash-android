package io.github.lootdev78.spdflash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProgressParserTest {
    @Test fun recognizesStagesAndPartitions() {
        assertEquals(DeviceStage.BROM, ProgressParser.parse("CMD_CONNECT bootrom").stage)
        assertEquals(DeviceStage.FDL1, ProgressParser.parse("CMD_CONNECT FDL1").stage)
        assertEquals(DeviceStage.FDL2, ProgressParser.parse("EXEC FDL2").stage)
        assertEquals("boot" to 64L * 1024 * 1024, ProgressParser.parse("  4 boot 64MB").partition)
    }

    @Test fun ignoresUnrelatedLines() {
        val parsed = ProgressParser.parse("hello")
        assertNull(parsed.stage)
        assertNull(parsed.partition)
    }
}
