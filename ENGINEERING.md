# Pmug Engineering Guide

## Current TTS playback & Multi-Voice architecture

`PmugApplication` owns one `HybridTtsEngine` and one process-scoped `PlaybackSession`. A Compose screen does not own or close the session. `ReaderScreen` publishes extracted reading units and handles play, pause, seek, speed, and provider selection; it does not advance Piper sentence playback.

### Piper Multi-Voice Support
`PiperVoiceCatalog` holds voice metadata (ID, display name, gender, accent, quality, download URL, expected size, SHA-256, sample rate, license and credit). `PiperModelInstaller` manages downloading and extracting voice packages while sharing a single `espeak-ng-data` directory across all voices. `VoiceSelectionSheet` provides gender filtering, download progress, cancel/resume, audio previews, and deletion. Switching voices reloads the model, clears the audio queue and cache, and resumes playback from the current sentence.

### Full-Screen PDF Viewer
`FullScreenPdfViewer` provides an immersive full-screen view for examining detailed PDF figures. Supports up to 8× pinch-to-zoom, drag-to-pan with clamped bounds, double-tap zoom, page navigation, orientation preservation across rotations, high-resolution rendering with `PdfRenderer` and memory capping (~4096px), and a collapsible audio control bar. Hidden for non-PDF documents.

### Reading units and lookahead

`SentenceSplitter` still creates the same one-sentence units. It assigns paragraph indices within each extracted page; `ReaderScreen` namespaces those indices by page so the session detects paragraph/page boundaries. The reader submits each unit with its displayed text, spoken text, page, and paragraph. The session has one `PmugSynthesis` worker and one `PmugAudioWriter` worker. Piper synthesis runs ahead of playback up to 15 seconds, with a 40 MiB PCM queue bound. The writer feeds one long-lived `AudioTrack` in stream mode.

The session starts after approximately 2.5 seconds of synthesized audio are queued, or earlier when the available plan is complete. Its adaptive start buffer can rise after underruns. When a Piper synthesis request fails, the reader switches to Android System Speech. If Piper is unavailable, `HybridTtsEngine` uses the Android fallback.

Each sentence boundary adds 150 ms of silence. A paragraph change adds 350 ms instead. These values are `PlaybackSession.SENTENCE_PAUSE_MS` and `PlaybackSession.PARAGRAPH_PAUSE_MS`.

### Audio position and highlighting

`AudioTrackPlaybackSink.playedFrames()` extrapolates `AudioTimestamp.framePosition` by the elapsed monotonic time and sample rate. If the timestamp is unavailable, it uses the playback head with output latency removed. The position is clamped to be monotonic and resets when audio is flushed for stop, seek, or speed changes.

The reader samples this position with `withFrameNanos`. A sentence becomes active on its first played frame and ends on its last. Word spans are calculated from the displayed `ParsedSentence.text` and distributed over the sentence clip using word-length/syllable weights and extra pause weight after commas, colons, and semicolons. Compose state changes only when the active word changes. Word highlighting uses a background style and does not change font weight or size.

Android System Speech keeps its utterance callback flow. `onRangeStart` supplies word offsets; `UtteranceProgressListener` callbacks are posted to the main thread before the reader updates Compose state. No words-per-minute timer is used.

### Speed, warm-up, and logs

Playback speed changes reset the current session generation and synthesize from the active sentence at the new speed. Piper model warm-up is dispatched to a worker after the voice is installed. Logs use the `PmugTts:` prefix and include synthesis duration, clip duration, RTF, audio and highlight boundaries, gap between units, tap-to-first-sound latency, buffered audio, and underrun counts.

## Components

* `app/src/main/java/com/example/PmugApplication.kt`: process-owned TTS engine and playback session.
* `app/src/main/java/com/example/domain/tts/PlaybackSession.kt`: reading plan, synthesis lookahead, audio writer, played-frame mapping, and paragraph/sentence silence.
* `app/src/main/java/com/example/domain/tts/HybridTtsEngine.kt`: Piper/Android provider selection and fallback.
* `piper/src/main/java/com/example/domain/tts/PiperTtsEngine.kt`: sherpa-onnx Piper synthesis, warm-up, multi-voice switching, and legacy `TtsEngine` adapter.
* `app/src/main/java/com/example/ui/reader/ReaderScreen.kt`: plan publication, user controls, audio-position rendering, and Android System Speech callbacks.
* `app/src/main/java/com/example/ui/reader/VoiceSelectionSheet.kt`: voice selection sheet with filters and downloads.
* `app/src/main/java/com/example/ui/reader/FullScreenPdfViewer.kt`: full-screen figure study viewer.
* `app/src/test/java/com/example/PlaybackSessionTest.kt`, `PiperVoiceTest.kt`, `PdfViewerMathTest.kt`: unit test suite.

## Build and test

```bash
./gradlew app:assembleDebug
./gradlew app:testDebugUnitTest
```

All unit tests pass successfully.
