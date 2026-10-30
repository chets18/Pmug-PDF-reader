# Pmug - Academic PDF Reader & Speech Companion

Pmug is a focused Android app for reading and listening to academic papers and PDFs. It uses PDFBox Android for page-by-page text extraction, Gemini and OpenAI TTS through Puter.js, Android System Text-to-Speech, bionic reading typography, and an expressive mascot companion.

---

## Features

* **Distraction-Free Reading**: Clean typography with bionic text formatting for faster scanning.
* **Cloud Voice Reader**: Gemini Puck, GPT-4o mini TTS, and TTS-1-HD through Puter.js, with automatic Android speech fallback.
* **Optional Offline Neural Voice**: Piper is delivered as an on-demand Play Feature Delivery module. Its Lessac medium model downloads to app-private storage on first use.
* **Smart PDF Extraction**: Built-in PDF stream parser that extracts text without heavy external libraries.
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
* For UI design tokens, color palette, typography, and UX guidelines, see [`DESIGN.md`](file:///home/chetraj/Downloads/pmug(2)/DESIGN.md).

## Reader behavior

* The reader shows only the selected PDF page's extracted text beneath its real page. Speech keeps the highlighted sentence in view, and page swipes change the selected page. One-finger horizontal swipes navigate pages; two-finger pinches zoom around the finger midpoint, drag to pan, and double-tap to zoom at the tapped location.
* Choosing a PDF from the library picker imports it, saves it, and opens the reader directly. The library shows an import status while processing.
* PDF import copies the file and reads its page count, then the reader extracts text page by page and shows each page as it becomes available. Extraction runs locally and does not require internet. Extracted pages are cached beside the private app copy, so reopening the same PDF skips the expensive parsing step.
* PDFBox Android extracts text page by page using PDF font mappings. Unreadable or image-only/scanned PDFs still display their rendered page, but are not spoken; scanned documents need OCR.
* Each play action sends one complete sentence to the selected speech engine. Android System Speech highlights the spoken word when the device reports word timing; cloud voices report sentence timing and the UI estimates word timing for the active highlight.
* Cloud voices use Puter.js `txt2speech`: Gemini uses provider `gemini`, model `gemini-3.8-flash-tts`, and voice `Puck`; OpenAI offers `gpt-4o-mini-tts` and `tts-1-hd` with voice `alloy`. If a cloud request fails, Android speaks that sentence and the chosen cloud model remains selected, with the error shown in a dark themed status message.
* Piper is powered by sherpa-onnx in the `:piper` on-demand feature module for release. Debug builds set `PIPER_BUNDLED_FOR_DEBUG` and compile the Piper source plus sherpa-onnx dependencies into the base APK, so Android Studio Run on a phone does not need to install a feature split. Selecting Piper downloads the Lessac medium model and pronunciation data to `files/tts/piper`. Voice selection shows combined module/model progress, falls back to Android TTS on failures, and offers removal of the model plus deferred module uninstall. Setup requires network access and about 300 MB of temporary free storage. Release keeps Piper on demand.
