package dev.amuse.forge.gateway.config

import dev.amuse.forge.contract.ConfigRoutes
import dev.amuse.forge.contract.MarketIssues
import dev.amuse.forge.contract.RemoteConfig
import dev.amuse.forge.gateway.ForgeLimits
import dev.amuse.forge.gateway.ForgeTokens
import dev.amuse.forge.gateway.JobStore
import dev.amuse.forge.gateway.forgeModule
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConfigApiTest {

    private val tokens = ForgeTokens(client = "client-secret", worker = "worker-secret", admin = "admin-secret")

    private fun ApplicationTestBuilder.start(
        dir: File = Files.createTempDirectory("config-api").toFile(),
        tokens: ForgeTokens = this@ConfigApiTest.tokens,
        limits: ForgeLimits = ForgeLimits(),
    ): HttpClient {
        application { forgeModule(JobStore(dir), tokens, longPollMaxSeconds = 1, config = ConfigStore(dir), forgeLimits = limits) }
        return createClient { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
    }

    private suspend fun HttpClient.putConfig(config: RemoteConfig, token: String = tokens.admin): HttpResponse =
        put(ConfigRoutes.ADMIN_CONFIG) {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(config)
        }

    @Test
    fun `anyone reads the config without a token, the default until one is set`() = testApplication {
        val client = start()
        val response = client.get(ConfigRoutes.CONFIG)
        assertEquals(HttpStatusCode.OK, response.status)
        val config = response.body<RemoteConfig>()
        assertEquals(0, config.configVersion)
        assertNull(config.models)
        assertTrue(assertNotNull(response.headers[HttpHeaders.CacheControl]).contains("max-age"))
    }

    @Test
    fun `only the admin token writes it`() = testApplication {
        val client = start()
        assertEquals(HttpStatusCode.Unauthorized, client.putConfig(ConfigFixtures.full(), token = tokens.client).status)
        assertEquals(HttpStatusCode.Unauthorized, client.putConfig(ConfigFixtures.full(), token = tokens.worker).status)
        assertEquals(HttpStatusCode.OK, client.putConfig(ConfigFixtures.full()).status)
    }

    @Test
    fun `no admin token configured means nobody writes`() = testApplication {
        val client = start(tokens = tokens.copy(admin = ""))
        assertEquals(HttpStatusCode.Unauthorized, client.putConfig(ConfigFixtures.full(), token = "").status)
    }

    @Test
    fun `a written config is served with an etag the app can revalidate`() = testApplication {
        val client = start()
        client.putConfig(ConfigFixtures.full())
        val first = client.get(ConfigRoutes.CONFIG)
        assertEquals(ConfigFixtures.full(), first.body<RemoteConfig>())
        val etag = assertNotNull(first.headers[HttpHeaders.ETag])

        assertEquals(HttpStatusCode.NotModified, client.get(ConfigRoutes.CONFIG) { header(HttpHeaders.IfNoneMatch, etag) }.status)
        client.putConfig(ConfigFixtures.full(version = 2))
        assertEquals(HttpStatusCode.OK, client.get(ConfigRoutes.CONFIG) { header(HttpHeaders.IfNoneMatch, etag) }.status)
    }

    @Test
    fun `an older or equal version is a conflict, so two editors cannot undo each other`() = testApplication {
        val client = start()
        client.putConfig(ConfigFixtures.full(version = 3))
        assertEquals(HttpStatusCode.Conflict, client.putConfig(ConfigFixtures.full(version = 3)).status)
        assertEquals(HttpStatusCode.Conflict, client.putConfig(ConfigFixtures.full(version = 2)).status)
        assertEquals(3, client.get(ConfigRoutes.CONFIG).body<RemoteConfig>().configVersion)
    }

    @Test
    fun `an invalid config is refused with every issue and changes nothing`() = testApplication {
        val client = start()
        val response = client.putConfig(ConfigFixtures.full().copy(models = emptyList(), updateUrl = "http://x"))
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals(2, response.body<MarketIssues>().issues.size)
        assertEquals(0, client.get(ConfigRoutes.CONFIG).body<RemoteConfig>().configVersion)
    }

    @Test
    fun `too big a config is refused`() = testApplication {
        val client = start(limits = ForgeLimits(maxClientBodyBytes = 100))
        assertEquals(HttpStatusCode.PayloadTooLarge, client.putConfig(ConfigFixtures.full()).status)
    }

    @Test
    fun `the config survives a restart, and a broken file falls back to the default`() {
        val dir = Files.createTempDirectory("config-store").toFile()
        runBlocking { ConfigStore(dir).replace(ConfigFixtures.full(version = 4)) }
        assertEquals(4, ConfigStore(dir).current().configVersion)

        File(dir, "config/remote-config.json").writeText("{ not json")
        assertEquals(0, ConfigStore(dir).current().configVersion)
    }
}
