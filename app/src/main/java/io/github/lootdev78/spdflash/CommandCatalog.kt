package io.github.lootdev78.spdflash

object CommandCatalog {
    private val brom = setOf(DeviceStage.BROM)
    private val fdl1 = setOf(DeviceStage.FDL1)
    private val fdl2 = setOf(DeviceStage.FDL2)
    private val anyConnected = setOf(DeviceStage.BROM, DeviceStage.FDL1, DeviceStage.FDL2, DeviceStage.DOWNLOAD, DeviceStage.UNKNOWN)

    val specs: List<CommandSpec> = listOf(
        CommandSpec("fdl", "Send FDL/loader", "Sends a loader to a physical memory address. Depending on the current stage, the command switches to FDL1 or loads FDL2.", "Loader", listOf(file("Loader file"), hex("Load address", "e.g. 0x5500")), RiskLevel.CAUTION, brom + fdl1),
        CommandSpec("loadfdl", "FDL address from filename", "Reads the final 0x… address from the filename and loads the file.", "Loader", listOf(file("Loader file with address")), RiskLevel.CAUTION, brom + fdl1),
        CommandSpec("exec", "Execute loaded loader", "Executes FDL1 or FDL2. An incorrect loader or address can leave the device unresponsive until it is restarted.", "Loader", risk = RiskLevel.CAUTION, stages = brom + fdl1),
        CommandSpec("exec_addr", "Boot ROM exec address", "Sets the address of the custom_exec_no_verify file.", "Loader", listOf(hex("Exec address")), RiskLevel.DESTRUCTIVE, brom),
        CommandSpec("exec_addr2", "Boot ROM exec address V2", "Enables the second exec-address method.", "Loader", listOf(hex("Exec address")), RiskLevel.DESTRUCTIVE, brom),
        CommandSpec("loadexec", "Load exec file", "Derives the exec address from custom_exec_no_verify_<addr>.bin.", "Loader", listOf(file("Exec file")), RiskLevel.DESTRUCTIVE, brom),
        CommandSpec("loadexec2", "Load exec file V2", "Derives the exec address from the filename and enables method V2.", "Loader", listOf(file("Exec file")), RiskLevel.DESTRUCTIVE, brom),

        CommandSpec("r", "Read partition or backup", "Reads a partition, all, all_lite, preset_modem, or preset_resign.", "Read", listOf(partition("Partition / all / all_lite")), stages = fdl2),
        CommandSpec("read_part", "Read partition range", "Reads a partition offset and size into an output file. Sizes such as 16m and ubi40m are supported.", "Read", listOf(partition("Partition"), size("Offset"), size("Size"), output("Output file", "e.g. boot.img")), stages = fdl2),
        CommandSpec("read_parts", "Read partitions from list", "Reads all entries from a partition list file.", "Read", listOf(file("Partition list")), stages = fdl2),
        CommandSpec("read_flash", "Read raw flash", "Reads a raw flash range using address, offset, and size.", "Read", listOf(hex("Flash address"), size("Offset"), size("Size"), output("Output file")), stages = fdl2),
        CommandSpec("read_mem", "Read RAM", "Reads a memory range from BROM or FDL.", "Read", listOf(hex("Address"), size("Size"), output("Output file")), stages = anyConnected),
        CommandSpec("partition_list", "Save partition table", "Reads the partition table and writes an XML file.", "Read", listOf(output("XML output file", "partition.xml")), stages = fdl2),
        CommandSpec("print", "Show partition table", "Prints the known partition table in the live console.", "Read", stages = fdl2, aliases = setOf("p")),
        CommandSpec("size_part", "Read partition size", "Reads the size of a partition.", "Read", listOf(partition("Partition")), stages = fdl2, aliases = setOf("part_size")),
        CommandSpec("check_part", "Check partition", "Checks whether a partition exists.", "Read", listOf(partition("Partition")), stages = fdl2),
        CommandSpec("chip_uid", "Read chip UID", "Reads the unique chip ID if supported by the active FDL.", "Read", stages = anyConnected),
        CommandSpec("read_pactime", "Read PAC timestamp", "Reads the PAC timestamp.", "Read", stages = fdl2),

        CommandSpec("write_part", "Write partition", "Writes an image to a partition.", "Write", listOf(partition("Partition"), file("Image file")), RiskLevel.DESTRUCTIVE, fdl2, aliases = setOf("w")),
        CommandSpec("write_parts", "Write backup directory", "Writes all matching images from an imported directory.", "Write", listOf(directory("Image directory")), RiskLevel.DESTRUCTIVE, fdl2),
        CommandSpec("write_parts_a", "Write backup to slot A", "Writes a backup and forces slot A.", "Write", listOf(directory("Image directory")), RiskLevel.DESTRUCTIVE, fdl2),
        CommandSpec("write_parts_b", "Write backup to slot B", "Writes a backup and forces slot B.", "Write", listOf(directory("Image directory")), RiskLevel.DESTRUCTIVE, fdl2),
        CommandSpec("w_force", "Force-write partition", "Uses the alternative write method based on the partition table position.", "Write", listOf(partition("Partition"), file("Image file")), RiskLevel.DESTRUCTIVE, fdl2),
        CommandSpec("wof", "Write file at partition offset", "Writes a file starting at an offset within the partition.", "Write", listOf(partition("Partition"), size("Offset"), file("File")), RiskLevel.DESTRUCTIVE, fdl2),
        CommandSpec("wov", "Write value at partition offset", "Writes a 32-bit value starting at an offset.", "Write", listOf(partition("Partition"), size("Offset"), hex("32-bit value")), RiskLevel.DESTRUCTIVE, fdl2),
        CommandSpec("send", "Send file to RAM", "Sends a file without a trailing END_DATA.", "Write", listOf(file("File"), hex("Address")), RiskLevel.CAUTION, anyConnected),
        CommandSpec("write_flash", "Write raw flash", "Writes a file to a raw flash address.", "Write", listOf(file("File"), hex("Flash address")), RiskLevel.DESTRUCTIVE, fdl2),
        CommandSpec("write_word", "Write 32-bit value to RAM", "Writes a value directly to a memory address.", "Write", listOf(hex("Address"), hex("32-bit value")), RiskLevel.DESTRUCTIVE, anyConnected),

        CommandSpec("erase_part", "Erase partition", "Erases a partition or partition ID.", "Erase", listOf(partition("Partition / ID")), RiskLevel.DESTRUCTIVE, fdl2, aliases = setOf("e")),
        CommandSpec("erase_all", "Erase entire flash", "Erases all partitions. This is the most dangerous operation.", "Erase", risk = RiskLevel.DESTRUCTIVE, stages = fdl2),
        CommandSpec("erase_flash", "Erase raw flash range", "Erases a raw address range.", "Erase", listOf(hex("Flash address"), size("Size")), RiskLevel.DESTRUCTIVE, fdl2),
        CommandSpec("repartition", "Repartition", "Applies a Spreadtrum partition XML.", "Erase", listOf(file("Partition XML")), RiskLevel.DESTRUCTIVE, fdl2),

        CommandSpec("verity", "Change dm-verity", "0 disables and 1 enables dm-verity.", "System", listOf(enum("Status", "0", "1")), RiskLevel.DESTRUCTIVE, fdl2),
        CommandSpec("set_active", "Set active slot", "Sets the active VAB slot.", "System", listOf(enum("Slot", "a", "b")), RiskLevel.DESTRUCTIVE, fdl2),
        CommandSpec("slot", "Internal slot selection", "Sets the internal slot selection to 0, 1, or 2.", "System", listOf(enum("Slot", "0", "1", "2")), RiskLevel.CAUTION, fdl2),
        CommandSpec("firstmode", "Set first mode", "Writes the startup mode to miscdata.", "System", listOf(integer("Mode ID")), RiskLevel.DESTRUCTIVE, fdl2),
        CommandSpec("reset", "Normal reboot", "Sends BSL_CMD_NORMAL_RESET.", "System", risk = RiskLevel.CAUTION, stages = fdl1 + fdl2),
        CommandSpec("reboot-recovery", "Reboot to recovery", "Writes misc and reboots to recovery.", "System", risk = RiskLevel.DESTRUCTIVE, stages = fdl2),
        CommandSpec("reboot-fastboot", "Reboot to fastbootd", "Writes misc and reboots to fastbootd.", "System", risk = RiskLevel.DESTRUCTIVE, stages = fdl2),
        CommandSpec("poweroff", "Power off device", "Sends BSL_CMD_POWER_OFF.", "System", risk = RiskLevel.CAUTION, stages = fdl1 + fdl2),

        CommandSpec("path", "Native output path", "Sets the native output path. The app normally configures this automatically.", "Advanced", listOf(directory("Directory")), stages = anyConnected),
        CommandSpec("nand_id", "NAND-ID", "Sets the fourth NAND ID byte for UBI size calculations.", "Advanced", listOf(hex("NAND-ID")), stages = fdl2),
        CommandSpec("rawdata", "Rawdata level", "Sets the rawdata level to 0, 1, or 2.", "Advanced", listOf(enum("Level", "0", "1", "2")), RiskLevel.CAUTION, fdl2),
        CommandSpec("blk_size", "Transfer block size", "Sets the block size in bytes; upstream limits it to 0xf800.", "Advanced", listOf(size("Bytes")), stages = anyConnected, aliases = setOf("bs")),
        CommandSpec("fblk_size", "File block size", "Sets the file block size in MiB.", "Advanced", listOf(integer("MiB")), stages = fdl2, aliases = setOf("fbs")),
        CommandSpec("timeout", "USB-Timeout", "Sets the timeout for read and write operations.", "Advanced", listOf(integer("Milliseconds")), stages = anyConnected),
        CommandSpec("verbose", "Log level", "Sets the native log level from 0 to 2.", "Advanced", listOf(enum("Level", "0", "1", "2")), stages = anyConnected),
        CommandSpec("keep_charge", "KEEP_CHARGE", "Enables or disables charging while FDL is active.", "Advanced", listOf(enum("Status", "0", "1")), stages = fdl1 + fdl2),
        CommandSpec("transcode", "HDLC-Transcoding", "Enables or disables HDLC transcoding.", "Advanced", listOf(enum("Status", "0", "1")), RiskLevel.CAUTION, anyConnected),
        CommandSpec("disable_transcode", "Disable transcoding", "Sends BSL_CMD_DISABLE_TRANSCODE.", "Advanced", risk = RiskLevel.CAUTION, stages = fdl2),
        CommandSpec("end_data", "END_DATA behavior", "Controls whether BSL_CMD_END_DATA is sent.", "Advanced", listOf(enum("Status", "0", "1")), RiskLevel.CAUTION, anyConnected),
        CommandSpec("skip_confirm", "Upstream confirmations", "Controls the built-in CLI confirmations. The Android safety checks remain active.", "Advanced", listOf(enum("Status", "0", "1")), RiskLevel.CAUTION, anyConnected),
        CommandSpec("sendloop", "Debug send loop", "Continuously sends four bytes to descending addresses. Intended only for protocol development.", "Debug", listOf(hex("Start address")), RiskLevel.DESTRUCTIVE, anyConnected),
    ).sortedWith(compareBy<CommandSpec> { it.category }.thenBy { it.title })

    val categories: List<String> = listOf("All") + specs.map { it.category }.distinct()
    private val byName: Map<String, CommandSpec> = buildMap {
        specs.forEach { spec ->
            put(spec.command, spec)
            spec.aliases.forEach { put(it, spec) }
        }
    }

    fun find(command: String): CommandSpec? = byName[command.lowercase()]

    private fun file(label: String) = CommandArgument(label, ArgumentType.FILE)
    private fun directory(label: String) = CommandArgument(label, ArgumentType.DIRECTORY)
    private fun output(label: String, hint: String = "") = CommandArgument(label, ArgumentType.OUTPUT_FILE, hint)
    private fun partition(label: String) = CommandArgument(label, ArgumentType.PARTITION)
    private fun hex(label: String, hint: String = "0x…") = CommandArgument(label, ArgumentType.HEX, hint)
    private fun size(label: String) = CommandArgument(label, ArgumentType.SIZE, "e.g. 16m, 0x1000")
    private fun integer(label: String) = CommandArgument(label, ArgumentType.INTEGER)
    private fun enum(label: String, vararg values: String) = CommandArgument(label, ArgumentType.ENUM, options = values.toList())
}
