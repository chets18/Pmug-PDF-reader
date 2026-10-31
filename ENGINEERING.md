# Pmug - Engineering & Architecture Specification

This document details the architectural implementation of advanced features in **Pmug**, including TTS prefetch pipeline, paragraph chunking, continuous AudioTrack streaming, silence trimming, deliberate pause injection, telemetry logging, engine fallback hierarchy, offline Piper neural voice integration, Puter cloud auth flows, and background media playback services.

## Pre-change playback trace (commit `2d450a1`)

This trace records what the code does before the playback-session work. It supersedes contradictory statements below that called the current player gapless or prefetch-driven.

1. **What starts the next unit:** `ReaderScreen`'s `DisposableEffectWithLifecycle` callback handles `onSentenceCompleted` at lines 323–331, increments the sentence index, and calls `ttsEngine.speakSentence` on the main thread. For Piper, `PiperTtsEngine.playOrStreamSamples` posts `onSentenceCompleted` through `mainHandler` at lines 144–149; `HybridTtsEngine.onSentenceCompleted` forwards it at lines 154–157. If a sentence is not extracted yet, the reader stores `pendingNextSentence`; a `LaunchedEffect` at `ReaderScreen.kt:224–234` starts it after extraction publishes state. Android starts the same UI chain from `AndroidTtsEngine`'s `UtteranceProgressListener.onDone` (`AndroidTtsEngine.kt:53–56`).
2. **Does highlighting gate audio?** There is no explicit wait for highlight completion, scrolling, or an animation. Next speech is requested directly in the completion callback. However, the Piper completion callback itself is delayed by a duration-sized `Thread.sleep`, and the estimate-based Compose highlight is an independent timer. This makes the UI appear behind the actual audio and then starts synthesis only after the delayed callback.
3. **Waits and timers in `tts/`, `reader/`, and UI:** `PiperTtsEngine.kt:141` sleeps for the entire generated chunk duration after blocking writes, then reports completion; this is in the playback path and is the principal avoidable wait. `ReaderScreen.kt:298` delays 1,000 ms before saving progress (not a playback gate); `:341` delays 1,800 ms for a mascot mood (not a speech gate); `:376` delays one estimated word interval in the highlight loop (display only). `PmugMug.kt:70,72,185,191,197` has animation delays only. `TtsPrefetchQueue.poll(timeoutMs = 100)` is a bounded queue wait, but no production code calls `prefetchNext`, `poll`, or `peek`. There are no `postDelayed`, `withTimeout`, or `collectLatest` calls in the searched playback/reader paths.
4. **Track and synthesizer lifetimes:** `PiperTtsEngine.getOrCreateAudioTrack` (`PiperTtsEngine.kt:157–169`) lazily creates one `AudioTrack` per engine instance and reuses it. `getOrCreateEngine` (`:187–209`) also caches one `OfflineTts` instance, configured with `maxNumSentences = 1`. The objects are long-lived, but audio is submitted one sentence at a time; this is not continuous lookahead streaming.
5. **Highlight source:** Piper signals `onSentenceStarted` only after the writer has submitted the full sample array (`PiperTtsEngine.kt:132–137`), not from an audio timestamp. Reader sets the sentence at that callback (`ReaderScreen.kt:317–322`). Word highlighting uses a timer at `ReaderScreen.kt:369–377`, estimating 150 words/minute and dividing by the current `playbackSpeed`. Android can provide `onRangeStart` offsets (`AndroidTtsEngine.kt:58–61`), but Piper does not use a playback-head/timestamp mapping. `Sonic` and `PositionMapper` are present, but `rg` shows no production call site for either.
6. **Synthesis unit and overlap:** Piper is called once per sentence from `ReaderScreen`; it calls `OfflineTts.generate` once for that sentence (`PiperTtsEngine.kt:46–60`). Sentence N+1 is not synthesized during N. The reader asks for it after `onSentenceCompleted`; `PiperTtsEngine` has one serial worker for both synthesis and writes. `groupIntoParagraphChunks` exists in `SentenceSplitter.kt` but is only referenced by tests, not the active reader/Piper path.
7. **Page boundaries:** PDF extraction runs page by page in document order (`PdfTextExtractor.extractPdfFilePageByPage`, `PdfTextExtractor.kt:27–66`) while publishing each page. There is no three-page extraction lookahead and no next-page synthesis. If the next unit is not yet in `sentences`, audio completion stores `pendingNextSentence`; the reader waits for the next Compose state update/extraction publication before starting it.

The decoupled, audio-time lookahead pipeline is **not implemented** in this baseline. The UI owns the sentence loop. The existing `TtsPrefetchQueue` is not connected to synthesis or playback. The foreground service currently owns notification/audio-focus state only; it does not own the TTS engine or playback session (`PmugTtsService.kt:21–34,61–87`).

---

## 1. TTS Architecture & Fallback Hierarchy

