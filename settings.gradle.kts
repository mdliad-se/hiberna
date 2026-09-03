pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositories {
        google(); mavenCentral()
        maven("https://api.xposed.info/")   // Shizuku artifacts
    }
}
rootProject.name = "hiberna"
include(":app")
