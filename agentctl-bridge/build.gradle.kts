plugins {
    alias(libs.plugins.android.library)
    `maven-publish`
}

android {
    namespace = "io.github.olbartek.agentctl.bridge"
    compileSdk = libs.versions.sdk.compile.get().toInt()
    defaultConfig {
        minSdk = libs.versions.sdk.min.get().toInt()
        consumerProguardFiles("consumer-rules.pro")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    publishing {
        singleVariant("release") { withSourcesJar() }
    }
}

kotlin {
    explicitApi()
}

dependencies {
    api(project(":agentctl-runtime"))
    implementation(libs.kotlinx.coroutines.android)
    // Whether a Compose UI is idle (ComposeUI): used only when the app has Compose, so never a dependency of the app.
    compileOnly(platform(libs.compose.bom))
    compileOnly(libs.compose.runtime)
}

// Published like the JVM modules (JitPack builds `publishToMavenLocal`), from the release variant.
afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("maven") {
                from(components["release"])
                artifactId = project.name
            }
        }
    }
}
