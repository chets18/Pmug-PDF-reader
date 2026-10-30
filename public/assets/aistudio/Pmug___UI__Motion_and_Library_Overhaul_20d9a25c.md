# Pmug – UI, Motion and Library Overhaul (follow-up prompt)

Paste everything below into your coding agent.

## Context

You are improving an existing Android app (Kotlin, Jetpack Compose) that opens a PDF, extracts its body text, reads it aloud with TTS, and highlights and auto-scrolls in sync. The extraction, TTS and sync logic already work. Do not rewrite them. This task covers: a new identity and UI, a real PDF library with previews, a code-drawn mascot, a motion system, saved progress, and smart reading nudges. The current UI is a generic dark-navy and cyan-glow template with fake sample papers. Replace all of it.

First read the project and write a short plan: files you will touch, files you will add. Then build in the order below, run the app, and verify with my real PDFs (section 11).

## 1. Name and idea

App name: **Pmug** (PDF + mug, said smugly). Update the app label, package display name and launcher icon.

Core idea: the reading voice becomes steam rising off a small, smug mug. The mug is the mascot, the progress meter and the app icon. While I read, the steam moves with the voice. When I finish something, the mug looks quietly pleased with me.

Emotional target: cozy focus, then small dry pride. Never cutesy, never nagging.

## 2. Look (house style)

Put all tokens in one theme file and reference them everywhere. No raw hex inside composables.

- Ground: #0E2B26 (deep bottle green). Raised surface: #153730. Ink: #F2EDE3. Muted ink: #B9B7AA.
- One accent only: highlighter #D6F25C. It is used for the sentence being read, the primary button and the mug's fill level. Nothing else gets colour. Text on the accent is #0E2B26.
- Type: Bricolage Grotesque for headings and short UI text, Literata for all reading text (18sp default, line height 1.6, size adjustable). Both are free Google Fonts, bundle them as font resources.
- Shape: one radius system, 20dp for cards and fully round buttons. Separate surfaces by tone, not by borders or shadows. Flat everywhere. The mug is the only detailed thing on screen.
- Layout: big, confident, left-aligned. The library opens with one large Continue card, then a two-column grid of covers whose heights follow each real page's aspect ratio. No rows of identical cards.

## 3. Library and PDF previews (file organization)

- Import through the system picker and through Share to Pmug / Open with. On import, copy the PDF into app storage so it survives if the original moves.
- Render page 1 with PdfRenderer into a cached bitmap (about 600px wide, disk cache keyed by file hash). Read the title from PDF metadata, falling back to the file name. Store the page count.
- Before saving, show an **Add to shelf** bottom sheet with the real preview.
- Library card: the real first-page thumbnail, the title, and Page 12 of 48. Progress is a bookmark ribbon on the thumbnail edge, placed proportionally down the cover.
- Automatic shelves only: Continue, All, Finished. Long-press a card for pin, rename and delete. Search by title. Sort by last read (default), title or date added.
- No invented categories, no preloaded sample papers, no fake authors, no fake progress numbers.
- Reader page strip: a row of page thumbnails at the bottom to jump between pages.

## 4. Reader screen

- The active sentence gets a highlighter-pen stroke that sweeps left to right over the sentence's real spoken duration (use the TTS progress callbacks). Text already read dims to 60% ink.
- Auto-scroll keeps the active sentence near 35% from the top with an eased scroll. If I scroll manually, stop following and show a **Jump back to voice** button.
- Bottom dock: small mug on the left, large play/pause, skip back and forward by sentence, speed control. Swipe left or right on the text to skip a sentence with a light haptic. Tap a sentence to start from there. Long-press a sentence to save it as a bookmark.
- If I leave the reader while audio plays, keep a mini bar (mug + title) on the library. Tapping it expands back with a shared-element transition.

## 5. Mascot: Pmug the mug

