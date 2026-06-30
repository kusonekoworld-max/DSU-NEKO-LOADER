package vegabobo.dsusideloader.domain.gsieditor

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import vegabobo.dsusideloader.util.BinaryExecutor
import vegabobo.dsusideloader.util.StorageHelper
import java.io.File

class GsiRepackUseCase(
    private val context: Context,
    private val executor: BinaryExecutor
) {

    enum class OutputFormat {
        RAW_EXT4, SPARSE_IMG, GZIP, XZ, LZ4
    }

    sealed class RepackState {
        data class Progress(val step: String, val log: String) : RepackState()
        data class Done(val outputFile: File) : RepackState()
        data class Error(val message: String) : RepackState()
    }

    fun repack(
        rawImage: File,
        outputFormat: OutputFormat = OutputFormat.SPARSE_IMG,
        outputFileName: String = "system_patched"
    ): Flow<RepackState> = flow {
        val outputDir = StorageHelper.getOutputDir(context)

        emit(RepackState.Progress("fsck", "Running e2fsck..."))
        val fsck = executor.execBinary("e2fsck", "-fy '${rawImage.absolutePath}'")
        fsck.output.forEach { emit(RepackState.Progress("fsck", it)) }
        if (fsck.exitCode > 1) {
            emit(RepackState.Error("e2fsck failed (exit ${fsck.exitCode})"))
            return@flow
        }

        emit(RepackState.Progress("shrink", "Shrinking filesystem..."))
        val shrink = executor.execBinary("resize2fs", "-M '${rawImage.absolutePath}'")
        shrink.output.forEach { emit(RepackState.Progress("shrink", it)) }
        if (!shrink.success) {
            emit(RepackState.Error("resize2fs failed"))
            return@flow
        }

        val finalFile: File = when (outputFormat) {
            OutputFormat.RAW_EXT4 -> {
                val out = File(outputDir, "$outputFileName.img")
                executor.execRoot("cp '${rawImage.absolutePath}' '${out.absolutePath}'")
                out
            }
            OutputFormat.SPARSE_IMG -> {
                emit(RepackState.Progress("convert", "Converting to sparse..."))
                val out = File(outputDir, "$outputFileName.img")
                val result = executor.execBinary("img2simg",
                    "'${rawImage.absolutePath}' '${out.absolutePath}'")
                result.output.forEach { emit(RepackState.Progress("convert", it)) }
                if (!result.success) {
                    emit(RepackState.Error("img2simg failed"))
                    return@flow
                }
                out
            }
            OutputFormat.GZIP -> {
                emit(RepackState.Progress("compress", "Compressing gzip..."))
                val out = File(outputDir, "$outputFileName.img.gz")
                executor.execRoot("gzip -9 -c '${rawImage.absolutePath}' > '${out.absolutePath}'")
                out
            }
            OutputFormat.XZ -> {
                emit(RepackState.Progress("compress", "Compressing xz..."))
                val out = File(outputDir, "$outputFileName.img.xz")
                executor.execRoot("xz -9 -k -c '${rawImage.absolutePath}' > '${out.absolutePath}'")
                out
            }
            OutputFormat.LZ4 -> {
                emit(RepackState.Progress("compress", "Compressing lz4..."))
                val out = File(outputDir, "$outputFileName.img.lz4")
                executor.execBinary("lz4", "-9 '${rawImage.absolutePath}' '${out.absolutePath}'")
                out
            }
        }

        emit(RepackState.Progress("done",
            "Done → ${finalFile.name} (${StorageHelper.formatSize(finalFile.length())})"))
        emit(RepackState.Done(finalFile))
    }.flowOn(Dispatchers.IO)
}
