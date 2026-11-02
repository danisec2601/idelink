dependencies {
    intellijPlatform {
        bundledPlugin("PythonCore")
    }
    implementation(project(":backend"))
    testImplementation(kotlin("test"))
}
