package vegabobo.dsusideloader.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Detects which root provider is currently active: Magisk, KernelSU, APatch, or none.
 * Also detects if SUSFS (kernel-level root hiding) is active alongside it.
 */
object RootProviderDetector {

    enum class RootProvider {
        MAGISK,
        KERNELSU,
        APATCH,
        UNKNOWN
    }

    data class RootInfo(
        val provider: RootProvider,
        val susfsActive: Boolean,
        val susfsVersion: String? = null
    )

    suspend fun detect(executor: BinaryExecutor): RootProvider {
        return detectFull(executor).provider
    }

    suspend fun detectFull(executor: BinaryExecutor): RootInfo = withContext(Dispatchers.IO) {
        val provider = detectProvider(executor)
        val (susfsActive, susfsVersion) = detectSusfs(executor)
        RootInfo(provider, susfsActive, susfsVersion)
    }

    private suspend fun detectProvider(executor: BinaryExecutor): RootProvider {
        val ksuCheck = executor.execRoot("which ksud || ls /data/adb/ksu 2>/dev/null")
        if (ksuCheck.success && ksuCheck.stdout.isNotEmpty()) {
            return RootProvider.KERNELSU
        }

        val apatchCheck = executor.execRoot("which apd || ls /data/adb/ap 2>/dev/null")
        if (apatchCheck.success && apatchCheck.stdout.isNotEmpty()) {
            return RootProvider.APATCH
        }

        val magiskCheck = executor.execRoot("which magisk || ls /data/adb/magisk 2>/dev/null")
        if (magiskCheck.success && magiskCheck.stdout.isNotEmpty()) {
            return RootProvider.MAGISK
        }

        return RootProvider.UNKNOWN
    }

    private suspend fun detectSusfs(executor: BinaryExecutor): Pair<Boolean, String?> {
        val sysCheck = executor.execRoot(
            "cat /proc/sys/kernel/susfs_version 2>/dev/null || " +
            "cat /sys/kernel/susfs/version 2>/dev/null || " +
            "ls /data/adb/ksu/.susfs 2>/dev/null"
        )
        if (sysCheck.success && sysCheck.stdout.isNotEmpty() && sysCheck.stdout.first().isNotBlank()) {
            return true to sysCheck.stdout.first().trim()
        }

        val dmesgCheck = executor.execRoot("dmesg 2>/dev/null | grep -i 'susfs' | head -1")
        if (dmesgCheck.success && dmesgCheck.stdout.isNotEmpty()) {
            return true to null
        }

        val kernelVerCheck = executor.execRoot("uname -r")
        if (kernelVerCheck.success &&
            kernelVerCheck.stdout.any { it.contains("susfs", ignoreCase = true) }
        ) {
            return true to null
        }

        return false to null
    }

    fun getModuleDir(provider: RootProvider): String = when (provider) {
        RootProvider.MAGISK   -> "/data/adb/modules"
        RootProvider.KERNELSU -> "/data/adb/modules"
        RootProvider.APATCH   -> "/data/adb/modules"
        RootProvider.UNKNOWN  -> "/data/adb/modules"
    }
}
