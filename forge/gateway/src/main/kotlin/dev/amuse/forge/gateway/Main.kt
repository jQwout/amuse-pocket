package dev.amuse.forge.gateway

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import java.io.File

/**
 * Entry point. Everything comes from the environment so the same jar runs on a laptop
 * (`./gradlew :gateway:run`) and on Fly.io (`fly secrets set ...`):
 *
 * - `PORT` (default 8080)
 * - `FORGE_DATA_DIR` (default `./data`; on Fly the mounted volume)
 * - `FORGE_CLIENT_TOKEN`, `FORGE_WORKER_TOKEN` - empty means that side is locked out
 */
fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    val dataDir = File(System.getenv("FORGE_DATA_DIR") ?: "data")
    val tokens = ForgeTokens(
        client = System.getenv("FORGE_CLIENT_TOKEN").orEmpty(),
        worker = System.getenv("FORGE_WORKER_TOKEN").orEmpty(),
    )
    if (tokens.client.isBlank()) System.err.println("forge gateway: FORGE_CLIENT_TOKEN is empty - the app side is locked out")
    if (tokens.worker.isBlank()) System.err.println("forge gateway: FORGE_WORKER_TOKEN is empty - the worker side is locked out")
    val store = JobStore(dataDir)
    println("forge gateway: listening on :$port, data in ${dataDir.absolutePath}")
    embeddedServer(Netty, port = port, host = "0.0.0.0") {
        forgeModule(store, tokens)
    }.start(wait = true)
}
