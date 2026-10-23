package dev.chatspellcheck.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Autocorrect must almost never change a word into the wrong one. Precision (right / applied) is
 * the number that matters; coverage (applied / all) is secondary. The thresholds in Autocorrect
 * were chosen on TUNING only; HELD_OUT and FINAL are reported as measured.
 */
class AutocorrectTest {
	private static final SuggestionRanker RANKER = new SuggestionRanker(TestResources.frequency());

	/** Words players type on purpose that the English dictionary doesn't know. */
	static final String[] INTENTIONAL = {
		"yeet", "pog", "poggers", "bruh", "lmao", "lmfao", "rofl", "ngl", "tbh", "imo", "idk", "smh", "fr", "nvm",
		"kinda", "gonna", "wanna", "gotta", "dunno", "sorta", "lemme", "gimme", "ya", "yea", "yup", "nope", "nah",
		"noob", "noobs", "rekt", "oof", "pvp", "pve", "nerf", "nerfed", "op", "afk", "ez", "gg", "ggs", "wp", "sus",
		"vibe", "vibing", "lowkey", "highkey", "irl", "brb", "ty", "thx", "pls", "plz", "ok", "okk", "hehe", "haha",
		"xd", "uwu", "owo", "skibidi", "rizz", "sigma", "bestie", "grindy", "farmable", "afking", "griefer",
		"steve", "alex", "notch", "dream", "technoblade", "hypixel", "mineplex", "wynncraft", "optifine", "sodium",
		"iris", "fabric", "forge", "modrinth", "curseforge", "bedwars", "skywars", "skyblock", "spleef", "parkour"
	};

	static Optional<String> autocorrect(String word) {
		List<String> ranked = RankingTest.suggest(RANKER, word);
		return Autocorrect.choose(word, ranked, TestResources.frequency());
	}

	/** @return {applied, right, total} */
	static int[] measure(String name, String[][] cases) {
		int applied = 0, right = 0, total = 0;
		List<String> wrong = new ArrayList<>();
		for (String[] c : cases) {
			if (TestResources.enUs().isCorrect(c[0])) {
				continue;
			}
			total++;
			Optional<String> fix = autocorrect(c[0]);
			if (fix.isPresent()) {
				applied++;
				if (fix.get().equals(c[1])) {
					right++;
				} else {
					wrong.add(c[0] + "→" + fix.get() + " (wanted " + c[1] + ")");
				}
			}
		}
		System.out.printf("AUTOCORRECT %s: applied %d/%d, right %d/%d (precision %.0f%%) wrong: %s%n",
			name, applied, total, right, applied, applied == 0 ? 100.0 : 100.0 * right / applied, wrong);
		return new int[] {applied, right, total};
	}

	@Test
	void autocorrectIsRarelyWrong() {
		int[] tuning = measure("TUNING", RankingTest.TUNING);
		int[] heldOut = measure("HELD_OUT", RankingTest.HELD_OUT);
		int[] fin = measure("FINAL", RankingTest.FINAL);
		int applied = tuning[0] + heldOut[0] + fin[0];
		int right = tuning[1] + heldOut[1] + fin[1];
		assertTrue(right >= applied * 0.95, "precision " + right + "/" + applied);
		assertTrue(applied >= 60, "too timid to be useful: " + applied);
	}

	@Test
	void slangAndNamesAreLeftAlone() {
		List<String> changed = new ArrayList<>();
		java.util.Set<String> allow = TestResources.allowList(); // never flagged in game, so never corrected
		for (String w : INTENTIONAL) {
			if (!allow.contains(w) && !TestResources.enUs().isCorrect(w)) {
				autocorrect(w).ifPresent(fix -> changed.add(w + "→" + fix));
			}
		}
		System.out.println("AUTOCORRECT intentional words changed: " + changed.size() + "/" + INTENTIONAL.length + " " + changed);
		assertTrue(changed.size() <= 2, "would change intentional words: " + changed);
	}

