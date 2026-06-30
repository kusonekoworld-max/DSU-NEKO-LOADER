package vegabobo.dsusideloader.util

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

class BinaryExecutor(private val context: Context) {

    data class ExecResult(
        val exitCode: Int,
        val stdout: List<String>,
        val stderr: List<String>
    ) {
        val success get() = exitCode == 0
        val output  get() = stdout + stderr
    }

    val nativeBinDir: File by lazy {
        File(context.filesDir, "bin").also { it.mkdirs() }
    }

    /**
     * Extract bundled binaries from APK assets to internal storage on first run.
     * Binaries are placed in assets/bin/ at build time via GitHub Actions workflow.
     */
    suspend fun extractBinaries() = withContext(Dispatchers.IO) {
        val binaries = listOf(
            "simg2img", "img2simg", "e2fsck",
            "resize2fs", "debugfs", "magiskboot",
            "lz4", "mke2fs"
        )
        binaries.forEach { bin ->
            val dest = File(nativeBinDir, bin)
            if (!dest.exists() || dest.length() == 0L) {
                try {
                    context.assets.open("bin/$bin").use { input ->
                        dest.outputStream().use { output -> input.copyTo(output) }
                    }
                    dest.setExecutable(true, false)
                } catch (e: Exception) {
                    // Binary missing from assets — likely a build without the CI binary-fetch step
                }
            }
        }
    }

    fun execRootFlow(command: String): Flow<String> = flow {
        val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
        val stdoutReader = BufferedReader(InputStreamReader(process.inputStream))
        val stderrReader = BufferedReader(InputStreamReader(process.errorStream))
        var line: String?
        while (stdoutReader.readLine().also { line = it } != null) {
            emit("[OUT] ${line!!}")
        }
        while (stderrReader.readLine().also { line = it } != null) {
            emit("[ERR] ${line!!}")
        }
        val code = process.waitFor()
        emit("[EXIT] $code")
    }.flowOn(Dispatchers.IO)

    suspend fun execRoot(command: String): ExecResult = withContext(Dispatchers.IO) {
        val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
        val stdout = process.inputStream.bufferedReader().readLines()
        val stderr = process.errorStream.bufferedReader().readLines()
        val code = process.waitFor()
        ExecResult(code, stdout, stderr)
    }

    suspend fun execBinary(binaryName: String, args: String): ExecResult {
        val binPath = File(nativeBinDir, binaryName).absolutePath
        return execRoot("$binPath $args")
    }

    fun execBinaryFlow(binaryName: String, args: String): Flow<String> {
        val binPath = File(nativeBinDir, binaryName).absolutePath
        return execRootFlow("$binPath $args")
    }

    fun binPath(name: String) = File(nativeBinDir, name).absolutePath
}
