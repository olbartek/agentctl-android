import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import java.util.zip.ZipFile
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.register
import org.gradle.kotlin.dsl.withType

/**
 * What an example app's release APK must leave out, and what it must keep. Class names are prefixes in the dex's
 * `a/b/C` form.
 */
abstract class ReleaseLeavesOutAgentCtlExtension {
    /** Besides AgentCtl's runtime, CLI, test support and bridge, which are always forbidden: the app's own config. */
    abstract val forbidden: ListProperty<String>

    /** Classes the app ships, so the check cannot pass on a shrunk, renamed build having proved nothing. */
    abstract val required: ListProperty<String>
}

/**
 * `agentctl.release.check`: a release build of an example app must carry none of AgentCtl but agentctl-core — not the
 * runtime, the CLI or the test support, not the bridge, and not the app's AgentCtl config, which are all
 * `debugImplementation` dependencies, as a host's should be.
 *
 * Registers `verify<Variant>LeavesOutAgentCtl` for every release variant, which reads the APK's dex files for their
 * classes, and makes `check` (so `./gradlew build`, and CI) depend on it.
 */
class ReleaseLeavesOutAgentCtlPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        val extension = extensions.create<ReleaseLeavesOutAgentCtlExtension>("releaseLeavesOutAgentCtl")
        pluginManager.withPlugin("com.android.application") {
            val components = extensions.getByType<ApplicationAndroidComponentsExtension>()
            components.onVariants(components.selector().withBuildType("release")) { variant ->
                val name = "verify${variant.name.replaceFirstChar { it.uppercase() }}LeavesOutAgentCtl"
                tasks.register<VerifyApkClasses>(name) {
                    apks.set(variant.artifacts.get(SingleArtifact.APK))
                    forbidden.set(ALWAYS_FORBIDDEN)
                    forbidden.addAll(extension.forbidden)
                    required.set(extension.required)
                    report.set(layout.buildDirectory.file("reports/$name.txt"))
                }
            }
        }
        tasks.matching { it.name == "check" }.configureEach { dependsOn(tasks.withType<VerifyApkClasses>()) }
    }

    private companion object {
        val ALWAYS_FORBIDDEN = listOf("runtime", "cli", "testsupport", "bridge").map { "io/github/olbartek/agentctl/$it/" }
    }
}

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
