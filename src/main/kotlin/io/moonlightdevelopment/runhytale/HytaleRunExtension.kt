package io.moonlightdevelopment.runhytale

import org.gradle.api.file.DirectoryProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import java.io.File
import javax.inject.Inject

abstract class HytaleRunExtension @Inject constructor(objects: ObjectFactory) {
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
     * Example: ["--bind", "0.0.0.0:25565", "--auth-mode", "offline"]
     */
    val serverArgs: ListProperty<String> = objects.listProperty(String::class.java)

    /**
     * Extra JVM args for server run.
     */
    val jvmArgs: ListProperty<String> = objects.listProperty(String::class.java)

    init {
        serverRootDir.convention(objects.directoryProperty().fileValue(File("run")))
        downloaderZipUrl.convention("https://downloader.hytale.com/hytale-downloader.zip")
        patchline.convention("")
        preferShadowJar.convention(true)
        skipDownloaderUpdateCheck.convention(true)
        serverArgs.convention(emptyList())
        jvmArgs.convention(emptyList())
    }
}