	@Test
	void caseAndShapeRules() {
		assertEquals(Optional.of("the"), autocorrect("teh"));
		assertEquals(Optional.of("The"), autocorrect("Teh"));
		assertEquals(Optional.of("don't"), autocorrect("dont"));
		assertEquals(Optional.empty(), autocorrect("TEH"), "shouting/acronyms are left alone");
		assertEquals(Optional.empty(), autocorrect("alot"), "splits are offered, not forced");
		assertEquals(Optional.empty(), Autocorrect.choose("wnat", List.of("want", "what"), TestResources.frequency()));
		assertEquals(Optional.empty(), Autocorrect.choose("xq", List.of("xi"), null), "too short");
	}

	// ---------------------------------------------------------------- Hebrew

	/**
	 * Realistic Hebrew chat typos, written before measuring: final-letter forms, neighbouring keys
	 * on the Hebrew keyboard, swapped, doubled and missing letters, and sound-alike letters.
	 * Typos that happen to be real words are skipped (the dictionary accepts them).
	 */
	static final String[][] HEBREW = {
		// final letters typed as regular forms
		{"שלומ", "שלום"}, {"עולמ", "עולם"}, {"ספרימ", "ספרים"}, {"חיימ", "חיים"}, {"מימ", "מים"}, {"יומ", "יום"},
		{"איכ", "איך"}, {"גדולימ", "גדולים"}, {"ילדימ", "ילדים"}, {"חברימ", "חברים"}, {"הולכ", "הולך"},
		{"צריכ", "צריך"}, {"אויבימ", "אויבים"}, {"משחקימ", "משחקים"}, {"עכשיו", "עכשיו"},
		// neighbouring keys (Hebrew layout)
		{"תודנ", "תודה"}, {"בבקשע", "בבקשה"}, {"טוכ", "טוב"}, {"חבג", "חבר"}, {"ילג", "ילד"}, {"יפע", "יפה"},
		{"מחשכ", "מחשב"}, {"אוהכ", "אוהב"}, {"עכשחו", "עכשיו"}, {"בסדק", "בסדר"}, {"קטו", "קטן"},
		// swapped letters
		{"תדוה", "תודה"}, {"בבשקה", "בבקשה"}, {"חבירם", "חברים"}, {"ילידם", "ילדים"}, {"אתמלו", "אתמול"},
		{"עכשוי", "עכשיו"}, {"מחשבו", "מחשבו"},
		// doubled letters
		{"שלוום", "שלום"}, {"תודהה", "תודה"}, {"בבבקשה", "בבקשה"}, {"עכשייו", "עכשיו"}, {"גדוול", "גדול"},
		{"יהלוום", "יהלום"}, {"מחשבב", "מחשב"},
		// missing letters
		{"עכשו", "עכשיו"}, {"בבקשא", "בבקשה"}, {"אתמל", "אתמול"},
		// sound-alike letters
		{"תוב", "טוב"}, {"כטן", "קטן"}, {"מסחק", "משחק"}, {"תאבון", "תיאבון"}
	};

	/** Held out from the original Hebrew ranking work; also used as regression cases for single-edit autocorrect. */
	static final String[][] HEBREW_HELD_OUT = {
		// final letters
		{"חלומ", "חלום"}, {"שמימ", "שמים"}, {"לחמ", "לחם"}, {"מקומ", "מקום"}, {"אנשימ", "אנשים"}, {"ירושלימ", "ירושלים"},
		{"נעימ", "נעים"}, {"לפעמימ", "לפעמים"},
		// neighbouring keys
		{"מלחמע", "מלחמה"}, {"דבריפ", "דברים"}, {"חשוכ", "חשוב"}, {"קשע", "קשה"}, {"זמו", "זמן"}, {"שוכ", "שוב"},
		// swapped letters
		{"משפהח", "משפחה"}, {"תלמידמי", "תלמידים"}, {"מכוינת", "מכונית"},
		// missing letters (often ו/י)
		{"מכנית", "מכונית"}, {"תלמדים", "תלמידים"}, {"לפעמם", "לפעמים"}, {"משו", "משהו"},
		// doubled letters
		{"אפשרר", "אפשר"}, {"מורהה", "מורה"}, {"חדשש", "חדש"}, {"תמידד", "תמיד"},
		// sound-alike letters
		{"כתול", "חתול"}, {"מחתב", "מכתב"}, {"טמיד", "תמיד"}
	};

