# Pmug Engineering Notes

This document describes the implementation currently in this checkout. Citations name the relevant source file and class or method. “Verified in code” means the behavior is present in the checked-in sources/configuration; “inferred” marks an engineering implication rather than a documented design reason. The bug reports in the debugging log were supplied in the task; this checkout contains no corresponding runtime logs or usable Git history, so those reports are not independently reproduced here.

## Table of contents

1. [Overview](#1-overview)
2. [Architecture](#2-architecture)
3. [Key engineering decisions](#3-key-engineering-decisions)
4. [Debugging log](#4-debugging-log)
5. [Known limitations and technical debt](#5-known-limitations-and-technical-debt)
6. [What I would do next](#6-what-i-would-do-next)
7. [FAQ](#7-faq)
8. [Glossary](#8-glossary)
9. [Needs author confirmation](#9-needs-author-confirmation)

## 1. Overview

Pmug is an Android PDF reader that extracts academic text, displays the source page, and reads text aloud one sentence at a time.  
The reader tracks sentence and page progress, highlights speech, and stores bookmarks locally.  
Speech options include Android System TTS, Puter-hosted cloud voices, and an optional Piper neural voice.  
The UI is built with Jetpack Compose and includes a library, reading controls, and a drawn mascot.  
The app uses Room, PDFBox Android, Android `PdfRenderer`, and WorkManager alongside those features. [README.md; `MainActivity.onCreate`; `ReaderScreen`; `HybridTtsEngine`; `PmugMug`; `PmugDatabase`; `PdfTextExtractor`; `ThumbnailCache`; `NudgeWorker`]

### Technical stack and declared versions

Versions below come from `gradle/libs.versions.toml`, the module Gradle files, or `gradle/wrapper/gradle-wrapper.properties`. Compose UI and Material 3 use the Compose BOM, so their individual resolved versions are not declared in this repository.

| Component | Version/configuration |
|---|---|
| Gradle wrapper | 9.3.1 |
| Android Gradle Plugin | 9.1.1 |
| Kotlin / Compose compiler plugin | 2.2.10 |
| Android compile SDK / minimum SDK / target SDK | 36 (minor API 1) / 24 / 36 |
| AndroidX Compose BOM | 2024.09.00; Compose UI and Material 3 are BOM-managed |
| Activity Compose | 1.10.1 |
| AndroidX Core KTX | 1.18.0 |
| Lifecycle runtime and ViewModel Compose | 2.8.7 |
| AndroidX Activity Compose | 1.10.1 |
| AndroidX Compose UI, Material 3, Material icons | Compose BOM 2024.09.00 (individual versions are BOM-managed) |
| Room runtime, KTX, compiler | 2.7.0 |
| WorkManager KTX | 2.9.1 (`app/build.gradle.kts`) |
| PDFBox Android | 2.0.27.0 (`app/build.gradle.kts`) |
| Play Feature Delivery | 2.1.0 (`app/build.gradle.kts`) |
| sherpa-onnx | v1.13.8; release feature dependency, debug app dependency |
| Commons Compress | 1.27.1; release feature dependency, debug app dependency |
| OkHttp | 4.10.0 |
| Moshi Kotlin | 1.15.2 |
| Retrofit / converter-moshi | 2.12.0 |
| Coroutines Android / Core | 1.10.2 |
| Firebase BOM | 34.17.0 |
| Firebase AI / App Check reCAPTCHA / App Check debug | 34.17.0 via Firebase BOM |
| Google Services Gradle plugin | 4.5.0 |
| Secrets Gradle plugin | 2.0.1 |
| KSP Gradle plugin | 2.3.5 |
| JUnit | 4.13.2 |
| AndroidX Test JUnit / Espresso / Runner / Core | 1.3.0 / 3.7.0 / 1.6.2 / 1.6.1 |
| Robolectric | 4.16.1 |
| Kotlin coroutines test | 1.10.2 |

The app module declares Firebase AI, App Check, Retrofit, and other libraries, but a dependency declaration alone does not show that a feature is used at runtime. Several catalog entries are commented out or not referenced by active dependencies. [app/build.gradle.kts; `gradle/libs.versions.toml`]

## 2. Architecture

The packages divide the app by responsibility, although the repository is a single-activity application and some UI code knows about the concrete hybrid TTS manager.

- **Data:** `PmugRepository` handles import, document metadata, hashing, progress, and bookmarks. `PmugDao`, `PmugDatabase`, and `PmugEntities` define Room storage. `ThumbnailCache` renders/caches PDF thumbnails. [data/PmugRepository.kt, `prepareImport`, `commitPreparedDocument`; data/PmugDao.kt; data/PmugDatabase.kt; data/ThumbnailCache.kt]
- **Domain:** `PdfTextExtractor`, `AcademicTextCleaner`, and `SentenceSplitter` produce page text and sentence units. `TtsEngine`, `HybridTtsEngine`, `PuterTtsEngine`, and `AndroidTtsEngine` provide speech. Piper’s implementation and installer live in the `:piper` feature for release. `RetentionEngine` produces heuristic review cards. [domain/pdf; domain/tts; domain/retention/RetentionEngine.kt]
- **UI:** `MainActivity` routes between `LibraryScreen`, `ReaderScreen`, and `DebugScreen`. The library imports/selects documents; the reader coordinates extraction, speech, progress, bookmarks, and page navigation. [MainActivity.kt, `PmugApp`; ui/library/LibraryScreen.kt; ui/reader/ReaderScreen.kt]
- **Mascot:** `PmugMug` draws the character on Canvas; `PmugMood` describes its visible states. [mascot/PmugMug.kt, `PmugMug`; mascot/PmugState.kt]
- **Nudges:** `NudgeWorker` runs periodic checks, `NudgePolicy` evaluates eligibility, `UsageSignals` reads UsageStats events, and `NudgeNotifier` posts the notification. [nudge/NudgeWorker.kt, `schedule`/`doWork`; nudge/NudgePolicy.kt, `evaluate`; nudge/UsageSignals.kt; nudge/NudgeNotifier.kt]

### PDF-to-highlighted-speech data flow

```text
User picks/shares PDF
  -> LibraryScreen OpenDocument or MainActivity ACTION_VIEW/ACTION_SEND
  -> PmugRepository.prepareImport: copy to files/documents, SHA-256, page count
  -> PmugRepository.commitPreparedDocument -> Room document + initial progress
  -> ReaderScreen -> PdfTextExtractor.extractPdfFilePageByPage
       -> PDFBox extracts each page; JSON text cache can bypass extraction
       -> AcademicTextCleaner.cleanExtractedText
       -> SentenceSplitter.splitIntoSentences -> ParsedSentence(text, spokenText, page)
  -> ReaderScreen displays PdfRenderer page + that page's sentence list
  -> TtsEngine.speakSentence(id, spokenText, rate, pitch)
       -> selected cloud/Piper engine; on eligible failures HybridTtsEngine uses AndroidTtsEngine
  -> TtsEngineListener callbacks update sentence/word range and completion
  -> ReaderScreen highlights current text, scrolls with playback, advances, saves progress
```

Verified in code: imported files are copied under app-private `files/documents`, and extraction is performed from that copy. The reader publishes a cumulative sentence list as page callbacks arrive. Speech completion advances to the next sentence; word callbacks or a timer update the active word range. [PmugRepository.prepareImport/commitPreparedDocument; `ReaderScreen` extraction `LaunchedEffect`, listener, and `SentenceItem`; `TtsEngine`; `HybridTtsEngine`]

## 3. Key engineering decisions

### PDF extraction, rendering, and cache

**Problem.** A PDF must provide both readable text for speech and a visual page that preserves its original layout.

**Options.** The current production path uses PDFBox Android for text and Android `PdfRenderer` for page images. A deprecated byte/stream parser remains in `PdfTextExtractor`, but the file comment identifies PDFBox as the production path because it interprets font mappings. OCR would be another path for image-only documents; there is no OCR implementation or OCR dependency in the inspected app sources/build files. [PdfTextExtractor; `extractPdfFilePageByPage`, deprecated `extractFromPdfStream`; `ThumbnailCache.renderPageBitmap`; app/build.gradle.kts]

**What the code does.** `extractPdfFilePageByPage` loads the document with `PDDocument`, sets `PDFTextStripper.startPage` and `endPage` for each page, and calls the reader callback after extracting each page. It checks the joined extraction for nonblank text, a letter ratio of at least 0.35, and at least eight matches of two or more letters. If the test fails, it returns no `rawPages` and an explanation that the PDF may be scanned or use unsupported encoding. It writes per-page text to a sibling `<pdf-name>.text-cache.json`, keyed by source length and last-modified time. `ThumbnailCache` uses `PdfRenderer`; thumbnails are keyed by file hash, page, and width in an in-memory LRU and under `cacheDir/pmug_thumbnails`. [PdfTextExtractor, `extractPdfFilePageByPage`, `readTextCache`, `writeTextCache`; `ThumbnailCache.getPageThumbnail`, `renderPageBitmap`]

**Trade-off — verified in code:** page-at-a-time extraction allows the reader to expose pages progressively and the cache avoids repeating extraction when the local PDF’s length and modification time match. PDFBox and the text cache do not provide OCR. **Inferred:** using the existing PDF text layer keeps the implementation local and simpler than adding OCR/model acquisition, at the cost of scanned pages remaining silent.

**Limitation.** The readability test is a heuristic on the combined text, not a semantic validation of each page. It does not detect reading order or columns. The cache validity check uses file length and modification time rather than a digest; imported filenames are content-hash based, but the cache check itself does not compare SHA-256. The old stream parser and `extraction_report.md` are not evidence of production PDFBox performance: the report’s test calls the deprecated stream parser. [PdfTextExtractor; `PdfExtractionTest`; extraction_report.md]

### Academic text cleaning and sentence units

**Problem.** Extracted academic text contains line wraps, repeated page furniture, abbreviations, decimals, and math notation that can be poor speech input.

**What the code does.** `AcademicTextCleaner.cleanExtractedText` removes repeated first/last lines across pages, standalone page numbers, and common page labels; it joins pages, repairs a word split by hyphen plus newline, and normalizes horizontal whitespace. `SentenceSplitter` treats blank-line-separated blocks as paragraphs, identifies simple numbered/uppercase headings, and splits at terminal punctuation while exempting configured abbreviations and decimal points. It makes a `ParsedSentence` with `text` for display and `spokenText` from `prepareForSpeech`, which expands selected symbols/abbreviations. [AcademicTextCleaner, `cleanExtractedText`, `removeHeadersAndFooters`, `prepareForSpeech`; SentenceSplitter, `splitIntoSentences`, `splitParagraph`; data/model/ParsedSentence.kt]

**Highlighted versus spoken unit.** One `ParsedSentence` is the playback and sentence-highlight unit: `ReaderScreen` passes its `spokenText` to `speakSentence`, but renders its `text`. The displayed words therefore remain faithful to extracted text while speech may say normalized forms such as “squared” or “approximately.” Word-range offsets are applied to displayed `text`; cloud/Piper timing estimates also scan displayed `text`. [ReaderScreen, `playSentence`, `SentenceItem`, TTS listener; AcademicTextCleaner.prepareForSpeech]

**Trade-off — verified in code:** a small deterministic cleaner/splitter can prepare common academic text without an online NLP service. **Inferred:** that makes behavior predictable but rules cannot reliably resolve every abbreviation, equation, or sentence boundary.

**Limitation.** Header/footer detection only examines the first and last nonblank line on each page plus simple page-number patterns. Hyphen repair is regex-based and may join a legitimate hyphenated phrase. Sentence IDs are assigned after page parsing and concatenated in `ReaderScreen`; the parser does not model multi-column reading order or complex formula structure. [AcademicTextCleaner; SentenceSplitter; ReaderScreen extraction callback]

### TTS abstraction, routing, and fallback

**Problem.** Reader controls need a common way to play a sentence across Android, cloud, and optional local neural engines.

**What the code does.** `TtsEngine` exposes initialization, listener registration, sentence speech, stop/shutdown, voices, and voice selection. `HybridTtsEngine` maps provider modes to `PuterTtsEngine`, `PiperFeatureManager`, or `AndroidTtsEngine`. It remembers the last sentence/rate/pitch. Synchronous start exceptions and asynchronous errors for cloud/Piper modes trigger Android speech for that sentence and notify the UI; an “unauthorized” cloud error instead tells the user to sign in. Android mode does not fall back to itself. [TtsEngine.kt; HybridTtsEngine, `engineFor`, `speakSentence`, `fallbackToAndroid`, `onError`]

**Extension boundary.** Reader playback accepts the `TtsEngine` interface, so the sentence display and callback contract do not have to depend on a specific playback implementation. However, `ReaderScreen` casts the engine to `HybridTtsEngine` for provider selection, Piper installation, and Puter sign-in controls; a new selectable provider currently requires updates to `TtsProviderMode`/`HybridTtsEngine` and likely those settings controls. [ReaderScreen; HybridTtsEngine; TtsProviderMode]

**Trade-off — verified in code:** cloud/Piper errors can preserve reading continuity by handing a sentence to Android TTS, while unauthorized cloud use is surfaced for sign-in rather than silently replaced. **Inferred:** this favors continuity but may change voice mid-sentence and Android timing/voice may differ.

**Limitation.** The cloud engine depends on the remote Puter.js script and service, network access, and a signed-in Puter user. The checked-in code implements a popup WebView and sign-in state check, but cannot prove that the current external service flow succeeds. An engine that fails to send either completion or error callbacks can leave reader playback waiting. [PuterTtsEngine; HybridTtsEngine; ReaderScreen listener]

### Word timing and highlighting

**Problem.** Speech should keep the current text visible and indicate the active word where possible.

**What the code does.** Android TTS forwards `UtteranceProgressListener.onRangeStart` as `onWordRange`. The reader stores the start/end offsets and highlights that range within the current displayed sentence. `TtsProviderMode.usesEstimatedWordTiming` is true for cloud and Piper; while a sentence-start flag is set, `ReaderScreen` walks non-whitespace ranges at an estimated interval derived from a fixed 150 words/minute and playback speed (with a 120 ms minimum). Sentence callbacks select/advance the sentence, and an eased list scroll keeps it in view. [AndroidTtsEngine, `setupUtteranceListener`; TtsEngineListener; TtsProviderMode; ReaderScreen timing `LaunchedEffect`, `SentenceItem`, auto-scroll]

**Trade-off — verified in code:** native word offsets are used when delivered; sentence-timed/no-word-timing engines get a moving estimate. **Inferred:** the estimate keeps a visible cue without requiring forced alignment, but it is not synchronized to actual phoneme/word durations.

**Limitation.** The estimate uses a constant speaking-rate assumption, and speech speed/accent/pauses can make the highlight drift. Android callbacks depend on the installed TTS implementation reporting ranges. [ReaderScreen; AndroidTtsEngine]

### Room schema and repository

**Problem.** The library needs stable local documents, resume position, and bookmarks.

**What the code does.** `PmugRepository.prepareImport` copies the picked URI into `files/documents`, calculates SHA-256, uses the digest in the stored filename, and asks `ThumbnailCache.getPdfPageCount` for page count. `PmugDocumentEntity` stores ID, digest, title, path, page count, timestamps, pinned and finished flags. Progress stores page and sentence index; bookmarks store document ID, sentence index, snippet, and timestamp. `PmugDao` exposes flows and suspend queries; repository methods perform DB/file work on `Dispatchers.IO`. The Room DB is version 2 and uses destructive migration with `dropAllTables = true`. [PmugRepository, `prepareImport`, `calculateSha256`, `saveReadingPosition`, `addBookmark`, `deleteDocument`; PmugEntities; PmugDao; PmugDatabase]

**Trade-off — verified in code:** the local database and app-private copied PDF allow the library and reading position to work without a remote account/database. **Inferred:** destructive migration avoids maintaining migration paths in this iteration but can delete the library database on a schema change.

**Limitation.** There is no cloud sync in the inspected repository. Deleting a document deletes its PDF and matching text cache before removing the Room row; backup behavior is not defined by these classes. The manifest enables Android backup and references backup/data extraction XML rules, so “private” describes the storage location, not a claim that data can never be backed up. [PmugRepository.deleteDocument; PmugDatabase; app/src/main/AndroidManifest.xml]

### Reading nudges

**Problem.** A reading reminder should avoid interrupting current playback, quiet hours, or a user who appears asleep/away.

**What the code does.** `MainActivity.onCreate` schedules unique periodic work every 30 minutes with a 10-minute flex window and `KEEP` policy. `NudgeWorker.doWork` checks snooze, resets a date-based daily count, reads last Pmug activity and usage permission/unlock signals, and calls the pure `NudgePolicy.evaluate`. Policy skips while audio is playing, during quiet hours (default 23:00–07:00), within 2.5 hours of app activity, after two consecutive dismissals until six hours have elapsed, at a daily cap (3 with Usage Access, 2 without), and within two hours of the prior reminder. If Usage Access is granted and an unlock signal exists, it also skips when the last unlock/screen-interactive event is more than 45 minutes old. `UsageSignals` looks back 12 hours for event types 18 and 15. [MainActivity.onCreate; NudgeWorker.schedule/doWork; NudgePolicy.evaluate; UsageSignals.getLastUnlockTime; NudgePreferences]

**Trade-off — verified in code:** WorkManager and multiple independent gates provide periodic, best-effort checks; without usage permission the cap is lower and the unlock gate is omitted. **Inferred:** UsageStats is a proxy for wakefulness, not proof that a user is awake or available.

**Limitation.** `NudgeWorker` currently passes `isAudioPlaying = false` unconditionally, so the policy’s “audio is playing” check is only effective in direct policy calls/tests, not from this background worker. The worker does not receive live app playback state. The checkout has no usable history from which to verify what the “earlier app” did; see author-confirmation question. [NudgeWorker.doWork; NudgePolicy.evaluate; NudgePolicyTest]

### APK size and Piper delivery

**Problem.** sherpa-onnx brings native libraries and the Piper model is a separate large asset; packaging every ABI and the model in the base install would add payload even for users who do not select Piper.

**What the code/build does.** ABI splits include `arm64-v8a`, `armeabi-v7a`, `x86_64`, and `x86`, with no universal APK. `:piper` is a dynamic feature and declares sherpa-onnx v1.13.8 plus Commons Compress 1.27.1. In release, those are feature dependencies; the app’s Piper lookup uses a provider interface and reflection. In debug, `PIPER_BUNDLED_FOR_DEBUG=true`, Piper source is added to the app debug source set, and the native/archive dependencies are debug-only app dependencies. Release minification is explicitly disabled (`isMinifyEnabled = false`), so R8 shrinking is not enabled for release in this configuration. [app/build.gradle.kts; piper/build.gradle.kts; settings.gradle.kts; PiperFeatureManager.loadProvider]

The Lessac medium model is downloaded by `PiperModelInstaller` on first install into `files/tts/piper`, then validated and extracted from a temporary archive; it is not an APK asset. `PiperFeatureManager` requests the `piper` module on demand in release and defers its uninstall after model removal. [PiperModelInstaller.ensureInstalled/deleteDownloadedModel; PiperFeatureManager.prepare/remove]

**Trade-off — verified in code:** release feature delivery separates optional Piper implementation from base dependencies and model download is opt-in. **Inferred:** users who do not use Piper avoid its model download, but users who do use it need network/storage for setup and a compatible ABI. The configured on-demand module is the mechanism chosen in this project; this repository alone does not establish Play policy approval.

**Observed sizes.** Existing debug APK artifacts in `app/build/outputs/apk/debug` were 55 MB (armeabi-v7a), 64 MB (arm64-v8a), 68 MB (x86_64), and 69 MB (x86), as reported by `ls -lh`. These are debug APK sizes from the existing build output, not release download sizes or AAB/device-delivered sizes. No verified release APK/AAB size was present. [app/build/outputs/apk/debug]

**Limitation.** PDFBox and other base dependencies still contribute to base size; ABI splitting only chooses a native architecture per APK. There is no build output here that attributes bytes to individual libraries. `R8` is not enabled for release, and no actual Play-delivered size is verified. [app/build.gradle.kts]

## 4. Debugging log

The following four incident descriptions come from the bug notes supplied with the task. The checkout has an empty `.git` directory and no runtime incident log; the only local Kotlin error log records a Compose compiler/runtime classpath error, not these incidents. I therefore treat the reported symptoms as author-provided history and cite code only for the current implementation and fix state.

### a. Piper provider unavailable in a base-only install

- **Symptom:** The supplied report says loading `com.example.piper.PiperProviderImpl` raised `ClassNotFoundException`, with only `base.apk` on the DexPathList.
- **How it was diagnosed:** The supplied report identified a base-only install. Current `PiperFeatureManager` logs module state, installed splits, ABI, and the exception from provider reflection; it checks whether the feature is installed before loading the class. [PiperFeatureManager, `isModuleInstalled`, `loadProvider`]
- **Root cause:** The provider implementation is in `:piper`, configured as an on-demand dynamic feature. A plain debug install that lacks that split cannot load the provider unless Piper is included in that debug APK. [piper/build.gradle.kts; piper/src/main/AndroidManifest.xml; PiperFeatureManager.loadProvider]
- **Fix/current state:** Debug sets `PIPER_BUNDLED_FOR_DEBUG=true`, adds `piper/src/main/java` to the debug source set, and adds sherpa-onnx/Commons Compress as `debugImplementation`. Release keeps `:piper` dynamic and requests it through Play Feature Delivery. The base communicates through `PiperProvider` and reflection. [app/build.gradle.kts; PiperProvider.kt; PiperFeatureManager.prepare/loadProvider]
- **Lesson:** Test the feature from the same packaging/install path used by the target build. This fix is present in Gradle/source; the supplied runtime event itself is not present in repository logs.

### b. Synthesized Piper samples but no audible playback

- **Symptom:** The supplied report describes valid samples (peak about 0.66 at about 22,050 Hz) but no audible Piper voice, and a `MediaPlayer` released roughly 40 ms after creation without prepare/start/error evidence. Android fallback speech could be mistaken for Piper.
- **How it was diagnosed:** The report distinguishes synthesis from playback and notes that the audible voice was Android’s fallback. Current Piper diagnostics log sample peak/rate/count, AudioTrack creation, writes, `play()`, completion, and failures; the debug screen exposes a Piper self-test. [PiperTtsEngine.playSamples/runSelfTest; DebugScreen]
- **Root cause:** Per the supplied report, generated audio was not being kept alive and played through the prior `MediaPlayer` lifecycle. That historical implementation is not in this checkout, so this root cause is not independently verifiable from current source.
- **Fix/current state:** Current `PiperTtsEngine` uses `AudioTrack` with PCM float mono output, `MODE_STREAM`, blocking sample writes on the worker executor, and a playback marker to complete the sentence. [PiperTtsEngine.playSamples]
- **Lesson:** Validate audible playback as a separate stage after synthesis; a nonempty waveform and sample rate do not prove the output path is audible. The fix is verified in current code, not by a runtime log in this checkout.

### c. AudioTrack initialization state 2

- **Symptom:** The supplied report says `AudioTrack` initialization failed with `state=2` when using `MODE_STATIC`.
- **How it was diagnosed:** The supplied explanation identifies `STATE_NO_STATIC_DATA` before data was written. Current code logs AudioTrack state and checks initialization.
- **Root cause:** A static buffer can report that state before the required samples are loaded; requiring initialized state immediately after construction is incompatible with that setup. The old static-mode code is absent here, so this diagnosis relies on the supplied report.
- **Fix/current state:** The current implementation uses `MODE_STREAM`; it checks `STATE_INITIALIZED` after building the stream track, then writes chunks and starts playback after the first successful write. [PiperTtsEngine.playSamples]
- **Lesson:** Initialization checks must match the lifecycle of the chosen transfer mode. Current code no longer uses static mode, but this checkout contains no runtime trace proving playback across devices.

### d. Puter.js Unauthorized and authentication popup

- **Symptom:** The supplied report describes an “Unauthorized” response in WebView and an authentication popup that the previous WebView setup did not handle.
- **How it was diagnosed:** Current `PuterTtsEngine` logs WebView console, HTTP, navigation, and auth-popup errors. `HybridTtsEngine.onError` maps an unauthorized cloud error to a sign-in message rather than falling back. [PuterTtsEngine.initWebView/PuterBridge; HybridTtsEngine.onError]
- **Root cause:** The supplied report says cloud speech requires a signed-in Puter account and the auth flow opens a window. The code exposes a sign-in action and configures multiple windows, but live Puter account/service behavior cannot be validated from source alone.
- **Fix/current state:** `PuterTtsEngine` enables multiple windows and implements `WebChromeClient.onCreateWindow` by placing an auth WebView in a dialog; it refreshes sign-in status when pages finish or the popup closes. `ReaderScreen` offers “Sign in to Puter” and refreshes the status. [PuterTtsEngine.initWebView; ReaderScreen voice settings]
- **Lesson:** Embedded browser authentication needs an explicit popup lifecycle and observable auth state. **Current runtime status: unverified.** The checked-in implementation is present, but I cannot say that cloud voices currently work against the live Puter service without a runtime result.

### Other checked-in evidence

`PdfExtractionTest` uses the deprecated byte-stream extractor, while production `ReaderScreen` calls `extractPdfFilePageByPage` and thus PDFBox. This means the existing sample extraction report does not validate the current production extraction path. [PdfExtractionTest; PdfTextExtractor; ReaderScreen]

The checkout’s `.git` directory contains no Git metadata; `git log` cannot be read.

## 5. Known limitations and technical debt

- **Text layer only:** scanned/image-only PDFs have no OCR path and remain unspoken; the reader displays a message directing users to OCR. [ReaderScreen; PdfTextExtractor]
- **Reading order:** column and layout reconstruction is not implemented; PDFBox text order is used as returned. Multi-column text may interleave. [PdfTextExtractor; SentenceSplitter]
- **Heuristic parsing:** readability, header/footer stripping, heading detection, sentence boundaries, and math pronunciation are rule-based. [PdfTextExtractor; AcademicTextCleaner; SentenceSplitter]
- **Cloud service dependency:** Puter.js is loaded remotely from `https://js.puter.com/v2/`; cloud speech depends on network, authentication, service availability, and third-party terms/behavior. Terms and availability are not stored in this repository. [PuterTtsEngine.initWebView]
- **Highlight timing:** non-Android word highlighting is estimated at a fixed nominal rate and can drift from audio. [ReaderScreen timing effect]
- **Nudge playback gate:** the worker hardcodes `isAudioPlaying = false`, so it cannot actually know whether foreground audio is playing. [NudgeWorker.doWork]
- **Destructive Room migration:** schema version changes drop all tables if there is no supported migration. [PmugDatabase.getInstance]
- **Debug/release split differs:** local debug includes Piper implementation/native dependencies in the base APK while release uses the feature split. A local debug result alone does not validate release delivery. [app/build.gradle.kts; PiperFeatureManager]
- **No release shrinking:** release `isMinifyEnabled` is false. [app/build.gradle.kts]
- **Legacy/duplicate UI infrastructure:** `ui/theme` and `core/theme` both define theme/color/typography concepts, but `MainActivity` applies `PmugTheme` from `core.theme`; `AddToShelfSheet` is documented as legacy while the library imports and opens directly. [MainActivity; ui/theme; core/theme; AGENTS.md]
- **Retention engine integration:** `RetentionEngine` creates heuristic cards and data models exist, but the checked `ReaderScreen` path does not show a retention-card workflow. Treat product exposure as unconfirmed. [RetentionEngine; ReaderScreen; `AcademicDocument`]
- **Import deduplication:** SHA-256 is computed and used for the stored path, but `commitPreparedDocument` creates a new random document ID and does not call `getDocumentByHash`; duplicate-library behavior should be confirmed. [PmugRepository.prepareImport/commitPreparedDocument; PmugDao.getDocumentByHash]
- **Room backup/privacy policy:** manifest backup is enabled. The referenced XML rules need review before making a stronger privacy/backup promise. [app/src/main/AndroidManifest.xml]

## 6. What I would do next

1. **Add an integration test for the production PDFBox path.** Existing `PdfExtractionTest` exercises the deprecated stream parser, while the reader uses PDFBox. Verify per-page text, page counts, readability failures, and cache hits against representative PDFs. [PdfExtractionTest; PdfTextExtractor]
2. **Fix the nudge worker’s playback signal.** It passes `false` to a policy that explicitly gates on active playback, so the worker can still notify during speech. Provide a durable/observable signal or remove the misleading gate and test the actual worker behavior. [NudgeWorker; NudgePolicy]
3. **Verify cloud sign-in and speech on current Android/WebView versions.** The source implements `onCreateWindow`, sign-in, and unauthorized messaging, but runtime success is unknown. Keep logs for auth state and test each configured provider/model. [PuterTtsEngine; ReaderScreen; HybridTtsEngine]
4. **Exercise Piper through both delivery paths on each shipped ABI.** The debug APK bundles Piper while release requests a dynamic module; confirm provider loading, model install, audio playback, removal, and fallback on actual release splits. [app/build.gradle.kts; PiperFeatureManager; PiperTtsEngine]
5. **Improve document handling for scale and layout.** The reader accumulates all extracted sentences in Compose state even though extraction is page-by-page. A 1,000-page file may increase memory, repeated list flattening work, and state-update cost; measure before redesigning around paged persistence/virtualization. [ReaderScreen extraction effect; PdfTextExtractor]
6. **Add explicit Room migrations and import deduplication behavior.** Destructive migration can erase the library, and the hash lookup is not used in the import commit path. [PmugDatabase; PmugRepository; PmugDao]
7. **Review APK/AAB size with release artifacts and dependency attribution.** Current size evidence is debug APK output only, release shrinking is off, and no release bundle size is checked in. [app/build.gradle.kts; `app/build/outputs/apk/debug`]

## 7. FAQ

### 1. How was the app built, and what part is hardest?

The checked-in app is a Kotlin Android application using a single `MainActivity`, Compose screens, Room, PDFBox Android, and several `TtsEngine` implementations. The `ReaderScreen` coordinates incremental extraction, page selection, speech callbacks, scrolling, progress writes, and highlighting. That coordination is the densest integration point visible in the code; calling it the hardest part is my engineering assessment, not a historical author quote. [MainActivity; ReaderScreen; app/build.gradle.kts]

### 2. Why PDFBox instead of another PDF library?

The source comment says the production path uses PDFBox Android so PDF font mappings are interpreted, unlike the old byte-scanning parser. Android `PdfRenderer` is used for displayed pages and thumbnails, not text extraction. No comparison benchmark or historical library evaluation is present, so a broader claim that PDFBox was objectively superior would need author confirmation. [PdfTextExtractor; ThumbnailCache]

### 3. How does sentence highlighting stay in sync with audio?

Each `ParsedSentence` has a stable integer ID plus display `text` and TTS `spokenText`. Reader callbacks identify the active sentence; Android TTS can also report character ranges through `onRangeStart`. Cloud/Piper modes use an estimated word timer because the current interface does not receive their word alignment. [ParsedSentence; ReaderScreen; AndroidTtsEngine; TtsEngineListener]

### 4. What happens when a TTS engine fails?

`HybridTtsEngine` routes a selected voice to Puter, Piper, or Android. Synchronous exceptions and asynchronous errors from modes marked as fallback-capable start Android speech for the remembered sentence and show an error message. Unauthorized cloud responses are treated specially and ask the user to sign in instead of silently switching voices. [HybridTtsEngine.speakSentence/onError]

### 5. Why move Piper out of the base APK, and how?

Release declares `:piper` as a dynamic feature delivered on demand; the base module has only the `PiperProvider` interface and loads its implementation reflectively after the module is installed. Debug deliberately bundles Piper source and dependencies into the base APK for local installation convenience. This is the project’s configured delivery design; repository files do not prove Play Console approval or final store behavior. [app/build.gradle.kts; piper/build.gradle.kts; PiperFeatureManager]

### 6. How do cloud voices work, and what is the risk?

`PuterTtsEngine` loads Puter.js in a hidden WebView and calls `puter.ai.txt2speech` with Gemini Puck or OpenAI model/voice settings. The settings include a Puter sign-in action; the engine handles popup windows and reports auth state. Runtime availability, terms, authentication success, and provider support are external dependencies that cannot be guaranteed from this source. [PuterTtsEngine; TtsProviderMode; ReaderScreen]

### 7. How does the app stay small?

The code splits APKs by four ABIs and does not build a universal APK. Release places sherpa-onnx and Commons Compress in the optional `:piper` feature, and the voice model is downloaded to app-private files only when installed. There is no actual release size or library byte attribution in the checked-in output; existing debug APKs are 55–69 MB and include debug-specific contents. [app/build.gradle.kts; piper/build.gradle.kts; PiperModelInstaller; build outputs]

### 8. How does text extraction handle bad PDFs?

`PdfTextExtractor` catches exceptions and returns an unsuccessful result with an error message. It also rejects combined text that is blank, has a letter ratio below 0.35, or has fewer than eight two-letter words, which catches some scanned or badly encoded documents. Those thresholds are heuristics and do not guarantee the extracted text is readable or in correct order. [PdfTextExtractor.extractPdfFilePageByPage]

### 9. How does the text cache work?

The cache is a JSON file beside the imported PDF, containing page strings, source byte length, last-modified time, page count, success state, and error. If source length and modification time still match, extraction replays cached pages to the callback. It does not use the SHA-256 field to validate cache freshness. [PdfTextExtractor.readTextCache/writeTextCache]

### 10. How do nudges avoid waking someone?

`NudgePolicy` observes quiet hours (23:00–07:00 by default), the most recent screen/unlock event when Usage Access exists, a daily cap, a two-hour gap between nudges, and time since reading activity. The worker runs every 30 minutes with a 10-minute flex window. UsageStats is only an indirect signal, and the worker currently supplies `isAudioPlaying = false`, so the playback guard is not connected to live state. [NudgePolicy; NudgeWorker; UsageSignals]

### 11. Where is user data stored, and what is private?

PDF copies are under app-private `files/documents`, the text cache sits beside each copy, Room stores library/progress/bookmarks in `pmug_library.db`, and nudge preferences use private SharedPreferences. The app manifest has backup enabled and references backup-rule resources, so private app storage alone does not establish whether backups include these files. No server sync is implemented in the inspected repository. [PmugRepository; PmugDatabase; NudgePreferences; AndroidManifest.xml]

### 12. What works offline?

PDF import from an available URI, PDFBox extraction, rendering, local Room state, Android System TTS, and Piper after its model is downloaded are coded locally. Cloud voice setup and playback require network access; Piper’s first model install also downloads an archive. [PdfTextExtractor; ThumbnailCache; AndroidTtsEngine; PiperModelInstaller; PuterTtsEngine]

### 13. How would the app scale or be monetized?

The source contains no billing, subscription, analytics, account, or sync implementation, so there is no verified monetization model. For larger PDFs, the current reader accumulates sentence lists in Compose state and flattens them for rendering, which deserves profiling and possibly paged storage/state. Monetization choices would need to account for third-party voice service terms and any model hosting costs; no specific plan is evidenced here. [ReaderScreen; app/build.gradle.kts]

### 14. How was it tested?

The repository contains unit tests for nudge policy, sentence cleaning/splitting, bionic splitting, and a sample-PDF extraction report, plus simple Robolectric and instrumentation smoke tests. `PdfExtractionTest` calls the deprecated stream extractor rather than the PDFBox production path. I did not run tests for this documentation task, and a checked-in extraction report is not a full test result for all PDF types. [app/src/test; app/src/androidTest; PdfExtractionTest; extraction_report.md]

### 15. What was the hardest bug?

The supplied bug notes identify Piper’s silent playback after successful synthesis as a key problem. The current code separates native synthesis from `AudioTrack` output, logs sample characteristics and write/play/complete events, and includes a diagnostic self-test. The runtime incident itself is not in this checkout’s logs, so this is the reported history, not an independently verified retrospective. [PiperTtsEngine; DebugScreen]

### 16. What is the biggest weakness?

The clearest source-level weakness is that several reader-facing guarantees rely on estimates or heuristics: extraction readability, sentence segmentation, and cloud/Piper word timing. Separately, the nudge worker cannot observe active playback despite a policy check for it. Which issue matters most to users should be confirmed with usage evidence; this repository has none. [PdfTextExtractor; SentenceSplitter; ReaderScreen; NudgeWorker]

### 17. What would break first with a 1,000-page PDF?

I cannot claim a measured failure point because no benchmark is present. The likely pressure points, inferred from code, are extraction time and memory: `PdfTextExtractor` temporarily keeps all page strings, and `ReaderScreen` keeps a growing cumulative sentence list and flattens it when the list changes. The displayed list is limited to the selected page, but the accumulated model is not. [PdfTextExtractor.extractPdfFilePageByPage; ReaderScreen extraction effect and `sentences`]

### 18. Why use the sentence as the playback unit?

`ReaderScreen` passes one `ParsedSentence.spokenText` to each `speakSentence` call and advances on `onSentenceCompleted`. This gives the UI a direct sentence ID for progress, highlighting, skip, and bookmark behavior. The trade-off inferred from that contract is that natural speech continuity across sentence boundaries depends on engine startup and callback behavior. [ParsedSentence; TtsEngine; ReaderScreen listener]

### 19. Why use Room and a repository rather than storing everything in Compose state?

The library needs document records and saved positions across launches, so `PmugRepository` persists those through Room and exposes a `Flow` of documents. Compose state is used for transient reader UI such as active sentence and zoom. The separation is visible in code; no architecture decision record comparing alternatives is present. [PmugRepository; PmugDao; ReaderScreen]

### 20. What happens if an imported PDF has no extractable text?

The page count and `PdfRenderer` display path can still succeed while PDFBox returns no readable text. The reader then shows a message that scanned PDFs need OCR and offers no built-in OCR path. Since no parsed sentences exist, there is no content for TTS to speak. [PmugRepository.prepareImport; PdfTextExtractor; ReaderScreen]

### 21. Why is the Piper model downloaded instead of packaged?

`PiperModelInstaller` downloads the Lessac medium tar.bz2 archive on demand, validates the ONNX model, tokens, and phoneme data, then stores them under `files/tts/piper`. Keeping model data out of APK assets avoids bundling it for every installation in this design. The project docs mention about 300 MB temporary free storage, but no measured model size is asserted here. [PiperModelInstaller; README.md]

### 22. What makes the nudge policy testable?

`NudgePolicy.evaluate` is a pure Kotlin function over a `NudgeInputs` data object, including an explicit timestamp and usage permission signals. `NudgePolicyTest` creates fixed clock values and checks send/skip cases without running WorkManager. The worker integration and real UsageStats access are not covered by that unit test. [NudgePolicy; NudgePolicyTest; NudgeWorker]

### 23. How does the app protect playback if cloud speech is unavailable?

For most cloud and Piper errors, `HybridTtsEngine` sends the current sentence to `AndroidTtsEngine` and reports the fallback in a UI message. For unauthorized Puter access it asks the user to sign in; it does not start Android speech in that branch. Android TTS availability and language data still depend on the device. [HybridTtsEngine.onError; AndroidTtsEngine]

### 24. Did you use AI to write this?

The repository itself does not record which parts were authored with AI, so I cannot answer that factually from code. Regardless of authorship, the maintainer needs to understand why the base app uses a provider interface/reflection for Piper, how release and debug packaging differ, how sentence IDs connect speech callbacks to UI state, and how to distinguish synthesis from audible playback. The supplied incident notes describe diagnosing those decisions, but there is no Git history here to establish who wrote which code. [PiperProvider; PiperFeatureManager; ReaderScreen; piper/PiperTtsEngine]

## 8. Glossary

- **ABI:** Application Binary Interface; here it identifies which CPU architecture’s native libraries an APK contains.
- **AAB:** Android App Bundle, a publishing artifact from which device-specific APK splits can be delivered. No AAB size is present here.
- **Android System TTS:** Android’s device-provided text-to-speech service, wrapped by `AndroidTtsEngine`.
- **AudioTrack stream/static:** Android PCM playback modes. Stream mode accepts chunks over time; static mode uses a preloaded buffer. Current Piper code uses stream mode.
- **Bionic text:** A text style that bolds leading characters in each word; `BionicText` uses `RetentionEngine.splitForBionicReading`.
- **Dynamic feature module:** An Android app module configured for separate feature delivery; this project names it `:piper` and requests it on demand in release.
- **ONNX:** Open Neural Network Exchange, a model format/runtime ecosystem used here by the Piper voice model.
- **PDFBox Android:** The PDFBox Android library used by `PdfTextExtractor` to obtain selectable text from PDF pages.
- **PdfRenderer:** Android API used by `ThumbnailCache` and the reader to rasterize PDF pages for display.
- **Piper:** Local neural TTS implementation in `:piper`, powered here by sherpa-onnx and an on-demand voice model.
- **Play Feature Delivery:** Google Play’s feature-module delivery mechanism referenced by the dynamic feature/Play Core configuration. This document verifies project configuration, not store approval.
- **R8:** Android code shrinking/optimization tool. Release minification is disabled in the checked-in Gradle config.
- **Room:** Android persistence library used for documents, reading progress, and bookmarks.
- **SHA-256:** Cryptographic digest calculated for imported PDFs and used in their private-storage filename.
- **sherpa-onnx:** Native speech toolkit dependency used to run Piper inference.
- **TTS:** Text-to-speech, converting text into audio.
- **UsageStats:** Android usage-event API used to approximate recent screen/unlock activity for nudges.
- **WorkManager:** Android library for persistent deferrable background work; used for periodic nudge checks.
- **Word timing:** Character offsets or estimated word positions used to highlight text during speech.

## 9. Needs author confirmation

- Does the current Puter popup/sign-in flow successfully authenticate and produce Gemini/OpenAI audio on supported devices? The source implements the flow but has no runtime success evidence here.
- Are the supplied Piper `MediaPlayer` and static `AudioTrack` bug descriptions accurate incident records, and are there logs or commits that document those fixes? This checkout has no usable Git history or corresponding runtime logs.
- What was the “known limitation from the earlier app” for reading nudges? The current code shows an unconnected playback gate, but history is unavailable.
- Should imported PDFs with an existing SHA-256 be deduplicated in the library? A DAO hash lookup exists, but `commitPreparedDocument` does not use it.
- Are `RetentionEngine` and `RetentionCard` intended to appear in the shipped UI, or are they currently unused scaffolding?
- Do the backup/data-extraction XML rules exclude imported PDFs, text caches, Room data, and preferences, or should the privacy description account for backup inclusion?
- Is the project intentionally still targeting API 36 with release shrinking disabled, and are there release APK/AAB sizes that should replace the debug-only size evidence?
- Is the on-demand `:piper` module configured and accepted in the intended Play release workflow? This checkout proves Gradle configuration, not Play Console validation.
- Which current user-facing theme is authoritative? `MainActivity` uses `core.theme`, while a second `ui.theme` palette/theme remains in source.
- Is the README’s “built-in PDF stream parser” description obsolete? Production reader code uses PDFBox, while the deprecated stream parser is still used by `PdfExtractionTest`.
