package io.moonlightdevelopment.runhytale

data class IncludeRequest(
    val projectPath: String,
    val directory: String,
    val taskName: String? = null
)
