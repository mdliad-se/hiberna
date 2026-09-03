pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
plugins {
    // Needed so Gradle can auto-provision a JDK 17 toolchain (the build's
    // only local JDKs are a JetBrains JBR 21 and an Oracle JRE 8; see
    // `kotlin { jvmToolchain(17) }` in app/build.gradle.kts). Not in the
    // brief's literal settings.gradle.kts; added because the build would
    // not otherwise resolve a toolchain on this machine.
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.9.0"
}
dependencyResolutionManagement {
    repositories {
        google(); mavenCentral()
        maven("https://api.xposed.info/")   // Shizuku artifacts
    }
}
rootProject.name = "hiberna"
include(":app")
