# Pmug - Academic PDF Reader & Speech Companion

Pmug is a focused Android app for reading and listening to academic papers and PDFs. It uses PDFBox Android for page-by-page text extraction, Piper offline neural text-to-speech, Android System Text-to-Speech, bionic reading typography, and an expressive mascot companion.

---

## Features

* **Distraction-Free Reading**: Clean typography with bionic text formatting for faster scanning.
* **Continuous Piper Playback**: A process-owned session synthesizes ahead and streams sentence audio through one long-lived `AudioTrack`; Android System Speech remains the fallback.
* **Audio-Synced Highlighting**: Piper sentence and word highlights follow played audio frames. Android System Speech uses platform word-range callbacks when available.
* **Multi-Voice Piper Library**: Choose among several offline neural Piper voices (including female and male options) with gender filtering, download progress, cancel/resume, per-voice deletion, preview, and shared pronunciation data (`espeak-ng-data`).
* **Full-Screen PDF Viewer**: Immersive full-screen view for studying detailed figures with up to 8× pinch zoom, pan, double-tap zoom, page swipe / navigation, high-resolution rendering with `PdfRenderer` and memory cap, and a floating audio control bar.
* **Multi-format Library**: Imports PDF, plain text, Markdown, and DOCX. Text formats are paginated locally and retain page navigation.
* **Reader Controls**: Selectable text, copy-page action, page jump, and PDF zoom that starts at 125% of fit-width.
* **Library Management**: Automatic shelves (*Continue*, *All*, *Finished*), document pinning, cover thumbnails, and search.
* **Progress Sync & Bookmarks**: Save passage highlights and resume exactly where you left off.
* **Interactive Companion**: "Pmug", an expressive mascot that reacts to reading speed and page milestones.

---

## Setup & Running

### Prerequisites
* [Android Studio](https://developer.android.com/studio) (2023.3+ or Ladybug/Koala/Mainline)
* JDK 11 or higher
* Android SDK 24+ (Android 7.0+)

### Building locally
1. Clone or open the project folder in Android Studio.
2. Let Gradle sync dependencies.
3. Run or build the app:
   ```bash
   ./gradlew app:assembleDebug
   ```
4. Install the ABI-specific APK for your device or emulator from `app/build/outputs/apk/debug/` (for most current Android phones, choose `app-arm64-v8a-debug.apk`):
   ```bash
   adb install app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
   ```

---

## Technical Documentation

* For developer and AI agent technical details, file maps, and component structure, see [`AGENTS.md`](file:///home/chetraj/Downloads/pmug(2)/AGENTS.md).
* For architecture and engineering design, see [`ENGINEERING.md`](file:///home/chetraj/Downloads/pmug(2)/ENGINEERING.md).
* For UI design tokens, color palette, typography, and UX guidelines, see [`DESIGN.md`](file:///home/chetraj/Downloads/pmug(2)/DESIGN.md).

## Reader behavior & Features

* The reader shows only the selected PDF page's extracted text beneath its real page. Speech keeps the highlighted sentence in view, and page swipes change the selected page. One-finger horizontal swipes navigate pages; two-finger pinches zoom around the finger midpoint, drag to pan, and double-tap to zoom at the tapped location.
* **Full-Screen PDF Viewer**: Tapping the full-screen icon in the top-right corner of the PDF page preview opens an immersive full-screen view. Supports pinch-to-zoom up to 8×, drag-to-pan, double-tap zoom, page navigation, orientation persistence, and a collapsible audio control bar. Hidden for non-PDF documents.
* **Piper Voice Library**: Accessible via the voice menu in the top app bar. Allows filtering voices by gender (All, Female, Male), viewing voice cards (name, accent, quality, size, download state), previewing voices, downloading on demand with progress, cancelling/resuming, and deleting voice models. Pronunciation data (`espeak-ng-data`) is shared across voices.
* Choosing PDF, TXT, Markdown, or DOCX from the library picker imports it, saves it, and opens the reader. Incoming VIEW and SEND intents accept the matching MIME types. Legacy .doc files and corrupt/encrypted DOCX files are not supported.
* PDF import copies the file and reads its page count, then the reader extracts text page by page and shows each page as it becomes available. Extraction runs locally and does not require internet. Extracted pages are cached beside the private app copy, so reopening the same PDF skips the expensive parsing step.
* PDFBox Android extracts text page by page using PDF font mappings. Unreadable or image-only/scanned PDFs still display their rendered page, but are not spoken; scanned documents need OCR. TXT uses UTF-8 then Windows-1252 fallback. Markdown markup is stripped for speech; DOCX reads paragraphs from `word/document.xml` without Apache POI.
* TXT and Markdown pages target about 2,000 characters and break at paragraph boundaries where possible. Non-PDF documents show a text-only reader. The library labels each format.
* New PDFs start at 125% of fit-width scale; double-tap, pinch, and pan remain available. Reader text supports selection and copying, with a Copy page text action. Tap the page count to jump and prioritize extraction around that page.
* Sentence splitting is unchanged. In Piper mode, `PlaybackSession` owns sentence progression and synthesizes up to 15 seconds ahead on a worker separate from the AudioTrack writer. The reader supplies extracted sentence units and consumes the session's audio position for highlighting and scrolling.
* Word spans use the displayed sentence text and real clip length. Android System Speech uses `onRangeStart` callbacks for word highlighting; callbacks are posted to the main thread. No estimated words-per-minute timer is used.
* Piper inserts 150 ms between sentences and 350 ms when the paragraph changes. `PmugTts:` logs synthesis timing, audio and highlight boundaries, gaps, buffered audio, first-sound latency, and underruns.
* Default voice order: (1) Piper offline neural voice; (2) Android System Speech. If a request fails, playback falls back to Android System Speech.
* Piper is powered by sherpa-onnx in the `:piper` on-demand feature module for release. Debug builds set `PIPER_BUNDLED_FOR_DEBUG` and compile the Piper source plus sherpa-onnx dependencies into the base APK. Selecting Piper downloads the voice model and pronunciation data to `files/tts/piper`.
* For the playback architecture and device-verification limits, see [`AUDIO_FLOW.md`](AUDIO_FLOW.md).
