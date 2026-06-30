package vegabobo.dsusideloader.ui.gsiEditor

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import vegabobo.dsusideloader.domain.gsieditor.*
import vegabobo.dsusideloader.util.BinaryExecutor
import vegabobo.dsusideloader.util.ImageFormatDetector
import vegabobo.dsusideloader.util.RootProviderDetector
import vegabobo.dsusideloader.util.StorageHelper
import java.io.File

data class GsiEditorUiState(
    val currentStep: EditorStep = EditorStep.PICK_IMAGE,
    val rootInfo: RootProviderDetector.RootInfo? = null,
    val inputFile: File? = null,
    val rawImageFile: File? = null,
    val detectedFormat: ImageFormatDetector.ImageFormat? = null,
    val imageSizeText: String = "",
    val freeSpaceText: String = "",
    val hasEnoughSpace: Boolean = true,
    val mountResult: GsiMountResult? = null,
    val currentBrowsePath: String = "/",
    val fileEntries: List<GsiFileEntry> = emptyList(),
    val buildProps: Map<String, String> = emptyMap(),
    val outputFormat: GsiRepackUseCase.OutputFormat = GsiRepackUseCase.OutputFormat.SPARSE_IMG,
    val outputFileName: String = "system_patched",
    val logLines: List<LogLine> = emptyList(),
    val isOperationRunning: Boolean = false,
    val outputFile: File? = null,
    val errorMessage: String? = null
)

data class GsiMountResult(val mountPoint: File, val loopDevice: String)
data class LogLine(val tag: String, val message: String, val isError: Boolean = false)

enum class EditorStep { PICK_IMAGE, UNPACK, EDIT, REPACK, DONE }

class GsiEditorViewModel(application: Application) : AndroidViewModel(application) {

    private val executor = BinaryExecutor(application)
    private val unpackUc = GsiUnpackUseCase(application, executor)
    private val mountUc  = GsiMountUseCase(application, executor)
    private val repackUc = GsiRepackUseCase(application, executor)
    private val rootInjectUc = RootInjectUseCase(application, executor)

