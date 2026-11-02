@file:Suppress("UnstableApiUsage")

import org.jetbrains.intellij.platform.gradle.extensions.intellijPlatform

pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        maven("https://packages.jetbrains.team/maven/p/ij/intellij-dependencies/")
    }
    plugins {
        // rpc tracks the Kotlin version; 2.4.0-RC-0.1 is the newest published (2026-05) — 2026.3 untested by JetBrains' docs.
        id("rpc") version "2.4.0-RC-0.1"
        id("org.jetbrains.kotlin.jvm") version "2.4.0"
        id("org.jetbrains.kotlin.plugin.serialization") version "2.4.0"
        // detekt runs its own kotlin-compiler (2.4.0 for alpha.5): move both versions together.
        id("dev.detekt") version "2.0.0-alpha.5"
    }
}

plugins {
    id("org.jetbrains.intellij.platform.settings") version "2.19.0"
}

rootProject.name = "idelink"

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        intellijPlatform {
            defaultRepositories()
        }
    }
}

include("shared")
include("frontend")
include("backend")
include("terminal")
include("python")
