package ru.hznik.devicebridge.release

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseBuildConfigurationContractTest {

    @Test
    fun releaseSigningUsesExternalPropertiesAndNeverFallsBackToDebugKey() {
        val buildFile = Files.readString(Path.of("build.gradle.kts"))

        REQUIRED_SIGNING_PROPERTIES.forEach { property ->
            assertTrue(
                "Missing external release signing property: $property",
                buildFile.contains("providers.gradleProperty(\"$property\")"),
            )
        }
        assertTrue(buildFile.contains("isDebuggable = false"))
        assertTrue(buildFile.contains("verifyReleaseSigningConfiguration"))
        assertFalse(buildFile.contains("signingConfigs.getByName(\"debug\")"))
        assertFalse(buildFile.contains("signingConfig = signingConfigs.named(\"debug\")"))
    }

    @Test
    fun signingMaterialIsIgnoredByGit() {
        val gitIgnore = Files.readString(Path.of("../.gitignore"))

        assertTrue(gitIgnore.lineSequence().any { it.trim() == "*.jks" })
        assertTrue(gitIgnore.lineSequence().any { it.trim() == "*.keystore" })
    }

    @Test
    fun releaseMetadataTaskCapturesCandidateIdentityAndMonotonicVersion() {
        val buildFile = Files.readString(Path.of("build.gradle.kts"))

        assertTrue(buildFile.contains("generateReleaseMetadata"))
        assertTrue(buildFile.contains("deviceBridgePreviousVersionCode"))
        assertTrue(buildFile.contains("gitCommit"))
        assertTrue(buildFile.contains("gitWorktreeClean"))
        assertTrue(buildFile.contains("apkSha256"))
        assertTrue(buildFile.contains("webAssetVersion"))
    }

    @Test
    fun releaseCandidateVersionCanBeSuppliedExternallyForMonotonicUpdate() {
        val buildFile = Files.readString(Path.of("build.gradle.kts"))

        assertTrue(buildFile.contains("providers.gradleProperty(\"deviceBridgeVersionCode\")"))
        assertTrue(buildFile.contains("providers.gradleProperty(\"deviceBridgeVersionName\")"))
    }

    @Test
    fun androidRuntimeDependenciesUseCommittedGradleLockState() {
        val buildFile = Files.readString(Path.of("build.gradle.kts"))
        val lockFile = Path.of("gradle.lockfile")

        assertTrue(buildFile.contains("dependencyLocking"))
        assertTrue(buildFile.contains("lockAllConfigurations()"))
        assertTrue(Files.exists(lockFile))
        val lockState = Files.readString(lockFile)
        assertTrue(lockState.contains("io.ktor:ktor-server-core-jvm:"))
        assertTrue(lockState.contains("org.jetbrains.kotlin:kotlin-stdlib:"))
    }

    private companion object {
        val REQUIRED_SIGNING_PROPERTIES = listOf(
            "deviceBridgeReleaseStoreFile",
            "deviceBridgeReleaseStorePassword",
            "deviceBridgeReleaseKeyAlias",
            "deviceBridgeReleaseKeyPassword",
        )
    }
}
