package vegabobo.dsusideloader.domain.gsieditor

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import vegabobo.dsusideloader.util.BinaryExecutor
import vegabobo.dsusideloader.util.StorageHelper
import java.io.File

class GsiMountUseCase(
    private val context: Context,
    private val executor: BinaryExecutor
) {

    data class MountResult(
        val mountPoint: File,
        val loopDevice: String,
        val isMounted: Boolean,
        val errorMessage: String? = null
    )

    suspend fun mount(rawImage: File): MountResult = withContext(Dispatchers.IO) {
        val mountDir = StorageHelper.getMountDir(context)
        executor.execRoot("mkdir -p '${mountDir.absolutePath}'")

        val loopResult = executor.execRoot("losetup -f")
        if (!loopResult.success || loopResult.stdout.isEmpty()) {
            return@withContext MountResult(mountDir, "", false, "No free loop device")
        }
        val loopDevice = loopResult.stdout.first().trim()

        val attachResult = executor.execRoot("losetup '$loopDevice' '${rawImage.absolutePath}'")
        if (!attachResult.success) {
            return@withContext MountResult(mountDir, loopDevice, false,
                "losetup failed: ${attachResult.stderr.joinToString()}")
        }

        val mountResult = executor.execRoot(
            "mount -t ext4 -o rw,noatime '$loopDevice' '${mountDir.absolutePath}'"
        )
        if (!mountResult.success) {
            executor.execRoot("losetup -d '$loopDevice'")
            return@withContext MountResult(mountDir, loopDevice, false,
                "mount failed: ${mountResult.stderr.joinToString()}")
        }

        MountResult(mountDir, loopDevice, true)
    }

    suspend fun unmount(mountResult: MountResult) = withContext(Dispatchers.IO) {
        executor.execRoot("umount '${mountResult.mountPoint.absolutePath}'")
        if (mountResult.loopDevice.isNotEmpty()) {
            executor.execRoot("losetup -d '${mountResult.loopDevice}'")
        }
    }

    suspend fun listFiles(mountPoint: File, relativePath: String = "/"): List<GsiFileEntry> =
        withContext(Dispatchers.IO) {
            val absPath = File(mountPoint, relativePath)
            val result = executor.execRoot("ls -la '${absPath.absolutePath}'")
            if (!result.success) return@withContext emptyList()
            result.stdout.drop(1).mapNotNull { parseLsLine(it, relativePath) }
        }

    private fun parseLsLine(line: String, parentPath: String): GsiFileEntry? {
        val parts = line.trim().split(Regex("\\s+"), limit = 9)
        if (parts.size < 9) return null
        val permissions = parts[0]
        val size        = parts[4].toLongOrNull() ?: 0L
        val name        = parts[8]
        if (name == "." || name == "..") return null
        return GsiFileEntry(
            name        = name,
            path        = "$parentPath/$name".replace("//", "/"),
            isDirectory = permissions.startsWith("d"),
            isSymlink   = permissions.startsWith("l"),
            size        = size,
            permissions = permissions
        )
    }
}

data class GsiFileEntry(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val isSymlink: Boolean,
    val size: Long,
    val permissions: String
)
