package io.github.lootdev78.spdflash

import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class NativeResultTest {
    @Test fun mapsCancellation() = assertEquals("Cancelled safely", NativeResult.describe(-214))

    @Test fun mapsKnownBslResponse() {
        assertEquals("0x00AA · Secure verification error", BslResponse.describeHex("0x00aa"))
    }

    @Test fun unknownNativeCodeRemainsActionable() {
        assertTrue(NativeResult.describe(-777).contains("-777"))
    }
}
