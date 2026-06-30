package vegabobo.dsusideloader.domain.gsieditor

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import vegabobo.dsusideloader.util.BinaryExecutor
import vegabobo.dsusideloader.util.ImageFormatDetector
import vegabobo.dsusideloader.util.StorageHelper
import java.io.File

class GsiUnpackUseCase(
    private val context: Context,
    private val executor: BinaryExecutor
) {

    sealed class UnpackState {
        data class Progress(val step: String, val log: String) : UnpackState()
        data class Done(val rawImageFile: File) : UnpackState()
        data class Error(val message: String) : UnpackState()
    }

    fun unpack(inputFile: File): Flow<UnpackState> = flow {
        val workDir = StorageHelper.getWorkDir(context)
        val format = ImageFormatDetector.detect(inputFile)
        emit(UnpackState.Progress("detect", "Format detected: $format"))

        val multiplier = ImageFormatDetector.requiredFreeSpaceMultiplier(format)
        if (!StorageHelper.hasEnoughSpace(inputFile, multiplier)) {
            val needed = StorageHelper.requiredSpaceGb(inputFile, multiplier)
            val free   = StorageHelper.getFreeSpaceGb()
            emit(UnpackState.Error(
                "Not enough space. Need %.2f GB, have %.2f GB free.".format(needed, free)
            ))
            return@flow
        }

        val decompressedFile = when (format) {
            ImageFormatDetector.ImageFormat.GZIP -> {
                emit(UnpackState.Progress("decompress", "Decompressing gzip..."))
                val out = File(workDir, "system_raw.img")
                val result = executor.execRoot("gzip -d -c '${inputFile.absolutePath}' > '${out.absolutePath}'")
                if (!result.success) {
                    emit(UnpackState.Error("gzip failed: ${result.stderr.joinToString()}"))
                    return@flow
                }
                out
            }
            ImageFormatDetector.ImageFormat.XZ -> {
                emit(UnpackState.Progress("decompress", "Decompressing xz..."))
                val out = File(workDir, "system_raw.img")
                val result = executor.execRoot("xz -d -k -c '${inputFile.absolutePath}' > '${out.absolutePath}'")
                if (!result.success) {
                    emit(UnpackState.Error("xz failed: ${result.stderr.joinToString()}"))
                    return@flow
                }
                out
            }
            ImageFormatDetector.ImageFormat.LZ4 -> {
                emit(UnpackState.Progress("decompress", "Decompressing lz4..."))
                val out = File(workDir, "system_raw.img")
                val result = executor.execBinary("lz4", "-d '${inputFile.absolutePath}' '${out.absolutePath}'")
                if (!result.success) {
                    emit(UnpackState.Error("lz4 failed: ${result.stderr.joinToString()}"))
                    return@flow
                }
                out
            }
            else -> inputFile
        }

        val innerFormat = ImageFormatDetector.detect(decompressedFile)
        val rawFile: File

        if (innerFormat == ImageFormatDetector.ImageFormat.SPARSE_IMG) {
            emit(UnpackState.Progress("convert", "Converting sparse → raw ext4..."))
            rawFile = File(workDir, "system_ext4.img")
            val result = executor.execBinary(
                "simg2img",
                "'${decompressedFile.absolutePath}' '${rawFile.absolutePath}'"
            )
            result.output.forEach { emit(UnpackState.Progress("convert", it)) }
            if (!result.success) {
                emit(UnpackState.Error("simg2img failed"))
                return@flow
            }
            if (decompressedFile != inputFile) decompressedFile.delete()
        } else {
            rawFile = decompressedFile
        }

        emit(UnpackState.Progress("fsck", "Checking filesystem integrity..."))
        val fsckResult = executor.execBinary("e2fsck", "-fy '${rawFile.absolutePath}'")
        fsckResult.output.forEach { emit(UnpackState.Progress("fsck", it)) }
        if (fsckResult.exitCode > 1) {
            emit(UnpackState.Error("e2fsck failed with exit code ${fsckResult.exitCode}"))
            return@flow
        }

        emit(UnpackState.Progress("done", "Ready: ${rawFile.name} (${StorageHelper.formatSize(rawFile.length())})"))
        emit(UnpackState.Done(rawFile))
    }.flowOn(Dispatchers.IO)
}
