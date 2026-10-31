# Pmug Engineering Guide

## Current TTS playback architecture

`PmugApplication` owns one `HybridTtsEngine` and one process-scoped `PlaybackSession`. A Compose screen does not own or close the session. `ReaderScreen` publishes extracted reading units and handles play, pause, seek, speed, and provider selection; it does not advance Piper sentence playback.

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
* `piper/src/main/java/com/example/domain/tts/PiperTtsEngine.kt`: sherpa-onnx Piper synthesis, warm-up, and legacy `TtsEngine` adapter. Its synthesis and legacy audio writer executors are separate; the active reader uses `PlaybackSession` for Piper playback.
* `app/src/main/java/com/example/ui/reader/ReaderScreen.kt`: plan publication, user controls, audio-position rendering, and Android System Speech callbacks.
* `app/src/test/java/com/example/PlaybackSessionTest.kt`: fake synthesizer/sink tests for lookahead, seek cancellation, and played-frame highlighting.

`TtsPrefetchQueue` is not connected to the active Piper playback path.

## Background audio and verification limits

`PmugTtsService` is declared and contains media notification/session infrastructure, while `PlaybackSession` is Application-owned. Device behavior for background playback, audio focus, audible boundary gaps, AudioTrack timestamps, and visual synchronization has not been verified on a phone. The service is not the owner of the playback session. See [`AUDIO_FLOW.md`](AUDIO_FLOW.md) for current verification status.

## Documents and reader controls

`PmugDocumentEntity.type` stores `PDF`, `TEXT`, `MARKDOWN`, or `DOCX`. Room migration 2→3 gives existing rows the `PDF` default. TXT/Markdown/DOCX page text is cached beside the imported file as `.text-cache`; PDF retains its PDFBox cache. Imports run on `Dispatchers.IO`.

`DocumentText.kt` decodes UTF-8 with Windows-1252 fallback, paginates text around 2,000 characters at paragraph boundaries where possible, removes Markdown markup for speech, and extracts DOCX paragraphs from `word/document.xml` with `ZipFile` and `XmlPullParser`. Tables and images are skipped; fenced code is described as a code block. Apache POI is not used. Legacy `.doc`, encrypted DOCX, and corrupt DOCX files are unsupported.

New PDFs open at 1.25× fit-width. `PageExtractionPriority` orders extraction around a requested page. The reader supports selectable/copyable text, bookmarks, page jumping, zoom, pan, and page swipes.

## Build and test

```bash
./gradlew app:assembleDebug
./gradlew app:testDebugUnitTest
```

The playback-session change was built and unit-tested on 2026-10-31. Phone-only acceptance checks remain outstanding.
