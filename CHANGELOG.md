# Changelog

## 2.0.1

- Minecraft words are now suggested for typos, and autocorrect can fix them: "minectaft" → "minecraft", "netherrite" → "netherite". Before, only dictionary words were offered.

Separate builds support Minecraft 26.2 and 26.3 with Fabric.

## 2.0.0

A big update to make Chat Spellcheck friendlier from the first message.

**Smarter spellchecking**
- Better suggestions in German, Swiss German, Spanish, French, Italian and Hebrew, using word frequencies like English already did. On a fixed test set of 400 typos, the right word now comes first 318 times instead of 279.
- Stretched words (`sooo`, `yesss`) and laughs (`hahaha`, `xddd`, `חחח`) are no longer underlined.
- Capitalised names in the middle of a sentence ("ask Zorblax") are no longer underlined. You can turn this off with **Skip names**, and it's never used with German.
- More chat slang is known, like `ngl`, `istg`, `tmrw`, `ggwp` and `goated`.

**New players**
- On first launch, the mod turns on the dictionary for your game's language, plus English (US) if your game isn't in English.
- The first few times you open chat, a small tip explains hover, Ctrl+. (Cmd+. on Mac) and `/spellcheck`. It goes away once you fix your first typo.

**Settings screen**
- New look, with a live preview line that shows your settings as you change them.
- Sections for languages, checking, look, stats and more, with on/off switches.
- Choose the underline style (squiggle, straight or dotted) and colour (red, pink, orange or blue). Blue is easy to see with red-green colour blindness.
- The screen scrolls when it doesn't fit, so it works at every GUI scale and window size.

**Stats and little touches**
- A stats card shows words checked, typos fixed, your streak of messages sent without a typo, and fixes per day this week. Stats stay on your computer in `config/chatspellcheck/stats.properties`.
- Underlines grow in gently, the suggestion box slides into place, and fixed words get a small sparkle. Drawing still takes about 0.05 ms per frame, the same as 1.0.3.

Separate builds support Minecraft 26.2 and 26.3 with Fabric. Existing settings and word lists carry over.

## 1.0.3

- New icon.

No other changes. Separate builds support Minecraft 26.2 and 26.3 with Fabric.

## 1.0.2

- Extend optional autocorrect coverage for unambiguous typos. Short and ambiguous typos remain suggestions only.
- Discard pending automatic corrections after language or dictionary changes, after undo, and when the correction window expires.
- Avoid autocorrecting words in the custom dictionary or session ignore list.
- Reduce temporary allocations in candidate searches and stop searching after a second distinct dictionary match.
- Clarify installation instructions and language coverage.

Separate builds support Minecraft 26.2 and 26.3 with Fabric. Autocorrect remains off by default.
