package vegabobo.dsusideloader.util

import android.content.Context
import android.os.Environment
import android.os.StatFs
import java.io.File

object StorageHelper {

    fun getFreeSpace(path: File = Environment.getExternalStorageDirectory()): Long {
        return StatFs(path.absolutePath).run {
            availableBlocksLong * blockSizeLong
        }
    }

    fun getFreeSpaceGb(path: File = Environment.getExternalStorageDirectory()): Double {
        return getFreeSpace(path) / (1024.0 * 1024.0 * 1024.0)
    }

    fun hasEnoughSpace(imageFile: File, multiplier: Double = 3.0): Boolean {
        val required = (imageFile.length() * multiplier).toLong()
        return getFreeSpace() >= required
    }

    fun requiredSpaceGb(imageFile: File, multiplier: Double = 3.0): Double {
        return (imageFile.length() * multiplier) / (1024.0 * 1024.0 * 1024.0)
    }

    fun getWorkDir(context: Context): File {
        val workDir = File(context.filesDir, "gsi_work")
        workDir.mkdirs()
        return workDir
    }

    fun getMountDir(context: Context): File {
        return File("/data/local/tmp/gsi_mount")
    }

    fun getOutputDir(context: Context): File {
        val outDir = File(
            Environment.getExternalStorageDirectory(),
            "DSUSideloader/output"
        )
        outDir.mkdirs()
        return outDir
    }

    fun formatSize(bytes: Long): String {
        return when {
            bytes >= 1_073_741_824 -> "%.2f GB".format(bytes / 1_073_741_824.0)
            bytes >= 1_048_576     -> "%.2f MB".format(bytes / 1_048_576.0)
            bytes >= 1_024         -> "%.2f KB".format(bytes / 1_024.0)
            else                   -> "$bytes B"
        }
    }

    fun cleanDir(dir: File) {
        if (dir.exists()) dir.deleteRecursively()
    }
}
