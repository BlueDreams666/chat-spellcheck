package dev.chatspellcheck.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class HebrewAutocorrectTest {
	@Test
	void existingHebrewExamplesRemainReliable() {
		HunspellChecker he = TestResources.checker(Language.HE_IL);
		int applied = 0;
		for (String[][] cases : new String[][][] {AutocorrectTest.HEBREW, AutocorrectTest.HEBREW_HELD_OUT}) {
			int[] measured = AutocorrectTest.measureHebrew("Hebrew single edit", cases,
				word -> Autocorrect.hebrewTypo(word, he::isCorrect));
			assertEquals(measured[0], measured[1]);
			applied += measured[0];
		}
		assertTrue(applied >= 20, "retains final-letter fixes and adds general typos");
		assertEquals(Optional.of("עכשיו"), Autocorrect.hebrewTypo("עכשחו", he::isCorrect));
		assertEquals(Optional.empty(), Autocorrect.hebrewTypo("חשוכ", he::isCorrect));
		assertEquals(Optional.empty(), Autocorrect.hebrewTypo("בבשקה", he::isCorrect), "multiple dictionary candidates");
	}

	@Test
	void additionalSingleEditTyposAreConservative() {
		HunspellChecker he = TestResources.checker(Language.HE_IL);
		String[] words = ("מקלדת מסעדה משקפיים חלונות שולחן מיטה בניין טלפון ארוחת מוזיקה עבודה חופשה תמונה "
			+ "מצלמה רכבת תחנה בקבוק כוסות מתנה מסיבה חנות רחוב מדרכה דלתות מפתח תפוח בננה תפוז "
			+ "עוגה שוקולד גלידה כלבים חתולים ציפורים דגים סוסים פרחים עצים גינה שמחה עצוב "
			+ "מצחיק מעניין משעמם מתחיל מסיים פותח סוגר מחפש מוצא כותב קורא לומד זוכר שוכח "
			+ "משחק לילה בוקר צהריים שבוע חודש שנה ראשון אחרון אפשרות תוכנית אינטרנט חברים").split(" ");
		List<String[]> cases = new ArrayList<>();
		for (String word : words) {
			if (!he.isCorrect(word)) {
				continue;
			}
			for (int i = 0; i < word.length(); i++) {
				cases.add(new String[] {word.substring(0, i) + word.substring(i + 1), word});
				cases.add(new String[] {word.substring(0, i) + word.charAt(i) + word.substring(i), word});
				if (i + 1 < word.length() && word.charAt(i) != word.charAt(i + 1)) {
					cases.add(new String[] {word.substring(0, i) + word.charAt(i + 1) + word.charAt(i) + word.substring(i + 2), word});
				}
			}
		}
		// Measure the added rule separately: the existing final-letter rule has its own tests.
		int[] measured = AutocorrectTest.measureHebrew("Hebrew additional single edits", cases.toArray(String[][]::new),
			word -> Autocorrect.hebrewFinalLetter(word, he::isCorrect).isPresent()
				? Optional.empty() : Autocorrect.hebrewTypo(word, he::isCorrect));
		assertEquals(measured[0], measured[1]);
		assertTrue(measured[0] >= 40, "must correct more than final-letter mistakes");
	}

	@Test
	void checksEverySingleEditAndRejectsCompetingCandidates() {
		String typo = "אבגדה";
		for (String candidate : List.of("אבגזה", "אבגדוה", "אבדה", "אבגהד")) {
			assertEquals(Optional.of(candidate), Autocorrect.hebrewTypo(typo, Set.of(candidate)::contains));
		}
		assertEquals(Optional.empty(), Autocorrect.hebrewTypo(typo, Set.of("אבגזה", "אבגדוה")::contains));
		assertEquals(Optional.empty(), Autocorrect.hebrewTypo(typo, w -> false));
		assertEquals(Optional.of(typo), Autocorrect.hebrewTypo("אבגדהה", Set.of(typo)::contains),
			"deleting either repeated letter produces the same candidate");
	}

	@Test
	void leavesAcceptedWordsShortTyposAndOtherScriptsAlone() {
		assertEquals(Optional.empty(), Autocorrect.hebrewTypo("ספרימ", Set.of("ספרימ", "ספרים")::contains));
		assertEquals(Optional.empty(), Autocorrect.hebrewTypo("תדוה", Set.of("תודה")::contains));
		for (String word : List.of("", "hello", "aשלומ", "שלו\"מ", "שָלומ", "שלומ!", "א".repeat(33))) {
			assertEquals(Optional.empty(), Autocorrect.hebrewTypo(word, w -> w.equals("שלום")), word);
		}
	}

	@Test
	void boundsDictionaryWorkForLongInputs() {
		AtomicInteger lookups = new AtomicInteger();
		assertEquals(Optional.empty(), Autocorrect.hebrewTypo("אבגדהוזחטיכלמנסעפצקרשתאבגדהוזחטיכ", word -> {
			lookups.incrementAndGet();
			return false;
		}));
		assertTrue(lookups.get() > 0 && lookups.get() <= 1_900, "bounded one-edit search: " + lookups.get());
	}
	@Test
	void optimizedSearchMatchesExhaustiveSearch() {
		HunspellChecker he = TestResources.checker(Language.HE_IL);
		for (String[][] cases : new String[][][] {AutocorrectTest.HEBREW, AutocorrectTest.HEBREW_HELD_OUT}) {
			for (String[] pair : cases) {
				assertEquals(reference(pair[0], he::isCorrect), Autocorrect.hebrewTypo(pair[0], he::isCorrect), pair[0]);
			}
		}
		java.util.Random random = new java.util.Random(20261012);
		for (int sample = 0; sample < 200; sample++) {
			StringBuilder word = new StringBuilder();
			for (int i = 0, length = 5 + random.nextInt(6); i < length; i++) {
				word.append((char) ('א' + random.nextInt('ת' - 'א' + 1)));
			}
			String input = word.toString();
			assertEquals(reference(input, he::isCorrect), Autocorrect.hebrewTypo(input, he::isCorrect), input);
		}
	}

	@Test
	void reportSearchCost() {
		HunspellChecker he = TestResources.checker(Language.HE_IL);
		String[] inputs = {"עכשחו", "בבשקה", "עכשייו", "מחשבב", "אתמלו", "תלמידמי", "מכוינת", "יהלוום"};
		var bean = (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
		if (!bean.isThreadAllocatedMemorySupported()) {
			return;
		}
		bean.setThreadAllocatedMemoryEnabled(true);
		for (int i = 0; i < 200; i++) {
			reference(inputs[i % inputs.length], he::isCorrect);
			Autocorrect.hebrewTypo(inputs[i % inputs.length], he::isCorrect);
		}
		for (boolean optimized : new boolean[] {false, true}) {
			long allocated = bean.getCurrentThreadAllocatedBytes();
			long start = System.nanoTime();
			for (int i = 0; i < 1_000; i++) {
				String input = inputs[i % inputs.length];
				if (optimized) Autocorrect.hebrewTypo(input, he::isCorrect);
				else reference(input, he::isCorrect);
			}
			System.out.printf("HEBREW SEARCH %s: %.1f us/op, %d bytes/op%n", optimized ? "optimized" : "reference",
				(System.nanoTime() - start) / 1_000_000.0, (bean.getCurrentThreadAllocatedBytes() - allocated) / 1_000);
		}
	}

	/** Original exhaustive enumeration retained to check optimization equivalence. */
	private static Optional<String> reference(String word, java.util.function.Predicate<String> isWord) {
		if (word.isEmpty() || !word.chars().allMatch(c -> c >= 'א' && c <= 'ת') || isWord.test(word)) {
			return Optional.empty();
		}
		Optional<String> finalLetter = Autocorrect.hebrewFinalLetter(word, isWord);
		if (finalLetter.isPresent()) {
			return finalLetter;
		}
		if (word.length() < 5 || word.length() > 32) {
			return Optional.empty();
		}
		Set<String> candidates = new java.util.LinkedHashSet<>();
		for (int i = 0; i <= word.length(); i++) {
			String before = word.substring(0, i);
			String after = word.substring(i);
			for (char letter = 'א'; letter <= 'ת'; letter++) {
				candidates.add(before + letter + after);
				if (i < word.length()) {
					candidates.add(before + letter + after.substring(1));
				}
			}
			if (i < word.length()) {
				candidates.add(before + after.substring(1));
			}
			if (i + 1 < word.length()) {
				candidates.add(before + word.charAt(i + 1) + word.charAt(i) + word.substring(i + 2));
			}
		}
		candidates.remove(word);
		String found = null;
		for (String candidate : candidates) {
			if (isWord.test(candidate)) {
				if (found != null) {
					return Optional.empty();
				}
				found = candidate;
			}
		}
		return Optional.ofNullable(found);
	}

}
