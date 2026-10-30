# Developer & AI Agent Guide for Pmug

This document serves as the primary technical reference for developers and AI agents working on the **Pmug** codebase.

---

## 1. Project Overview & Architecture

**Pmug** is an Android academic PDF reader and text-to-speech companion app designed for distraction-free reading, bionic typography scanning, and audio playback powered by Gemini/OpenAI TTS through Puter.js and Android System TTS.

### Core Stack
* **UI**: Jetpack Compose with Material 3 design system (`PmugTokens`).
* **Database**: Room Database (`PmugDatabase`, `PmugDao`) for documents, reading progress, and bookmarks.
* **PDF Engine**: PDFBox Android (`PdfTextExtractor`) for font-aware text extraction and Android `PdfRenderer` for displayed PDF pages and thumbnails (`ThumbnailCache`).
* **TTS Engine**: `HybridTtsEngine` orchestrating Gemini Puck, OpenAI GPT-4o mini TTS, or TTS-1-HD via Puter.js, with automatic fallback to offline `AndroidTtsEngine`.
* **Mascot**: Canvas/Vector drawn interactive companion (`PmugMug`).
* **Background Processing**: WorkManager (`NudgeWorker`) for periodic reading habit check-ins.

---

## 2. Directory & File Map

```
app/src/main/java/com/example/
├── MainActivity.kt                      # Single Activity, Compose routing, incoming Intent handling
├── data/
│   ├── PmugDatabase.kt                  # Room database instance
│   ├── PmugDao.kt                       # DAO for Room DB operations
│   ├── PmugEntities.kt                  # Entity models (PmugDocumentEntity, PmugProgressEntity, PmugBookmarkEntity)
│   ├── PmugRepository.kt                # Repository layer for file imports, DB queries, SHA-256 hashing
│   ├── ThumbnailCache.kt                # PDF page thumbnail renderer and cache wrapper
│   └── model/                           # Data models (AcademicDocument, ParsedSentence, RetentionCard)
├── domain/
│   ├── pdf/
│   │   ├── PdfTextExtractor.kt          # PDFBox Android page-by-page text extraction and readability check
│   │   ├── AcademicTextCleaner.kt       # Strips headers/footers, fixes hyphenation, normalizes math for TTS
│   │   └── SentenceSplitter.kt          # Splits cleaned academic text into spoken & displayed sentence units
│   ├── tts/
│   │   ├── TtsEngine.kt                 # Common interface for text-to-speech engines
│   │   ├── PuterTtsEngine.kt            # Activity-backed WebView bridge to Gemini/OpenAI TTS via Puter.js
│   │   ├── AndroidTtsEngine.kt          # Built-in offline Android TextToSpeech engine
│   │   └── HybridTtsEngine.kt           # Hybrid TTS manager with cloud voice selection and Android fallback
│   └── retention/
│       └── RetentionEngine.kt           # Key takeaway and retention review card generator
├── mascot/
│   ├── PmugMug.kt                       # Vector/Canvas animated mascot composable
│   └── PmugState.kt                     # Mascot state & mood enums (IDLE, READING, PARSING, etc.)
├── nudge/
│   ├── NudgeWorker.kt                   # WorkManager periodic worker (checks every 30 mins)
│   ├── NudgePolicy.kt                   # Rules engine deciding nudge notifications
│   ├── NudgeNotifier.kt                 # System notification manager
│   ├── NudgePreferences.kt              # SharedPreferences for activity tracking
│   └── UsageSignals.kt                  # Android usage stats query helper
└── ui/
    ├── library/
    │   ├── LibraryScreen.kt             # Main library screen (shelves, staggered grid, search, import FAB)
│   └── AddToShelfSheet.kt           # Legacy import confirmation sheet (library now opens imported PDFs directly)
    ├── reader/
    │   └── ReaderScreen.kt              # Academic reader with sentence highlighting, TTS controls, font size, bookmarks
    ├── components/
    │   └── BionicText.kt                # Bionic reading text component (bolds leading word letters)
    ├── debug/
    │   └── DebugScreen.kt               # Developer debug menu for DB inspection and TTS testing
    ├── onboarding/
    │   └── UsagePermissionDialog.kt    # Permission dialog for usage statistics
    └── theme/                           # Color, Typography, and Material 3 theme configuration
```

---

## 3. Issue Resolution & File Guide

