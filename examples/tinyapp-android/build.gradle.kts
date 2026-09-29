import com.android.build.api.artifact.SingleArtifact
import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "io.github.olbartek.agentctl.examples.tinyapp.android"
    compileSdk = libs.versions.sdk.compile.get().toInt()
    defaultConfig {
        applicationId = "io.github.olbartek.agentctl.examples.tinyapp"
        minSdk = libs.versions.sdk.min.get().toInt()
        targetSdk = libs.versions.sdk.compile.get().toInt()
        versionCode = 1
        versionName = "0.1"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // TinyApp's screens and store, on agentctl-core alone: what ships.
    implementation(project(":examples:tinyapp"))
    implementation(libs.kotlinx.coroutines.android)
    // The bridge and the config it runs on, which brings agentctl-runtime: debug builds only.
    debugImplementation(project(":agentctl-bridge"))
    debugImplementation(project(":examples:tinyapp-config"))
}

// A release build must carry none of AgentCtl but agentctl-core: not the runtime, the CLI or the test support, not
// the bridge, and not TinyApp's config. The check reads the release APK's dex files for their classes; it is part
// of `check`, so `./gradlew build` (and CI) runs it. TinyApp's own classes must be there under their names, or the
// check would pass on a shrunk, renamed build having proved nothing.
androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        val name = "verify${variant.name.replaceFirstChar { it.uppercase() }}LeavesOutAgentCtl"
        tasks.register<VerifyApkClasses>(name) {
            apks.set(variant.artifacts.get(SingleArtifact.APK))
            forbidden.set(
                listOf("runtime", "cli", "testsupport", "bridge").map { "io/github/olbartek/agentctl/$it/" } +
                    "io/github/olbartek/agentctl/examples/tinyapp/TinyAppConfig",
            )
            required.set(listOf("io/github/olbartek/agentctl/examples/tinyapp/TinyApp;"))
            report.set(layout.buildDirectory.file("reports/$name.txt"))
        }
    }
}

tasks.named("check") { dependsOn(tasks.withType<VerifyApkClasses>()) }

/** Fails when an APK's dex files hold a class under one of [forbidden], or none under one of [required]. */
abstract class VerifyApkClasses : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val apks: DirectoryProperty

    /** Class name prefixes, in the dex's `a/b/C` form. */
    @get:Input
    abstract val forbidden: ListProperty<String>

    @get:Input
    abstract val required: ListProperty<String>

    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun verify() {
        val files = apks.get().asFile.listFiles { file -> file.extension == "apk" }.orEmpty().sortedBy { it.name }
        if (files.isEmpty()) throw GradleException("no APK in ${apks.get().asFile}")
        val problems = mutableListOf<String>()
        for (apk in files) {
            // A dex file names every class it defines or references as an `La/b/C;` descriptor in its string table.
            val dex = ZipFile(apk).use { zip ->
                zip.entries().asSequence()
                    .filter { it.name.matches(Regex("classes\\d*\\.dex")) }
                    .map { String(zip.getInputStream(it).readBytes(), Charsets.ISO_8859_1) }
                    .toList()
            }
            forbidden.get().filter { prefix -> dex.any { "L$prefix" in it } }.mapTo(problems) { "${apk.name} has $it classes" }
            required.get().filter { prefix -> dex.none { "L$prefix" in it } }.mapTo(problems) { "${apk.name} has no $it class" }
        }
        if (problems.isNotEmpty()) throw GradleException(problems.joinToString("\n"))
        report.get().asFile.writeText(files.joinToString("\n") { "${it.name}: none of ${forbidden.get()}" } + "\n")
    }
}
