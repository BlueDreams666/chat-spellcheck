package dev.chatspellcheck.core;

import java.io.InputStream;

final class TestResources {
	private static HunspellChecker enUs;
	private static WordFrequency frequency;

	private TestResources() {
	}

	static InputStream open(String name) {
		return TestResources.class.getResourceAsStream("/assets/chatspellcheck/" + name);
	}

	static synchronized HunspellChecker enUs() {
		if (enUs == null) {
			enUs = HunspellChecker.load(Language.EN_US, n -> open("dictionaries/" + n));
		}
		return enUs;
	}

	/** The bundled allow-list (words the game never flags). */
	static java.util.Set<String> allowList() {
		java.util.Set<String> out = new java.util.HashSet<>();
		try (var r = new java.io.BufferedReader(new java.io.InputStreamReader(open("allowlist.txt"), java.nio.charset.StandardCharsets.UTF_8))) {
			r.lines().map(String::strip).filter(l -> !l.isEmpty() && !l.startsWith("#")).forEach(l -> out.add(l.toLowerCase(java.util.Locale.ROOT)));
		} catch (java.io.IOException e) {
			throw new java.io.UncheckedIOException(e);
		}
		return out;
	}

	private static final java.util.Map<Language, HunspellChecker> CHECKERS = new java.util.EnumMap<>(Language.class);

	static synchronized HunspellChecker checker(Language language) {
		return CHECKERS.computeIfAbsent(language, l -> HunspellChecker.load(l, n -> open("dictionaries/" + n)));
	}

	static synchronized WordFrequency frequency() {
		if (frequency == null) {
			frequency = WordFrequency.load(open("frequency/en_word_frequency.txt"));
		}
		return frequency;
	}
}
