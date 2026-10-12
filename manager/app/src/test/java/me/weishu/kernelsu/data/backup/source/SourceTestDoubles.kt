package me.weishu.kernelsu.data.backup.source

import java.io.File

/** Scriptable [PartitionAccess] backed by an in-memory partition table. */
class FakePartitionAccess(
    private val partitions: List<String> = listOf("boot"),
    private val deviceInfo: DeviceInfo = DeviceInfo("device", "fingerprint"),
    var availableFailure: Throwable? = null,
    var readFailure: Throwable? = null,
    var writeFailure: Throwable? = null,
) : PartitionAccess {

    /** Raw bytes reported for each partition. */
    val partitionBytes: MutableMap<String, ByteArray> = LinkedHashMap()

    /** The partition and payload of the last [writePartition] call. */
    var lastWritten: Pair<String, ByteArray>? = null
        private set

    override suspend fun availablePartitions(): Result<List<String>> =
        availableFailure?.let { Result.failure(it) } ?: Result.success(partitions)

    override suspend fun readPartition(partition: String, target: File): Result<Unit> {
        readFailure?.let { return Result.failure(it) }
        val bytes = partitionBytes[partition]
            ?: return Result.failure(NoSuchElementException("missing partition: $partition"))
        target.writeBytes(bytes)
        return Result.success(Unit)
    }

    override suspend fun writePartition(partition: String, source: File): Result<Unit> {
        writeFailure?.let { return Result.failure(it) }
        lastWritten = partition to source.readBytes()
        return Result.success(Unit)
    }

    override suspend fun deviceInfo(): Result<DeviceInfo> = Result.success(deviceInfo)
}

/** Scriptable [ModuleAccess] over an in-memory module set. */
class FakeModuleAccess(
    private val modules: List<InstalledModule> = emptyList(),
    private val packedBytes: ByteArray = "module-zip".toByteArray(),
    var installFailure: Throwable? = null,
    var inspectFailure: Throwable? = null,
    private val installerModules: Map<String, InstalledModule> = emptyMap(),
) : ModuleAccess {

    /** The module id of the last [packModule] call. */
    var lastPacked: String? = null
        private set

    /** The file of the last [inspectInstaller] call. */
    var lastInspected: File? = null
        private set

    /** The bytes handed to the last [installModule] call. */
    var lastInstalled: ByteArray? = null
        private set

    override suspend fun installedModules(): Result<List<InstalledModule>> = Result.success(modules)

    override suspend fun inspectInstaller(zip: File): Result<InstalledModule> {
        lastInspected = zip
        inspectFailure?.let { return Result.failure(it) }
        val module = installerModules[zip.name] ?: modules.firstOrNull()
        return module?.let { Result.success(it) }
            ?: Result.failure(IllegalArgumentException("no module.prop in ${zip.name}"))
    }

    override suspend fun packModule(moduleId: String, target: File): Result<Unit> {
        lastPacked = moduleId
        target.writeBytes(packedBytes)
        return Result.success(Unit)
    }

    override suspend fun installModule(zip: File): Result<Unit> {
        installFailure?.let { return Result.failure(it) }
        lastInstalled = zip.readBytes()
        return Result.success(Unit)
    }
}

/** Scriptable [AllowlistAccess] returning a fixed document. */
class FakeAllowlistAccess(
    private val document: me.weishu.kernelsu.data.backup.AllowlistDocument,
    var applyFailure: Throwable? = null,
) : AllowlistAccess {

    /** The document handed to the last [applyDocument] call. */
    var lastApplied: me.weishu.kernelsu.data.backup.AllowlistDocument? = null
        private set

    override suspend fun exportDocument(): Result<me.weishu.kernelsu.data.backup.AllowlistDocument> =
        Result.success(document)

    override suspend fun applyDocument(
        document: me.weishu.kernelsu.data.backup.AllowlistDocument,
    ): Result<Unit> {
        applyFailure?.let { return Result.failure(it) }
        lastApplied = document
        return Result.success(Unit)
    }
}

/** Creates a throwaway directory that is removed when the JVM exits. */
fun newTempDir(prefix: String = "backup-test"): File =
    File.createTempFile(prefix, "").let { probe ->
        probe.delete()
        probe.mkdirs()
        probe.deleteOnExit()
        probe
    }
