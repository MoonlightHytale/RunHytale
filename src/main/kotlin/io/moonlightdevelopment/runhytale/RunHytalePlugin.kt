package io.moonlightdevelopment.runhytale
import org.gradle.api.GradleException
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

        val runTaskProvider = project.tasks.register("runHytale", RunHytaleTask::class.java) { t ->
            t.group = "hytale"
            t.description =
                "Builds the mod, downloads/extracts versioned Hytale server zip, installs jars into Server/, and starts the server."

            t.serverRootDir.set(ext.serverRootDir)
            t.downloaderZipUrl.set(ext.downloaderZipUrl)
            t.patchline.set(ext.patchline)
            t.preferShadowJar.set(ext.preferShadowJar)
            t.skipDownloaderUpdateCheck.set(ext.skipDownloaderUpdateCheck)
            t.serverArgs.set(ext.serverArgs)
            t.jvmArgs.set(ext.jvmArgs)
            t.enableEarlyPluginLoading.set(ext.enableEarlyPluginLoading)
            t.asEarlyPlugin.set(ext.asEarlyPlugin)

            // these get populated in projectsEvaluated
            t.includedMods.from(ext.includedMods)
            t.includedEarlyPlugins.from(ext.includedEarlyPlugins)
            t.includedPlugins.from(ext.includedPlugins)
        }

        project.gradle.projectsEvaluated {
            val runTask = runTaskProvider.get()

            // resolve own jar AFTER evaluation as well (shadowJar might be registered late)
            val shadowJar = project.tasks.findByName("shadowJar")
            val jarTask = project.tasks.withType(Jar::class.java).findByName("jar")
                ?: throw GradleException("Task 'jar' not found in this project. Apply 'java' or 'org.jetbrains.kotlin.jvm'.")

            val useShadow = ext.preferShadowJar.getOrElse(true) && shadowJar is Jar
            val buildTaskProvider = if (useShadow) project.tasks.named("shadowJar") else project.tasks.named("build")

            val jarFileProvider: Provider<RegularFile> =
                if (useShadow) (shadowJar as Jar).archiveFile else jarTask.archiveFile

            runTask.dependsOn(buildTaskProvider)
            runTask.modJar.set(jarFileProvider)

            fun resolveJarTask(p: Project, overrideTaskName: String?): org.gradle.api.tasks.TaskProvider<Jar> {
                if (!overrideTaskName.isNullOrBlank()) {
                    val task = p.tasks.findByName(overrideTaskName)
                    if (task !is Jar) {
                        throw GradleException(
                            "includeBuild(${p.path}, taskName = \"$overrideTaskName\") must point to a Jar task, but was: " +
                                    (task?.javaClass?.name ?: "null")
                        )
                    }
                    return p.tasks.named(overrideTaskName, Jar::class.java)
                }

                val preferShadow = ext.preferShadowJar.getOrElse(true)

                val shadow = p.tasks.findByName("shadowJar")
                if (preferShadow && shadow is Jar) return p.tasks.named("shadowJar", Jar::class.java)

                val jar = p.tasks.findByName("jar")
                if (jar is Jar) return p.tasks.named("jar", Jar::class.java)

                val all = p.tasks.withType(Jar::class.java).toList()
                if (all.isEmpty()) {
                    throw GradleException(
                        "Project '${p.path}' has no Jar task. Apply 'java' or 'org.jetbrains.kotlin.jvm', or pass taskName explicitly."
                    )
                }

                val chosen = all
                    .filterNot { it.name.lowercase().contains("sources") }
                    .filterNot { it.name.lowercase().contains("javadoc") }
                    .filterNot { it.name.lowercase().contains("test") }
                    .sortedWith(
                        compareByDescending<Jar> { it.name.lowercase().contains("shadow") }
                            .thenBy { it.name }
                    )
                    .firstOrNull() ?: all.first()

                return p.tasks.named(chosen.name, Jar::class.java)
            }

            ext.includeRequests
                .distinctBy { "${it.projectPath}|${it.directory}|${it.taskName ?: ""}" }
                .forEach { req ->
                    val p = project.findProject(req.projectPath)
                        ?: throw GradleException(
                            "Included project '${req.projectPath}' not found. Is it included in settings.gradle.kts?"
                        )

                    val jarTaskProvider = resolveJarTask(p, req.taskName)
                    val jarFileProvider = jarTaskProvider.flatMap { it.archiveFile }

                    runTask.dependsOn(jarTaskProvider)

                    when (req.directory) {
                        "mods" -> ext.includedMods.from(jarFileProvider)
                        "earlyplugins" -> ext.includedEarlyPlugins.from(jarFileProvider)
                        "plugins" -> ext.includedPlugins.from(jarFileProvider)
                        else -> throw GradleException(
                            "Unsupported directory '${req.directory}'. Supported: mods, earlyplugins, plugins."
                        )
                    }
                }
        }
    }
}
