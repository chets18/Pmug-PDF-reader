# Developer & AI Agent Guide for Pmug

This document serves as the primary technical reference for developers and AI agents working on the **Pmug** codebase.

---

## 1. Project Overview & Architecture

**Pmug** is an Android academic PDF reader and text-to-speech companion app designed for distraction-free reading, bionic typography scanning, and audio playback powered by Piper offline neural TTS and Android System TTS.

### Core Stack
* **UI**: Jetpack Compose with Material 3 design system (`PmugTokens`).
* **Database**: Room Database (`PmugDatabase`, `PmugDao`) for documents, reading progress, and bookmarks.
* **Document Engine**: PDFBox Android (`PdfTextExtractor`) for PDF extraction and Android `PdfRenderer` for PDF pages; `DocumentText` handles TXT, Markdown, and DOCX without a heavy document library.
* **TTS Engine**: `HybridTtsEngine` orchestrating Piper offline neural voice with automatic fallback to offline `AndroidTtsEngine`.
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
│   └── ThumbnailCache.kt                # PDF page thumbnail renderer and cache wrapper
├── domain/
│   ├── pdf/
│   │   ├── PdfTextExtractor.kt          # PDFBox Android page-by-page text extraction and readability check
│   │   ├── AcademicTextCleaner.kt       # Strips headers/footers, fixes hyphenation, normalizes math for TTS
│   │   └── SentenceSplitter.kt          # Splits cleaned academic text into spoken & displayed sentence units
│   ├── tts/
│   │   ├── TtsEngine.kt                 # Common interface for text-to-speech engines
│   │   ├── AndroidTtsEngine.kt          # Built-in offline Android TextToSpeech engine
│   │   ├── PiperFeatureManager.kt       # SplitInstall and reflective loader for Piper feature
│   │   ├── HybridTtsEngine.kt           # Hybrid TTS manager with voice fallback
│   │   ├── TtsPrefetchQueue.kt          # Bounded prefetch pipeline for gapless speech playback
│   │   └── PmugTtsService.kt            # Foreground media playback service with audio focus and notifications
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
    │   └── UsagePermissionDialog.kt     # Permission dialog for usage statistics
    └── theme/                           # Color, Typography, and Material 3 theme configuration
```

---

## 3. Build & Test Commands

* **Compile & Build ABI-specific Debug APKs** (install the APK matching the target device; most current phones use `arm64-v8a`):
  ```bash
  ./gradlew app:assembleDebug
  ```
* **Run Unit Tests**:
  ```bash
  ./gradlew app:testDebugUnitTest
  ```

## 4. Document formats and reader behavior

`PmugDocumentEntity.type` stores `PDF`, `TEXT`, `MARKDOWN`, or `DOCX`. Room migration 2→3 gives existing rows the `PDF` default. TXT/Markdown/DOCX page text is cached beside the imported file as `.text-cache`; PDF retains its PDFBox cache. Imports run on `Dispatchers.IO`. The picker and incoming intents accept PDF, `text/plain`, `text/markdown`, and DOCX MIME types. Legacy `.doc` files are rejected.

`DocumentText.kt` contains strict UTF-8 with Windows-1252 fallback decoding, paragraph-aware pages targeting 2,000 characters, Markdown cleanup, and DOCX paragraph extraction from `word/document.xml`. It uses `ZipFile` and `XmlPullParser`; no Apache POI dependency is needed. Tables and images are skipped and fenced code is described as a code block.

Non-PDF documents use a text-only reader. The library displays the stored document type. New PDFs open at 1.25× the fit-width scale. Page-number validation and extraction order are in `PageNumberValidation` and `PageExtractionPriority`; the reader supports selectable text, a Copy page text action, and a page-jump dialog.
