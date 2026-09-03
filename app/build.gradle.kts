plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.jinatra.hiberna"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.jinatra.hiberna"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        jvmToolchain(21)
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
    }
    // buildConfig must be opted in explicitly on AGP 8+ (default flipped to
    // false); HibernaApp.kt reads BuildConfig.DEBUG for the boot assertion.
    buildFeatures { compose = true; buildConfig = true }
    testOptions { unitTests.isIncludeAndroidResources = true }

    sourceSets["main"].kotlin.srcDir("src/main/kotlin")
    sourceSets["test"].kotlin.srcDir("src/test/kotlin")
    sourceSets["androidTest"].kotlin.srcDir("src/androidTest/kotlin")
}

/**
 * The privileged entry point is reached by reflection into a private method,
 * so the compiler cannot see it and R8 does not warn when a `-keep` rule stops
 * matching. `-printseeds` in proguard-rules.pro records what R8 actually kept;
 * this task turns a missing `newProcess` seed into a failed release build
 * instead of an app that installs, launches, and silently does nothing.
 */
val verifyShizukuSeeds = tasks.register("verifyShizukuSeeds") {
    group = "verification"
    description = "Fails the release build if R8 did not seed Shizuku.newProcess."
    dependsOn("minifyReleaseWithR8")
    val seeds = layout.buildDirectory.file("outputs/mapping/release/seeds.txt")
    outputs.upToDateWhen { false }
    doLast {
        val file = seeds.get().asFile
        check(file.isFile) {
            "R8 seeds file missing at ${file.absolutePath}: the -printseeds directive " +
                "in proguard-rules.pro is not being applied"
        }
        val seeded = file.readLines()
        val sdkSeeds = seeded.filter { it.startsWith("rikka.shizuku.") }

        // 1. The privileged entry point itself.
        val newProcess = sdkSeeds.filter {
            Regex("""^rikka\.shizuku\.Shizuku:.*\bnewProcess\b""").containsMatchIn(it)
        }
        check(newProcess.isNotEmpty()) {
            "R8 did not keep rikka.shizuku.Shizuku.newProcess (${sdkSeeds.size} Shizuku seeds " +
                "in ${file.absolutePath}). Every privileged command in this build would fail."
        }

        // 2. Proof the keep rule is what kept it. R8 infers a keep for
        //    getDeclaredMethod("newProcess", ...) from the constant strings in
        //    RealShizukuPlatform, so `newProcess` alone is seeded even with a
        //    dead keep rule - measured, not assumed. waitForTimeout is only
        //    ever *reachable*, never *seeded*, unless the package-wide rule
        //    matches, so it is the honest canary for rule drift.
        val ruleIsLive = sdkSeeds.any { it.contains("waitForTimeout") }
        val classCount = sdkSeeds.map { it.substringBefore(':') }.distinct().size
        check(ruleIsLive && classCount >= 10) {
            "the `-keep class rikka.shizuku.**` rule in proguard-rules.pro matched almost " +
                "nothing: $classCount Shizuku classes and ${sdkSeeds.size} members seeded " +
                "(expect ~22 classes / ~258 members). R8 does not warn about a keep rule " +
                "that stops matching, so this check is the only signal. See ${file.absolutePath}."
        }

        logger.lifecycle(
            "verifyShizukuSeeds: ${newProcess.first()} " +
                "($classCount classes, ${sdkSeeds.size} members seeded)",
        )
    }
}

tasks.matching { it.name == "assembleRelease" || it.name == "bundleRelease" }.configureEach {
    dependsOn(verifyShizukuSeeds)
}

dependencies {
    implementation(libs.core.ktx)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.graphics)
    implementation(libs.compose.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    debugImplementation(libs.compose.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.turbine)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.test.core)

    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.test.junit4)
    androidTestImplementation(libs.test.runner)
    debugImplementation(libs.compose.test.manifest)
}
