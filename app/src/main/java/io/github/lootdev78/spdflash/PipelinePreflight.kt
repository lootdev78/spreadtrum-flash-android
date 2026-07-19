package io.github.lootdev78.spdflash

import java.io.File

data class PreflightResult(
    val errors: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
) {
    val valid: Boolean get() = errors.isEmpty()
}

object PipelinePreflight {
    private val terminalCommands = setOf("reset", "reboot-recovery", "reboot-fastboot", "poweroff", "sendloop")

    fun check(
        config: PipelineConfig,
        queue: List<QueuedCommand>,
        currentStage: DeviceStage,
        fdl1: LoaderSelection,
        fdl2: LoaderSelection,
        hasDirectOutputTree: Boolean,
    ): PreflightResult {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        if (queue.isEmpty() && !config.useLoaders) errors += "The queue and loader pipeline are empty."
        if (config.directSafOutput && !hasDirectOutputTree) {
            errors += "Direct SAF output is enabled, but no writable destination directory was selected."
        }
        if (config.kickMode !in 0..2) errors += "Invalid kick mode."
        if (config.kickToMode !in 0..127) errors += "Kick target mode must be between 0 and 127."
        if (config.waitSeconds !in 1..3600) errors += "Wait time must be between 1 and 3600 seconds."

        if (config.useLoaders) {
            if (!isInputReference(fdl1.path)) errors += "The FDL1 file is missing or unreadable."
            if (!isInputReference(fdl2.path)) errors += "The FDL2 file is missing or unreadable."
            if (!isHex(config.fdl1Address)) errors += "The FDL1 address is invalid."
            if (!isHex(config.fdl2Address)) errors += "The FDL2 address is invalid."
            if (fdl1.path == fdl2.path && !fdl1.path.isNullOrBlank()) {
                warnings += "FDL1 and FDL2 refer to the same file. Verify the loader selection."
            }
        }

        if (queue.any { it.command in setOf("quit", "exit") }) {
            errors += "quit/exit is appended automatically by the app and must not be added to the queue."
        }
        queue.forEachIndexed { index, command ->
            if (command.command in terminalCommands && index != queue.lastIndex) {
                errors += "${command.title} must be the final operation."
            }
        }
        if (queue.count { it.command in terminalCommands } > 1) {
            errors += "Only one terminal reboot, power-off, or infinite-loop operation is allowed."
        }

        val eraseAllIndex = queue.indexOfFirst { it.command == "erase_all" }
        if (eraseAllIndex >= 0) {
            val allowedAfter = queue.drop(eraseAllIndex + 1).all { it.command in terminalCommands }
            if (eraseAllIndex > 0 || !allowedAfter) {
                errors += "Erase entire flash must run alone or immediately before a terminal reboot."
            }
        }

        val outputNames = queue.mapNotNull(::outputName)
        outputNames.groupingBy { it.lowercase() }.eachCount()
            .filterValues { it > 1 }
            .keys
            .forEach { warnings += "Output file '$it' is used more than once and may be overwritten." }

        var stage = when {
            config.useLoaders && config.executeFdl2 -> DeviceStage.FDL2
            config.useLoaders -> DeviceStage.FDL1
            currentStage == DeviceStage.DOWNLOAD -> DeviceStage.BROM
            else -> currentStage
        }
        queue.forEach { command ->
            val spec = CommandCatalog.find(command.command)
            when {
                spec == null -> warnings += "Unknown raw command '${command.command}': stage and risk cannot be verified automatically."
                spec.stages.isNotEmpty() && stage !in spec.stages && stage != DeviceStage.UNKNOWN -> {
                    errors += "${command.title} requires ${spec.stages.joinToString { it.label }}, but ${stage.label} is expected to be active."
                }
            }
            stage = when (command.command) {
                "fdl" -> if (stage == DeviceStage.BROM || stage == DeviceStage.DOWNLOAD) DeviceStage.FDL1 else stage
                "exec" -> if (stage == DeviceStage.FDL1) DeviceStage.FDL2 else stage
                "reset", "reboot-recovery", "reboot-fastboot", "poweroff" -> DeviceStage.DISCONNECTED
                else -> stage
            }
        }

        if (queue.any { it.risk == RiskLevel.DESTRUCTIVE || CommandCatalog.find(it.command) == null } && !config.dangerConfirmed) {
            errors += "Destructive or unknown raw operations were not confirmed."
        }
        return PreflightResult(errors.distinct(), warnings.distinct())
    }

    private fun isHex(value: String): Boolean {
        val normalized = value.removePrefix("0x").removePrefix("0X")
        return normalized.isNotBlank() && normalized.toULongOrNull(16)?.let { it <= 0xffff_ffffuL } == true
    }

    private fun isInputReference(value: String?): Boolean = when {
        value.isNullOrBlank() -> false
        value.startsWith("content://") -> true
        else -> File(value).let { it.isFile && it.canRead() }
    }

    private fun outputName(command: QueuedCommand): String? = when (command.command) {
        "read_part" -> command.tokens.getOrNull(4)
        "read_flash" -> command.tokens.getOrNull(4)
        "read_mem" -> command.tokens.getOrNull(3)
        "partition_list" -> command.tokens.getOrNull(1)
        else -> null
    }
}
