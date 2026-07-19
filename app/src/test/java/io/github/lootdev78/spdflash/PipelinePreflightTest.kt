package io.github.lootdev78.spdflash

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PipelinePreflightTest {
    private val noLoader = LoaderSelection()

    @Test fun requiresSafTreeWhenDirectOutputIsEnabled() {
        val result = PipelinePreflight.check(
            PipelineConfig(useLoaders = false, directSafOutput = true),
            listOf(QueuedCommand(title = "UID", tokens = listOf("chip_uid"))),
            DeviceStage.BROM,
            noLoader,
            noLoader,
            hasDirectOutputTree = false,
        )
        assertFalse(result.valid)
    }

    @Test fun terminalCommandMustBeLast() {
        val result = PipelinePreflight.check(
            PipelineConfig(useLoaders = false, directSafOutput = false),
            listOf(
                QueuedCommand(title = "Reset", tokens = listOf("reset")),
                QueuedCommand(title = "UID", tokens = listOf("chip_uid")),
            ),
            DeviceStage.FDL2,
            noLoader,
            noLoader,
            hasDirectOutputTree = false,
        )
        assertTrue(result.errors.any { it.contains("final") })
    }

    @Test fun warnsAboutDuplicateOutputs() {
        val queue = listOf(
            QueuedCommand(title = "RAM A", tokens = listOf("read_mem", "0", "4k", "dump.bin")),
            QueuedCommand(title = "RAM B", tokens = listOf("read_mem", "0x1000", "4k", "dump.bin")),
        )
        val result = PipelinePreflight.check(
            PipelineConfig(useLoaders = false, directSafOutput = false),
            queue,
            DeviceStage.BROM,
            noLoader,
            noLoader,
            hasDirectOutputTree = false,
        )
        assertTrue(result.warnings.any { it.contains("more than once") })
    }
}
