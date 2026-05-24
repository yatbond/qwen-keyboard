# Dee Keyboard v4 Roadmap

Created: 2026-05-24

## Goal
Version 4 focuses on turning the current voice-first keyboard into a more complete daily keyboard: Chinese typing, smarter punctuation, better UI polish, and more model choices.

## Priority order

### C1. Chinese keyboard input — first core feature
Target: add a practical Chinese input path beyond handwriting and voice.

Recommended v4.0 slice:
- Add a Chinese button on the top button row, next to handwriting.
- Keep the status text area smaller to make room for the added Chinese button.
- Add a Pinyin typing mode using the existing QWERTY layout.
- Add 九方 input as a second Chinese input mode.
- Show Chinese candidates in the existing suggestion row.
- Commit selected candidate into the active app.
- Output should follow the existing 原文 / 繁中 / 简中 setting.
- Keep handwriting as fallback for characters that are hard to type.

Likely implementation options:
1. Bundled lightweight Pinyin dictionary / phrase table — best for offline and easiest to ship.
2. Android system transliterator / ICU only — useful helper, but not enough for good candidate ranking.
3. Cloud/PC candidate generation — nice later, but not ideal as the default typing path.

Recommendation: start with bundled offline Pinyin candidates, then improve ranking from user learning.

### C2. AI auto punctuation based on context — second core feature
Target: improve dictated text after ASR without changing the ASR engine.

Recommended v4.0 slice:
- Keep existing rule punctuation as fast offline fallback.
- Add PC AI punctuation as a selectable higher-quality option when the PC server is available.
- Add context-aware punctuation pass using recent committed text + new dictated text.
- Run automatically only after dictation chunk/final text, not after every keystroke.
- Make it selectable: Off / Offline rules / PC AI / Cloud AI if configured.

Important: punctuation should be a post-processing layer, not baked into ASR. This protects short utterance speed and lets us use different text-fix models later.

### D1. App logo — quick polish win
Target: replace default Android bot icon.

Recommended v4.0/v4.1 slice:
- Create adaptive icon foreground/background.
- Use a simple keyboard + wave/Q mark motif.
- Apply to launcher and IME settings icon.

### D2. Customized UI sizing — medium priority
Current code already has display-aware sizing and split keyboard support. v4 should expose this better.

Recommended controls:
- Keyboard height: Compact / Normal / Tall.
- Key spacing: Tight / Normal / Comfortable.
- Suggestion row size: Small / Normal / Large.
- Landscape split: Off / Auto / On.

### D3. Custom local / PC / cloud model selection — high value, but should be structured
Target: allow adding extra providers like Doubao Speech via Volc Engine API without hardcoding every engine into UI logic.

Recommended v4.1 slice:
- Add provider categories: Phone local, PC server, Cloud API.
- Add a generic custom cloud ASR config: name, endpoint, auth header/token, request format.
- Add Doubao/Volc as a preset once credentials and API format are confirmed.
- Keep tokens private in Android SharedPreferences.

What Dee can help with:
- Volc Engine/Doubao API docs or sample curl.
- Whether the key is OK to store only on phone, or should proxy through PC server.

### D4. SwiftKey-style flow input — biggest feature, defer
Target: swipe across keyboard for word predictions.

This is a large feature because it needs gesture path tracking, candidate scoring, dictionary search, and conflict handling with existing swipe-delete / long-press symbols.

Recommended: defer to v4.2+ after Chinese input and punctuation are stable.

## Proposed releases

### v4.0 — Chinese + punctuation foundation
- Pinyin mode with candidate row.
- English/Chinese keyboard toggle.
- Context-aware punctuation setting.
- Small UI cleanup around language/model controls.

### v4.1 — polish + model expansion
- New app icon.
- User-visible keyboard sizing controls.
- Provider/model settings redesign.
- Doubao/Volc preset if API credentials/docs are available.

### v4.2 — flow input experiment
- Gesture path capture.
- English swipe candidate prototype.
- Resolve conflicts with swipe-delete and long-press symbols.

## First implementation slice
1. Add `keyboardLanguageMode`: `en`, `pinyin`, `jiufang`.
2. Add a top-row `中` button next to handwriting to cycle English → Pinyin → 九方.
3. Make the top status text smaller / narrower enough to fit the extra button.
4. Change spacebar label from `English (US)` to current mode.
5. Add Pinyin buffer and candidate rendering in existing suggestion row.
6. Add 九方 radical/stroke-style key layout and candidate rendering.
7. Start with small bundled dictionaries, then expand.

## Questions for Dee
1. For 九方 input, confirm the exact layout/variant Dee expects if there is a preferred Hong Kong 九方 layout.
2. For Doubao/Volc, can Dee provide console/API access details or a sample request once available?
3. Decide whether cloud credentials should stay on phone or be proxied through PC server.
