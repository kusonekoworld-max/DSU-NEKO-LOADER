package vegabobo.dsusideloader.domain.gsieditor

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import vegabobo.dsusideloader.util.BinaryExecutor
import vegabobo.dsusideloader.util.RootProviderDetector
import java.io.File

class RootInjectUseCase(
    private val context: Context,
    private val executor: BinaryExecutor
) {

    sealed class InjectState {
        data class Progress(val step: String, val log: String) : InjectState()
        data class Done(val patchedFile: File?) : InjectState()
        data class Error(val message: String) : InjectState()
    }

    fun patchBootImage(bootImage: File): Flow<InjectState> = flow {
        val workDir = bootImage.parentFile ?: run {
            emit(InjectState.Error("Invalid boot image path"))
            return@flow
        }

        val provider = RootProviderDetector.detect(executor)
        emit(InjectState.Progress("detect", "Root provider detected: $provider"))

        emit(InjectState.Progress("extract", "Unpacking boot image..."))
        val extractResult = executor.execBinary("magiskboot", "unpack '${bootImage.absolutePath}'")
        extractResult.output.forEach { emit(InjectState.Progress("extract", it)) }
        if (!extractResult.success) {
            emit(InjectState.Error("magiskboot unpack failed"))
            return@flow
        }

        emit(InjectState.Progress("patch", "Patching ramdisk..."))
        val patchResult = executor.execRoot(
            "cd '${workDir.absolutePath}' && " +
            "KEEPVERITY=true KEEPFORCEENCRYPT=true " +
            "'${executor.binPath("magiskboot")}' patch ramdisk.cpio"
        )
        patchResult.output.forEach { emit(InjectState.Progress("patch", it)) }
        if (!patchResult.success) {
            emit(InjectState.Error("Ramdisk patch failed"))
            return@flow
        }

        emit(InjectState.Progress("repack", "Repacking boot image..."))
        val patchedBoot = File(workDir, "patched_boot.img")
        val repackResult = executor.execBinary("magiskboot",
            "repack '${bootImage.absolutePath}' '${patchedBoot.absolutePath}'")
        repackResult.output.forEach { emit(InjectState.Progress("repack", it)) }
        if (!repackResult.success) {
            emit(InjectState.Error("magiskboot repack failed"))
            return@flow
        }

        executor.execRoot("cd '${workDir.absolutePath}' && '${executor.binPath("magiskboot")}' cleanup")
        emit(InjectState.Progress("done", "Boot patched → ${patchedBoot.name}"))
        emit(InjectState.Done(patchedBoot))
    }.flowOn(Dispatchers.IO)

    fun injectRootModule(mountPoint: File): Flow<InjectState> = flow {
        val rootInfo = RootProviderDetector.detectFull(executor)
        val provider = rootInfo.provider
        emit(InjectState.Progress("detect", "Root provider detected: $provider"))
        if (rootInfo.susfsActive) {
            emit(InjectState.Progress("detect",
                "SUSFS active${rootInfo.susfsVersion?.let { " (v$it)" } ?: ""}"))
        }

        when (provider) {
            RootProviderDetector.RootProvider.KERNELSU ->
                emit(InjectState.Progress("inject", "Using KernelSU module injection path"))
            RootProviderDetector.RootProvider.APATCH ->
                emit(InjectState.Progress("inject", "Using APatch module injection path"))
            RootProviderDetector.RootProvider.MAGISK ->
                emit(InjectState.Progress("inject", "Using Magisk module injection path"))
            RootProviderDetector.RootProvider.UNKNOWN -> {
                emit(InjectState.Error("No supported root provider detected (Magisk/KernelSU/APatch)"))
                return@flow
            }
        }

        val moduleDir = File(mountPoint, "system/etc/init/root_module")
        executor.execRoot("mkdir -p '${moduleDir.absolutePath}'")

        val moduleProp = """
            id=gsi_root_overlay
            name=GSI Root Overlay
            version=v1
            versionCode=1
            author=DSU-NEKO-LOADER
            description=Auto-injected root overlay for GSI compatibility
        """.trimIndent()

        val propFile = File(moduleDir, "module.prop")
        executor.execRoot("printf '%s' '$moduleProp' > '${propFile.absolutePath}'")

        emit(InjectState.Progress("done", "Root module overlay written to ${moduleDir.absolutePath}"))
        emit(InjectState.Done(null))
    }.flowOn(Dispatchers.IO)
}
