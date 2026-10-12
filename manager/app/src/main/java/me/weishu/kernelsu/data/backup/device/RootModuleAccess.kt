package me.weishu.kernelsu.data.backup.device

import android.content.Context
import android.os.Process
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.weishu.kernelsu.data.backup.source.InstalledModule
import me.weishu.kernelsu.data.backup.backupResult
import me.weishu.kernelsu.data.backup.source.ModuleAccess
import me.weishu.kernelsu.ksuApp
import me.weishu.kernelsu.ui.util.execKsudResult
import me.weishu.kernelsu.ui.util.listModules
import org.json.JSONArray
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * A [ModuleAccess] backed by the root shell and ksud.
 *
 * Installed modules are enumerated with `ksud module list`; packaging stages a copy of the module
 * directory under the app cache with `cp -a`, then rewrites it as a root-level ZIP (ksud's installer
 * reads `module.prop` at the archive root and extracts without stripping a top directory). Installing
 * delegates to `ksud module install`.
 */
class RootModuleAccess(private val context: Context = ksuApp) : ModuleAccess {

    override suspend fun installedModules(): Result<List<InstalledModule>> = backupResult {
        val array = JSONArray(listModules())
        (0 until array.length()).map { index ->
            val obj = array.getJSONObject(index)
            InstalledModule(
                id = obj.getString("id"),
                name = obj.optString("name"),
                versionName = obj.optString("version", "Unknown"),
                versionCode = obj.optInt("versionCode", 0),
                author = obj.optString("author", "Unknown"),
                metamodule = obj.optInt("metamodule", 0) != 0 || obj.optBoolean("metamodule", false),
            )
        }
    }

    override suspend fun inspectInstaller(zip: File): Result<InstalledModule> = withContext(Dispatchers.IO) {
        backupResult {
            ZipFile(zip).use { archive ->
                val entry = archive.getEntry(MODULE_PROP)
                    ?: archive.entries().asSequence().firstOrNull { it.name.endsWith("/$MODULE_PROP") }
                    ?: throw IOException("module.prop not found in ${zip.name}")
                val props = archive.getInputStream(entry).bufferedReader().useLines { lines ->
                    lines.mapNotNull { raw ->
                        val line = raw.substringBefore('#').trim()
                        val separator = line.indexOf('=')
                        if (separator <= 0) {
                            null
                        } else {
                            line.substring(0, separator).trim() to line.substring(separator + 1).trim()
                        }
                    }.toMap()
                }
                val id = props["id"]?.takeIf { it.isNotBlank() }
                    ?: throw IOException("module.prop has no id in ${zip.name}")
                InstalledModule(
                    id = id,
                    name = props["name"].orEmpty(),
                    versionName = props["version"]?.takeIf { it.isNotBlank() } ?: "Unknown",
                    versionCode = props["versionCode"]?.toIntOrNull() ?: 0,
                    author = props["author"]?.takeIf { it.isNotBlank() } ?: "Unknown",
                    metamodule = props["metamodule"]?.trim() == "1",
                )
            }
        }
    }

    override suspend fun packModule(moduleId: String, target: File): Result<Unit> = backupResult {
        require(moduleId.matches(MODULE_ID_PATTERN)) { "invalid module id: $moduleId" }
        // A fresh path avoids collisions with root-owned leftovers from interrupted copies.
        val stageRoot = File(context.cacheDir, "backup-module-stage-$moduleId-${System.nanoTime()}")
        try {
            // cp -a preserves root ownership; make the staged copy readable by the app before zipping.
            val stagedPath = shellQuote("${stageRoot.absolutePath}/$moduleId")
            val copied = runRootCommand(
                "mkdir -p ${shellQuote(stageRoot.absolutePath)} && " +
                    "busybox chmod 0755 ${shellQuote(stageRoot.absolutePath)} && " +
                    "busybox cp -a ${shellQuote("$MODULES_DIR/$moduleId")} ${shellQuote(stageRoot.absolutePath)}/ && " +
                    "busybox chown -R ${Process.myUid()} $stagedPath && " +
                    "busybox chmod -R u+rwX $stagedPath"
            )
            check(copied.isSuccess) {
                "failed to stage module $moduleId: ${describe(copied)}"
            }
            val staged = File(stageRoot, moduleId)
            check(staged.isDirectory) { "staged module directory missing: $moduleId" }
            target.parentFile?.mkdirs()
            writeRootLevelZip(staged, target)
        } finally {
            // Tear down as root: the tree can still be root-owned when the copy above failed early.
            runRootCommand("rm -rf ${shellQuote(stageRoot.absolutePath)}")
            stageRoot.deleteRecursively()
        }
    }

    override suspend fun installModule(zip: File): Result<Unit> = withContext(Dispatchers.IO) {
        backupResult {
            val result = execKsudResult("module install ${shellQuote(zip.absolutePath)}")
            check(result.isSuccess) {
                result.err.joinToString("\n").ifBlank { "module install failed: exit ${result.code}" }
            }
        }
    }

    /** Writes [root]'s contents as ZIP entries at the archive root, skipping symlinks. */
    private fun writeRootLevelZip(root: File, target: File) {
        ZipOutputStream(BufferedOutputStream(FileOutputStream(target))).use { zip ->
            root.walkTopDown()
                .filter { it.isFile && !Files.isSymbolicLink(it.toPath()) }
                .forEach { file ->
                    val name = file.relativeTo(root).invariantSeparatorsPath
                    zip.putNextEntry(ZipEntry(name))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
        }
    }

    private fun describe(result: Shell.Result): String =
        result.err.joinToString(" ").ifBlank { "exit ${result.code}" }

    private companion object {
        const val MODULES_DIR = "/data/adb/modules"
        const val MODULE_PROP = "module.prop"
        val MODULE_ID_PATTERN = Regex("[A-Za-z][A-Za-z0-9._-]*")
    }
}
