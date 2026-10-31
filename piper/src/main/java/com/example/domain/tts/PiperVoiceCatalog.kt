package com.example.domain.tts

data class PiperVoice(
    val id: String,
    val displayName: String,
    val gender: String, // "female" or "male"
    val accent: String, // e.g. "US", "UK"
    val quality: String, // "low", "medium", "high"
    val downloadUrl: String,
    val expectedDownloadSize: Long,
    val sha256: String,
    val sampleRate: Int,
    val licenseName: String,
    val licenseUrl: String,
    val credit: String
)

object PiperVoiceCatalog {
    val defaultVoiceId: String = "en_US-lessac-medium"

    val voices: List<PiperVoice> = listOf(
        PiperVoice(
            id = "en_US-lessac-medium",
            displayName = "Lessac (US Female)",
            gender = "female",
            accent = "US",
            quality = "medium",
            downloadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-en_US-lessac-medium.tar.bz2",
            expectedDownloadSize = 65_000_000L,
            sha256 = "",
            sampleRate = 22050,
            licenseName = "MIT / CC0",
            licenseUrl = "https://github.com/rhasspy/piper",
            credit = "Lessac dataset (Mycroft AI & Rhasspy)"
        )
    )

    fun getVoice(id: String?): PiperVoice {
        return voices.firstOrNull { it.id == id } ?: voices.first()
    }
}
