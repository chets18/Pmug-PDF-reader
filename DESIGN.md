# Pmug - Design System & UX Specification

This document details the visual design system, color palette, typography, layout guidelines, component specifications, and motion guidelines for the **Pmug** Android application.

---

## 1. Design Philosophy

Pmug follows a **Warm Academic & Distraction-Free** design ethos:
* **Tactile & Paper-like**: Uses warm grounded tones (`#F7F5F0`) mimicking physical paper and linen book bindings rather than harsh sterile whites.
* **Typographic Focus**: Prioritizes legibility with serif typefaces (`Literata`) optimized for long-form academic reading and bionic letter bolding.
* **Expressive Companion**: Features "Pmug", a vector mug mascot that subtly changes expressions to keep the reading experience engaging without interrupting focus.
* **Minimal Friction**: Simple gestures, smooth bottom sheet sheets, and single-tap voice controls.

---

## 2. Color Palette & Tokens

All UI components consume tokens defined in `com.example.core.theme.PmugTokens`:

| Token | Hex / Value | Purpose |
|---|---|---|
| `Ground` | `#F7F5F0` | Warm cream canvas background |
| `Surface` | `#FFFFFF` | Primary card and bottom sheet container background |
| `SurfaceSubtle` | `#EFECE6` | Secondary input fields, disabled states, and thumbnail placeholders |
| `Ink` | `#1C1B1F` | High-contrast body text, primary titles, and icons |
| `MutedInk` | `#6C6A72` | Secondary labels, page counts, dates, and metadata |
| `Accent` | `#C85A32` | Warm terracotta primary button, active voice indicator, and bookmark ribbons |
| `AccentVariant` | `#A84422` | Darker terracotta state for pressed buttons and active toggles |
| `TextOnAccent` | `#FFFFFF` | Contrast text on terracotta buttons and badges |
| `BionicHighlight` | `#000000` | Bold leading letters for bionic text scanning |
| `SentenceHighlight` | `#E6DACB` | Eased background highlight on active spoken sentence |

---

## 3. Typography & Bionic Text

### Font Families
* **Serif / Long-form Body**: `Literata` (Book, Medium, SemiBold, Bold) for reading passages.
* **Sans-Serif / UI**: System Default (Roboto / Sans) for headers, buttons, pills, and navigation.

### Bionic Reading Format
Pmug uses `BionicText` in `ui/components/BionicText.kt` to bold the leading 30-50% of characters in each word. This guides the eyes smoothly across academic paragraphs, accelerating reading comprehension.

---

## 4. Components & Layouts

### Library Screen
* **Staggered Grid**: 2-column staggered grid showcasing document covers with a realistic 0.72 aspect ratio (standard paper book ratio).
* **Bookmark Ribbon**: Proportional vertical ribbon positioned on document cover cards reflecting current reading percentage.
* **Shelf Pills**: Segmented filter bar for switching between *All*, *Continue*, and *Finished* shelves.

### Reader Screen
* **Active Sentence Focus**: Active sentence highlighted with soft `#E6DACB` background and automatically kept ~35% down the viewport during voice playback.
* **Control Bar**: Floating bottom audio controller with Play/Pause, Skip Backward/Forward, playback speed menu (0.75x to 2.0x), and voice provider switcher.
* **Page Strip & Bookmarks**: Bottom sheets for rapid page jumping and review of saved passages.

### Mascot ("Pmug Mug")
Vector canvas mug rendered in `mascot/PmugMug.kt` supporting 7 dynamic moods:
1. `IDLE`: Calm neutral smile.
2. `READING`: Happy squinting eyes with subtle steam animation.
3. `PAGE_FINISHED`: Confident wink when completing a page.
4. `DOCUMENT_FINISHED`: Celebratory star eyes upon finishing a book.
5. `SMUG`: Proud smirk for milestones.
6. `PARSING`: Rotating steam or eyes during PDF import.
7. `PAUSED`: Gently closed eyes when audio is paused.

---

## 5. Motion & Interaction

* **Press Feedback**: Custom scale animation (`PmugMotionSpec.pmugPressScale`) providing tactile depth when pressing cards or buttons.
* **Eased Auto-Scroll**: Smooth list animation keeping the spoken sentence centered without jarring jumps.
* **Haptics**: Subtle haptic ticks (`PmugHaptics.tick`) on skip, bookmarking, and long-press context actions.
