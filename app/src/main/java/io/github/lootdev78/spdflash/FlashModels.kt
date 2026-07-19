package io.github.lootdev78.spdflash

import android.hardware.usb.UsbDevice

enum class DeviceStage(val label: String) {
    DISCONNECTED("Disconnected"),
    DIAG("Diag"),
    DOWNLOAD("Download"),
    BROM("Boot-ROM"),
    FDL1("FDL1"),
    FDL2("FDL2"),
    UNKNOWN("Unknown"),
}

enum class RiskLevel(val label: String) {
    SAFE("Safe"),
    CAUTION("Caution"),
    DESTRUCTIVE("Destructive"),
}

enum class ArgumentType {
    TEXT,
    HEX,
    INTEGER,
    SIZE,
    FILE,
    DIRECTORY,
    OUTPUT_FILE,
    PARTITION,
    ENUM,
}

data class CommandArgument(
    val label: String,
    val type: ArgumentType = ArgumentType.TEXT,
    val hint: String = "",
    val options: List<String> = emptyList(),
    val optional: Boolean = false,
)

data class CommandSpec(
    val command: String,
    val title: String,
    val description: String,
    val category: String,
    val arguments: List<CommandArgument> = emptyList(),
    val risk: RiskLevel = RiskLevel.SAFE,
    val stages: Set<DeviceStage> = emptySet(),
    val aliases: Set<String> = emptySet(),
) {
    val dangerous: Boolean get() = risk == RiskLevel.DESTRUCTIVE
    override fun toString(): String = title
}

data class QueuedCommand(
    val id: Long = System.nanoTime(),
    val title: String,
    val tokens: List<String>,
    val risk: RiskLevel = RiskLevel.SAFE,
    val expectedBytes: Long? = null,
) {
    val dangerous: Boolean get() = risk == RiskLevel.DESTRUCTIVE
    val command: String get() = tokens.firstOrNull().orEmpty()
}

data class UsbDeviceItem(val device: UsbDevice) {
    val identifier: String = "%04x:%04x".format(device.vendorId, device.productId)
    val isDownloadPort: Boolean = device.vendorId == 0x1782 && device.productId == 0x4d00

    override fun toString(): String = buildString {
        append(identifier)
        append(if (isDownloadPort) "  Download" else "  Diag/Boot")
        append(" · ")
        append(device.productName ?: device.deviceName)
    }
}

data class ImportedFile(
    val path: String,
    val displayName: String,
    val sizeBytes: Long,
    val sha256: String,
)

data class LoaderSelection(
    val path: String? = null,
    val displayName: String = "No file selected",
    val address: String = "",
    val sizeBytes: Long = 0,
    val sha256: String = "",
)

data class TransferProgress(
    val currentBytes: Long = 0,
    val totalBytes: Long = 0,
    val percent: Int = 0,
    val bytesPerSecond: Long = 0,
    val etaSeconds: Long? = null,
    val label: String = "",
    val indeterminate: Boolean = true,
)

data class FlashUiState(
    val connectedLabel: String? = null,
    val connectedDeviceId: Int? = null,
    val connectedPid: Int? = null,
    val stage: DeviceStage = DeviceStage.DISCONNECTED,
    val running: Boolean = false,
    val cancelling: Boolean = false,
    val status: String = "No device connected",
    val currentCommand: String = "",
    val progress: TransferProgress = TransferProgress(),
    val lastResult: Int? = null,
    val outputTarget: String = "App workspace",
    val partitionCount: Int = 0,
    val interruptedRunAvailable: Boolean = false,
)

data class PipelineConfig(
    val waitSeconds: Int = 30,
    val verbose: Int = 1,
    val reconnect: Boolean = false,
    val sync: Boolean = false,
    val kickMode: Int = 0,
    val kickToMode: Int = 2,
    val useLoaders: Boolean = true,
    val fdl1Address: String = "",
    val fdl2Address: String = "",
    val executeFdl2: Boolean = true,
    val dangerConfirmed: Boolean = false,
    val directSafOutput: Boolean = true,
)



data class PipelineDraft(
    val waitSeconds: Int = 30,
    val verbose: Int = 1,
    val reconnect: Boolean = false,
    val sync: Boolean = false,
    val kickMode: Int = 0,
    val kickToMode: Int = 2,
    val useLoaders: Boolean = true,
    val executeFdl2: Boolean = true,
    val directSafOutput: Boolean = false,
) {
    companion object {
        fun from(config: PipelineConfig) = PipelineDraft(
            waitSeconds = config.waitSeconds,
            verbose = config.verbose,
            reconnect = config.reconnect,
            sync = config.sync,
            kickMode = config.kickMode,
            kickToMode = config.kickToMode,
            useLoaders = config.useLoaders,
            executeFdl2 = config.executeFdl2,
            directSafOutput = config.directSafOutput,
        )
    }
}

data class InputMetadata(
    val displayName: String,
    val sizeBytes: Long,
    val sha256: String = "",
)

data class CommandValidation(
    val valid: Boolean,
    val normalizedArguments: List<String>,
    val errors: Map<Int, String> = emptyMap(),
    val warning: String? = null,
    val expectedBytes: Long? = null,
)
