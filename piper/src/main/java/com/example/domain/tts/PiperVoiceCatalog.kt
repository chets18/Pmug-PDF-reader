package com.example.domain.tts

data class PiperVoice(
    val id: String,
    val displayName: String,
    val gender: String, // "female" or "male"
    val accent: String, // "US", "UK", etc.
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
    val defaultVoiceId: String = "en_US-ryan-medium"

    val voices: List<PiperVoice> = listOf(
        PiperVoice(
            id = "en_US-ryan-medium",
            displayName = "Ryan (US Male, Default)",
            gender = "male",
            accent = "US",
            quality = "medium",
            downloadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-en_US-ryan-medium.tar.bz2",
            expectedDownloadSize = 62_000_000L,
            sha256 = "",
            sampleRate = 22050,
            licenseName = "MIT / CC0",
            licenseUrl = "https://github.com/rhasspy/piper",
            credit = "Ryan dataset (Rhasspy)"
        ),
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
        ),
        PiperVoice(
            id = "en_US-amy-medium",
            displayName = "Amy (US Female)",
            gender = "female",
            accent = "US",
            quality = "medium",
            downloadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-en_US-amy-medium.tar.bz2",
            expectedDownloadSize = 60_000_000L,
            sha256 = "",
            sampleRate = 22050,
            licenseName = "MIT / CC0",
            licenseUrl = "https://github.com/rhasspy/piper",
            credit = "Amy dataset (Rhasspy)"
        ),
        PiperVoice(
            id = "en_GB-alan-medium",
            displayName = "Alan (UK Male)",
            gender = "male",
            accent = "UK",
            quality = "medium",
            downloadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-en_GB-alan-medium.tar.bz2",
            expectedDownloadSize = 64_000_000L,
            sha256 = "",
            sampleRate = 22050,
            licenseName = "MIT / CC0",
            licenseUrl = "https://github.com/rhasspy/piper",
            credit = "Alan dataset (Rhasspy)"
        )
    )

    fun getVoice(id: String?): PiperVoice {
        return voices.firstOrNull { it.id == id } ?: voices.first()
    }
}
