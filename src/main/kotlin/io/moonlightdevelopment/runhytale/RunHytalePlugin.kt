package io.moonlightdevelopment.runhytale
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.bundling.Jar

class RunHytalePlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val ext = project.extensions.create("hytaleRun", HytaleRunExtension::class.java)

        // Root-relative default
        ext.serverRootDir.convention(project.layout.projectDirectory.dir("run"))

        project.afterEvaluate {
            val shadowJar = project.tasks.findByName("shadowJar")
            val jarTask = project.tasks.withType(Jar::class.java).named("jar").get()

            val useShadow = ext.preferShadowJar.getOrElse(true) && shadowJar != null
            val buildTaskProvider = if (useShadow) project.tasks.named("shadowJar") else project.tasks.named("build")

            val jarFileProvider: Provider<RegularFile> =
                if (useShadow) (shadowJar as Jar).archiveFile else jarTask.archiveFile

            project.tasks.register("runHytale", RunHytaleTask::class.java) { t ->
                t.group = "hytale"
                t.description = "Builds the mod, downloads/extracts versioned Hytale server zip, installs the jar into mods, and starts the server."

                t.dependsOn(buildTaskProvider)

                t.serverRootDir.set(ext.serverRootDir)
                t.downloaderZipUrl.set(ext.downloaderZipUrl)
                t.patchline.set(ext.patchline)
                t.preferShadowJar.set(ext.preferShadowJar)
                t.skipDownloaderUpdateCheck.set(ext.skipDownloaderUpdateCheck)
                t.serverArgs.set(ext.serverArgs)
                t.jvmArgs.set(ext.jvmArgs)

                t.modJar.set(jarFileProvider)
            }
        }
    }
}
