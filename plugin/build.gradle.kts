import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.extensions.IntelliJPlatformDependenciesExtension
import org.jetbrains.intellij.platform.gradle.models.ProductRelease
import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginStructureTask
import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask.FailureLevel
import org.jetbrains.intellij.platform.gradle.tasks.aware.SplitModeAware

group = "io.github.danisec2601"
version = "0.5.2"

plugins {
    id("org.jetbrains.intellij.platform")
    id("org.jetbrains.kotlin.jvm")
    id("rpc") apply false
    id("org.jetbrains.kotlin.plugin.serialization") apply false
    id("dev.detekt") apply false
}

// -PideDir= (blank) switches back to the download, e.g. in CI.
val ideDir = providers.gradleProperty("ideDir").filter { it.isNotBlank() }
val platformVersion = providers.gradleProperty("platformVersion")

// Extra installed IDEs for the Plugin Verifier, comma-separated (e.g. a local GoLand); unset in public builds.
val verifyIdeDirs = providers.gradleProperty("verifyIdeDirs")
    .map { dirs -> dirs.split(',').map(String::trim).filter(String::isNotEmpty) }
    .orElse(emptyList())

// Releases target stable IDEs (D-080); -PallowPrerelease builds against an EAP, Beta or RC anyway.
val allowPrerelease = providers.gradleProperty("allowPrerelease").isPresent

fun refusePrerelease(what: String, suffix: String) {
    if (suffix.isNotBlank() && !allowPrerelease) {
        throw GradleException("$what is a pre-release ($suffix); build against a stable IDE, or pass -PallowPrerelease")
    }
}

// One IDE for every module: an installed one when ideDir is set, else PyCharm platformVersion from JetBrains.
fun IntelliJPlatformDependenciesExtension.platform() {
    if (ideDir.isPresent) {
        val dir = file(ideDir.get())
        if (!dir.isDirectory) {
            throw GradleException("ideDir=$dir is not a directory: pass -PideDir=<an installed IDE>, or -PideDir= " +
                "(empty) to download PyCharm ${platformVersion.get()}")
        }
        @Suppress("UNCHECKED_CAST")
        val info = groovy.json.JsonSlurper().parse(dir.resolve("product-info.json")) as Map<String, Any?>
        refusePrerelease("ideDir=$dir", info["versionSuffix"]?.toString().orEmpty())
        local(dir)
    } else {
        pycharm(platformVersion)
    }
}

subprojects {
    apply(plugin = "org.jetbrains.intellij.platform.module")
    apply(plugin = "rpc")
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "org.jetbrains.kotlin.plugin.serialization")
    apply(plugin = "dev.detekt")
    extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> { jvmToolchain(25) }
    dependencies.extensions.configure<IntelliJPlatformDependenciesExtension> { platform() }
    extensions.configure<dev.detekt.gradle.extensions.DetektExtension> {
        config.setFrom(rootProject.file("config/detekt.yml"))
        buildUponDefaultConfig = true
        baseline = rootProject.file("config/detekt-baseline-${project.name}.xml")
    }
    // The IDE launches with this flag too; without it the JDK warns on the first libc downcall.
    tasks.withType<Test>().configureEach { jvmArgs("--enable-native-access=ALL-UNNAMED") }
}

kotlin { jvmToolchain(25) }

dependencies {
    intellijPlatform {
        platform()
        pluginVerifier()
        pluginModule(implementation(project(":shared")))
        pluginModule(implementation(project(":frontend")))
        pluginModule(implementation(project(":backend")))
        pluginModule(implementation(project(":terminal")))
        pluginModule(implementation(project(":python")))
    }
}

intellijPlatform {
    splitMode = true
    pluginInstallationTarget = SplitModeAware.PluginInstallationTarget.BOTH
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "262.11879"
            untilBuild = "263.7307.*"
        }
    }
    buildSearchableOptions = false
    pluginVerification {
        ides {
            val channel = providers.gradleProperty("verifyChannel")
            when {
                channel.isPresent -> latest {
                    types = listOf(IntelliJPlatformType.PyCharm)
                    channels = listOf(ProductRelease.Channel.valueOf(channel.get()))
                    sinceBuild = "262"
                    untilBuild = ""
                }
                ideDir.isPresent -> local(ideDir)
                else -> create(IntelliJPlatformType.PyCharm, platformVersion)
            }
            verifyIdeDirs.get().forEach { local(it) }
        }
        // Internal and experimental API use is deliberate (docs/decisions.md D-046): reported, not failing.
        failureLevel = listOf(
            FailureLevel.COMPATIBILITY_PROBLEMS,
            FailureLevel.INVALID_PLUGIN,
            FailureLevel.MISSING_DEPENDENCIES,
            FailureLevel.OVERRIDE_ONLY_API_USAGES,
            FailureLevel.NON_EXTENDABLE_API_USAGES,
        )
    }
}

tasks.named<VerifyPluginStructureTask>("verifyPluginStructure") { ignoreWarnings = false }
