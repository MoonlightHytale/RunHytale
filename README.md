# RunHytale Gradle Plugin

A Gradle plugin that builds your Hytale plugin, ensures a local Hytale server is available, installs one or more plugin jars into the server, and starts it with an interactive console.

## Features

- Builds your project (prefers `shadowJar`, falls back to `jar`)
- Downloads the official Hytale downloader
- Downloads the server for the current version (versioned zip like `2026.01.13-dcad8778f`)
- Extracts `Assets.zip` and `Server/` (including `HytaleServer.jar`)
- Copies jars into `Server/mods`, `Server/earlyplugins`, or `Server/plugins`
- Starts the server with stdin/stdout wired (you can type server console commands)
- Optional early plugin loading (`--accept-early-plugins`)
- Duplicate jar names are handled by renaming (`foo.jar`, `foo-1.jar`, ...)

## Requirements

- Gradle 9.2.1+
- Java 21
- A valid Hytale account (first run requires authentication via the downloader)

## Installation

```kotlin
plugins {
    id("io.moonlightdevelopment.run-hytale") version "<version>"
}
```

## Basic Usage

```bash
./gradlew runHytale
```

On first run, the downloader will prompt you to authenticate in your browser. Credentials are stored locally and reused.

## Configuration

```kotlin
hytaleRun {
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

    // Adds --accept-early-plugins
    enableEarlyPluginLoading.set(true)

    // Copies THIS project's jar into Server/earlyplugins instead of Server/mods
    asEarlyPlugin.set(false)
}
```

## Including other modules

You can include the build outputs of other Gradle projects directly into the server.

### Default (mods)

```kotlin
hytaleRun {
    includeBuild(project(":sample-plugin"))
}
```

### Target a directory

All directories are relative to `Server/`. Supported values:

- `mods`
- `earlyplugins`
- `plugins`

```kotlin
hytaleRun {
    includeBuild(project(":sample-plugin"), directory = "plugins")
    includeBuild(project(":early-hook"), directory = "earlyplugins")
}
```

### Override the producing task

If a module produces its jar via a custom task, you can override it:

```kotlin
hytaleRun {
    includeBuild(project(":fat-plugin"), directory = "mods", taskName = "shadowJar")
}
```

## Interactive Console

The server is started so that:

- logs appear live in your IDE or terminal
- commands you type are forwarded to the server process

For best results, run from a terminal (`./gradlew runHytale`) or an IDE run configuration that supports stdin.

## Files and Caching

By default, files are created under:

```
run/
  cache/        # Downloader zip and versioned server zips (game-<version>.zip)
  bin/          # Downloader binary
  runtime/      # Extracted server versions (runtime/<version>/Server + Assets.zip)
```

You should usually add `run/` to `.gitignore`.

## Notes

- Server versions are cached by version string
- Re-running the task reuses existing downloads when possible
- Intended for local development workflows
