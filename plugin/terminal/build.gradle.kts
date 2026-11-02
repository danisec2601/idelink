dependencies {
    intellijPlatform {
        bundledModule("intellij.platform.rpc")
        bundledModule("intellij.platform.rpc.topics")
        bundledModule("intellij.platform.frontend")
        bundledPlugin("org.jetbrains.plugins.terminal")
        bundledModule("intellij.terminal.frontend")
    }
    implementation(project(":shared"))
    testImplementation(kotlin("test"))
}
