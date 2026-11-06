plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// One version for both apps (gradle.properties). 0.5.0 -> 500: the code always grows with it.
val castifyVersion = providers.gradleProperty("castify.version").get()
val castifyVersionCode = castifyVersion.split(".").map(String::toInt).let { (a, b, c) -> a * 10_000 + b * 100 + c }

android {
    namespace = "app.castify.tv"
    compileSdk = 36
    defaultConfig {
        applicationId = "app.castify.tv"
        minSdk = 31
        targetSdk = 36
        versionCode = castifyVersionCode
        versionName = castifyVersion
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging {
        resources.excludes += setOf("META-INF/INDEX.LIST", "META-INF/io.netty.versions.properties", "META-INF/DEPENDENCIES")
    }
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":core:domain"))
    implementation(project(":shared:update"))
    implementation(project(":tv:data"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons) // player control icons
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
}
