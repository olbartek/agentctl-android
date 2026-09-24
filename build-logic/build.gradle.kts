plugins {
    `kotlin-dsl`
}

dependencies {
    compileOnly(libs.kotlin.gradle.plugin)
    compileOnly(libs.android.gradle.plugin)
}

gradlePlugin {
    plugins {
        register("jvmLibrary") {
            id = "agentctl.jvm.library"
            implementationClass = "JvmLibraryConventionPlugin"
        }
        register("published") {
            id = "agentctl.published"
            implementationClass = "PublishedConventionPlugin"
        }
    }
}
