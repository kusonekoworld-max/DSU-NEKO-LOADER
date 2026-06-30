package vegabobo.dsusideloader.ui.gsiEditor

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import vegabobo.dsusideloader.ui.screen.Destinations
import vegabobo.dsusideloader.util.StorageHelper
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GsiEditorScreen(
    navigate: (String) -> Unit,
    vm: GsiEditorViewModel = viewModel()
) {
    val uiState by vm.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            val path = uri.path?.replace("/document/primary:", "/storage/emulated/0/") ?: return@let
            vm.onImagePicked(File(path))
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("GSI Editor") },
                navigationIcon = {
                    IconButton(onClick = {
                        vm.cleanup()
                        navigate(Destinations.Up)
                    }) { Icon(Icons.Default.ArrowBack, "Back") }
                },
                actions = {
                    if (uiState.currentStep == EditorStep.EDIT) {
                        IconButton(onClick = { vm.startRepack() }) {
                            Icon(Icons.Default.Archive, "Repack")
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            StepIndicator(currentStep = uiState.currentStep)

            uiState.errorMessage?.let { err ->
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Error, null, tint = MaterialTheme.colorScheme.onErrorContainer)
                        Spacer(Modifier.width(8.dp))
                        Text(err, color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.weight(1f))
                    }
                }
            }

            when (uiState.currentStep) {
                EditorStep.PICK_IMAGE -> PickImageSection(
                    onPickFile = { filePicker.launch("*/*") }
                )
                EditorStep.UNPACK -> UnpackSection(
                    uiState = uiState,
                    onStartUnpack = vm::startUnpack
                )
                EditorStep.EDIT -> EditSection(
                    uiState = uiState,
                    vm = vm
                )
                EditorStep.REPACK -> RepackSection(
                    uiState = uiState,
                    vm = vm
                )
                EditorStep.DONE -> DoneSection(
                    uiState = uiState,
                    onStartOver = vm::cleanup
                )
            }

            if (uiState.logLines.isNotEmpty()) {
                LogPanel(logLines = uiState.logLines)
            }
        }
    }
}

@Composable
private fun PickImageSection(onPickFile: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Icon(
            Icons.Default.DriveFolderUpload,
            contentDescription = null,
            modifier = Modifier.size(72.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Text("Select a GSI Image", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Supported: .img, .img.gz, .img.xz, .img.lz4, sparse img",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Button(onClick = onPickFile, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.FolderOpen, null)
            Spacer(Modifier.width(8.dp))
            Text("Browse Files")
        }
    }
}

@Composable
private fun UnpackSection(
    uiState: GsiEditorUiState,
    onStartUnpack: () -> Unit
) {
    Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        uiState.inputFile?.let { file ->
            ImageInfoCard(
                fileName   = file.name,
                format     = uiState.detectedFormat?.name ?: "Unknown",
                size       = uiState.imageSizeText,
                freeSpace  = uiState.freeSpaceText,
                hasSpace   = uiState.hasEnoughSpace
            )
        }

        if (uiState.isOperationRunning) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text("Unpacking...", style = MaterialTheme.typography.bodySmall)
        } else {
            Button(
                onClick  = onStartUnpack,
                enabled  = uiState.hasEnoughSpace && !uiState.isOperationRunning,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Unarchive, null)
                Spacer(Modifier.width(8.dp))
                Text("Unpack Image")
            }
        }
    }
}

@Composable
private fun EditSection(
    uiState: GsiEditorUiState,
    vm: GsiEditorViewModel
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Files", "build.prop", "Root")

    Column {
        TabRow(selectedTabIndex = selectedTab) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTab == index,
                    onClick  = { selectedTab = index },
                    text     = { Text(title) }
                )
            }
        }
        when (selectedTab) {
            0 -> FileBrowserTab(uiState = uiState, vm = vm)
            1 -> BuildPropTab(uiState = uiState, vm = vm)
            2 -> MagiskTab(uiState = uiState, vm = vm)
        }
    }
}

