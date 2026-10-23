# Third-party notices

Chat Spellcheck's own code is MIT-licensed (see `LICENSE`). The mod JAR also contains:

## Apache Lucene 10.5.2 (`lucene-core`, `lucene-analysis-common`)

Bundled unmodified via Fabric jar-in-jar. Provides the Hunspell spelling engine.
Licensed under the Apache License, Version 2.0 — https://www.apache.org/licenses/LICENSE-2.0.
The Lucene JARs include their own `LICENSE.txt` and `NOTICE.txt` in `META-INF/`.

## English Hunspell dictionaries (SCOWL), version 2026.02.25

`assets/chatspellcheck/dictionaries/en_US|en_GB|en_CA|en_AU.{aff,dic}` are the unmodified
`hunspell-en_US`, `hunspell-en_GB-ise` (renamed `en_GB`), `hunspell-en_CA` and `hunspell-en_AU`
builds from https://github.com/en-wl/wordlist/releases/tag/rel-2026.02.25
(https://wordlist.aspell.net). The full copyright and licence text, including the SCOWL
notice (Copyright 2000–2026 Kevin Atkinson), the Benjamin Titze notice, and Geoff Kuenning's
BSD licence for the affix file, is shipped verbatim in
`assets/chatspellcheck/dictionaries/README_SCOWL.txt`.

## Other language dictionaries (LibreOffice dictionaries repository)

Bundled unmodified, except for renamed files, from https://github.com/LibreOffice/dictionaries
(fetched 2026‑10‑09). Each dictionary keeps its own licence. The original README and licence
files, and the full GPL‑2.0, GPL‑3.0, AGPL‑3.0 and MPL‑2.0 texts, are shipped in
`assets/chatspellcheck/dictionaries/licenses/`. These files are data loaded at runtime. The
mod's own MIT licence does not apply to them, and anyone redistributing the JAR must follow
each dictionary's licence.

| File | Source | Licence |
|---|---|---|
| `da_DK` | da_DK (Stavekontrolden) | GPL‑2.0 / LGPL‑2.1 / MPL‑1.1 (any one) |
| `nl_NL` | nl_NL (OpenTaal) | BSD‑3‑Clause and/or CC BY 3.0 |
| `fr_FR` | fr_FR/dictionaries/fr (Dicollecte, "classique") | MPL‑2.0 |
| `de_DE` | de/de_DE_frami (igerman98, Franz Michael Baumann) | GPL‑2.0 or GPL‑3.0 |
| `de_CH` | de/de_CH_frami | GPL‑2.0 or GPL‑3.0 |
| `it_IT` | it_IT (LibreItalia) | GPL‑3.0 |
| `nb_NO` | no/nb_NO (Norsk Ordbank / CLARIN) | CC BY 4.0, CLARIN PUB+BY |
| `es_ES` | es/es_ES (RLA‑ES) | GPL‑3.0+ / LGPL‑3.0+ / MPL‑1.1+ (any one) |
| `he_IL` | he_IL (Hspell) | AGPL‑3.0 |
| `ar` | ar (Ayaspell) | GPL‑2.0+ / LGPL‑2.1+ / MPL‑1.1+ (any one) |

To obtain the dictionary sources, use the repository above. The bundled `.aff`/`.dic`
files are themselves the source form.

## Word frequency lists

The files in `assets/chatspellcheck/frequency/` are derived from SymSpell's frequency
dictionaries (https://github.com/wolfgarbe/SymSpell), Copyright (c) 2018 Wolf Garbe, MIT License:

| File | SymSpell source |
|---|---|
| `en_word_frequency.txt` | `en-80k.txt` |
| `de_word_frequency.txt` | `SymSpell.FrequencyDictionary/de-100k.txt` |
| `es_word_frequency.txt` | `SymSpell.FrequencyDictionary/es-100l.txt` |
| `fr_word_frequency.txt` | `SymSpell.FrequencyDictionary/fr-100k.txt` |
| `he_word_frequency.txt` | `SymSpell.FrequencyDictionary/he-100k.txt` |
| `it_word_frequency.txt` | `SymSpell.FrequencyDictionary/it-100k.txt` |

Those lists were built from Google Books Ngram counts (Creative Commons Attribution 3.0,
https://creativecommons.org/licenses/by/3.0/) intersected with Hunspell word lists.
Modification: counts were converted to round(10 × log10(count)), words were lower-cased, and
non-alphabetic entries were removed. They are used only to order suggestions.

MIT License text for SymSpell:

    Permission is hereby granted, free of charge, to any person obtaining a copy of this
    software and associated documentation files (the "Software"), to deal in the Software
    without restriction, including without limitation the rights to use, copy, modify, merge,
    publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons
    to whom the Software is furnished to do so, subject to the following conditions:

    The above copyright notice and this permission notice shall be included in all copies or
    substantial portions of the Software.

    THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED,
    INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR
    PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE
    FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR
    OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER
    DEALINGS IN THE SOFTWARE.
