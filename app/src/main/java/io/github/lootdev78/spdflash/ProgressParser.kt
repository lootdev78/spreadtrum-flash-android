package io.github.lootdev78.spdflash

object ProgressParser {
    private val partitionLine = Regex("^\\s*\\d+\\s+([A-Za-z0-9_.-]+)\\s+(\\d+)MB\\s*$")
    private val unexpectedResponse = Regex("unexpected response \\((0x[0-9a-fA-F]+)\\)", RegexOption.IGNORE_CASE)

    data class Parsed(
        val stage: DeviceStage? = null,
        val partition: Pair<String, Long>? = null,
        val status: String? = null,
    )

    fun parse(line: String): Parsed {
        val stage = when {
            line.contains("EXEC FDL2", ignoreCase = true) || line.contains("FDL2 >", ignoreCase = true) -> DeviceStage.FDL2
            line.contains("CMD_CONNECT FDL1", ignoreCase = true) || line.contains("CHECK_BAUD FDL1", ignoreCase = true) -> DeviceStage.FDL1
            line.contains("CMD_CONNECT bootrom", ignoreCase = true) || line.contains("CHECK_BAUD bootrom", ignoreCase = true) -> DeviceStage.BROM
            line.contains("download", ignoreCase = true) && line.contains("1782", ignoreCase = true) -> DeviceStage.DOWNLOAD
            else -> null
        }
        val partition = partitionLine.matchEntire(line)?.let { match ->
            match.groupValues[1] to (match.groupValues[2].toLong() * 1024L * 1024L)
        }
        val status = when {
            line.contains("timeout reached", ignoreCase = true) -> "USB timeout"
            line.contains("device removed", ignoreCase = true) || line.contains("connection closed", ignoreCase = true) -> "USB device disconnected"
            unexpectedResponse.containsMatchIn(line) -> unexpectedResponse.find(line)?.groupValues?.getOrNull(1)
                ?.let(BslResponse::describeHex)?.let { "Device response: $it" } ?: "Unexpected device response"
            line.contains("Read Flash Done", ignoreCase = true) -> "Flash range read"
            line.contains("Read Mem Done", ignoreCase = true) -> "Memory range read"
            line.contains("SEND ", ignoreCase = true) -> "File transferred"
            else -> null
        }
        return Parsed(stage, partition, status)
    }
}
