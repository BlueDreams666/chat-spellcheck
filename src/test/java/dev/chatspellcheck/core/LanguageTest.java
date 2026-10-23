package dev.chatspellcheck.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Every bundled dictionary loads, accepts a common word, rejects a typo and suggests the fix. */
class LanguageTest {
	private static void check(Language language, String good, String typo, String expected) {
		long t0 = System.nanoTime();
		HunspellChecker c = HunspellChecker.load(language, n -> TestResources.open("dictionaries/" + n));
		long ms = (System.nanoTime() - t0) / 1_000_000;
		assertTrue(c.isCorrect(good), language + " should accept " + good);
		assertFalse(c.isCorrect(typo), language + " should reject " + typo);
		List<String> s = new SuggestionRanker(WordFrequency.empty(), w -> false, language.isEnglish()).rank(typo, c.suggest(typo));
		System.out.printf("%s loaded in %d ms; %s -> %s%n", language.code(), ms, typo, s);
		assertTrue(s.subList(0, Math.min(3, s.size())).contains(expected), language + ": " + typo + " -> " + s);
	}

	@Test
	void danish() {
		check(Language.DA_DK, "verden", "verdn", "verden");
	}

	@Test
	void dutch() {
		check(Language.NL_NL, "wereld", "wereldd", "wereld");
	}

	@Test
	void french() {
		check(Language.FR_FR, "bonjour", "bonjoru", "bonjour");
	}

	@Test
	void german() {
		check(Language.DE_DE, "Straße", "Strasse", "Straße");
	}

	@Test
	void swissGerman() {
		check(Language.DE_CH, "Strasse", "Straße", "Strasse");
	}

	@Test
	void italian() {
		check(Language.IT_IT, "mondo", "mondoo", "mondo");
	}

	@Test
	void norwegian() {
		check(Language.NB_NO, "verden", "verdne", "verden");
	}

	@Test
	void spanish() {
		check(Language.ES_ES, "mundo", "mundoo", "mundo");
	}

	@Test
	void hebrew() {
		check(Language.HE_IL, "שלום", "עולמ", "עולם");
	}

	@Test
	void arabic() {
		check(Language.AR, "مرحبا", "مرحباا", "مرحبا");
	}

	@Test
	void englishVariants() {
		check(Language.EN_GB, "colour", "color", "colour");
		check(Language.EN_CA, "colour", "colur", "colour");
		check(Language.EN_AU, "colour", "colur", "colour");
	}

	@Test
	void parsing() {
		assertEquals(Optional.of(Language.DE_CH), Language.parse("de-ch"));
		assertEquals(Optional.of(Language.DE_DE), Language.parse("de"));
		assertEquals(Optional.of(Language.EN_US), Language.parse("en"));
		assertEquals(Optional.of(Language.EN_GB), Language.parse("gb"));
		assertEquals(Optional.of(Language.AR), Language.parse("ar"));
		assertEquals(Optional.of(Language.HE_IL), Language.parse("he"));
		assertEquals(Optional.empty(), Language.parse("klingon"));
		assertEquals(List.of(Language.EN_US, Language.ES_ES), Language.parseList("en_US, es,en_US,zz"));
	}
}
