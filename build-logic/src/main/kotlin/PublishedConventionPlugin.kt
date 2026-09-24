import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.create

/**
 * A JVM library published as a Maven artifact (JitPack builds `publishToMavenLocal` from a tag):
 * `io.github.olbartek.agentctl:<module>:<version>` locally, `com.github.olbartek.agentctl-android:<module>:<tag>`
 * from JitPack.
 */
class PublishedConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("maven-publish")
        extensions.configure<JavaPluginExtension> {
            withSourcesJar()
        }
        extensions.configure<PublishingExtension> {
            publications {
                create<MavenPublication>("maven") {
                    from(components.getByName("java"))
                    artifactId = project.name
                    pom {
                        name.set(project.name)
                        description.set("AgentCtl for Kotlin and Android: ${project.name}")
                        url.set("https://github.com/olbartek/agentctl-android")
                        licenses {
                            license {
                                name.set("MIT")
                                url.set("https://opensource.org/licenses/MIT")
                            }
                        }
                    }
                }
            }
        }
    }
}
