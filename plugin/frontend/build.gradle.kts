dependencies {
    intellijPlatform {
        bundledModule("intellij.platform.rpc")
        bundledModule("intellij.platform.rpc.topics")
        bundledModule("intellij.platform.frontend")
    }
    implementation(project(":shared"))
    testImplementation(kotlin("test"))
}
