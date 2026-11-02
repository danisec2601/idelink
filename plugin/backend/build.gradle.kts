dependencies {
    intellijPlatform {
        bundledModule("intellij.platform.kernel.backend")
        bundledModule("intellij.platform.rpc.backend")
        bundledModule("intellij.platform.rpc.topics")
        bundledModule("intellij.platform.backend")
    }
    implementation(project(":shared"))
    testImplementation(kotlin("test"))
}