	private static final SuggestionRanker HEBREW_RANKER = new SuggestionRanker(WordFrequency.empty(), w -> false, false);

	static Optional<String> autocorrectHebrew(String word) {
		HunspellChecker he = TestResources.checker(Language.HE_IL);
		List<String> ranked = HEBREW_RANKER.rank(word, he.suggest(SpellService.lookupForm(word)));
		return Autocorrect.choose(word, ranked, null);
	}

	/**
	 * General Hebrew autocorrect is measured but not used: without word frequencies it can't tell
	 * near-identical words apart (חשוב/חשוך), and it was right only 69–78% of the time.
	 */
	@Test
	void generalHebrewAutocorrectIsTooUnreliableToEnable() {
		int[] tuning = measureHebrew("HEBREW general", HEBREW, AutocorrectTest::autocorrectHebrew);
		int[] heldOut = measureHebrew("HEBREW_HELD_OUT general", HEBREW_HELD_OUT, AutocorrectTest::autocorrectHebrew);
		assertTrue(tuning[1] + heldOut[1] < (tuning[0] + heldOut[0]) * 0.95, "if this ever passes 95%, reconsider enabling it");
	}

	/** The final-letter fix that is used must be right essentially every time. */
	@Test
	void hebrewFinalLetterFixIsReliable() {
		HunspellChecker he = TestResources.checker(Language.HE_IL);
		java.util.function.Function<String, Optional<String>> rule = w -> Autocorrect.hebrewFinalLetter(w, he::isCorrect);
		int[] tuning = measureHebrew("HEBREW final letters", HEBREW, rule);
		int[] heldOut = measureHebrew("HEBREW_HELD_OUT final letters", HEBREW_HELD_OUT, rule);
		assertEquals(tuning[0], tuning[1], "every applied final-letter fix should be right");
		assertEquals(heldOut[0], heldOut[1], "every applied final-letter fix should be right");
		assertTrue(tuning[0] + heldOut[0] >= 15, "covers the common final-letter mistakes");
		assertEquals(Optional.empty(), rule.apply("חשוכ"), "final כ is left alone (ב is next to it)");
		assertEquals(Optional.of("שלום"), rule.apply("שלומ"));
	}

	/** @return {applied, right, total} */
	static int[] measureHebrew(String name, String[][] cases, java.util.function.Function<String, Optional<String>> autocorrect) {
		HunspellChecker he = TestResources.checker(Language.HE_IL);
		int applied = 0, right = 0, total = 0;
		List<String> wrong = new ArrayList<>();
		for (String[] c : cases) {
			if (he.isCorrect(c[0])) {
				continue; // the "typo" is itself a word
			}
			total++;
			Optional<String> fix = autocorrect.apply(c[0]);
			if (fix.isPresent()) {
				applied++;
				if (fix.get().equals(c[1])) {
					right++;
				} else {
					wrong.add(c[0] + "→" + fix.get() + " (wanted " + c[1] + ")");
				}
			}
		}
		System.out.printf("AUTOCORRECT %s: applied %d/%d, right %d/%d (precision %.0f%%) wrong: %s%n",
			name, applied, total, right, applied, applied == 0 ? 100.0 : 100.0 * right / applied, wrong);
		return new int[] {applied, right, total};
	}
}
