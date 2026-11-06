// Phone adapters: WebView sniffer, NSD discovery, LAN client, preferences.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "app.castify.phone.data"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin { jvmToolchain(17) }

dependencies {
    api(project(":core:domain"))
    implementation(project(":core:protocol"))
    implementation(libs.kotlinx.coroutines.android)
}
