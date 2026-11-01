package com.aiopenpocket.di

// @trace cloud-forged-screens-40d702

import android.app.Application
import com.aiopenpocket.BuildConfig
import com.aiopenpocket.agent.core.forge.ForgeGatewayConfig
import com.aiopenpocket.agent.core.forge.ScreenForgeClient
import com.aiopenpocket.agent.core.identity.UserPreferencesRepository
import com.aiopenpocket.data.repository.SettingsRepository
import dev.ketoy.adapters.material3.registerGeneratedAdapters
import dev.ketoy.adapters.material3.registerGeneratedConstructors
import dev.ketoy.capabilities.core.registerCoreCapabilities
import dev.ketoy.runtime.KetoyConfig
import dev.ketoy.runtime.KetoyRuntime
import dev.ketoy.runtime.bundle.KetoyBundleLoader
import dev.ketoy.runtime.capability.CapabilityRegistry
import dev.ketoy.runtime.security.KetoyKeystore
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import io.ktor.client.engine.HttpClientEngine

/**
 * Ketoy host wiring: the runtime that interprets `.ktx` bundles and the client of the forge gateway.
 *
 * One [KetoyRuntime] per process. Its config carries the forge's public key, so a bundle signed with
 * any other key never renders: `nativeFallback` shows instead. Core capabilities are registered the
 * way `ketoy init` does it (dispatchers, flows, storage), because a bundle's manifest may list them
 * even when the screen only keeps local state; app-specific capabilities (ids 0x4000+) are none yet.
 */
@ContributesTo(AppScope::class)
@BindingContainer
object KetoyModule {

  /** Settings-side name of the gateway token; stored next to the model keys, never in BuildConfig. */
  const val TOKEN_PROVIDER = "FORGE"

  /**
   * Where a debug build looks for the gateway when Settings has no address: the host machine as the
   * emulator sees it. A release build has no default - the person types the address.
   */
  val DEBUG_BASE_URL: String = if (BuildConfig.DEBUG) "http://10.0.2.2:8080" else ""

  const val PUBLIC_KEY_ASSET = "ketoy/keys/forge-public.key"

  @Provides
  @SingleIn(AppScope::class)
  fun provideKetoyRuntime(application: Application): KetoyRuntime {
    val capabilities = CapabilityRegistry().apply {
      registerCoreCapabilities(context = application)
    }
    val config = KetoyConfig(
      enableSignatureVerification = true,
      publicKey = KetoyKeystore.loadFromAsset(application, PUBLIC_KEY_ASSET),
    )
    return KetoyRuntime(capabilityRegistry = capabilities, config = config).also { runtime ->
      registerGeneratedAdapters(runtime.adapterRegistry)
      registerGeneratedConstructors(runtime.constructorRegistry)
    }
  }

  @Provides
  @SingleIn(AppScope::class)
  fun provideKetoyBundleLoader(application: Application, runtime: KetoyRuntime): KetoyBundleLoader =
    KetoyBundleLoader(runtime, application)

  /**
   * Same engine as every model client. The address and token are read on every call, so a change in
   * Settings applies at once; an empty token means "not configured" and the client never dials out.
   */
  @Provides
  @SingleIn(AppScope::class)
  fun provideScreenForgeClient(
    engine: HttpClientEngine,
    settingsRepository: SettingsRepository,
    userPreferencesRepository: UserPreferencesRepository,
  ): ScreenForgeClient = ScreenForgeClient(engine) {
    ForgeGatewayConfig(
      baseUrl = userPreferencesRepository.getForgeGatewayUrl().trim().ifBlank { DEBUG_BASE_URL },
      token = settingsRepository.getApiKey(TOKEN_PROVIDER).trim(),
    )
  }
}
