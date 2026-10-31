# Pmug Audio Flow

`PmugApplication` owns one `HybridTtsEngine` and one `PlaybackSession` for the process. `ReaderScreen` supplies extracted sentence units and sends play, pause, seek, and speed controls. The Piper session serializes synthesis on `PmugSynthesis`, ahead up to 15 seconds, and writes PCM on `PmugAudioWriter` to one long-lived `AudioTrack`.

Piper sentence progression and highlighting read the AudioTrack played-frame position. Display word spans are generated from the same `ParsedSentence.text` shown in the reader and mapped across that sentence's synthesized clip. Android System Speech retains its utterance completion chain; word timing comes from `onRangeStart` callbacks posted to the main thread.

The stream inserts 150 ms between sentences and 350 ms when the paragraph index changes. Piper model warm-up is dispatched to a worker after the voice is installed. Session logging uses the `PmugTts:` prefix and includes synthesis duration, RTF, audio boundaries, highlight boundaries, inter-unit gap, first-sound latency, buffered audio, and underruns.

## Verification

`app:testDebugUnitTest` and `app:assembleDebug` passed on 2026-10-31. `PlaybackSessionTest` covers synthesis lookahead, seek cancellation, sentence boundaries driven by fake played-frame positions, and word spans over displayed text. Phone timing, audible gaps, Android device TTS timing, background playback, and highlight stability at 0.5x, 1.0x, and 1.7x still require device verification.
