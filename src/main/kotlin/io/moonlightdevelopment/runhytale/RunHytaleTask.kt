package io.moonlightdevelopment.runhytale

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import org.gradle.process.ExecOperations
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import javax.inject.Inject

abstract class RunHytaleTask : DefaultTask() {

    @get:Inject
    abstract val execOps: ExecOperations

    @get:OutputDirectory
    abstract val serverRootDir: DirectoryProperty

    @get:Input
    abstract val downloaderZipUrl: Property<String>

    @get:Input
    abstract val patchline: Property<String>

    @get:Input
    abstract val preferShadowJar: Property<Boolean>

    @get:Input
    abstract val skipDownloaderUpdateCheck: Property<Boolean>

    @get:Input
    abstract val serverArgs: ListProperty<String>

    @get:Input
    abstract val jvmArgs: ListProperty<String>

    @get:Input
    abstract val enableEarlyPluginLoading: Property<Boolean>

    @get:Input
    abstract val asEarlyPlugin: Property<Boolean>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val modJar: RegularFileProperty

    @get:InputFiles
    @get:Classpath
    abstract val includedMods: ConfigurableFileCollection

    @get:InputFiles
    @get:Classpath
    abstract val includedEarlyPlugins: ConfigurableFileCollection

    @get:InputFiles
    @get:Classpath
    abstract val includedPlugins: ConfigurableFileCollection

    @TaskAction
    fun run() {
        val root = serverRootDir.get().asFile
        val cacheDir = File(root, "cache").apply { mkdirs() }
        val binDir = File(root, "bin").apply { mkdirs() }

        val downloaderZip = File(cacheDir, "hytale-downloader.zip")

        if (!downloaderZip.exists()) {
            logger.lifecycle("Downloading hytale-downloader.zip -> ${downloaderZip.absolutePath}")
            downloadTo(downloaderZipUrl.get(), downloaderZip)
        }

        val (zipEntryName, binaryFileName, needsChmod) = selectDownloaderBinary()
        val downloaderBinary = File(binDir, binaryFileName)

        if (!downloaderBinary.exists()) {
            logger.lifecycle("Extracting $zipEntryName -> ${downloaderBinary.absolutePath}")
            extractSingleFileFromZip(downloaderZip, zipEntryName, downloaderBinary)
            if (needsChmod) makeExecutable(downloaderBinary)
        }

        val serverVersion = readServerVersion(downloaderBinary, root)
        val gameZip = File(cacheDir, "game-$serverVersion.zip")

        if (!gameZip.exists()) {
            logger.lifecycle("Downloading Hytale server zip for version '$serverVersion' -> ${gameZip.absolutePath}")
            runDownloaderDownload(downloaderBinary, root, gameZip)
            if (!gameZip.exists() || gameZip.length() == 0L) {
                throw GradleException("Downloader finished, but game zip was not created at ${gameZip.absolutePath}")
            }
        } else {
            logger.lifecycle("Using cached server zip: ${gameZip.name}")
        }

        val runtimeDir = File(root, "runtime/$serverVersion")
        val serverJar = File(runtimeDir, "Server/HytaleServer.jar")
        val assetsZip = File(runtimeDir, "Assets.zip")

        if (!serverJar.exists() || !assetsZip.exists()) {
            logger.lifecycle("Extracting ${gameZip.name} -> ${runtimeDir.absolutePath}")
            runtimeDir.deleteRecursively()
            runtimeDir.mkdirs()
            unzip(gameZip, runtimeDir)
        }

        if (!serverJar.exists()) throw GradleException("Expected server jar not found: ${serverJar.absolutePath}")
        if (!assetsZip.exists()) throw GradleException("Expected Assets.zip not found: ${assetsZip.absolutePath}")

        val built = modJar.get().asFile
        val serverDir = File(runtimeDir, "Server")

        val modsDir = File(serverDir, "mods").apply { mkdirs() }
        val earlyDir = File(serverDir, "earlyplugins").apply { mkdirs() }
        val pluginsDir = File(serverDir, "plugins").apply { mkdirs() }

        val usedModsNames = mutableSetOf<String>()
        val usedEarlyNames = mutableSetOf<String>()
        val usedPluginsNames = mutableSetOf<String>()

        val shouldUseEarlyPluginDirectory = asEarlyPlugin.getOrElse(false)
        val targetDir = if (shouldUseEarlyPluginDirectory) earlyDir else modsDir
        val targetUsed = if (shouldUseEarlyPluginDirectory) usedEarlyNames else usedModsNames

        logger.lifecycle("Copying mod jar -> ${targetDir.absolutePath}")
        built.copyTo(File(targetDir, built.name), overwrite = true)
        targetUsed += built.name

        copyJarSet(includedMods, modsDir, usedModsNames)
        copyJarSet(includedEarlyPlugins, earlyDir, usedEarlyNames)
        copyJarSet(includedPlugins, pluginsDir, usedPluginsNames)

        val fullArgs = mutableListOf<String>().apply {
            addAll(serverArgs.getOrElse(emptyList()))
            if (enableEarlyPluginLoading.getOrElse(false)) add("--accept-early-plugins")
            if (!contains("--assets")) {
                add("--assets")
                add(assetsZip.absolutePath)
            }
        }

        logger.lifecycle("Starting server (version $serverVersion): java -jar ${serverJar.name} ${fullArgs.joinToString(" ")}")

        execOps.javaexec { spec ->
            spec.workingDir = serverDir
            spec.mainClass.set("-jar")
            spec.args(serverJar.absolutePath)
            spec.args(fullArgs)
            spec.jvmArgs(jvmArgs.get())
            spec.standardInput = System.`in`
        }
    }

