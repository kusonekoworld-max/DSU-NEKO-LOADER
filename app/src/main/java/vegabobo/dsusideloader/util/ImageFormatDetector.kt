package vegabobo.dsusideloader.util

import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile

object ImageFormatDetector {

    enum class ImageFormat {
        RAW_EXT4, SPARSE_IMG, GZIP, XZ, LZ4, UNKNOWN
    }

    private val MAGIC_SPARSE = byteArrayOf(0x3A.toByte(), 0xFF.toByte(), 0x26.toByte(), 0xED.toByte())
    private val MAGIC_EXT4   = byteArrayOf(0x53.toByte(), 0xEF.toByte())
    private val MAGIC_GZIP   = byteArrayOf(0x1F.toByte(), 0x8B.toByte())
    private val MAGIC_XZ     = byteArrayOf(0xFD.toByte(), 0x37, 0x7A, 0x58, 0x5A, 0x00)
    private val MAGIC_LZ4    = byteArrayOf(0x04.toByte(), 0x22.toByte(), 0x4D.toByte(), 0x18.toByte())

    fun detect(file: File): ImageFormat {
        if (!file.exists() || file.length() < 8) return ImageFormat.UNKNOWN
        return FileInputStream(file).use { fis ->
            val header = ByteArray(8)
            fis.read(header)
            when {
                header.startsWith(MAGIC_SPARSE) -> ImageFormat.SPARSE_IMG
                header.startsWith(MAGIC_GZIP)   -> ImageFormat.GZIP
                header.startsWith(MAGIC_XZ)     -> ImageFormat.XZ
                header.startsWith(MAGIC_LZ4)    -> ImageFormat.LZ4
                isExt4(file)                    -> ImageFormat.RAW_EXT4
                else                            -> ImageFormat.UNKNOWN
            }
        }
    }

    private fun isExt4(file: File): Boolean {
        if (file.length() < 0x440) return false
        return try {
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(0x438)
                val magic = ByteArray(2)
                raf.read(magic)
                magic.contentEquals(MAGIC_EXT4)
            }
        } catch (e: Exception) { false }
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
        if (this.size < prefix.size) return false
        return prefix.indices.all { this[it] == prefix[it] }
    }

    fun requiredFreeSpaceMultiplier(format: ImageFormat): Double = when (format) {
        ImageFormat.GZIP, ImageFormat.XZ, ImageFormat.LZ4 -> 3.5
        ImageFormat.SPARSE_IMG -> 2.5
        ImageFormat.RAW_EXT4   -> 2.0
        else                   -> 2.0
    }
}
