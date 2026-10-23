<div align="center">

<img src="docs/icon.png" alt="Chat Spellcheck icon" width="128">

# 💖 Chat Spellcheck 💖

**A little spellchecker for Minecraft's chat box ♡**

Typos get a red squiggle as you type. Hover one and click to fix it!

<img src="docs/demo.gif" alt="Typing in chat, typos get underlined, then hovering one shows suggestions" width="640">

Client-side Fabric mod · works on any server · fully offline

[Download](https://github.com/BlueDreams666/chat-spellcheck/releases/latest) · [Report a bug](https://github.com/BlueDreams666/chat-spellcheck/issues)

</div>

## 💕 Features

- **Underlines as you type.** Misspelled words get a red squiggle. Nothing else about the chat box changes.
- **Click to fix.** Hover a word to see suggestions, then click one. Only that word changes, and your cursor stays where it was.
- **Keyboard shortcut.** Press **Ctrl+.** (**Cmd+.** on Mac) to open suggestions for the word at your cursor. Use **↑/↓** to pick one, **Enter** to apply it, **Esc** to close.
- **Ignore or add words.** **Ignore** hides a word until you close the game. **Add to dictionary** saves it for good.
- **14 dictionaries.** You can turn on as many as you like, and a word counts as correct if any of them knows it:
  - English (US, UK, Canada, Australia)
  - Danish, Dutch, French, German, Swiss German, Italian, Norwegian (Bokmål), Spanish
  - Hebrew and Arabic
- **Knows Minecraft.** Item, block and mob names (modded ones too), online players' names, and common chat slang like `gg`, `afk` and `brb` are never flagged.
- **Optional autocorrect.** Off by default. When it's on, only typos it's very sure about are fixed (see [Autocorrect](#-autocorrect)).

## 📸 Screenshots

| | |
|---|---|
| <img src="docs/keyboard.png" alt="Keyboard shortcut popover with the first suggestion highlighted" width="400"> | <img src="docs/languages.png" alt="Spanish, English and Hebrew in one message, each checked" width="400"> |
| **Ctrl+.** picks a fix from the keyboard | Several languages in one message |
| <img src="docs/autocorrect.png" alt="Autocorrect notice saying teh was corrected to the, Backspace to undo" width="400"> | <img src="docs/settings.png" alt="Settings screen with 14 dictionaries" width="400"> |
| Optional autocorrect, Backspace undoes it | `/spellcheck` settings with 14 dictionaries |

## 🌸 Install

1. Install [Fabric Loader](https://fabricmc.net/use/installer/) 0.19.5 or newer.
2. Download the JAR that matches your Minecraft version, then put it and [Fabric API](https://modrinth.com/mod/fabric-api) in your `.minecraft/mods` folder.
3. Start the game and open chat.

| Minecraft | Mod JAR | Fabric API |
|---|---|---|
| 26.3 | `chat-spellcheck-1.0.3+26.3.jar` | 0.162.0+26.3 |
| 26.2 | `chat-spellcheck-1.0.3+26.2.jar` | 0.161.0+26.2 |

The JAR is about 17 MB because it includes every dictionary, but only the languages you turn on are loaded into memory.

## ⚙️ Settings

Type `/spellcheck` in chat to open the settings screen. If you have [Mod Menu](https://modrinth.com/mod/modmenu), you can also open it from the mod list. On that screen you can choose languages, turn checking on or off, and open your word list.

You can also use commands:

| Command | What it does |
|---|---|
| `/spellcheck status` | Shows what's turned on |
| `/spellcheck on` / `off` | Turns checking on or off |
| `/spellcheck language en_US,es_ES` | Sets your languages |
| `/spellcheck add de_DE` / `remove de_DE` | Adds or removes one language |
| `/spellcheck autocorrect on` / `off` | Turns autocorrect on or off |
| `/spellcheck commands on` / `off` | Also checks the text of `/msg`, `/tell`, `/w`, `/r`, `/me`, `/say`, `/teammsg` |
| `/spellcheck forget <word>` | Removes a word you added |

Language codes: `en_US en_GB en_CA en_AU da_DK nl_NL fr_FR de_DE de_CH it_IT nb_NO es_ES he_IL ar`. Short forms like `de`, `es` or `he` work too.

Your settings and saved words are in `.minecraft/config/chatspellcheck/`. The word list, `custom-words.txt`, is a plain text file with one word per line, so you can edit it by hand.

<p align="center"><img src="docs/underlines.png" alt="Misspelled words underlined in the chat box while the link is left alone" width="640"></p>

## 🙈 What doesn't get checked

To avoid nagging you about things that aren't typos, the mod skips:

- slash commands (unless you turn on `/spellcheck commands`)
- links, email addresses and file names
- @mentions, #tags, `:emoji:` codes and colour codes
- anything with a number in it, like coordinates or `1st`
- single letters, ALL-CAPS words, and mixed-case words like `iPhone`
- words in a language you don't have turned on

Lowercase names like "london" are accepted too.

## ✨ Autocorrect

Autocorrect is off until you turn it on. When it's on, it only fixes a word after you finish it, and only when the right fix is clear. If it gets one wrong, press **Backspace** straight away to undo it, and it won't change that word again until you restart the game. Capitalised words are only fixed at the start of a sentence, so names are left alone.

How much it fixes depends on the language:

| Language | What autocorrect fixes |
|---|---|
| English | Most clear one-letter typos ("teh" → "the") |
| Spanish, French, German, Italian, Dutch, Norwegian | Missing accents only ("cancion" → "canción", "uber" → "über") |
| Hebrew | Final letter forms ("שלומ" → "שלום") and clear one-letter typos in longer words |
| Swiss German, Danish, Arabic | Nothing. Typos are still underlined with suggestions. |

## 💭 Good to know

- It checks spelling one word at a time. It can't catch real words used in the wrong place ("their" vs "there"), and it doesn't check grammar.
- Hebrew and Arabic show in the right reading order while you type, with Arabic letters joined. Only the display changes; your message is sent exactly as you typed it.
- It only works in the chat box, not on signs, books or anvils.

## 🛠️ For developers

You'll need JDK 25.

```bash
./gradlew build
```

The JAR ends up in `build/libs/`. The default build targets Minecraft 26.3. To build for 26.2:

```bash
./gradlew build -Pminecraft_version=26.2 -Pfabric_api_version=0.161.0+26.2
```

There's a separate JAR per version because 26.2 uses GLFW for input and 26.3 uses SDL3, which number keys differently.

### Tests

- `./gradlew test` runs the unit tests: suggestion ranking, autocorrect accuracy, text scanning, background threading, and handling of broken or hand-edited config files.
- `./gradlew runClientGameTest` starts the real game, creates a world and drives the chat box: underlines, the suggestion popover, keyboard controls, the settings screen, right-to-left text and autocorrect. Screenshots go to `build/run/clientGameTest/screenshots/`.

### How it works

- Spelling uses [Apache Lucene's Hunspell](https://lucene.apache.org/) implementation, written in pure Java, with the same dictionaries as LibreOffice.
- Hunspell finds good candidates but doesn't know which words are common, so the mod re-ranks them. It weighs typo distance (neighbouring keys, swapped or doubled letters), English word frequency, and a small boost for Minecraft words. On the English test lists, this puts the right word first noticeably more often than Hunspell's own order: 45 vs 37 out of 49 on the final list.
- Checking, suggestions and dictionary loading each run on their own background thread, so the game never waits on them. Results are matched to the exact text they were made for, so a slow result can't underline or replace the wrong word.

The code is organised like this:

| Package | What's in it |
|---|---|
| `core` | Spellchecking, ranking, autocorrect and settings. No Minecraft code. |
| `ui` | Popover layout and state, right-to-left text layout |
| `client` | The chat overlay, settings screen and `/spellcheck` command |
| `mixin` | Hooks into Minecraft's chat screen and text box |

## 📜 License

The mod's code is [MIT](LICENSE). The bundled dictionaries and libraries have their own licences:

- Apache Lucene: Apache-2.0
- English dictionaries (SCOWL): permissive
- Word frequency list: MIT / CC BY 3.0
- German, Swiss German, Italian: GPL
- Hebrew: AGPL-3.0
- Danish, Spanish, Arabic: GPL, LGPL or MPL (your choice)
- French: MPL-2.0
- Dutch: BSD or CC BY
- Norwegian: CC BY 4.0

If you redistribute the JAR, follow those licences. You can find the full details in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

<div align="center">

Made with 💗 for everyone who types too fast in chat

</div>
