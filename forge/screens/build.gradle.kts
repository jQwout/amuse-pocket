// The module the coding agent writes into. Ketoy's embedded compiler turns every
// @KetoyEntryPoint in src/main/kotlin into one signed bundle: build/ketoy-bundles/agent.ktx.
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("dev.ketoy.compiler")
}

android {
    namespace = "dev.amuse.forge.screens"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    // Pinned to the Compose line the Ketoy adapters were generated against.
    implementation(platform("androidx.compose:compose-bom:2024.11.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")

    implementation(platform("dev.ketoy.vm:ketoy-bom:0.4.20-alpha"))
    implementation("dev.ketoy.vm:ketoy-annotations")
    implementation("dev.ketoy.vm:ketoy-runtime")
    implementation("dev.ketoy.vm:ketoy-adapters-material3")
}

ketoy {
    // Library mode: the bundle is a product of this module, not an asset of an app.
    exportFromAppModule.set(false)
    bundleId.set("agent")
    bundleVariant.set("release")
    capabilityRegistryFile.set(rootProject.file("ketoy-capabilities.json"))
    minAppVersion.set(0)
    debugMode.set(true)

    // Ed25519 seed, 32 bytes. Without it the bundle is unsigned and the app refuses it.
    val signingKey = rootProject.file("keys/forge-private.key")
    if (signingKey.exists()) {
        signingKeyFile.set(signingKey)
    } else {
        logger.warn("forge: keys/forge-private.key is missing - the bundle will be UNSIGNED and the app will reject it")
    }
}