### Default Voice Order
1. **Piper (`PIPER_OFFLINE`)**: Offline neural voice; downloads Lessac medium model once into `files/tts/piper`.
2. **OpenAI HD (`OPENAI_TTS_HD`)**: High quality OpenAI `tts-1-hd` (alloy) via Puter.js.
3. **Android System Speech (`ANDROID_SYSTEM`)**: Built-in Android `TextToSpeech` engine.
4. **OpenAI Mini (`OPENAI_MINI_TTS`)** & **Gemini Puter (`GEMINI_PUTER`)**: Selectable in voice menu.

### Per-Sentence Fallback Mechanism
`HybridTtsEngine` handles playback failures per sentence without switching the active session mode:
* If Piper is selected but not downloaded or fails during synthesis, it automatically falls back to OpenAI HD (if Puter is signed in) or Android System speech for that sentence.
* If cloud synthesis fails, it falls back to Android System speech.

---

## 2. Prefetch Pipeline & Paragraph Continuous Streaming

### Root Cause Analysis of Previous Audible Gaps
Previously, `PiperTtsEngine` instantiated a new `AudioTrack` per sentence (`speakSentence`), wrote samples for a single sentence, set a completion marker, and destroyed/released the `AudioTrack`. Upon completion, a new asynchronous request was dispatched to synthesize the next sentence, JNI-generate samples, create a brand-new `AudioTrack`, and start playback. This multi-step sequence introduced hundreds of milliseconds of latency and silence gaps between every sentence.

### The Paragraph & Continuous Streaming Solution
1. **Paragraph Chunking (`groupIntoParagraphChunks`)**: Sentences are grouped into paragraph chunks (minimum ~150 characters, maximum ~600 characters) to provide larger, natural speech units.
2. **Long-Lived Streaming AudioTrack (`MODE_STREAM`)**: A single `AudioTrack` object is created once per session (`audioTrackObjectsCreated = 1`) and continuously fed with upcoming PCM float audio chunks before previous buffers drain, eliminating start/stop gaps.
3. **Silence Trimming & Deliberate Pauses**: Leading and trailing near-silence (`abs(sample) <= 0.01f`) is trimmed, and precise deliberate pauses (120 ms between sentences, 300 ms between paragraphs) are injected as zero float samples.
4. **Step 1 Telemetry (`PmugTts`)**: Logs synthesis start/end time, audio duration, playback start/end time, queue depth, chunk gaps, underrun warnings, AudioTrack count (1), trimmed silence, and Piper Real-Time Factor (RTF).

---

## 3. Background Playback & Screen Stay-Awake

* **Foreground Service (`PmugTtsService`)**: Runs speech in a foreground service with `foregroundServiceType="mediaPlayback"`, supporting MediaSession notifications, audio focus management, and `ACTION_AUDIO_BECOMING_NOISY` listener (pausing when headphones are unplugged).
* **Screen Keep-On**: Uses Compose `DisposableEffect` with `LocalView.current.keepScreenOn = isPlaying` to keep the screen active while audio is playing in the reader.

---

## 4. Puter Cloud Authentication

* Configured with full JavaScript, DOM storage, third-party cookie support, `setSupportMultipleWindows(true)`, and `onCreateWindow` popup dialog routing.
* Tracks authentication state via `puter.auth.isSignedIn()` and supports one-time sign-in.

## 5. Imported document formats

`PmugDocumentEntity.type` stores `PDF`, `TEXT`, `MARKDOWN`, or `DOCX`. Room migration 2→3 adds the column with `PDF` as the default for existing rows. The repository copies imports into private storage and parses them on `Dispatchers.IO`.

* TXT decoding tries strict UTF-8 first, then Windows-1252, with ISO-8859-1 as the final fallback. Pages target about 2,000 characters and split at paragraph boundaries where possible.
* Markdown strips heading markers, emphasis, and link destinations while keeping link labels. Tables and image references are ignored. Fenced code is represented as “Code block.”
* DOCX reads `word/document.xml` using `ZipFile` and Android `XmlPullParser`. Paragraphs and runs are read; tables are skipped. No Apache POI dependency is used. Legacy `.doc`, encrypted, and corrupt DOCX inputs return import errors.
* Non-PDF pages are cached beside the source as `<filename>.text-cache` and have no PDF preview. PDF retains the PDFBox extraction and rendering path.

## 6. Reader page controls and text access

New PDFs start at 1.25× the fit-width scale (125% of the width-fitted page). Zoom state is held per open document; pinch, pan, and double-tap remain enabled. The page label opens a validated 1…N jump dialog with a slider. `PageExtractionPriority` orders the target page and its immediate neighbors before remaining pages. When requested page text arrives, reading resumes there if audio was active. The reader uses Compose `SelectionContainer` and has a separate Copy page text action.

## 7. Size and dependency policy

Document import uses platform APIs and adds no document-processing library. Piper remains behind `PiperProvider`; the reusable `TtsEngine` interface does not depend on Piper implementation classes. Piper remains the default TTS provider.
