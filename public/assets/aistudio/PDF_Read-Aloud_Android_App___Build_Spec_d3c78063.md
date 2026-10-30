# PDF Read-Aloud Android App – Build Spec

## Goal

Build an Android app that opens a PDF, reads its text aloud in a natural-sounding voice, and auto-scrolls and highlights the text in sync with the audio. The purpose is to make reading study material less boring and improve attention and retention. It is for personal use first, with a possible Play Store launch around March or April 2027.

## Scope (MVP)

- Text-only PDFs. Ignore multi-column layouts, images, diagrams and scanned pages.
- Extract only the running body text.
- Strip page headers, footers and page numbers so the voice does not break mid-sentence.
- Single-user, offline library of opened PDFs is optional. Core flow comes first.

## Core Features

1. **Open PDF**: pick a file from device storage using the Android file picker.
2. **Text extraction**: parse text page by page, clean it, and split it into sentences.
3. **Read aloud**: send sentence chunks to a natural-sounding TTS voice and play the audio.
4. **Read-along sync**: highlight the sentence currently being spoken and auto-scroll to keep it in view.
5. **Playback controls**: play, pause, skip forward and back by sentence, speed control (0.75x to 2x).
6. **Resume**: remember the last position in each PDF.

## Technical Approach

- **Platform**: Android, Kotlin, Jetpack Compose.
- **PDF text extraction**: PdfBox-Android (or similar library) to pull text. Android PdfRenderer is an option if page rendering is needed later.
- **Text cleaning**: remove lines that repeat across pages (headers and footers), remove standalone page numbers, merge hyphenated line breaks, join wrapped lines into paragraphs, then split into sentences.
- **TTS voice**: use a natural cloud voice, starting with Google Cloud Text-to-Speech (Chirp or Neural2 voices). Keep the TTS layer behind an interface so another provider (for example ElevenLabs) can be swapped in.
- **Audio pipeline**: synthesize sentence by sentence, prefetch the next few sentences while the current one plays, and cache audio locally to save cost and allow replay.
- **Sync**: each sentence maps to a position in the text view, so the highlight and scroll follow the currently playing sentence.
- **Storage**: Room database for the PDF list, reading position and cached audio references.

## Voice Quality Notes

- Test a few real paragraphs from an actual PDF with several voices before choosing a default.
- Long-form reading is where cheaper voices start to sound tired, so judge on at least a few minutes of audio.
- Keep a free built-in Android TTS fallback for when the user is offline or out of quota.

## Cost Considerations

- Cloud TTS is billed per character. Cache synthesized audio and avoid re-synthesizing the same sentence.
- Use the free monthly tier during development.

## Suggested Build Order

1. PDF picker and text extraction screen showing raw text.
2. Text cleaning and sentence splitting.
3. Read aloud with the built-in Android TTS, with highlight and auto-scroll, to prove the sync logic.
4. Swap in the cloud TTS voice with prefetching and caching.
5. Playback controls, speed and resume position.
6. Polish the reading UI: font size, theme, distraction-free mode.

## Out of Scope for Now

- OCR for scanned PDFs.
- Multi-column layout handling.
- Reading text inside images and diagrams.
- Social features, accounts and cloud sync.
- Monetization and subscriptions, to be revisited before the Play Store launch.

## Open Questions

- Which cloud voice sounds best for long-form reading?
- Free tier limits versus expected monthly usage.
- Whether to add a premium tier later for higher-quality voices.
