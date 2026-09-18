plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.hilt.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

val webRootDirectory = rootProject.layout.projectDirectory.dir("web")
val webOutputDirectory = layout.projectDirectory.dir("src/main/assets/web")
val defaultNpmExecutable = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
    "npm.cmd"
} else {
    "npm"
}
val npmExecutable = providers.gradleProperty("deviceBridgeNpmExecutable")
    .orElse(defaultNpmExecutable)

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

val npmCi by tasks.registering(Exec::class) {
    group = "build"
    description = "Installs the locked DeviceBridge web build dependencies."
    workingDir(webRootDirectory)
    commandLine(npmExecutable.get(), "ci", "--no-audit", "--no-fund")

    inputs.files(
        webRootDirectory.file("package.json"),
        webRootDirectory.file("package-lock.json"),
    ).withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.dir(webRootDirectory.dir("node_modules"))
}

val buildWebAssets by tasks.registering(Exec::class) {
    group = "build"
    description = "Builds the offline browser shell into Android assets."
    dependsOn(npmCi)
    workingDir(webRootDirectory)
    commandLine(npmExecutable.get(), "run", "build")

    inputs.files(
        webRootDirectory.file("index.html"),
        webRootDirectory.file("package.json"),
        webRootDirectory.file("package-lock.json"),
        webRootDirectory.file("tsconfig.json"),
        webRootDirectory.file("vite.config.ts"),
    ).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir(webRootDirectory.dir("src"))
        .withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.dir(webOutputDirectory)

    doFirst {
        outputs.files.singleFile.deleteRecursively()
    }

    doLast {
        val outputDirectory = outputs.files.singleFile
        val requiredOutputs = listOf(
            outputDirectory.resolve("index.html"),
            outputDirectory.resolve("asset-manifest.json"),
            outputDirectory.resolve("web-manifest.json"),
        )
        val missingOutputs = requiredOutputs.filterNot { it.isFile && it.length() > 0L }
        val generatedAssets = outputDirectory.resolve("assets")
            .listFiles()
            ?.filter { it.isFile }
            .orEmpty()

        if (missingOutputs.isNotEmpty() || generatedAssets.isEmpty()) {
            throw GradleException(
                "Vite web output is incomplete; refusing to package stale Android assets.",
            )
        }
    }
}

tasks.matching {
    it.name != "buildWebAssets" &&
        (
            it.name.contains("assets", ignoreCase = true) ||
                it.name.contains("lint", ignoreCase = true)
        )
}.configureEach {
    dependsOn(buildWebAssets)
}

android {
    namespace = "ru.hznik.devicebridge"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "ru.hznik.devicebridge"
        minSdk = 29
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.hilt.android)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.websockets)
    implementation(libs.kotlinx.serialization.json)
    ksp(libs.hilt.android.compiler)
    ksp(libs.androidx.room.compiler)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.client.core)
    testImplementation(libs.ktor.client.cio)
    testImplementation(libs.ktor.client.websockets)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.ktor.client.cio)
    androidTestImplementation(libs.ktor.client.core)
    androidTestImplementation(libs.ktor.client.websockets)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