@Composable
private fun FileBrowserTab(
    uiState: GsiEditorUiState,
    vm: GsiEditorViewModel
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick  = vm::navigateUp,
                    enabled  = uiState.currentBrowsePath != "/"
                ) { Icon(Icons.Default.ArrowUpward, "Up") }
                Text(
                    uiState.currentBrowsePath,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        if (uiState.mountResult == null) {
            Box(
                modifier = Modifier.fillMaxWidth().padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(8.dp))
                    Text("Mounting image...")
                }
            }
        } else {
            LazyColumn {
                items(uiState.fileEntries) { entry ->
                    var showConfirm by remember { mutableStateOf(false) }
                    ListItem(
                        headlineContent = {
                            Text(entry.name, fontFamily = FontFamily.Monospace)
                        },
                        supportingContent = {
                            Text("${entry.permissions}  ${if (!entry.isDirectory) StorageHelper.formatSize(entry.size) else ""}",
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        },
                        leadingContent = {
                            Icon(
                                if (entry.isDirectory) Icons.Default.Folder
                                else Icons.Default.InsertDriveFile,
                                null,
                                tint = if (entry.isDirectory)
                                    MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        trailingContent = {
                            IconButton(onClick = { showConfirm = true }) {
                                Icon(Icons.Default.Delete, null,
                                    tint = MaterialTheme.colorScheme.error)
                            }
                        },
                        modifier = Modifier.clickable {
                            if (entry.isDirectory) vm.browseDirectory(entry.path)
                        }
                    )
                    HorizontalDivider()
                    if (showConfirm) {
                        AlertDialog(
                            onDismissRequest = { showConfirm = false },
                            title = { Text("Delete ${entry.name}?") },
                            text  = { Text("This cannot be undone.") },
                            confirmButton = {
                                TextButton(onClick = { vm.deleteFile(entry); showConfirm = false }) {
                                    Text("Delete")
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = { showConfirm = false }) { Text("Cancel") }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BuildPropTab(uiState: GsiEditorUiState, vm: GsiEditorViewModel) {
    var searchQuery by remember { mutableStateOf("") }
    Column {
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            label = { Text("Search props") },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            singleLine = true
        )
        val filtered = uiState.buildProps.entries
            .filter { it.key.contains(searchQuery, true) || it.value.contains(searchQuery, true) }
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(filtered.toList()) { (key, value) ->
                var editing   by remember { mutableStateOf(false) }
                var editValue by remember(value) { mutableStateOf(value) }
                ListItem(
                    headlineContent = {
                        Text(key, fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodyMedium)
                    },
                    supportingContent = {
                        if (editing) {
                            OutlinedTextField(
                                value = editValue,
                                onValueChange = { editValue = it },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        } else {
                            Text(value, fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary)
                        }
                    },
                    trailingContent = {
                        if (editing) {
                            Row {
                                IconButton(onClick = { vm.updateBuildProp(key, editValue); editing = false }) {
                                    Icon(Icons.Default.Check, null,
                                        tint = MaterialTheme.colorScheme.primary)
                                }
                                IconButton(onClick = { editValue = value; editing = false }) {
                                    Icon(Icons.Default.Close, null)
                                }
                            }
                        } else {
                            IconButton(onClick = { editing = true }) {
                                Icon(Icons.Default.Edit, null)
                            }
                        }
                    }
                )
                HorizontalDivider()
            }
        }
        Button(
            onClick = vm::saveBuildProps,
            modifier = Modifier.fillMaxWidth().padding(16.dp)
        ) {
            Icon(Icons.Default.Save, null)
            Spacer(Modifier.width(8.dp))
            Text("Save build.prop")
        }
    }
}

@Composable
private fun MagiskTab(
    uiState: GsiEditorUiState,
    vm: GsiEditorViewModel
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Card {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Inject Root Module", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Otomatis mendeteksi root provider aktif (Magisk, KernelSU, " +
                    "atau APatch) dan inject overlay module yang kompatibel ke GSI.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Button(
            onClick  = { vm.injectRootModule() },
            enabled  = !uiState.isOperationRunning,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (uiState.isOperationRunning) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text("Detect & Inject")
        }
    }
}

@Composable
private fun RepackSection(
    uiState: GsiEditorUiState,
    vm: GsiEditorViewModel
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Repack Options", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = uiState.outputFileName,
            onValueChange = vm::setOutputFileName,
            label = { Text("Output filename") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        Text("Output format:")
        GsiRepackUseCase.OutputFormat.values().forEach { fmt ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = uiState.outputFormat == fmt,
                    onClick = { vm.setOutputFormat(fmt) })
                Text(fmt.name)
            }
        }
        if (uiState.isOperationRunning) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        } else {
            Button(onClick = vm::startRepack, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Archive, null)
                Spacer(Modifier.width(8.dp))
                Text("Start Repack")
            }
        }
    }
}

@Composable
private fun DoneSection(uiState: GsiEditorUiState, onStartOver: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Icon(Icons.Default.CheckCircle, null,
            modifier = Modifier.size(72.dp),
            tint = MaterialTheme.colorScheme.primary)
        Text("Repack Complete!", style = MaterialTheme.typography.headlineSmall)
        uiState.outputFile?.let { f ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(f.name, style = MaterialTheme.typography.titleMedium)
                    Text(f.absolutePath, fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(StorageHelper.formatSize(f.length()))
                }
            }
        }
        OutlinedButton(onClick = onStartOver, modifier = Modifier.fillMaxWidth()) {
            Text("Start Over")
        }
    }
}

@Composable
private fun StepIndicator(currentStep: EditorStep) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        EditorStep.values().forEach { step ->
            val isActive = step == currentStep
            val isPassed = step.ordinal < currentStep.ordinal
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(28.dp).background(
                        if (isActive || isPassed) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant,
                        CircleShape
                    )
                ) {
                    if (isPassed)
                        Icon(Icons.Default.Check, null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(16.dp))
                    else
                        Text("${step.ordinal + 1}",
                            color = if (isActive) MaterialTheme.colorScheme.onPrimary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelSmall)
                }
                Text(step.name.lowercase().replaceFirstChar { it.uppercaseChar() },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isActive) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ImageInfoCard(
    fileName: String,
    format: String,
    size: String,
    freeSpace: String,
    hasSpace: Boolean
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(fileName, style = MaterialTheme.typography.titleMedium)
            InfoRow("Format", format)
            InfoRow("Size", size)
            InfoRow("Free space", freeSpace)
            if (!hasSpace) {
                Text("⚠ Not enough free space!",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall)
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun LogPanel(logLines: List<LogLine>) {
    val listState = rememberLazyListState()
    LaunchedEffect(logLines.size) {
        if (logLines.isNotEmpty()) listState.animateScrollToItem(logLines.size - 1)
    }
    Surface(
        modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 200.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        LazyColumn(state = listState, modifier = Modifier.padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items(logLines) { line ->
                Text("[${line.tag}] ${line.message}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = if (line.isError) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