Piper implementation and its sherpa-onnx/ONNX Runtime libraries live in the `:piper` Play Feature Delivery module for release. The base app depends only on `PiperProvider` and Play Feature Delivery in release; base sources must not import Piper implementation classes. `PiperTtsEngine.kt`, `PiperModelInstaller.kt`, and `PiperProviderImpl.kt` are under `piper/src/main/java/`. Debug builds set `PIPER_BUNDLED_FOR_DEBUG=true`, add that source directory to the app debug source set, and add sherpa-onnx/Commons Compress as debug-only dependencies, placing the reflected provider and its native libraries in the base debug APK for Android Studio Run.

When tasked with fixing a bug or adding a feature, refer to this mapping:

| Problem / Task | Files to Inspect & Modify |
|---|---|
| **PDF File Import or Extraction fails** | [`PmugRepository.kt`](file:///home/chetraj/Downloads/pmug(2)/app/src/main/java/com/example/data/PmugRepository.kt) & [`PdfTextExtractor.kt`](file:///home/chetraj/Downloads/pmug(2)/app/src/main/java/com/example/domain/pdf/PdfTextExtractor.kt) |
| **Cloud or Text-to-Speech playback issues** | [`HybridTtsEngine.kt`](file:///home/chetraj/Downloads/pmug(2)/app/src/main/java/com/example/domain/tts/HybridTtsEngine.kt) & [`PuterTtsEngine.kt`](file:///home/chetraj/Downloads/pmug(2)/app/src/main/java/com/example/domain/tts/PuterTtsEngine.kt); Piper feature loading is in `PiperFeatureManager.kt`, and Piper internals are in `piper/` |
| **Reader sentence highlighting, auto-scroll, playback speed** | [`ReaderScreen.kt`](file:///home/chetraj/Downloads/pmug(2)/app/src/main/java/com/example/ui/reader/ReaderScreen.kt) |
| **Library shelves, sorting, context menus, search** | [`LibraryScreen.kt`](file:///home/chetraj/Downloads/pmug(2)/app/src/main/java/com/example/ui/library/LibraryScreen.kt) |
| **Academic text cleaning or TTS math symbol pronunciation** | [`AcademicTextCleaner.kt`](file:///home/chetraj/Downloads/pmug(2)/app/src/main/java/com/example/domain/pdf/AcademicTextCleaner.kt) & [`SentenceSplitter.kt`](file:///home/chetraj/Downloads/pmug(2)/app/src/main/java/com/example/domain/pdf/SentenceSplitter.kt) |
| **Mascot visuals, expressions, or state transitions** | [`PmugMug.kt`](file:///home/chetraj/Downloads/pmug(2)/app/src/main/java/com/example/mascot/PmugMug.kt) & [`PmugState.kt`](file:///home/chetraj/Downloads/pmug(2)/app/src/main/java/com/example/mascot/PmugState.kt) |
| **Database schema or persistence changes** | [`PmugEntities.kt`](file:///home/chetraj/Downloads/pmug(2)/app/src/main/java/com/example/data/PmugEntities.kt), [`PmugDao.kt`](file:///home/chetraj/Downloads/pmug(2)/app/src/main/java/com/example/data/PmugDao.kt), [`PmugDatabase.kt`](file:///home/chetraj/Downloads/pmug(2)/app/src/main/java/com/example/data/PmugDatabase.kt) |

---

## 4. Build & Test Commands

* **Compile & Build ABI-specific Debug APKs** (install the APK matching the target device; most current phones use `arm64-v8a`):
  ```bash
  ./gradlew app:assembleDebug
  ```
* **Run Unit Tests**:
  ```bash
  ./gradlew app:testDebugUnitTest
  ```

## 5. Reader behavior notes

Choosing a PDF copies it into app-private storage and opens the reader after reading the page count. PDFBox Android then extracts and publishes one page at a time, with page-level text cached beside the PDF. The reader displays only the selected page's sentences; the rendered PDF page stays above that text, follows speech, supports one-finger horizontal page swipes, and zooms around the pinch midpoint. Double-tap zooms at the tapped location, and dragging pans a zoomed page. Speech keeps the active sentence in view without manual scrolling. Puter.js supplies Gemini Puck (`gemini-3.8-flash-tts`), OpenAI GPT-4o mini TTS, or OpenAI TTS-1-HD; each play action sends one complete sentence. Gemini receives energetic academic reading instructions. Cloud voices report sentence timing, so the UI estimates word timing for the active highlight. Android System Speech reports word-range callbacks when supported. Cloud and Piper failures fall back to Android TTS for that sentence. Piper is an on-demand feature and downloads its Lessac medium model into `files/tts/piper`; reader voice selection reports combined feature/model install progress and can remove the model plus request deferred feature uninstall. Scanned image PDFs render normally, but require OCR for spoken/extracted text. A previously extracted document opens from the local text cache without reparsing.
