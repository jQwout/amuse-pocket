package dev.amuse.forge.gateway.config

import dev.amuse.forge.contract.RemoteConfig
import dev.amuse.forge.gateway.JobStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File

// @trace remote-config-2403cd

/** What a write came to; see [ConfigStore.replace]. */
sealed interface ReplaceResult {
    data class Ok(val config: RemoteConfig) : ReplaceResult
    /** [current] already has this version or a newer one. */
    data class Stale(val current: RemoteConfig) : ReplaceResult
}

/**
 * The one remote config, in memory and in `dataDir/config/remote-config.json`. Reads are lock-free
 * (every app polls this); writes take a mutex so two editors cannot both win. The served bytes and
 * their ETag are computed once per write, not per request.
 */
class ConfigStore(dataDir: File) {
    private val file = File(File(dataDir, "config"), FILE_NAME)
    private val disk = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }
    private val mutex = Mutex()

    @Volatile
    private var served: Served = Served.of(load())

    fun current(): RemoteConfig = served.config

    /** The compact JSON the API sends and its quoted ETag. */
    fun served(): Pair<String, String> = served.body to served.etag

    suspend fun replace(next: RemoteConfig): ReplaceResult = mutex.withLock {
        val now = served.config
        if (next.configVersion <= now.configVersion) return@withLock ReplaceResult.Stale(now)
        file.parentFile.mkdirs()
        val tmp = File(file.parentFile, "$FILE_NAME.tmp")
        tmp.writeText(disk.encodeToString(RemoteConfig.serializer(), next))
        if (!tmp.renameTo(file)) {
            file.writeText(tmp.readText())
            tmp.delete()
        }
        served = Served.of(next)
        ReplaceResult.Ok(next)
    }

    /** A missing file is the default; a broken one too, loudly, because the app must keep working. */
    private fun load(): RemoteConfig {
        if (!file.isFile) return RemoteConfig()
        return runCatching { disk.decodeFromString(RemoteConfig.serializer(), file.readText()) }
            .onFailure { log.error("Remote config at {} is unreadable, serving the default", file.absolutePath, it) }
            .getOrDefault(RemoteConfig())
    }

    private class Served(val config: RemoteConfig, val body: String, val etag: String) {
        companion object {
            private val wire = Json { encodeDefaults = true }

            fun of(config: RemoteConfig): Served {
                val body = wire.encodeToString(RemoteConfig.serializer(), config)
                return Served(config, body, "\"${JobStore.sha256Hex(body.toByteArray()).take(32)}\"")
            }
        }
    }

    private companion object {
        const val FILE_NAME = "remote-config.json"
        val log = LoggerFactory.getLogger(ConfigStore::class.java)
    }
}
