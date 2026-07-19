package io.github.lootdev78.spdflash

import android.app.Application
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.net.Uri
import android.os.PowerManager
import android.provider.OpenableColumns
import android.system.Os
import android.system.OsConstants
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.min

class FlashViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as SpdFlashApplication
    private val coordinator = app.usbCoordinator
    private val store = app.queueStore
    private val outputRouter = app.safOutputRouter

    private var connection: UsbDeviceConnection? = null
    private var connectedDevice: UsbDevice? = null
    private val stateLock = Any()
    private val metadataLock = Any()
    private val inputMetadata = linkedMapOf<String, InputMetadata>()
    @Volatile private var reenumerating = false
    @Volatile private var runStartedAt: Long? = null

    val workspace: File = (application.getExternalFilesDir("workspace")
        ?: File(application.filesDir, "workspace")).apply { mkdirs() }
    private val importDir = (application.getExternalFilesDir("imports")
        ?: File(application.filesDir, "imports")).apply { mkdirs() }

    val devices: StateFlow<List<UsbDeviceItem>> = coordinator.devices

    private val _queue = MutableStateFlow(store.loadQueue())
    val queue: StateFlow<List<QueuedCommand>> = _queue.asStateFlow()

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    private val _partitions = MutableStateFlow<Map<String, Long>>(emptyMap())
    val partitions: StateFlow<Map<String, Long>> = _partitions.asStateFlow()

    private val _fdl1 = MutableStateFlow(loaderFromStored(store.fdl1Path, store.fdl1Address))
    val fdl1: StateFlow<LoaderSelection> = _fdl1.asStateFlow()

    private val _fdl2 = MutableStateFlow(loaderFromStored(store.fdl2Path, store.fdl2Address))
    val fdl2: StateFlow<LoaderSelection> = _fdl2.asStateFlow()

    private val _ui = MutableStateFlow(
        FlashUiState(
            outputTarget = outputRouter.label(),
            interruptedRunAvailable = store.interruptedRun,
        ),
    )
    val ui: StateFlow<FlashUiState> = _ui.asStateFlow()

    init {
        coordinator.refresh()
        appendLog("Native Engine: ${runCatching { NativeBridge.version() }.getOrElse { it.message ?: "not loaded" }}")
        appendLog("Workspace: ${workspace.absolutePath}")
        if (store.interruptedRun) appendLog("Notice: The previous operation did not finish normally. The queue was restored.")

        viewModelScope.launch {
            coordinator.detached.collect { device ->
                if (device.deviceId == connectedDevice?.deviceId) {
                    if (reenumerating) {
                        appendLog("USB device is re-enumerating for the next protocol mode…")
                    } else {
                        if (_ui.value.running) {
                            appendLog("USB device disconnected during the operation; controlled cancellation is being requested.")
                            NativeBridge.cancel()
                        }
                        closeConnection("USB device disconnected")
                    }
                }
            }
        }
        viewModelScope.launch {
            outputRouter.treeUri.collect {
                updateUi { it.copy(outputTarget = outputRouter.label()) }
            }
        }
        refreshLoaderMetadata()
    }

    fun refreshDevices() = coordinator.refresh()

    fun savedPipelineDraft(): PipelineDraft = store.loadPipelineDraft()
    fun hasOutputTree(): Boolean = outputRouter.hasUsableTree()
    fun inputSizesSnapshot(): Map<String, Long> = synchronized(metadataLock) {
        inputMetadata.mapValues { it.value.sizeBytes }
    }
    fun metadataFor(path: String): InputMetadata? = synchronized(metadataLock) { inputMetadata[path] }

    private fun rememberInput(file: ImportedFile) = synchronized(metadataLock) {
        inputMetadata[file.path] = InputMetadata(file.displayName, file.sizeBytes, file.sha256)
    }

    private inline fun updateUi(transform: (FlashUiState) -> FlashUiState) {
        synchronized(stateLock) { _ui.value = transform(_ui.value) }
    }

    suspend fun connect(device: UsbDevice): Result<Unit> = runCatching {
        val opened = withContext(Dispatchers.IO) { coordinator.open(device) }
        withContext(Dispatchers.Main) { adoptConnection(device, opened) }
    }

    fun disconnect() {
        if (_ui.value.running) NativeBridge.cancel()
        closeConnection("No device connected")
    }

    private fun adoptConnection(device: UsbDevice, opened: UsbDeviceConnection) {
        connection?.close()
        connection = opened
        connectedDevice = device
        val stage = if (device.productId == UsbCoordinator.DOWNLOAD_PRODUCT_ID) DeviceStage.DOWNLOAD else DeviceStage.DIAG
        val label = "%04x:%04x · %s".format(device.vendorId, device.productId, device.productName ?: device.deviceName)
        updateUi { it.copy(
            connectedLabel = label,
            connectedDeviceId = device.deviceId,
            connectedPid = device.productId,
            stage = stage,
            status = "Connected: $label",
        ) }
        appendLog("USB connected: $label, fd=${opened.fileDescriptor}")
    }

    private fun closeConnection(status: String) {
        connection?.close()
        connection = null
        connectedDevice = null
        updateUi { it.copy(
            connectedLabel = null,
            connectedDeviceId = null,
            connectedPid = null,
            stage = DeviceStage.DISCONNECTED,
            status = status,
        ) }
    }

    suspend fun importUri(uri: Uri): ImportedFile = withContext(Dispatchers.IO) {
        val application = getApplication<Application>()
        val resolver = application.contentResolver
        val displayName = UriFileName.resolve(application, uri)
        val persisted = runCatching {
            resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }.isSuccess
        val seekableSize = if (persisted) inspectSeekableDocument(uri) else null

        val imported = if (persisted && seekableSize != null) {
            val queriedSize = queryContentSize(uri)
            val size = queriedSize.takeIf { it > 0 } ?: seekableSize
            val hash = resolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "Could not open file" }
                HashUtils.sha256(input.buffered())
            }
            ImportedFile(uri.toString(), displayName, size, hash).also {
                appendLog("Persisted document access: ${it.displayName} (${SizeParser.human(it.sizeBytes)})")
            }
        } else {
            if (persisted) {
                runCatching { resolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                appendLog("The document provider is not seekable; the file will be mirrored locally for the native C core.")
            }
            val safeName = displayName.replace(Regex("[^A-Za-z0-9._-]"), "_")
            val target = File(importDir, "${UUID.randomUUID()}_$safeName")
            resolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "Could not open file" }
                FileOutputStream(target).use { output -> input.copyTo(output, 1024 * 1024) }
            }
            ImportedFile(target.absolutePath, displayName, target.length(), HashUtils.sha256(target)).also {
                appendLog("Imported locally: ${it.displayName} (${SizeParser.human(it.sizeBytes)})")
            }
        }
        rememberInput(imported)
        imported
    }

    suspend fun importTree(uri: Uri): String = withContext(Dispatchers.IO) {
        val root = DocumentFile.fromTreeUri(getApplication(), uri) ?: error("Could not open directory")
        val safeName = (root.name ?: "folder").replace(Regex("[^A-Za-z0-9._-]"), "_")
        val target = File(importDir, "${UUID.randomUUID()}_$safeName").apply { mkdirs() }
        copyDocumentTree(root, target)
        appendLog("Directory imported: ${target.absolutePath}")
        target.absolutePath
    }

    private fun copyDocumentTree(source: DocumentFile, target: File) {
        source.listFiles().forEach { child ->
            val safeName = (child.name ?: "unnamed").replace(Regex("[\\\\/:*?\"<>|]"), "_")
            val destination = File(target, safeName)
            if (child.isDirectory) {
                destination.mkdirs()
                copyDocumentTree(child, destination)
            } else if (child.isFile) {
                getApplication<Application>().contentResolver.openInputStream(child.uri).use { input ->
                    requireNotNull(input) { "Could not read file: ${child.name}" }
                    FileOutputStream(destination).use { output -> input.copyTo(output, 1024 * 1024) }
                }
            }
        }
    }

    fun setFdl1(file: ImportedFile) {
        _fdl1.value = LoaderSelection(file.path, file.displayName, _fdl1.value.address, file.sizeBytes, file.sha256)
        store.fdl1Path = file.path
    }

    fun setFdl2(file: ImportedFile) {
        _fdl2.value = LoaderSelection(file.path, file.displayName, _fdl2.value.address, file.sizeBytes, file.sha256)
        store.fdl2Path = file.path
    }

    fun setFdlAddresses(fdl1Address: String, fdl2Address: String) {
        _fdl1.value = _fdl1.value.copy(address = fdl1Address.trim())
        _fdl2.value = _fdl2.value.copy(address = fdl2Address.trim())
        store.fdl1Address = fdl1Address.trim()
        store.fdl2Address = fdl2Address.trim()
    }

    fun setOutputTree(uri: Uri): Result<Unit> = runCatching { outputRouter.setTree(uri) }
    fun clearOutputTree() = outputRouter.clear()

    fun addCommand(command: QueuedCommand) = updateQueue(_queue.value + command)
    fun removeCommand(command: QueuedCommand) = updateQueue(_queue.value.filterNot { it.id == command.id })
    fun duplicateCommand(command: QueuedCommand) {
        val index = _queue.value.indexOfFirst { it.id == command.id }
        if (index < 0) return
        val mutable = _queue.value.toMutableList()
        mutable.add(index + 1, command.copy(id = System.nanoTime(), title = "${command.title} (Kopie)"))
        updateQueue(mutable)
    }

    fun moveCommand(command: QueuedCommand, direction: Int) {
        val mutable = _queue.value.toMutableList()
        val from = mutable.indexOfFirst { it.id == command.id }
        val to = (from + direction).coerceIn(0, mutable.lastIndex)
        if (from < 0 || from == to) return
        val item = mutable.removeAt(from)
        mutable.add(to, item)
        updateQueue(mutable)
    }

    fun clearQueue() = updateQueue(emptyList())
    fun clearLog() { _logs.value = emptyList() }
    fun acknowledgeInterruptedRun() {
        store.interruptedRun = false
        updateUi { it.copy(interruptedRunAvailable = false) }
        appendLog("Recovery notice acknowledged; the queue remains available.")
    }

    private fun updateQueue(queue: List<QueuedCommand>) {
        _queue.value = queue
        store.saveQueue(queue)
    }

    fun runPipeline(config: PipelineConfig) {
        if (_ui.value.running) return
        store.savePipelineDraft(PipelineDraft.from(config))
        if (connection == null || connectedDevice == null) {
            appendLog("ERROR: No USB device connected")
            updateUi { it.copy(status = "No USB device connected") }
            return
        }
        val preflight = PipelinePreflight.check(
            config = config,
            queue = _queue.value,
            currentStage = _ui.value.stage,
            fdl1 = _fdl1.value,
            fdl2 = _fdl2.value,
            hasDirectOutputTree = outputRouter.hasUsableTree(),
        )
        preflight.warnings.forEach { appendLog("WARNING: $it") }
        if (!preflight.valid) {
            preflight.errors.forEach { appendLog("ERROR: $it") }
            updateUi { it.copy(status = preflight.errors.first()) }
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            val androidApp = getApplication<Application>()
            val power = androidApp.getSystemService(Context.POWER_SERVICE) as PowerManager
            val wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "spdflash:operation")
            var result = -999
            try {
                withContext(Dispatchers.Main) {
                    updateUi { it.copy(
                        running = true,
                        cancelling = false,
                        status = "Preparing USB connection…",
                        progress = TransferProgress(indeterminate = true),
                        currentCommand = "",
                        lastResult = null,
                    ) }
                }
                store.interruptedRun = true
                runStartedAt = System.currentTimeMillis()
                FlashKeepAliveService.start(androidApp)
                wakeLock.acquire(6 * 60 * 60 * 1000L)

                val activeConnection = prepareDownloadConnection(config)
                validateRuntimeQueue()
                val arguments = buildArguments(config)
                val expected = expectedBytes(config)
                checkStorageBeforeRun(expected, config)
                appendLog("--- START ---")
                appendLog("spd_dump ${arguments.joinToString(" ") { quoteForLog(it) }}")

                result = NativeBridge.run(
                    activeConnection.fileDescriptor,
                    arguments.toTypedArray(),
                    workspace.absolutePath,
                    config.dangerConfirmed,
                    expected,
                    config.directSafOutput && outputRouter.treeUri.value != null,
                    object : NativeCallbacks {
                        override fun onNativeLog(line: String) = handleNativeLog(line)

                        override fun onNativeProgress(currentBytes: Long, totalBytes: Long, bytesPerSecond: Long, label: String) {
                            val total = totalBytes.coerceAtLeast(0)
                            val current = if (total > 0) min(currentBytes, total) else currentBytes
                            val percent = if (total > 0) ((current * 100L) / total).toInt().coerceIn(0, 99) else 0
                            val eta = if (total > current && bytesPerSecond > 0) (total - current) / bytesPerSecond else null
                            updateUi { state -> state.copy(
                                currentCommand = label,
                                status = if (label.isBlank()) "Flash operation in progress…" else "Running: $label",
                                progress = TransferProgress(
                                    currentBytes = current,
                                    totalBytes = total,
                                    percent = percent,
                                    bytesPerSecond = bytesPerSecond,
                                    etaSeconds = eta,
                                    label = label,
                                    indeterminate = total <= 0,
                                ),
                            ) }
                            FlashKeepAliveService.update(androidApp, label, percent, total > 0)
                        }

                        override fun onNativeOpenOutput(relativePath: String, append: Boolean): Int =
                            if (config.directSafOutput) outputRouter.openOutputFd(relativePath, append) else -1

                        override fun onNativeOpenInput(uri: String): Int = runCatching {
                            getApplication<Application>().contentResolver
                                .openFileDescriptor(Uri.parse(uri), "r")
                                ?.detachFd() ?: -1
                        }.getOrElse {
                            appendLog("ERROR: Could not open document: ${it.message}")
                            -1
                        }
                    },
                )
            } catch (error: Throwable) {
                appendLog("ERROR: ${error.message ?: error.javaClass.simpleName}")
                result = -998
            } finally {
                FlashKeepAliveService.stop(androidApp)
                if (wakeLock.isHeld) wakeLock.release()
                val cancelled = result == -214 || _ui.value.cancelling
                val successful = result == 0
                if (successful || cancelled) store.interruptedRun = false
                appendLog("--- ENDE, Code $result: ${NativeResult.describe(result)} ---")
                if (!successful) {
                    connection?.close()
                    connection = null
                    connectedDevice = null
                }
                updateUi { state -> state.copy(
                    connectedLabel = if (successful) state.connectedLabel else null,
                    connectedDeviceId = if (successful) state.connectedDeviceId else null,
                    connectedPid = if (successful) state.connectedPid else null,
                    stage = if (successful) state.stage else DeviceStage.DISCONNECTED,
                    running = false,
                    cancelling = false,
                    currentCommand = "",
                    status = when {
                        successful -> "Operation completed"
                        cancelled -> "Operation cancelled safely · reconnect USB"
                        else -> "${NativeResult.describe(result)} · reconnect USB"
                    },
                    progress = if (successful) state.progress.copy(
                        currentBytes = state.progress.totalBytes,
                        percent = 100,
                        indeterminate = false,
                    ) else state.progress,
                    lastResult = result,
                    interruptedRunAvailable = store.interruptedRun,
                ) }
            }
        }
    }

    private suspend fun prepareDownloadConnection(config: PipelineConfig): UsbDeviceConnection {
        var currentDevice = connectedDevice ?: error("No USB device connected")
        var currentConnection = connection ?: error("USB connection is closed")
        check(currentConnection.fileDescriptor >= 0) { "Invalid USB file descriptor" }
        if (currentDevice.productId == UsbCoordinator.DOWNLOAD_PRODUCT_ID) return currentConnection
        check(config.kickMode != 0) {
            "The device is not on download port 1782:4d00. Enable Kick or place it in download mode manually."
        }

        check(coordinator.attachedSpreadtrumDevices().size == 1) {
            "Exactly one Spreadtrum/Unisoc device may be connected for automatic Kick."
        }
        repeat(4) { pass ->
            updateUi { it.copy(status = "Diag → Download, Schritt ${pass + 1} …", stage = DeviceStage.DIAG) }
            appendLog("Kick-Schritt ${pass + 1}: %04x:%04x".format(currentDevice.vendorId, currentDevice.productId))
            val outcome = UsbKickTransport.execute(
                currentConnection,
                currentDevice,
                config.kickMode,
                config.kickToMode,
                pass,
                config.waitSeconds.coerceAtLeast(1) * 1000,
                ::appendLog,
            )
            appendLog(outcome.message)
            if (!outcome.needsReenumeration && currentDevice.productId == UsbCoordinator.DOWNLOAD_PRODUCT_ID) return currentConnection

            val oldId = currentDevice.deviceId
            reenumerating = true
            try {
                currentConnection.close()
                connection = null
                delay(250)
                currentDevice = coordinator.awaitSpreadtrumDevice(
                    excludeDeviceId = oldId,
                    timeoutMs = config.waitSeconds.coerceAtLeast(1) * 1000L,
                    preferDownloadPort = true,
                )
                currentConnection = coordinator.open(currentDevice, config.waitSeconds.coerceAtLeast(1) * 1000L)
                withContext(Dispatchers.Main) { adoptConnection(currentDevice, currentConnection) }
            } finally {
                reenumerating = false
            }
            if (currentDevice.productId == UsbCoordinator.DOWNLOAD_PRODUCT_ID) return currentConnection
        }
        error("Download port 1782:4d00 was not detected after several mode changes")
    }

    private fun buildArguments(config: PipelineConfig): List<String> = buildList {
        addAll(listOf("--wait", config.waitSeconds.coerceAtLeast(1).toString()))
        addAll(listOf("--verbose", config.verbose.coerceIn(0, 2).toString()))
        if (config.reconnect) addAll(listOf("--stage", "1"))
        if (config.sync) add("--sync")
        if (config.useLoaders) {
            val loader1 = _fdl1.value
            val loader2 = _fdl2.value
            val one = loader1.path?.takeIf(::isReadableInput) ?: error("FDL1 is missing or document access has expired")
            val two = loader2.path?.takeIf(::isReadableInput) ?: error("FDL2 is missing or document access has expired")
            check(config.fdl1Address.matches(Regex("^(?:0x)?[0-9a-fA-F]+$"))) { "Invalid FDL1 address" }
            check(config.fdl2Address.matches(Regex("^(?:0x)?[0-9a-fA-F]+$"))) { "Invalid FDL2 address" }
            addAll(listOf("fdl", one, config.fdl1Address))
            addAll(listOf("fdl", two, config.fdl2Address))
            if (config.executeFdl2) add("exec")
        }
        addAll(listOf("path", workspace.absolutePath))
        _queue.value.forEach { addAll(it.tokens) }
        add("quit")
    }

    private fun validateRuntimeQueue() {
        val sizes = inputSizesSnapshot().toMutableMap()
        _queue.value.forEach { queued ->
            val spec = CommandCatalog.find(queued.command) ?: return@forEach
            spec.arguments.forEachIndexed { index, argument ->
                val value = queued.tokens.getOrNull(index + 1).orEmpty()
                if (argument.type == ArgumentType.FILE) {
                    check(isReadableInput(value)) { "${queued.title}: input file is no longer readable" }
                    inputSize(value)?.let { size ->
                        sizes[value] = size
                        synchronized(metadataLock) {
                            val existing = inputMetadata[value]
                            inputMetadata[value] = InputMetadata(existing?.displayName ?: value.substringAfterLast('/'), size, existing?.sha256.orEmpty())
                        }
                    }
                } else if (argument.type == ArgumentType.DIRECTORY) {
                    check(File(value).let { it.isDirectory && it.canRead() }) { "${queued.title}: input directory is no longer readable" }
                }
            }
            val validation = CommandValidator.validate(spec, queued.tokens.drop(1), _partitions.value, sizes)
            check(validation.valid) {
                "${queued.title}: ${validation.errors.values.joinToString(" · ")}"
            }
            check(validation.warning?.contains("larger", ignoreCase = true) != true) {
                "${queued.title}: ${validation.warning}"
            }
        }
    }

    private fun expectedBytes(config: PipelineConfig): Long {
        var total = 0L
        _queue.value.forEach { command ->
            val value = command.expectedBytes ?: run {
                val spec = CommandCatalog.find(command.command)
                val sizes = inputSizesSnapshot().toMutableMap()
                spec?.arguments?.forEachIndexed { index, argument ->
                    if (argument.type == ArgumentType.FILE) {
                        val path = command.tokens.getOrNull(index + 1)
                        if (path != null) inputSize(path)?.let { sizes[path] = it }
                    }
                }
                spec?.let { CommandValidator.validate(it, command.tokens.drop(1), _partitions.value, sizes).expectedBytes }
            }
            if (value != null && value > 0) total = runCatching { Math.addExact(total, value) }.getOrDefault(Long.MAX_VALUE)
        }
        if (config.useLoaders) {
            total = runCatching { Math.addExact(total, _fdl1.value.sizeBytes.coerceAtLeast(0)) }.getOrDefault(Long.MAX_VALUE)
            total = runCatching { Math.addExact(total, _fdl2.value.sizeBytes.coerceAtLeast(0)) }.getOrDefault(Long.MAX_VALUE)
        }
        return total.coerceAtLeast(0)
    }

    private fun inputSize(path: String): Long? {
        synchronized(metadataLock) { inputMetadata[path]?.sizeBytes?.takeIf { it >= 0 }?.let { return it } }
        return if (path.startsWith("content://")) {
            runCatching {
                val uri = Uri.parse(path)
                queryContentSize(uri).takeIf { it > 0 } ?: inspectSeekableDocument(uri)
            }.getOrNull()
        } else {
            File(path).takeIf(File::isFile)?.length()
        }
    }

    private fun checkStorageBeforeRun(expectedBytes: Long, config: PipelineConfig) {
        if (expectedBytes <= 0 || (config.directSafOutput && outputRouter.treeUri.value != null)) return
        val free = workspace.usableSpace
        check(expectedBytes < free * 9 / 10) {
            "Not enough free app storage: expected ${SizeParser.human(expectedBytes)}, available ${SizeParser.human(free)}. Select a direct SAF output directory."
        }
    }

    fun cancel() {
        if (_ui.value.running) {
            appendLog("Controlled cancellation requested…")
            updateUi { it.copy(cancelling = true, status = "Preparing cancellation…") }
            NativeBridge.cancel()
        }
    }

    private fun handleNativeLog(line: String) {
        appendLog(line)
        val parsed = ProgressParser.parse(line)
        parsed.partition?.let { (name, size) ->
            _partitions.value = _partitions.value + (name to size)
            updateUi { it.copy(partitionCount = _partitions.value.size) }
        }
        if (parsed.stage != null || parsed.status != null) {
            updateUi { state -> state.copy(
                stage = parsed.stage ?: state.stage,
                status = parsed.status ?: state.status,
            ) }
        }
    }

    @Synchronized
    private fun appendLog(line: String) {
        val normalized = line.trimEnd('\r', '\n')
        if (normalized.isEmpty()) return
        _logs.value = (_logs.value + normalized).takeLast(6000)
    }

    private fun refreshLoaderMetadata() {
        viewModelScope.launch(Dispatchers.IO) {
            listOf(true, false).forEach { first ->
                val current = if (first) _fdl1.value else _fdl2.value
                val path = current.path ?: return@forEach
                val updated = if (path.startsWith("content://")) {
                    val uri = Uri.parse(path)
                    val resolver = getApplication<Application>().contentResolver
                    runCatching {
                        current.copy(
                            displayName = UriFileName.resolve(getApplication(), uri),
                            sizeBytes = queryContentSize(uri).takeIf { it > 0 } ?: inspectSeekableDocument(uri) ?: 0,
                            sha256 = resolver.openInputStream(uri).use { input ->
                                requireNotNull(input)
                                HashUtils.sha256(input.buffered())
                            },
                        )
                    }.getOrElse {
                        appendLog("Persisted document access is no longer available: ${it.message}")
                        current.copy(displayName = "Dokumentzugriff abgelaufen", sizeBytes = 0, sha256 = "")
                    }
                } else {
                    val file = File(path).takeIf(File::isFile) ?: return@forEach
                    current.copy(
                        displayName = file.name.substringAfter('_', file.name),
                        sizeBytes = file.length(),
                        sha256 = runCatching { HashUtils.sha256(file) }.getOrDefault(""),
                    )
                }
                updated.path?.let { pathValue ->
                    synchronized(metadataLock) {
                        inputMetadata[pathValue] = InputMetadata(updated.displayName, updated.sizeBytes, updated.sha256)
                    }
                }
                if (first) _fdl1.value = updated else _fdl2.value = updated
            }
        }
    }

    private fun loaderFromStored(path: String?, address: String): LoaderSelection {
        if (path?.startsWith("content://") == true) {
            return LoaderSelection(path = path, displayName = "Gespeichertes Android-Dokument", address = address)
        }
        val file = path?.let(::File)?.takeIf(File::isFile)
        return LoaderSelection(
            path = file?.absolutePath,
            displayName = file?.name?.substringAfter('_', file.name) ?: "No file selected",
            address = address,
            sizeBytes = file?.length() ?: 0,
        )
    }

    private fun inspectSeekableDocument(uri: Uri): Long? = runCatching {
        getApplication<Application>().contentResolver.openFileDescriptor(uri, "r")?.use { parcel ->
            Os.lseek(parcel.fileDescriptor, 0L, OsConstants.SEEK_CUR)
            Os.fstat(parcel.fileDescriptor).st_size.coerceAtLeast(0L)
        }
    }.getOrNull()

    private fun queryContentSize(uri: Uri): Long {
        val resolver = getApplication<Application>().contentResolver
        resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (index >= 0 && !cursor.isNull(index)) return cursor.getLong(index).coerceAtLeast(0)
            }
        }
        return resolver.openAssetFileDescriptor(uri, "r")?.use { it.length.coerceAtLeast(0) } ?: 0
    }

    private fun isReadableInput(path: String): Boolean = if (path.startsWith("content://")) {
        runCatching {
            getApplication<Application>().contentResolver.openFileDescriptor(Uri.parse(path), "r")?.use { it.fd >= 0 } == true
        }.getOrDefault(false)
    } else {
        File(path).let { it.isFile && it.canRead() }
    }

    fun buildDiagnosticReport(): String {
        val state = _ui.value
        val queueJson = JSONArray().apply {
            _queue.value.forEach { command ->
                put(JSONObject().apply {
                    put("title", command.title)
                    put("risk", command.risk.name)
                    put("tokens", JSONArray(command.tokens))
                    put("expectedBytes", command.expectedBytes ?: JSONObject.NULL)
                })
            }
        }
        val partitionsJson = JSONObject().apply {
            _partitions.value.toSortedMap().forEach { (name, size) -> put(name, size) }
        }
        fun loaderJson(loader: LoaderSelection) = JSONObject().apply {
            put("displayName", loader.displayName)
            put("address", loader.address)
            put("sizeBytes", loader.sizeBytes)
            put("sha256", loader.sha256)
            put("reference", loader.path ?: JSONObject.NULL)
        }
        return JSONObject().apply {
            put("reportVersion", 1)
            put("createdAt", Instant.now().toString())
            put("runStartedAt", runStartedAt?.let { Instant.ofEpochMilli(it).toString() } ?: JSONObject.NULL)
            put("nativeEngine", runCatching { NativeBridge.version() }.getOrElse { it.message ?: "unavailable" })
            put("device", JSONObject().apply {
                put("label", state.connectedLabel ?: JSONObject.NULL)
                put("pid", state.connectedPid ?: JSONObject.NULL)
                put("stage", state.stage.name)
            })
            put("state", JSONObject().apply {
                put("status", state.status)
                put("lastResult", state.lastResult ?: JSONObject.NULL)
                put("interruptedRun", state.interruptedRunAvailable)
                put("outputTarget", state.outputTarget)
            })
            put("fdl1", loaderJson(_fdl1.value))
            put("fdl2", loaderJson(_fdl2.value))
            put("queue", queueJson)
            put("partitions", partitionsJson)
            put("logs", JSONArray(_logs.value))
        }.toString(2)
    }

    private fun quoteForLog(value: String): String =
        if (value.any(Char::isWhitespace)) "\"${value.replace("\"", "\\\"")}\"" else value

    override fun onCleared() {
        if (_ui.value.running) NativeBridge.cancel()
        connection?.close()
        FlashKeepAliveService.stop(getApplication())
        super.onCleared()
    }
}
