package io.github.lootdev78.spdflash

import java.io.File

object CommandValidator {
    private val hexPattern = Regex("^(?:0x)?[0-9a-fA-F]+$")
    private val outputPattern = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,179}$")
    private val partitionPattern = Regex("^[A-Za-z0-9_.-]{1,72}$")

    fun validate(
        spec: CommandSpec,
        rawArguments: List<String>,
        partitions: Map<String, Long> = emptyMap(),
        inputSizes: Map<String, Long> = emptyMap(),
    ): CommandValidation {
        val normalized = MutableList(spec.arguments.size) { index -> rawArguments.getOrNull(index)?.trim().orEmpty() }
        val errors = linkedMapOf<Int, String>()

        spec.arguments.forEachIndexed { index, argument ->
            val value = normalized[index]
            if (value.isBlank()) {
                if (!argument.optional) errors[index] = "Required"
                return@forEachIndexed
            }
            when (argument.type) {
                ArgumentType.HEX -> {
                    val parsed = parseUnsignedHex(value)
                    if (parsed == null) errors[index] = "Expected a hexadecimal value up to 0xFFFFFFFF"
                }
                ArgumentType.INTEGER -> {
                    val parsed = value.toLongOrNull()
                    if (parsed == null || parsed < 0) errors[index] = "Expected a non-negative integer"
                }
                ArgumentType.SIZE -> if (SizeParser.parse(value) == null) errors[index] = "Invalid size"
                ArgumentType.FILE -> {
                    if (!value.startsWith("content://")) {
                        val file = File(value)
                        if (!file.isFile || !file.canRead()) errors[index] = "File is not readable"
                    }
                }
                ArgumentType.DIRECTORY -> {
                    val file = File(value)
                    if (!file.isDirectory || !file.canRead()) errors[index] = "Directory is not readable"
                }
                ArgumentType.OUTPUT_FILE -> if (!outputPattern.matches(value) || value == "." || value == "..") {
                    errors[index] = "Expected a safe filename containing letters, digits, period, underscore, or hyphen"
                }
                ArgumentType.ENUM -> if (argument.options.none { it.equals(value, ignoreCase = true) }) {
                    errors[index] = "Erlaubt: ${argument.options.joinToString()}"
                }
                ArgumentType.PARTITION -> if (!partitionPattern.matches(value)) {
                    errors[index] = "Invalid partition name"
                }
                ArgumentType.TEXT -> Unit
            }
        }

        val expected = estimateBytes(spec.command, normalized, partitions, inputSizes)
        val warning = estimateWarning(spec.command, normalized, partitions, inputSizes)
        return CommandValidation(errors.isEmpty(), normalized, errors, warning, expected)
    }

    private fun parseUnsignedHex(value: String): ULong? {
        if (!hexPattern.matches(value)) return null
        val parsed = value.removePrefix("0x").removePrefix("0X").toULongOrNull(16) ?: return null
        return parsed.takeIf { it <= 0xffff_ffffuL }
    }

    private fun estimateBytes(
        command: String,
        args: List<String>,
        partitions: Map<String, Long>,
        inputSizes: Map<String, Long>,
    ): Long? = when (command) {
        "fdl", "send", "write_flash" -> inputLength(args.firstOrNull(), inputSizes)
        "loadfdl", "loadexec", "loadexec2" -> inputLength(args.firstOrNull(), inputSizes)
        "write_part", "w_force" -> inputLength(args.getOrNull(1), inputSizes)
        "wof" -> inputLength(args.getOrNull(2), inputSizes)
        "read_part" -> args.getOrNull(2)?.let(SizeParser::parse)
        "read_flash" -> args.getOrNull(2)?.let(SizeParser::parse)
        "read_mem" -> args.getOrNull(1)?.let(SizeParser::parse)
        "r" -> args.firstOrNull()?.let { partitions[it] }
        else -> null
    }

    private fun inputLength(value: String?, inputSizes: Map<String, Long>): Long? {
        if (value == null) return null
        inputSizes[value]?.takeIf { it >= 0 }?.let { return it }
        return value.takeUnless { it.startsWith("content://") }
            ?.let(::File)
            ?.takeIf(File::isFile)
            ?.length()
    }

    private fun estimateWarning(
        command: String,
        args: List<String>,
        partitions: Map<String, Long>,
        inputSizes: Map<String, Long>,
    ): String? {
        if (command !in setOf("write_part", "w_force", "wof")) return null
        val partition = args.getOrNull(0) ?: return null
        val imageIndex = if (command == "wof") 2 else 1
        val imagePath = args.getOrNull(imageIndex) ?: return null
        val imageSize = inputLength(imagePath, inputSizes)
            ?: return "Image size is not known yet; it will be checked again immediately before starting."
        val partitionSize = partitions[partition]
            ?: return "Partition size is not known yet; the image size cannot be checked in advance."
        val offset = if (command == "wof") args.getOrNull(1)?.let(SizeParser::parse) ?: 0L else 0L
        val end = runCatching { Math.addExact(offset, imageSize) }.getOrNull()
            ?: return "Offset plus image size exceeds the 64-bit range."
        return if (end > partitionSize) {
            "Target range (${SizeParser.human(end)}) is larger than the partition (${SizeParser.human(partitionSize)})."
        } else null
    }
}
