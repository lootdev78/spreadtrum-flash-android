package io.github.lootdev78.spdflash

import android.Manifest
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.content.IntentFilter
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.navigation.NavigationBarView
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class MainActivity : AppCompatActivity() {
    private val viewModel: FlashViewModel by viewModels()

    private lateinit var deviceSpinner: Spinner
    private lateinit var connectButton: MaterialButton
    private lateinit var verboseSpinner: Spinner
    private lateinit var kickModeSpinner: Spinner
    private lateinit var commandView: MaterialAutoCompleteTextView
    private lateinit var logText: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var globalStatus: TextView
    private lateinit var globalDetail: TextView
    private lateinit var globalProgress: LinearProgressIndicator
    private lateinit var progressText: TextView
    private lateinit var stageChip: Chip
    private lateinit var statusDot: View
    private lateinit var runButton: MaterialButton
    private lateinit var cancelButton: MaterialButton
    private lateinit var queueAdapter: CommandQueueAdapter
    private lateinit var navigationView: NavigationBarView

    private val argumentRows by lazy {
        listOf<View>(findViewById(R.id.arg1Row), findViewById(R.id.arg2Row), findViewById(R.id.arg3Row), findViewById(R.id.arg4Row))
    }
    private val argumentLayouts by lazy {
        listOf<TextInputLayout>(findViewById(R.id.arg1Layout), findViewById(R.id.arg2Layout), findViewById(R.id.arg3Layout), findViewById(R.id.arg4Layout))
    }
    private val argumentInputs by lazy {
        listOf<MaterialAutoCompleteTextView>(findViewById(R.id.arg1Input), findViewById(R.id.arg2Input), findViewById(R.id.arg3Input), findViewById(R.id.arg4Input))
    }
    private val argumentButtons by lazy {
        listOf<MaterialButton>(findViewById(R.id.importArg1Button), findViewById(R.id.importArg2Button), findViewById(R.id.importArg3Button), findViewById(R.id.importArg4Button))
    }

    private var selectedSpec: CommandSpec? = null
    private var selectedCategory: String = "All"
    private var visibleSpecs: List<CommandSpec> = CommandCatalog.specs
    private var importTarget: ImportTarget? = null

    private val openDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val target = importTarget ?: return@registerForActivityResult
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch {
            setBusyMessage("Importing and hashing file…")
            runCatching { viewModel.importUri(uri) }
                .onSuccess { imported ->
                    when (target) {
                        ImportTarget.Fdl1 -> viewModel.setFdl1(imported)
                        ImportTarget.Fdl2 -> viewModel.setFdl2(imported)
                        is ImportTarget.ArgumentFile -> {
                            argumentInputs[target.index].setText(imported.path)
                            updateCommandForm()
                        }
                        else -> Unit
                    }
                }
                .onFailure { showError(it.message ?: "Import failed") }
            restoreStatus()
        }
    }

    private val openTree = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val target = importTarget ?: return@registerForActivityResult
        if (uri == null) return@registerForActivityResult
        when (target) {
            ImportTarget.OutputFolder -> {
                viewModel.setOutputTree(uri)
                    .onSuccess {
                        findViewById<MaterialCheckBox>(R.id.directSafCheck).isChecked = true
                        toast("Direct output directory saved")
                    }
                    .onFailure { showError(it.message ?: "Directory access failed") }
            }
            is ImportTarget.ArgumentDirectory -> lifecycleScope.launch {
                setBusyMessage("Importing directory for native input…")
                runCatching { viewModel.importTree(uri) }
                    .onSuccess {
                        argumentInputs[target.index].setText(it)
                        updateCommandForm()
                    }
                    .onFailure { showError(it.message ?: "Directory import failed") }
                restoreStatus()
            }
            else -> Unit
        }
    }

    private val createLogDocument = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                contentResolver.openOutputStream(uri, "wt").use { output ->
                    requireNotNull(output) { "Could not open destination file" }
                    output.bufferedWriter().use { writer -> viewModel.logs.value.forEach { writer.appendLine(it) } }
                }
            }.exceptionOrNull()?.let { error ->
                withContext(Dispatchers.Main) { showError(error.message ?: "Export failed") }
            }
        }
    }

    private val createDiagnosticDocument = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                contentResolver.openOutputStream(uri, "wt").use { output ->
                    requireNotNull(output) { "Could not open destination file" }
                    output.bufferedWriter().use { it.write(viewModel.buildDiagnosticReport()) }
                }
            }.exceptionOrNull()?.let { error ->
                withContext(Dispatchers.Main) { showError(error.message ?: "Diagnostic export failed") }
            }
        }
    }

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bindViews()
        setupNavigation()
        setupDeviceControls()
        setupLoaderControls()
        setupCommandLibrary()
        setupQueue()
        setupConsole()
        setupOutputControls()
        restorePipelineDraft()
        observeState()
        requestNotificationPermissionIfNeeded()
        findViewById<TextView>(R.id.workspacePath).text = viewModel.workspace.absolutePath
        handleUsbIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleUsbIntent(intent)
    }

    private fun bindViews() {
        deviceSpinner = findViewById(R.id.deviceSpinner)
        connectButton = findViewById(R.id.connectButton)
        verboseSpinner = findViewById(R.id.verboseSpinner)
        kickModeSpinner = findViewById(R.id.kickModeSpinner)
        commandView = findViewById(R.id.commandSpinner)
        logText = findViewById(R.id.logText)
        logScroll = findViewById(R.id.logScroll)
        globalStatus = findViewById(R.id.globalStatus)
        globalDetail = findViewById(R.id.globalDetail)
        globalProgress = findViewById(R.id.globalProgress)
        progressText = findViewById(R.id.progressText)
        stageChip = findViewById(R.id.stageChip)
        statusDot = findViewById(R.id.statusDot)
        runButton = findViewById(R.id.runButton)
        cancelButton = findViewById(R.id.cancelButton)
        navigationView = findViewById(R.id.bottomNavigation)
    }

    private fun setupNavigation() {
        val screens = mapOf(
            R.id.nav_dashboard to findViewById<View>(R.id.screenDashboard),
            R.id.nav_loaders to findViewById<View>(R.id.screenLoaders),
            R.id.nav_commands to findViewById<View>(R.id.screenCommands),
            R.id.nav_queue to findViewById<View>(R.id.screenQueue),
            R.id.nav_console to findViewById<View>(R.id.screenConsole),
        )
        navigationView.apply {
            setOnItemSelectedListener { item ->
                screens.forEach { (id, view) -> view.isVisible = id == item.itemId }
                true
            }
            selectedItemId = R.id.nav_dashboard
        }
    }

    private fun setupDeviceControls() {
        verboseSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            listOf("0 · Status", "1 · Verbose", "2 · USB-Hexdump"),
        )
        verboseSpinner.setSelection(1)

        kickModeSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            listOf("Manual / already 1782:4d00", "Automatic: --kick", "Custom: --kickto"),
        )
        kickModeSpinner.onItemSelectedListener = SimpleItemSelectedListener {
            findViewById<TextInputLayout>(R.id.kickToLayout).isVisible = kickModeSpinner.selectedItemPosition == 2
        }

        findViewById<MaterialButton>(R.id.refreshDevicesButton).setOnClickListener { viewModel.refreshDevices() }
        connectButton.setOnClickListener {
            if (viewModel.ui.value.connectedLabel != null) {
                viewModel.disconnect()
            } else {
                val device = selectedDevice() ?: return@setOnClickListener showError("No Spreadtrum/Unisoc device selected")
                lifecycleScope.launch {
                    connectButton.isEnabled = false
                    setBusyMessage("Requesting USB permission…")
                    viewModel.connect(device)
                        .onFailure { showError(it.message ?: "USB connection failed") }
                    connectButton.isEnabled = true
                    restoreStatus()
                }
            }
        }
    }

    private fun setupLoaderControls() {
        findViewById<MaterialButton>(R.id.selectFdl1Button).setOnClickListener {
            importTarget = ImportTarget.Fdl1
            openDocument.launch(arrayOf("application/octet-stream", "*/*"))
        }
        findViewById<MaterialButton>(R.id.selectFdl2Button).setOnClickListener {
            importTarget = ImportTarget.Fdl2
            openDocument.launch(arrayOf("application/octet-stream", "*/*"))
        }
    }

    private fun setupCommandLibrary() {
        val categoryGroup = findViewById<ChipGroup>(R.id.categoryChipGroup)
        CommandCatalog.categories.forEachIndexed { index, category ->
            val chip = Chip(this).apply {
                id = View.generateViewId()
                text = category
                isCheckable = true
                isChecked = index == 0
                setOnClickListener {
                    selectedCategory = category
                    filterCommands()
                }
            }
            categoryGroup.addView(chip)
        }

        findViewById<TextInputEditText>(R.id.commandSearchInput).addTextChangedListener(textWatcher { filterCommands() })
        commandView.setOnItemClickListener { parent, _, position, _ ->
            selectedSpec = parent.getItemAtPosition(position) as? CommandSpec
            argumentInputs.forEach { it.text?.clear() }
            updateCommandForm()
        }

        argumentInputs.forEach { input ->
            input.addTextChangedListener(textWatcher { validateCurrentForm(showErrors = false) })
        }

        argumentButtons.forEachIndexed { index, button ->
            button.setOnClickListener {
                val argument = selectedSpec?.arguments?.getOrNull(index) ?: return@setOnClickListener
                when (argument.type) {
                    ArgumentType.FILE -> {
                        importTarget = ImportTarget.ArgumentFile(index)
                        openDocument.launch(arrayOf("*/*"))
                    }
                    ArgumentType.DIRECTORY -> {
                        importTarget = ImportTarget.ArgumentDirectory(index)
                        openTree.launch(null)
                    }
                    else -> Unit
                }
            }
        }

        findViewById<MaterialButton>(R.id.addCommandButton).setOnClickListener { addSelectedCommand() }
        findViewById<MaterialButton>(R.id.addRawCommandButton).setOnClickListener { addRawCommand() }
        filterCommands()
    }

    private fun filterCommands() {
        val query = findViewById<TextInputEditText>(R.id.commandSearchInput).text?.toString()?.trim().orEmpty()
        visibleSpecs = CommandCatalog.specs.filter { spec ->
            (selectedCategory == "All" || spec.category == selectedCategory) &&
                (query.isBlank() || spec.title.contains(query, true) || spec.command.contains(query, true) || spec.description.contains(query, true))
        }
        commandView.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, visibleSpecs))
        val previous = selectedSpec
        val next = previous?.takeIf { it in visibleSpecs } ?: visibleSpecs.firstOrNull()
        if (next != previous) argumentInputs.forEach { it.text?.clear() }
        selectedSpec = next
        commandView.setText(next?.toString().orEmpty(), false)
        updateCommandForm()
    }

    private fun updateCommandForm() {
        val spec = selectedSpec
        findViewById<MaterialButton>(R.id.addCommandButton).isEnabled = spec != null
        if (spec == null) {
            findViewById<TextView>(R.id.commandDescription).text = "No matching operation found"
            argumentRows.forEach { it.isVisible = false }
            return
        }
        findViewById<TextView>(R.id.commandDescription).text = spec.description
        findViewById<TextView>(R.id.commandStageText).text = if (spec.stages.isEmpty()) {
            "Stage: any"
        } else {
            "Stage: ${spec.stages.joinToString { it.label }}"
        }
        val riskChip = findViewById<Chip>(R.id.commandRiskChip)
        riskChip.text = spec.risk.label
        val riskColor = riskColor(spec.risk)
        riskChip.chipBackgroundColor = ColorStateList.valueOf(riskColor.copyAlpha(0.18f))
        riskChip.setTextColor(riskColor)

        argumentRows.forEachIndexed { index, row ->
            val argument = spec.arguments.getOrNull(index)
            row.isVisible = argument != null
            if (argument == null) {
                argumentInputs[index].text?.clear()
                return@forEachIndexed
            }
            val layout = argumentLayouts[index]
            val input = argumentInputs[index]
            val button = argumentButtons[index]
            layout.hint = argument.label
            val metadata = viewModel.metadataFor(input.text?.toString().orEmpty())
            layout.helperText = when {
                metadata != null -> buildString {
                    append(metadata.displayName)
                    append(" · ${SizeParser.human(metadata.sizeBytes)}")
                    if (metadata.sha256.isNotBlank()) append(" · SHA-256 ${metadata.sha256.take(16)}…")
                }
                argument.options.isNotEmpty() -> "Erlaubt: ${argument.options.joinToString()}"
                argument.hint.isNotBlank() -> argument.hint
                else -> null
            }
            layout.error = null
            input.inputType = when (argument.type) {
                ArgumentType.INTEGER -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
                else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            }
            val suggestions = when (argument.type) {
                ArgumentType.ENUM -> argument.options
                ArgumentType.PARTITION -> (viewModel.partitions.value.keys + listOf("all", "all_lite", "preset_modem", "preset_resign")).distinct().sorted()
                else -> emptyList()
            }
            input.setAdapter(
                if (suggestions.isEmpty()) null
                else ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, suggestions),
            )
            input.threshold = 0
            input.setOnClickListener { if (suggestions.isNotEmpty()) input.showDropDown() }
            button.isVisible = argument.type == ArgumentType.FILE || argument.type == ArgumentType.DIRECTORY
            button.setIconResource(if (argument.type == ArgumentType.DIRECTORY) R.drawable.ic_folder else R.drawable.ic_file)
        }
        validateCurrentForm(showErrors = false)
    }

    private fun validateCurrentForm(showErrors: Boolean): CommandValidation? {
        val spec = selectedSpec ?: return null
        val raw = spec.arguments.indices.map { argumentInputs[it].text?.toString().orEmpty() }
        val validation = CommandValidator.validate(spec, raw, viewModel.partitions.value, viewModel.inputSizesSnapshot())
        argumentLayouts.forEachIndexed { index, layout -> layout.error = if (showErrors) validation.errors[index] else null }
        findViewById<TextView>(R.id.commandValidationWarning).apply {
            text = validation.warning.orEmpty()
            isVisible = !validation.warning.isNullOrBlank()
        }
        return validation
    }

    private fun addSelectedCommand() {
        val spec = selectedSpec ?: return
        val validation = validateCurrentForm(showErrors = true) ?: return
        if (!validation.valid) return
        if (validation.warning?.contains("larger", ignoreCase = true) == true) {
            showError(validation.warning ?: "The image is larger than the target partition")
            return
        }
        viewModel.addCommand(
            QueuedCommand(
                title = spec.title,
                tokens = listOf(spec.command) + validation.normalizedArguments,
                risk = spec.risk,
                expectedBytes = validation.expectedBytes,
            ),
        )
        argumentInputs.take(spec.arguments.size).forEach { it.text?.clear() }
        toast("${spec.title} added")
        navigationView.selectedItemId = R.id.nav_queue
    }

    private fun addRawCommand() {
        val input = findViewById<TextInputEditText>(R.id.rawCommandInput)
        runCatching { CommandLineParser.parse(input.text?.toString().orEmpty()) }
            .onSuccess { tokens ->
                if (tokens.isEmpty()) return@onSuccess showError("Raw command is empty")
                val spec = CommandCatalog.find(tokens.first())
                val validation = spec?.let {
                    CommandValidator.validate(it, tokens.drop(1), viewModel.partitions.value, viewModel.inputSizesSnapshot())
                }
                if (validation != null && !validation.valid) {
                    showError(validation.errors.values.joinToString(" · "))
                    return@onSuccess
                }
                val risk = spec?.risk ?: RiskLevel.DESTRUCTIVE
                viewModel.addCommand(
                    QueuedCommand(
                        title = spec?.title ?: "Raw command: ${tokens.first()}",
                        tokens = tokens,
                        risk = risk,
                        expectedBytes = validation?.expectedBytes,
                    ),
                )
                input.text?.clear()
                navigationView.selectedItemId = R.id.nav_queue
            }
            .onFailure { showError(it.message ?: "Could not parse command") }
    }

    private fun setupQueue() {
        queueAdapter = CommandQueueAdapter(
            onRemove = viewModel::removeCommand,
            onMove = viewModel::moveCommand,
            onDuplicate = viewModel::duplicateCommand,
        )
        findViewById<RecyclerView>(R.id.queueRecycler).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = queueAdapter
        }
        findViewById<MaterialButton>(R.id.clearQueueButton).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Clear queue?")
                .setMessage("The persisted operation list will be removed.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Clear") { _, _ -> viewModel.clearQueue() }
                .show()
        }
        findViewById<MaterialButton>(R.id.acknowledgeInterruptedButton).setOnClickListener {
            viewModel.acknowledgeInterruptedRun()
        }
        runButton.setOnClickListener { beginRun() }
        cancelButton.setOnClickListener { viewModel.cancel() }
    }

    private fun beginRun() {
        viewModel.setFdlAddresses(
            findViewById<TextInputEditText>(R.id.fdl1AddressInput).text?.toString().orEmpty(),
            findViewById<TextInputEditText>(R.id.fdl2AddressInput).text?.toString().orEmpty(),
        )
        val risky = viewModel.queue.value.filter { it.dangerous }
        if (risky.isNotEmpty()) showDangerConfirmation(risky) { runPipeline(true) } else runPipeline(false)
    }

    private fun runPipeline(dangerConfirmed: Boolean) {
        val wait = findViewById<TextInputEditText>(R.id.waitSecondsInput).text?.toString()?.toIntOrNull() ?: 30
        val config = PipelineConfig(
            waitSeconds = wait.coerceIn(1, 3600),
            verbose = verboseSpinner.selectedItemPosition.coerceIn(0, 2),
            reconnect = findViewById<MaterialCheckBox>(R.id.reconnectCheck).isChecked,
            sync = findViewById<MaterialCheckBox>(R.id.syncCheck).isChecked,
            kickMode = kickModeSpinner.selectedItemPosition,
            kickToMode = findViewById<TextInputEditText>(R.id.kickToInput).text?.toString()?.toIntOrNull() ?: 2,
            useLoaders = findViewById<MaterialCheckBox>(R.id.useLoadersCheck).isChecked,
            fdl1Address = findViewById<TextInputEditText>(R.id.fdl1AddressInput).text?.toString()?.trim().orEmpty(),
            fdl2Address = findViewById<TextInputEditText>(R.id.fdl2AddressInput).text?.toString()?.trim().orEmpty(),
            executeFdl2 = findViewById<MaterialCheckBox>(R.id.executeFdl2Check).isChecked,
            dangerConfirmed = dangerConfirmed,
            directSafOutput = findViewById<MaterialCheckBox>(R.id.directSafCheck).isChecked,
        )
        navigationView.selectedItemId = R.id.nav_console
        viewModel.runPipeline(config)
    }

    private fun showDangerConfirmation(commands: List<QueuedCommand>, onConfirmed: () -> Unit) {
        val pid = viewModel.ui.value.connectedPid
        val confirmation = if (pid != null) "FLASH %04X".format(pid) else "FLASH"
        val input = EditText(this).apply {
            hint = confirmation
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
        }
        val ownership = CheckBox(this).apply {
            text = "I am authorized to service this device and have an appropriate backup."
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 4, 48, 0)
            addView(TextView(this@MainActivity).apply {
                text = commands.joinToString("\n") { "• ${it.title}" }
            })
            addView(ownership)
            addView(input)
        }
        val battery = batteryStatus()
        val batteryNote = when {
            battery.percent < 0 -> "Battery level could not be determined."
            battery.percent < 25 && !battery.charging -> "WARNING: Battery is only ${battery.percent}% and the device is not charging."
            else -> "Battery ${battery.percent}%${if (battery.charging) " · charging" else ""}."
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Confirm destructive pipeline")
            .setMessage("The following operations can erase data or make the device unbootable. $batteryNote Type $confirmation.")
            .setView(container)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Run", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                when {
                    !ownership.isChecked -> ownership.error = "Confirmation required"
                    input.text.toString() != confirmation -> input.error = "Enter exactly $confirmation"
                    else -> {
                        dialog.dismiss()
                        onConfirmed()
                    }
                }
            }
        }
        dialog.show()
    }

    private fun setupConsole() {
        findViewById<MaterialButton>(R.id.clearLogButton).setOnClickListener { viewModel.clearLog() }
        findViewById<MaterialButton>(R.id.copyLogButton).setOnClickListener {
            val clipboard = getSystemService(ClipboardManager::class.java)
            clipboard.setPrimaryClip(ClipData.newPlainText("SPRD Flash Log", viewModel.logs.value.joinToString("\n")))
            toast("Log copied")
        }
        findViewById<MaterialButton>(R.id.exportLogButton).setOnClickListener {
            createLogDocument.launch("spreadtrum_flash_${System.currentTimeMillis()}.log")
        }
        findViewById<MaterialButton>(R.id.exportDiagnosticButton).setOnClickListener {
            createDiagnosticDocument.launch("spreadtrum_diagnostic_${System.currentTimeMillis()}.json")
        }
    }

    private fun setupOutputControls() {
        findViewById<MaterialButton>(R.id.selectOutputFolderButton).setOnClickListener {
            importTarget = ImportTarget.OutputFolder
            openTree.launch(null)
        }
        findViewById<MaterialButton>(R.id.clearOutputFolderButton).setOnClickListener {
            viewModel.clearOutputTree()
            findViewById<MaterialCheckBox>(R.id.directSafCheck).isChecked = false
        }
        findViewById<MaterialButton>(R.id.shareWorkspaceButton).setOnClickListener { shareWorkspaceZip() }
    }

    private fun restorePipelineDraft() {
        val draft = viewModel.savedPipelineDraft()
        findViewById<TextInputEditText>(R.id.waitSecondsInput).setText(draft.waitSeconds.toString())
        verboseSpinner.setSelection(draft.verbose.coerceIn(0, 2))
        findViewById<MaterialCheckBox>(R.id.reconnectCheck).isChecked = draft.reconnect
        findViewById<MaterialCheckBox>(R.id.syncCheck).isChecked = draft.sync
        kickModeSpinner.setSelection(draft.kickMode.coerceIn(0, 2))
        findViewById<TextInputEditText>(R.id.kickToInput).setText(draft.kickToMode.toString())
        findViewById<MaterialCheckBox>(R.id.useLoadersCheck).isChecked = draft.useLoaders
        findViewById<MaterialCheckBox>(R.id.executeFdl2Check).isChecked = draft.executeFdl2
        findViewById<MaterialCheckBox>(R.id.directSafCheck).isChecked = draft.directSafOutput && viewModel.hasOutputTree()
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.devices.collect { devices ->
                        val oldId = selectedDevice()?.deviceId
                        deviceSpinner.adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, devices)
                        val restore = devices.indexOfFirst { it.device.deviceId == oldId }
                        if (restore >= 0) deviceSpinner.setSelection(restore)
                        findViewById<TextView>(R.id.deviceDetails).text = when {
                            devices.isEmpty() -> "No device with vendor ID 1782 detected"
                            else -> "${devices.size} device(s) detected · download port preferred"
                        }
                    }
                }
                launch {
                    viewModel.queue.collect { queue ->
                        queueAdapter.submitList(queue)
                        val risky = queue.count { it.dangerous }
                        findViewById<TextView>(R.id.queueSummary).text = if (queue.isEmpty()) {
                            "No operations yet"
                        } else {
                            "${queue.size} operations · $risky require safety confirmation"
                        }
                        val known = queue.mapNotNull { it.expectedBytes }.sum()
                        findViewById<TextView>(R.id.estimatedTransferText).text = if (known > 0) {
                            "Estimated payload: ${SizeParser.human(known)}"
                        } else "Estimated payload: dynamic / unknown"
                    }
                }
                launch {
                    viewModel.logs.collect { lines ->
                        logText.text = lines.joinToString("\n")
                        logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
                    }
                }
                launch { viewModel.fdl1.collect { renderLoader(it, first = true) } }
                launch { viewModel.fdl2.collect { renderLoader(it, first = false) } }
                launch { viewModel.partitions.collect { updateCommandForm() } }
                launch {
                    viewModel.ui.collect { state -> renderUiState(state) }
                }
            }
        }
    }

    private fun renderLoader(loader: LoaderSelection, first: Boolean) {
        findViewById<TextView>(if (first) R.id.fdl1Path else R.id.fdl2Path).text = loader.displayName
        findViewById<TextView>(if (first) R.id.fdl1Metadata else R.id.fdl2Metadata).text =
            if (loader.sizeBytes > 0) "${SizeParser.human(loader.sizeBytes)} · ready" else "No file selected"
        findViewById<TextView>(if (first) R.id.fdl1Hash else R.id.fdl2Hash).text =
            if (loader.sha256.isNotBlank()) "SHA-256 ${loader.sha256}" else ""
        val addressInput = findViewById<TextInputEditText>(if (first) R.id.fdl1AddressInput else R.id.fdl2AddressInput)
        if (!addressInput.hasFocus() && addressInput.text?.toString() != loader.address) addressInput.setText(loader.address)
    }

    private fun renderUiState(state: FlashUiState) {
        globalStatus.text = state.status
        globalDetail.text = buildString {
            append(state.connectedLabel ?: "Select a USB device, then connect")
            if (state.currentCommand.isNotBlank()) append(" · ${state.currentCommand}")
            if (state.partitionCount > 0) append(" · ${state.partitionCount} partitions")
        }
        stageChip.text = state.stage.label
        findViewById<Chip>(R.id.consoleStageChip).text = state.stage.label
        findViewById<TextView>(R.id.consoleSubtitle).text = state.status
        statusDot.setBackgroundResource(if (state.connectedLabel != null) R.drawable.status_dot_on else R.drawable.status_dot_off)
        connectButton.text = if (state.connectedLabel != null) "Disconnect" else "Connect"
        runButton.isEnabled = !state.running && state.connectedLabel != null
        cancelButton.isEnabled = state.running && !state.cancelling
        findViewById<View>(R.id.interruptedRunCard).isVisible = state.interruptedRunAvailable
        findViewById<TextView>(R.id.outputTargetText).text = state.outputTarget
        renderStageChips(state.stage)
        if (state.running) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        globalProgress.isVisible = state.running || state.progress.percent == 100
        progressText.isVisible = globalProgress.isVisible
        globalProgress.isIndeterminate = state.running && state.progress.indeterminate
        if (!globalProgress.isIndeterminate) globalProgress.setProgressCompat(state.progress.percent, true)
        progressText.text = if (state.progress.indeterminate) {
            listOfNotNull(
                state.currentCommand.takeIf(String::isNotBlank),
                state.progress.bytesPerSecond.takeIf { it > 0 }?.let { "${SizeParser.human(it)}/s" },
            ).joinToString(" · ").ifBlank { "Transfer in progress" }
        } else {
            buildString {
                append("${state.progress.percent}%")
                if (state.progress.totalBytes > 0) append(" · ${SizeParser.human(state.progress.currentBytes)} / ${SizeParser.human(state.progress.totalBytes)}")
                if (state.progress.bytesPerSecond > 0) append(" · ${SizeParser.human(state.progress.bytesPerSecond)}/s")
                state.progress.etaSeconds?.let { append(" · ETA ${formatDuration(it)}") }
            }
        }
    }

    private fun renderStageChips(stage: DeviceStage) {
        val brom = findViewById<Chip>(R.id.stageBromChip)
        val fdl1 = findViewById<Chip>(R.id.stageFdl1Chip)
        val fdl2 = findViewById<Chip>(R.id.stageFdl2Chip)
        listOf(brom, fdl1, fdl2).forEach { it.isCheckable = true; it.isClickable = false }
        brom.isChecked = stage == DeviceStage.BROM
        fdl1.isChecked = stage == DeviceStage.FDL1
        fdl2.isChecked = stage == DeviceStage.FDL2
    }

    private fun selectedDevice(): UsbDevice? = (deviceSpinner.selectedItem as? UsbDeviceItem)?.device

    private fun handleUsbIntent(intent: Intent?) {
        if (intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            viewModel.refreshDevices()
        }
    }

    private fun shareWorkspaceZip() {
        lifecycleScope.launch {
            setBusyMessage("Packaging workspace…")
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val target = File(cacheDir, "spreadtrum-workspace-${System.currentTimeMillis()}.zip")
                    zipDirectory(viewModel.workspace, target)
                    target
                }
            }
            result.onSuccess { file ->
                val uri = FileProvider.getUriForFile(this@MainActivity, "$packageName.files", file)
                startActivity(
                    Intent.createChooser(
                        Intent(Intent.ACTION_SEND).apply {
                            type = "application/zip"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        },
                        "Share workspace",
                    ),
                )
            }.onFailure { showError(it.message ?: "ZIP creation failed") }
            restoreStatus()
        }
    }

    private fun zipDirectory(root: File, target: File) {
        FileOutputStream(target).buffered().use { output ->
            ZipOutputStream(output).use { zip ->
                root.walkTopDown().filter(File::isFile).forEach { file ->
                    zip.putNextEntry(ZipEntry(file.relativeTo(root).invariantSeparatorsPath))
                    file.inputStream().buffered().use { it.copyTo(zip, 1024 * 1024) }
                    zip.closeEntry()
                }
            }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }


    private data class BatteryStatus(val percent: Int, val charging: Boolean)

    private fun batteryStatus(): BatteryStatus {
        val battery = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return BatteryStatus(-1, false)
        val level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val percent = if (level >= 0 && scale > 0) level * 100 / scale else -1
        val status = battery.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        return BatteryStatus(
            percent = percent,
            charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL,
        )
    }

    private fun riskColor(level: RiskLevel): Int = ContextCompat.getColor(
        this,
        when (level) {
            RiskLevel.SAFE -> R.color.success
            RiskLevel.CAUTION -> R.color.warning
            RiskLevel.DESTRUCTIVE -> R.color.danger
        },
    )

    private fun Int.copyAlpha(alpha: Float): Int =
        (this and 0x00ffffff) or ((alpha.coerceIn(0f, 1f) * 255).toInt() shl 24)

    private fun formatDuration(seconds: Long): String = when {
        seconds < 60 -> "${seconds}s"
        seconds < 3600 -> "${seconds / 60}m ${seconds % 60}s"
        else -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
    }

    private fun textWatcher(after: () -> Unit) = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, afterCount: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        override fun afterTextChanged(s: Editable?) = after()
    }

    private fun setBusyMessage(message: String) { globalStatus.text = message }
    private fun restoreStatus() { globalStatus.text = viewModel.ui.value.status }
    private fun showError(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    private sealed interface ImportTarget {
        data object Fdl1 : ImportTarget
        data object Fdl2 : ImportTarget
        data object OutputFolder : ImportTarget
        data class ArgumentFile(val index: Int) : ImportTarget
        data class ArgumentDirectory(val index: Int) : ImportTarget
    }
}

private class SimpleItemSelectedListener(
    private val onSelected: () -> Unit,
) : android.widget.AdapterView.OnItemSelectedListener {
    override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) = onSelected()
    override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
}
