import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.hilt.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

abstract class VerifyReleaseSigningConfigurationTask : org.gradle.api.DefaultTask() {
    @get:org.gradle.api.tasks.Input
    abstract val missingPropertyNames: org.gradle.api.provider.ListProperty<String>

    @get:org.gradle.api.tasks.Input
    @get:org.gradle.api.tasks.Optional
    abstract val storeFilePath: org.gradle.api.provider.Property<String>

    @org.gradle.api.tasks.TaskAction
    fun verifySigningConfiguration() {
        val missing = missingPropertyNames.get()
        if (missing.isNotEmpty()) {
            throw GradleException(
                "Release distribution is blocked. Supply external Gradle properties: " +
                    missing.joinToString() +
                    ". Never commit signing values or use the debug keystore.",
            )
        }

        val configuredStoreFile = File(storeFilePath.get())
        if (!configuredStoreFile.isFile) {
            throw GradleException(
                "Release distribution is blocked because the configured keystore does not exist.",
            )
        }
    }
}

abstract class GenerateReleaseMetadataTask : org.gradle.api.DefaultTask() {
    @get:org.gradle.api.tasks.Input
    abstract val versionCode: org.gradle.api.provider.Property<Int>

    @get:org.gradle.api.tasks.Input
    abstract val versionName: org.gradle.api.provider.Property<String>

    @get:org.gradle.api.tasks.Input
    abstract val gitCommit: org.gradle.api.provider.Property<String>

    @get:org.gradle.api.tasks.Input
    abstract val gitWorktreeClean: org.gradle.api.provider.Property<Boolean>

    @get:org.gradle.api.tasks.Input
    @get:org.gradle.api.tasks.Optional
    abstract val previousVersionCode: org.gradle.api.provider.Property<String>

    @get:org.gradle.api.tasks.Input
    abstract val releaseSigningConfigured: org.gradle.api.provider.Property<Boolean>

    @get:org.gradle.api.tasks.InputDirectory
    @get:org.gradle.api.tasks.PathSensitive(org.gradle.api.tasks.PathSensitivity.RELATIVE)
    abstract val apkDirectory: org.gradle.api.file.DirectoryProperty

    @get:org.gradle.api.tasks.InputFile
    @get:org.gradle.api.tasks.PathSensitive(org.gradle.api.tasks.PathSensitivity.RELATIVE)
    abstract val webManifestFile: org.gradle.api.file.RegularFileProperty

    @get:org.gradle.api.tasks.OutputFile
    abstract val reportFile: org.gradle.api.file.RegularFileProperty

    @org.gradle.api.tasks.TaskAction
    fun generateMetadata() {
        val candidateVersionCode = versionCode.get()
        val candidateVersionName = versionName.get().trim()
        require(candidateVersionCode > 0) { "versionCode must be positive." }
        require(candidateVersionName.isNotEmpty()) { "versionName must not be empty." }
        verifyMonotonicVersion(candidateVersionCode)

        val commit = gitCommit.get().trim()
        require(COMMIT_SHA.matches(commit)) { "Git commit must be a full 40-character SHA." }

        val apkCandidates = apkDirectory.get().asFile
            .listFiles { file -> file.isFile && file.extension.equals("apk", ignoreCase = true) }
            .orEmpty()
            .sortedBy { it.name }
        require(apkCandidates.size == 1) {
            "Expected exactly one release APK, found: " +
                apkCandidates.joinToString { it.name }.ifEmpty { "none" }
        }
        val apk = apkCandidates.single()
        require(apk.length() > 0L) { "Release APK must not be empty." }

        val webManifest = webManifestFile.get().asFile.readText()
        val webAssetVersion = WEB_ASSET_VERSION
            .find(webManifest)
            ?.groupValues
            ?.get(1)
            ?: error("web-manifest.json does not contain a valid webAssetVersion.")
        val apkSha256 = apk.inputStream().buffered().use { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
            digest.digest().joinToString("") { byte -> "%02x".format(byte) }
        }
        val signingConfigured = releaseSigningConfigured.get()
        val worktreeClean = gitWorktreeClean.get()
        val status = when {
            !worktreeClean && !signingConfigured -> "BLOCKED_DIRTY_UNSIGNED"
            !worktreeClean -> "BLOCKED_DIRTY"
            signingConfigured -> "SIGNED_CANDIDATE_REQUIRES_APKSIGNER_VERIFICATION"
            else -> "BLOCKED_UNSIGNED"
        }

        val output = reportFile.get().asFile
        output.parentFile.mkdirs()
        output.writeText(
            """
            {
              "artifactFile": "${json(apk.name)}",
              "apkSha256": "$apkSha256",
              "gitCommit": "$commit",
              "gitWorktreeClean": $worktreeClean,
              "versionCode": $candidateVersionCode,
              "versionName": "${json(candidateVersionName)}",
              "webAssetVersion": "${json(webAssetVersion)}",
              "distributionStatus": "$status"
            }
            """.trimIndent() + "\n",
        )
    }

    private fun verifyMonotonicVersion(candidate: Int) {
        val previousText = previousVersionCode.orNull?.trim()
        if (previousText == null) {
            require(candidate == 1) {
                "deviceBridgePreviousVersionCode is required when versionCode is greater than 1."
            }
            return
        }
        val previous = previousText.toIntOrNull()
            ?: error("deviceBridgePreviousVersionCode must be a positive integer.")
        require(previous > 0 && candidate > previous) {
            "versionCode $candidate must be greater than previous versionCode $previous."
        }
    }