    private val _uiState = MutableStateFlow(GsiEditorUiState())
    val uiState: StateFlow<GsiEditorUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            executor.extractBinaries()
            val info = RootProviderDetector.detectFull(executor)
            _uiState.update { it.copy(rootInfo = info) }
        }
    }

    fun onImagePicked(file: File) {
        val format   = ImageFormatDetector.detect(file)
        val multi    = ImageFormatDetector.requiredFreeSpaceMultiplier(format)
        val hasSpace = StorageHelper.hasEnoughSpace(file, multi)
        _uiState.update { it.copy(
            inputFile      = file,
            detectedFormat = format,
            imageSizeText  = StorageHelper.formatSize(file.length()),
            freeSpaceText  = "%.2f GB free".format(StorageHelper.getFreeSpaceGb()),
            hasEnoughSpace = hasSpace,
            currentStep    = EditorStep.UNPACK
        )}
    }

    fun startUnpack() {
        val input = _uiState.value.inputFile ?: return
        _uiState.update { it.copy(isOperationRunning = true, logLines = emptyList()) }
        viewModelScope.launch {
            unpackUc.unpack(input).collect { state ->
                when (state) {
                    is GsiUnpackUseCase.UnpackState.Progress ->
                        appendLog(state.step, state.log)
                    is GsiUnpackUseCase.UnpackState.Done -> {
                        _uiState.update { it.copy(
                            rawImageFile       = state.rawImageFile,
                            isOperationRunning = false,
                            currentStep        = EditorStep.EDIT
                        )}
                        mountImage(state.rawImageFile)
                    }
                    is GsiUnpackUseCase.UnpackState.Error ->
                        _uiState.update { it.copy(
                            isOperationRunning = false,
                            errorMessage       = state.message
                        )}
                }
            }
        }
    }

    private fun mountImage(rawImage: File) {
        viewModelScope.launch {
            appendLog("mount", "Mounting image...")
            val result = mountUc.mount(rawImage)
            if (result.isMounted) {
                _uiState.update { it.copy(
                    mountResult = GsiMountResult(result.mountPoint, result.loopDevice)
                )}
                appendLog("mount", "Mounted at ${result.mountPoint}")
                browseDirectory("/")
                loadBuildProps(result.mountPoint)
            } else {
                appendLog("mount", "Mount failed: ${result.errorMessage}", isError = true)
            }
        }
    }

    fun browseDirectory(path: String) {
        val mount = _uiState.value.mountResult?.mountPoint ?: return
        viewModelScope.launch {
            val entries = mountUc.listFiles(mount, path)
            _uiState.update { it.copy(currentBrowsePath = path, fileEntries = entries) }
        }
    }

    fun navigateUp() {
        val current = _uiState.value.currentBrowsePath
        if (current == "/") return
        val parent = current.substringBeforeLast("/").ifEmpty { "/" }
        browseDirectory(parent)
    }

    private fun loadBuildProps(mountPoint: File) {
        viewModelScope.launch {
            val result = executor.execRoot("cat '${mountPoint.absolutePath}/system/build.prop'")
            if (result.success) {
                val props = result.stdout
                    .filter { it.contains("=") && !it.startsWith("#") }
                    .associate { line ->
                        val idx = line.indexOf('=')
                        line.substring(0, idx) to line.substring(idx + 1)
                    }
                _uiState.update { it.copy(buildProps = props) }
            }
        }
    }

    fun updateBuildProp(key: String, value: String) {
        _uiState.update { it.copy(buildProps = it.buildProps + (key to value)) }
    }

    fun saveBuildProps() {
        val mount = _uiState.value.mountResult?.mountPoint ?: return
        val props = _uiState.value.buildProps
        viewModelScope.launch {
            appendLog("props", "Writing build.prop...")
            val content = props.entries.joinToString("\n") { "${it.key}=${it.value}" }
            val tmpFile = File(StorageHelper.getWorkDir(getApplication()), "build.prop.tmp")
            tmpFile.writeText(content)
            executor.execRoot("cp '${tmpFile.absolutePath}' '${mount.absolutePath}/system/build.prop'")
            appendLog("props", "Saved ${props.size} entries")
        }
    }

    fun deleteFile(entry: GsiFileEntry) {
        val mount = _uiState.value.mountResult?.mountPoint ?: return
        viewModelScope.launch {
            val fullPath = "${mount.absolutePath}${entry.path}"
            val cmd = if (entry.isDirectory) "rm -rf '$fullPath'" else "rm -f '$fullPath'"
            val result = executor.execRoot(cmd)
            if (result.success) {
                appendLog("edit", "Deleted: ${entry.name}")
                browseDirectory(_uiState.value.currentBrowsePath)
            } else {
                appendLog("edit", "Delete failed", isError = true)
            }
        }
    }

    fun pushFile(localFile: File, targetPath: String) {
        val mount = _uiState.value.mountResult?.mountPoint ?: return
        viewModelScope.launch {
            val destPath = "${mount.absolutePath}$targetPath/${localFile.name}"
            appendLog("edit", "Pushing ${localFile.name}...")
            val result = executor.execRoot("cp '${localFile.absolutePath}' '$destPath'")
            if (result.success) {
                appendLog("edit", "Done")
                browseDirectory(_uiState.value.currentBrowsePath)
            } else {
                appendLog("edit", "Push failed", isError = true)
            }
        }
    }

    fun injectRootModule() {
        val mount = _uiState.value.mountResult?.mountPoint ?: return
        _uiState.update { it.copy(isOperationRunning = true) }
        viewModelScope.launch {
            rootInjectUc.injectRootModule(mount).collect { state ->
                when (state) {
                    is RootInjectUseCase.InjectState.Progress ->
                        appendLog(state.step, state.log)
                    is RootInjectUseCase.InjectState.Done ->
                        _uiState.update { it.copy(isOperationRunning = false) }
                    is RootInjectUseCase.InjectState.Error -> {
                        appendLog("root", state.message, isError = true)
                        _uiState.update { it.copy(isOperationRunning = false) }
                    }
                }
            }
        }
    }

    fun setOutputFormat(format: GsiRepackUseCase.OutputFormat) {
        _uiState.update { it.copy(outputFormat = format) }
    }

    fun setOutputFileName(name: String) {
        _uiState.update { it.copy(outputFileName = name) }
    }

    fun startRepack() {
        val rawImage = _uiState.value.rawImageFile ?: return
        _uiState.update { it.copy(isOperationRunning = true, currentStep = EditorStep.REPACK) }
        viewModelScope.launch {
            _uiState.value.mountResult?.let { mr ->
                mountUc.unmount(GsiMountUseCase.MountResult(mr.mountPoint, mr.loopDevice, true))
                appendLog("umount", "Unmounted")
            }
            repackUc.repack(rawImage, _uiState.value.outputFormat, _uiState.value.outputFileName)
                .collect { state ->
                    when (state) {
                        is GsiRepackUseCase.RepackState.Progress ->
                            appendLog(state.step, state.log)
                        is GsiRepackUseCase.RepackState.Done ->
                            _uiState.update { it.copy(
                                isOperationRunning = false,
                                outputFile         = state.outputFile,
                                currentStep        = EditorStep.DONE
                            )}
                        is GsiRepackUseCase.RepackState.Error -> {
                            appendLog("repack", state.message, isError = true)
                            _uiState.update { it.copy(isOperationRunning = false) }
                        }
                    }
                }
        }
    }

    fun cleanup() {
        viewModelScope.launch {
            _uiState.value.mountResult?.let { mr ->
                mountUc.unmount(GsiMountUseCase.MountResult(mr.mountPoint, mr.loopDevice, true))
            }
            StorageHelper.cleanDir(StorageHelper.getWorkDir(getApplication()))
            _uiState.update { GsiEditorUiState() }
        }
    }

    override fun onCleared() {
        super.onCleared()
        viewModelScope.launch {
            _uiState.value.mountResult?.let { mr ->
                mountUc.unmount(GsiMountUseCase.MountResult(mr.mountPoint, mr.loopDevice, true))
            }
        }
    }

    private fun appendLog(tag: String, message: String, isError: Boolean = false) {
        _uiState.update { it.copy(logLines = it.logLines + LogLine(tag, message, isError)) }
    }
}