Draw it in code with Compose Canvas. No Lottie files, no image assets, no borrowed characters. An off-white enamel mug with a handle, a face (dot eyes with lids, a brow, a mouth) and three animated Bézier steam paths. Expressions are parameters (eyelid, brow tilt, mouth curve, steam amplitude, fill level) animated with springs.

States:

- Idle: slow random blink every 3 to 6 seconds, two lazy steam wisps.
- Parsing a PDF: steam curls tight and fast, eyes glance sideways.
- Reading: steam amplitude pulses with speech (sentence start/end and word boundaries), eyes drift along the reading direction, a slight bob. On the reader screen keep it small and calm so it never competes with the text.
- Paused: steam fades, eyelids half down.
- Page finished: a one-second smug smirk, one raised brow, a tiny squash-and-stretch hop.
- Document finished: mug full to the brim, big smug look, one line of copy.
- Long idle: eyes closed, very slow steam.
- Tap the mug anywhere: it reacts. Rotate between hop, cheeky blink and a steam puff, never the same one twice in a row.

Mug fill level equals progress through the current document, drawn in the accent colour.

## 6. Motion system

Create one MotionSpec file with the springs and durations, and use it everywhere.

- Press: scale to 0.96 with a spring, plus a light haptic tick on primary actions.
- Library to reader: shared-element transition, the cover thumbnail expands into the reader header, then the content below fades and rises 16dp, staggered by 60ms.
- List items rise in on first appearance only, not on every scroll.
- Play/pause icon morphs, it does not swap.
- Add to shelf: the thumbnail flies into its shelf slot.
- Animate only graphicsLayer properties (alpha, scale, translation, rotation). Never animate layout size per frame inside lists. Target 60fps and check on a mid-range device.
- Respect the system Remove animations setting: the mascot holds static expressions and transitions become crossfades.
- Timing: feedback 200ms or less, transitions 250 to 350ms. Ambient mascot loops run only on the library and empty states, never while text is being read.

## 7. Saved progress and bookmarks (Room)

Tables: documents (id, hash, title, path, pages, addedAt, lastReadAt, pinned, finished), progress (documentId, page, sentenceIndex, updatedAt), bookmarks (documentId, sentenceIndex, snippet, createdAt).

- Save the position on every sentence change (debounced 1s), on pause, on onStop, and so it survives the process being killed.
- The resume point shows on the card ribbon and on the Continue card.
- A Saved passages sheet lists bookmarks per document, tap to jump.

## 8. Reading nudges: exact rules

Goal: nudge me about every 2 to 3 hours, only when I am clearly awake and using my phone. If the phone has been untouched for hours, assume I am asleep and send nothing.

Signals:

- lastUnlock: when the phone was last unlocked or the screen last turned on, read from UsageStatsManager.queryEvents (KEYGUARD\_HIDDEN and SCREEN\_INTERACTIVE events). This needs Usage Access, so ask in context with a clear explanation screen.
- lastPmugActivity: the last time reading was active in Pmug.

Run a WorkManager periodic job every 30 minutes. No foreground service, no wakelocks. Send a nudge only if ALL of these are true:

1. Now minus lastPmugActivity is at least 2.5 hours (configurable between 2 and 3).
2. Now minus lastUnlock is at most 45 minutes (I am awake and around).
3. Fewer than 3 nudges sent today, and at least 2 hours since the last nudge.
4. The time is outside quiet hours (default 23:00 to 07:00, changeable). This is a backup to rule 2.
5. Audio is not currently playing.

If two nudges in a row are dismissed without opening, wait 6 hours before the next one.

Do NOT use a 'phone has been continuously on since X' check. That was the wrong approach in my last app. Only the last unlock time matters.

Without the Usage Access permission, fall back to rules 1, 3, 4 and 5, with a lower cap of 2 per day.

The notification: its own channel called Reading nudges. Tapping it opens the reader at the saved sentence without auto-playing. Actions: **Read now** and **Later** (snooze 1 hour). The text names the resume point and is written in the mug's voice, rotating variants and never repeating the same line twice in a row. Ask for POST\_NOTIFICATIONS (Android 13+) only after I first save a document, never at launch.

