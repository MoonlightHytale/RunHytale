# Run Hytale Gradle Plugin

A Gradle plugin that builds your Hytale plugin, ensures a local Hytale server is available, installs the built jar into the server, and runs the server with an interactive console.

## Features

- Builds your project (`shadowJar` if present, otherwise `build`)
- Downloads the official Hytale downloader
- Downloads the server for the current version
- Extracts assets and server files
- Installs your plugin jar into the server `mods` directory
- Starts the server with a live, interactive console (stdin/stdout wired)
- Stops the server automatically after a configurable time (default: enabled)

## Requirements

- Gradle 9.2.1 or newer
- Java 21
- A valid Hytale account (first run requires authentication via the downloader)

## Installation

```kotlin
plugins {
    id("io.moonlightdevelopment.runhytale") version "<version>"
}
```

## Basic Usage

```bash
./gradlew runHytale
```

On first run, the downloader will prompt you to authenticate in your browser. Credentials are stored locally and reused.

## Configuration

```kotlin
runHytale {
    // Directory where the server and runtime files live
    // Defaults to: run/
    serverRootDir.set(layout.projectDirectory.dir("run"))

    // Automatically stop the server after N seconds (default: 5)
    // Set to 0 to run indefinitely
    serverRunSeconds.set(5)

    // Extra JVM args for the server
    jvmArgs.addAll(listOf("-Xms1G", "-Xmx2G"))

    // Extra server arguments
    serverArgs.addAll(listOf(
        "--bind", "0.0.0.0:25565",
        "--auth-mode", "authenticated"
    ))
}
```

## Interactive Console

The server is started using a native process, not `javaexec`. This ensures:

- Server logs appear live in your IDE or terminal
- Commands typed into the console are forwarded directly to the server

For best results, run the task from a terminal (`./gradlew runHytale`) or an IDE run configuration that supports stdin.

## Generated Files

By default, files are created under:

```
run/
  cache/        # Downloader zip and server zip
  bin/          # Downloader binary
  runtime/      # Extracted server versions
```

You should usually add `run/` to `.gitignore`.

## Notes

- The server version is cached by version string
- Re-running the task will reuse existing downloads when possible
- This plugin is intended for development workflows