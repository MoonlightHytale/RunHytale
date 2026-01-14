package io.moonlightdevelopment.runhytale

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.net.URI
import java.net.URL
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import javax.inject.Inject
import kotlin.collections.plusAssign
import kotlin.text.trim

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

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val modJar: RegularFileProperty

    @TaskAction
    fun run() {
        val root = serverRootDir.get().asFile
        val cacheDir = File(root, "cache").apply { mkdirs() }
        val binDir = File(root, "bin").apply { mkdirs() }

        val downloaderZip = File(cacheDir, "hytale-downloader.zip")

        // 1) Ensure downloader zip exists
        if (!downloaderZip.exists()) {
            logger.lifecycle("Downloading hytale-downloader.zip -> ${downloaderZip.absolutePath}")
            downloadTo(downloaderZipUrl.get(), downloaderZip)
        }

        // 2) Extract correct downloader binary
        val (zipEntryName, binaryFileName, needsChmod) = selectDownloaderBinary()
        val downloaderBinary = File(binDir, binaryFileName)

        if (!downloaderBinary.exists()) {
            logger.lifecycle("Extracting $zipEntryName -> ${downloaderBinary.absolutePath}")
            extractSingleFileFromZip(downloaderZip, zipEntryName, downloaderBinary)
            if (needsChmod) makeExecutable(downloaderBinary)
        }

        // 3) Ask downloader for server version, use it for zip filename
        val serverVersion = readServerVersion(downloaderBinary, root)
        val gameZip = File(cacheDir, "game-$serverVersion.zip")

        // 4) Ensure versioned game zip exists
        if (!gameZip.exists()) {
            logger.lifecycle("Downloading Hytale server zip for version '$serverVersion' -> ${gameZip.absolutePath}")
            runDownloaderDownload(downloaderBinary, root, gameZip)
            if (!gameZip.exists() || gameZip.length() == 0L) {
                throw GradleException("Downloader finished, but game zip was not created at ${gameZip.absolutePath}")
            }
        } else {
            logger.lifecycle("Using cached server zip: ${gameZip.name}")
        }

        // 5) Extract into runtime/<version> (so you can keep multiple)
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

        // 6) Copy built mod jar to mods folder
        val built = modJar.get().asFile
        val modsDir = File(runtimeDir, "Server/mods").apply { mkdirs() }
        val target = File(modsDir, built.name)

        logger.lifecycle("Copying mod jar -> ${target.absolutePath}")
        built.copyTo(target, overwrite = true)

        // 7) Start server
        val fullArgs = mutableListOf<String>()
        fullArgs += "--assets"
        fullArgs += assetsZip.absolutePath
        fullArgs += serverArgs.get()

        logger.lifecycle("Starting server (version $serverVersion): java -jar ${serverJar.name} ${fullArgs.joinToString(" ")}")

        execOps.javaexec { spec ->
            spec.workingDir = File(runtimeDir, "Server")
            spec.mainClass.set("-jar")
            spec.args(serverJar.absolutePath)
            spec.args(fullArgs)
            spec.jvmArgs(jvmArgs.get())
            spec.standardInput = System.`in`
        }
    }

    private fun readServerVersion(binary: File, workingDir: File): String {
        val out = ByteArrayOutputStream()

        execOps.exec { spec ->
            spec.workingDir = workingDir
            spec.commandLine(binary.absolutePath, "-print-version", "-skip-update-check")

            // live + capture
            spec.standardOutput = TeeOutputStream(System.out, out)
            spec.errorOutput = TeeOutputStream(System.err, out)

            spec.isIgnoreExitValue = false
        }

        val text = out.toString(Charsets.UTF_8.name()).trim()
        val version = parseVersion(text)
        if (version.isNullOrBlank()) {
            throw GradleException("Could not parse server version from hytale-downloader output:\n$text")
        }
        return version
    }

    private fun parseVersion(output: String): String? {
        // Best-effort parsing:
        // - if the output is exactly a version, return it
        // - else search for something like 2026.01.13-dcad8778f
        val singleLine = output.lines()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() } ?: return null

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

            // live output
            spec.standardOutput = System.out
            spec.errorOutput = System.err

            spec.isIgnoreExitValue = false
        }
    }

    private fun selectDownloaderBinary(): Triple<String, String, Boolean> {
        val os = System.getProperty("os.name").lowercase(Locale.getDefault())
        return if (os.contains("windows")) {
            Triple("hytale-downloader-windows-amd64.exe", "hytale-downloader.exe", false)
        } else {
            Triple("hytale-downloader-linux-amd64", "hytale-downloader", true)
        }
    }

    private fun downloadTo(url: String, dest: File) {
        dest.parentFile.mkdirs()
        URI(url).toURL().openStream().use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        }
    }

    private fun extractSingleFileFromZip(zipFile: File, entryName: String, dest: File) {
        ZipInputStream(BufferedInputStream(FileInputStream(zipFile))).use { zis ->
            while (true) {
                val e = zis.nextEntry ?: break
                if (!e.isDirectory && e.name == entryName) {
                    dest.parentFile.mkdirs()
                    dest.outputStream().use { out -> zis.copyTo(out) }
                    return
                }
            }
        }
        throw GradleException("Entry '$entryName' not found in ${zipFile.absolutePath}")
    }

    private fun unzip(zipFile: File, destDir: File) {
        ZipInputStream(BufferedInputStream(FileInputStream(zipFile))).use { zis ->
            while (true) {
                val e: ZipEntry = zis.nextEntry ?: break
                val outFile = File(destDir, e.name)

                if (e.isDirectory) {
                    outFile.mkdirs()
                    continue
                }

                outFile.parentFile?.mkdirs()
                outFile.outputStream().use { out -> zis.copyTo(out) }
            }
        }
    }

    private fun makeExecutable(file: File) {
        try {
            val p = ProcessBuilder("chmod", "+x", file.absolutePath).inheritIO().start()
            val code = p.waitFor()
            if (code != 0) logger.warn("chmod +x failed with exit code $code for ${file.absolutePath}")
        } catch (_: Throwable) {
            file.setExecutable(true, false)
        }
    }
}