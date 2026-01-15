plugins {
    alias(libs.plugins.kotlin)
    `java-gradle-plugin`
    alias(libs.plugins.publish)
}

group = "io.moonlightdevelopment"
version = "1.0.0"

repositories {
    mavenCentral()
}

gradlePlugin {
    website.set("https://moonlightdevelopment.io")
    vcsUrl.set("https://github.com/MoonlightHytale/runhytale")
    plugins {
        create("runHytale") {
            id = "io.moonlightdevelopment.runhytale"
            implementationClass = "io.moonlightdevelopment.runhytale.RunHytalePlugin"
            displayName = "RunHytale"
            description = "Adds a runHytale task that builds the mod, downloads/extracts Hytale server, copies the jar to mods, and starts it."
            tags = setOf("run", "run", "download", "extracts")
        }
    }
}