Keep the decision logic in a pure Kotlin class (NudgePolicy) that takes the current time and the signals as inputs, so it can be unit tested with a fake clock. Test these cases:

- Unlocked 10 min ago, last Pmug activity 3h ago, 0 nudges today: send.
- Unlocked 4h ago, last Pmug activity 3h ago: skip (away or asleep).
- Unlocked 10 min ago, last Pmug activity 40 min ago: skip (read recently).
- Unlocked 10 min ago, last Pmug activity 3h ago, 3 nudges today: skip (daily cap).
- Everything eligible but the time is 02:00: skip (quiet hours).
- Everything eligible but audio is playing: skip.

Add a hidden debug screen (long-press the app title) where I can set fake lastUnlock and lastActivity values, run the check now, and see a log of why a nudge was sent or skipped.

## 9. Copy (voice: dry, smug, short)

Buttons start with a verb and say what happens. No exclamation overload, no emoji.

- Empty library: title **Nothing to sip yet.** Body: Add a PDF and I will read it to you. Button: **Add a PDF**.
- Add sheet: **Add to shelf** and **Not now**.
- Resume button: **Pick up at page 12**.
- Page finished: Page 12. Easy.
- Document finished: Cover to cover. Obviously.
- Unreadable PDF: Could not read this one. It looks like a scan, and I only read real text. Try another PDF.
- Nudge variants: Page 12 is still warm. Two minutes? / Your PDF is waiting on page 12. / Pick up where you stopped, page 12.
- Usage permission screen: title **One thing I need to peek at**. Body: Pmug only looks at when you last unlocked your phone, so it nudges you when you are awake. Buttons: **Open settings** and **Skip**.

## 10. Code organization

Keep the existing pdf/ and tts/ packages untouched. Add:

- core/ theme (Tokens, Type, Theme, MotionSpec), haptics
- data/ Room database and DAOs, repository, ThumbnailCache
- mascot/ PmugMug, PmugState, Steam
- nudge/ NudgePolicy (pure Kotlin), UsageSignals, NudgeWorker, NudgeNotifier
- ui/library, ui/reader, ui/onboarding
- debug/ the hidden debug screen

## 11. Test with my real PDFs (required)

I have uploaded PDFs into the project. Do not ship any placeholder papers.

1. Copy them into a sample\_pdfs folder and auto-import them into the library in debug builds only.
2. Write a test that runs the extractor on every PDF and records: page count, sentence count, characters removed as headers and footers, the first 3 sentences, and any failure.
3. Produce extraction\_report.md with one row per PDF and a verdict: reads fine, partial, or fails, with the reason.
4. Run the app on an emulator or device. Open each PDF and confirm the highlight advances and auto-scroll follows. Capture screenshots of: library, add sheet, reader mid-read, each mascot state, empty state, and a nudge notification.
5. Force-stop the app mid-read, reopen it, and confirm it resumes at the same sentence.
6. Report what works, what does not, and which kinds of PDF fail. Do not claim anything you did not actually run.

## 12. Do not

- No navy-and-cyan neon, no glow on buttons, no gradient cards, no glassmorphism, no drop shadows on resting cards.
- No tiny uppercase letter-spaced labels or pill badges (the old AEROSPACE ENGINEERING and PRELOADED PAPER tags).
- No icon in a tinted square, no rows of identical cards, no rainbow category colours, no purple or indigo Material defaults.
- No fake data: no sample papers, invented authors, or made-up percentages.
- No emoji, no flame or streak counters, no vanity stats.
- No features I did not ask for: no quizzes, formula sheets, AI summaries or accounts.
- No second typeface pair, no second accent colour.
- No looping animation while text is being read, no animation longer than 400ms for feedback, no animating layout properties in lists.
- No foreground service or constant background polling for nudges.
- Do not touch the PDF extraction, TTS or sync code except where a new feature needs a hook, and tell me when you do.