    private fun json(value: String): String = value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")

    private companion object {
        val COMMIT_SHA = Regex("^[a-fA-F0-9]{40}$")
        val WEB_ASSET_VERSION =
            Regex("\\\"webAssetVersion\\\"\\s*:\\s*\\\"(sha256-[a-f0-9]{16})\\\"")
    }
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
val releaseSigningProperties = linkedMapOf(
    "deviceBridgeReleaseStoreFile" to
        providers.gradleProperty("deviceBridgeReleaseStoreFile"),
    "deviceBridgeReleaseStorePassword" to
        providers.gradleProperty("deviceBridgeReleaseStorePassword"),
    "deviceBridgeReleaseKeyAlias" to
        providers.gradleProperty("deviceBridgeReleaseKeyAlias"),
    "deviceBridgeReleaseKeyPassword" to
        providers.gradleProperty("deviceBridgeReleaseKeyPassword"),
)
val hasReleaseSigningConfiguration = releaseSigningProperties.values.all { it.isPresent }
val deviceBridgeVersionCode = providers.gradleProperty("deviceBridgeVersionCode")
    .map(String::toInt)
    .orElse(1)
val deviceBridgeVersionName = providers.gradleProperty("deviceBridgeVersionName")
    .orElse("1.0")
val gitWorktreeCleanProvider = providers.exec {
    commandLine(
        "git",
        "status",
        "--porcelain",
        "--untracked-files=no",
        "--",
        "app",
        "web",
        "build.gradle.kts",
        "settings.gradle.kts",
        "gradle",
        "gradle.properties",
    )
    workingDir(rootProject.projectDir)
}.standardOutput.asText.map { it.isBlank() }

dependencyLocking {
    lockAllConfigurations()
}

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

val verifyReleasePolicy by tasks.registering(Exec::class) {
    group = "verification"
    description = "Scans production Android/web sources and bundled web assets."
    dependsOn(buildWebAssets)
    workingDir(webRootDirectory)
    commandLine(npmExecutable.get(), "run", "verify:release-policy")
    inputs.files(
        webRootDirectory.file("package.json"),
        webRootDirectory.file("scripts/release-policy-scan.mjs"),
        webRootDirectory.file("scripts/release-policy-scan.test.mjs"),
        layout.projectDirectory.file("build.gradle.kts"),
        rootProject.layout.projectDirectory.file("settings.gradle.kts"),
        rootProject.layout.projectDirectory.file("gradle.properties"),
        rootProject.layout.projectDirectory.file("gradle/libs.versions.toml"),
    ).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir(webRootDirectory.dir("src"))
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir(layout.projectDirectory.dir("src/main"))
        .withPathSensitivity(PathSensitivity.RELATIVE)
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
        versionCode = deviceBridgeVersionCode.get()
        versionName = deviceBridgeVersionName.get()

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasReleaseSigningConfiguration) {
            create("release") {
                storeFile = rootProject.file(
                    releaseSigningProperties.getValue("deviceBridgeReleaseStoreFile").get(),
                )
                storePassword =
                    releaseSigningProperties.getValue("deviceBridgeReleaseStorePassword").get()
                keyAlias = releaseSigningProperties.getValue("deviceBridgeReleaseKeyAlias").get()
                keyPassword =
                    releaseSigningProperties.getValue("deviceBridgeReleaseKeyPassword").get()
            }
        }
    }

    buildTypes {
        release {
            isDebuggable = false
            signingConfig = signingConfigs.findByName("release")
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

val verifyReleaseSigningConfiguration by tasks.registering(
    VerifyReleaseSigningConfigurationTask::class,
) {
    group = "verification"
    description = "Fails unless external release signing properties are complete and usable."
    missingPropertyNames.set(
        releaseSigningProperties
            .filterValues { !it.isPresent }
            .keys
            .toList(),
    )
    releaseSigningProperties.getValue("deviceBridgeReleaseStoreFile").orNull?.let {
        storeFilePath.set(rootProject.file(it).absolutePath)
    }
}

tasks.matching { it.name == "assembleRelease" }.configureEach {
    mustRunAfter(verifyReleaseSigningConfiguration)
}

val generateReleaseMetadata by tasks.registering(GenerateReleaseMetadataTask::class) {
    group = "verification"
    description = "Writes commit, version, APK checksum, and web asset identity."
    dependsOn("assembleRelease")
    versionCode.set(deviceBridgeVersionCode)
    versionName.set(deviceBridgeVersionName)
    gitCommit.set(
        providers.environmentVariable("GIT_COMMIT").orElse(
            providers.exec {
                commandLine("git", "rev-parse", "HEAD")
                workingDir(rootProject.projectDir)
            }.standardOutput.asText.map { it.trim() },
        ),
    )
    gitWorktreeClean.set(gitWorktreeCleanProvider)
    providers.gradleProperty("deviceBridgePreviousVersionCode").orNull?.let {
        previousVersionCode.set(it)
    }
    releaseSigningConfigured.set(hasReleaseSigningConfiguration)
    apkDirectory.set(layout.buildDirectory.dir("outputs/apk/release"))
    webManifestFile.set(webOutputDirectory.file("web-manifest.json"))
    reportFile.set(
        layout.buildDirectory.file("reports/release/devicebridge-release-metadata.json"),
    )
}

tasks.register("assembleDistributionRelease") {
    group = "distribution"
    description = "Builds a signed release APK and blocks when signing is not configured."
    dependsOn(verifyReleaseSigningConfiguration)
    dependsOn(generateReleaseMetadata)
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
