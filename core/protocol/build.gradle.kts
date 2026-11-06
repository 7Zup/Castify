// Phone <-> TV wire format. Pure Kotlin, depends only on the domain.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin { jvmToolchain(17) }

dependencies {
    api(project(":core:domain"))
    api(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
}