    private fun copyJarSet(files: ConfigurableFileCollection, destDir: File, usedNames: MutableSet<String>) {
        files.files.forEach { f ->
            if (f.isFile && f.name.endsWith(".jar")) {
                val name = uniqueJarName(f.name, usedNames, destDir)
                copyFile(f, File(destDir, name))
            }
        }
    }

    private fun uniqueJarName(original: String, used: MutableSet<String>, dir: File): String {
        val dot = original.lastIndexOf('.')
        val base = if (dot > 0) original.substring(0, dot) else original
        val ext = if (dot > 0) original.substring(dot) else ""

        fun candidate(i: Int): String = if (i == 0) original else "${base}-$i$ext"

        var i = 0
        while (true) {
            val name = candidate(i)
            val existsOnDisk = File(dir, name).exists()
            val alreadyPlanned = name in used
            if (!existsOnDisk && !alreadyPlanned) {
                used += name
                return name
            }
            i++
        }
    }

    private fun readServerVersion(binary: File, workingDir: File): String {
        val out = ByteArrayOutputStream()

        execOps.exec { spec ->
            spec.workingDir = workingDir
            spec.commandLine(binary.absolutePath, "-print-version", "-skip-update-check")
            spec.standardOutput = TeeOutputStream(System.out, out)
            spec.errorOutput = TeeOutputStream(System.err, out)
            spec.isIgnoreExitValue = false
        }

        val text = out.toString(Charsets.UTF_8.name()).trim()
        val version = parseVersion(text)
        if (version.isNullOrBlank()) throw GradleException("Could not parse server version from hytale-downloader output:\n$text")
        return version
    }

    private fun parseVersion(output: String): String? {
        val singleLine = output.lines().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return null
        val versionRegex = Regex("""\b\d{4}\.\d{2}\.\d{2}-[0-9a-fA-F]{7,}\b""")
        return when {
            versionRegex.matches(singleLine) -> singleLine
            else -> versionRegex.find(output)?.value
        }
    }

    private fun runDownloaderDownload(binary: File, workingDir: File, gameZip: File) {
        val args = mutableListOf<String>()
        args += "-download-path"
        args += gameZip.absolutePath

        val pl = patchline.get().trim()
        if (pl.isNotEmpty()) {
            args += "-patchline"
            args += pl
        }

        if (skipDownloaderUpdateCheck.getOrElse(true)) {
            args += "-skip-update-check"
        }

        execOps.exec { spec ->
            spec.workingDir = workingDir
            spec.commandLine(listOf(binary.absolutePath) + args)
            spec.standardOutput = System.out
            spec.errorOutput = System.err
            spec.isIgnoreExitValue = false
        }
    }

    private fun unzip(zipFile: File, destDir: File) {
        ZipInputStream(zipFile.inputStream().buffered()).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                val out = File(destDir, entry.name)
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile.mkdirs()
                    out.outputStream().use { os -> zis.copyTo(os) }
                }
                zis.closeEntry()
            }
        }
    }

    private fun selectDownloaderBinary(): Triple<String, String, Boolean> {
        val os = System.getProperty("os.name").lowercase()
        val isWindows = os.contains("windows")
        return if (isWindows) {
            Triple("hytale-downloader-windows-amd64.exe", "hytale-downloader.exe", false)
        } else {
            Triple("hytale-downloader-linux-amd64", "hytale-downloader", true)
        }
    }

    private fun extractSingleFileFromZip(zipFile: File, entryName: String, outFile: File) {
        ZipInputStream(zipFile.inputStream().buffered()).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                if (entry.name == entryName) {
                    outFile.parentFile.mkdirs()
                    outFile.outputStream().use { os -> zis.copyTo(os) }
                    return
                }
                zis.closeEntry()
            }
        }
        throw GradleException("Entry '$entryName' not found in zip: ${zipFile.absolutePath}")
    }

    private fun makeExecutable(file: File) {
        try {
            file.setExecutable(true)
        } catch (_: Throwable) {
            // ignore
        }
    }

    private fun downloadTo(url: String, target: File) {
        val conn = java.net.URL(url).openConnection()
        conn.getInputStream().use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
    }

    private fun copyFile(from: File, to: File) {
        to.parentFile.mkdirs()
        from.inputStream().use { input ->
            to.outputStream().use { output -> input.copyTo(output) }
        }
    }
}