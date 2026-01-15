package io.moonlightdevelopment.runhytale

import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFile
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.bundling.Jar
import java.io.File
import javax.inject.Inject


abstract class HytaleRunExtension @Inject constructor(
    objects: ObjectFactory,
    private val rootProject: Project
) {
    /**
     * Root dir for all Hytale runtime state.
     * Default: <root>/run
     */
    val serverRootDir: DirectoryProperty = objects.directoryProperty()

    /**
     * URL to hytale-downloader.zip
     */
    val downloaderZipUrl: Property<String> = objects.property(String::class.java)

    /**
     * Patchline to use, e.g. "pre-release".
     * If empty, downloader default is used.
     */
    val patchline: Property<String> = objects.property(String::class.java)

    /**
     * Prefer shadowJar output when available.
     */
    val preferShadowJar: Property<Boolean> = objects.property(Boolean::class.java)

    /**
     * If true, pass -skip-update-check to downloader.
     */
    val skipDownloaderUpdateCheck: Property<Boolean> = objects.property(Boolean::class.java)

    /**
     * Extra args passed to server after required "--assets <Assets.zip>".
     */
    val serverArgs: ListProperty<String> = objects.listProperty(String::class.java)

    /**
     * Extra JVM args for server run.
     */
    val jvmArgs: ListProperty<String> = objects.listProperty(String::class.java)

    /**
     * Should early plugin loading be enabled.
     */
    val enableEarlyPluginLoading: Property<Boolean> = objects.property(Boolean::class.java)

    /**
     * Copies this project's jar into earlyplugins instead of mods.
     */
    val asEarlyPlugin: Property<Boolean> = objects.property(Boolean::class.java)

    /**
     * Resolved jars that go into Server/mods
     */
    val includedMods: ConfigurableFileCollection = objects.fileCollection()

    /**
     * Resolved jars that go into Server/earlyplugins
     */
    val includedEarlyPlugins: ConfigurableFileCollection = objects.fileCollection()

    /**
     * Resolved jars that go into Server/plugins
     */
    val includedPlugins: ConfigurableFileCollection = objects.fileCollection()

    /**
     * We only record requests here. They get resolved in RunHytalePlugin.projectsEvaluated.
     */
    internal val includeRequests: MutableList<IncludeRequest> = mutableListOf()

    init {
        serverRootDir.convention(objects.directoryProperty().fileValue(File("run")))
        downloaderZipUrl.convention("https://downloader.hytale.com/hytale-downloader.zip")
        patchline.convention("")
        preferShadowJar.convention(true)
        skipDownloaderUpdateCheck.convention(true)
        serverArgs.convention(emptyList())
        jvmArgs.convention(emptyList())
        enableEarlyPluginLoading.convention(false)
        asEarlyPlugin.convention(false)
    }

    // ----- includeBuild DSL -----

    fun includeBuild(p: Project) {
        includeBuild(p, directory = "mods", taskName = null)
    }

    fun includeBuild(p: Project, asEarlyPlugin: Boolean) {
        includeBuild(p, directory = if (asEarlyPlugin) "earlyplugins" else "mods", taskName = null)
    }

    fun includeBuild(p: Project, asEarlyPlugin: Boolean, taskName: String) {
        includeBuild(p, directory = if (asEarlyPlugin) "earlyplugins" else "mods", taskName = taskName)
    }

    fun includeBuild(p: Project, directory: String) {
        includeBuild(p, directory = directory, taskName = null)
    }

    fun includeBuild(p: Project, directory: String, taskName: String?) {
        rootProject.evaluationDependsOn(p.path)

        val dir = normalizeDirectory(directory)
        includeRequests += IncludeRequest(
            projectPath = p.path,
            directory = dir,
            taskName = taskName
        )
    }

    private fun normalizeDirectory(directory: String): String {
        val d = directory.trim().trim('/').trim()
        require(d.isNotEmpty()) { "directory must not be blank" }
        require(!d.startsWith(".")) { "directory must be relative to Server/ (no '.' prefix)" }
        return d
    }
}