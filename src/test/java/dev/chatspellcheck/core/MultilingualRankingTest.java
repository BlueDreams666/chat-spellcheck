package dev.chatspellcheck.core;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Frequency ranking for the non-English lists. There are no hand-written typo lists for these
 * languages, so each one gets a fixed sample of synthetic typos (a dropped, doubled, swapped or
 * neighbouring-key letter) of common words, and the frequency ranker must put the right word
 * first more often than ranking without frequencies. The printed numbers are the measurement.
 */
class MultilingualRankingTest {
	private static final int SAMPLE = 80;
	private static final String[] QWERTY = {"qwertyuiop", "asdfghjkl", "zxcvbnm"};

	@ParameterizedTest
	@EnumSource(value = Language.class, names = {"DE_DE", "ES_ES", "FR_FR", "IT_IT", "HE_IL"})
	void frequencyPutsTheRightWordFirstMoreOften(Language language) {
		HunspellChecker checker = TestResources.checker(language);
		WordFrequency frequency = WordFrequency.load(TestResources.open("frequency/" + language.frequencyList() + "_word_frequency.txt"));
		SuggestionRanker ranked = new SuggestionRanker(frequency, w -> false, false);
		SuggestionRanker plain = new SuggestionRanker(WordFrequency.empty(), w -> false, false);

		Random random = new Random(20261023L + language.ordinal());
		List<String> words = commonWords(language.frequencyList(), checker);
		int n = 0, rankedHits = 0, plainHits = 0;
		while (n < SAMPLE) {
			String word = words.get(random.nextInt(words.size()));
			String typo = typo(word, random);
			if (typo.equals(word) || accepted(checker, typo)) {
				continue;
			}
			List<String> raw = checker.suggest(typo);
			n++;
			rankedHits += first(ranked.rank(typo, raw), word);
			plainHits += first(plain.rank(typo, raw), word);
		}
		System.out.printf("%s: right word first %d/%d with frequencies, %d/%d without%n", language.code(), rankedHits, n, plainHits, n);
		assertTrue(rankedHits > plainHits, language + ": " + rankedHits + " vs " + plainHits);
	}

	private static int first(List<String> suggestions, String expected) {
		return !suggestions.isEmpty() && suggestions.getFirst().toLowerCase(Locale.ROOT).equals(expected) ? 1 : 0;
	}

	/** Words ranked 300 to 5,000 in the list, 5 to 10 letters, that the dictionary accepts. */
	private static List<String> commonWords(String list, HunspellChecker checker) {
		List<String> out = new ArrayList<>();
		try (BufferedReader r = new BufferedReader(new InputStreamReader(TestResources.open("frequency/" + list + "_word_frequency.txt"), StandardCharsets.UTF_8))) {
			int rank = 0;
			String line;
			while ((line = r.readLine()) != null && rank < 5_000) {
				if (line.startsWith("#")) {
					continue;
				}
				String word = line.substring(0, line.indexOf(' '));
				if (++rank >= 300 && word.length() >= 5 && word.length() <= 10 && word.chars().allMatch(Character::isLetter)
					&& accepted(checker, word)) {
					out.add(word);
				}
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		return out;
	}

	private static boolean accepted(HunspellChecker checker, String word) {
		return checker.isCorrect(word) || checker.isCorrect(Character.toUpperCase(word.charAt(0)) + word.substring(1));
	}

	private static String typo(String word, Random random) {
		int i = 1 + random.nextInt(word.length() - 2); // keep the first letter, like most real typos
		char c = word.charAt(i);
		return switch (random.nextInt(4)) {
			case 0 -> word.substring(0, i) + word.substring(i + 1);
			case 1 -> word.substring(0, i) + c + word.substring(i);
			case 2 -> word.substring(0, i) + word.charAt(i + 1) + c + word.substring(i + 2);
			default -> word.substring(0, i) + neighbour(c, word, random) + word.substring(i + 1);
		};
	}

	/** A key next to {@code c} on a QWERTY keyboard, or another letter of the word for other scripts. */
	private static char neighbour(char c, String word, Random random) {
		for (int row = 0; row < QWERTY.length; row++) {
			int col = QWERTY[row].indexOf(c);
			if (col >= 0) {
				String keys = QWERTY[row];
				return keys.charAt(col == 0 ? 1 : col == keys.length() - 1 ? col - 1 : col + (random.nextBoolean() ? 1 : -1));
			}
		}
		return word.charAt(random.nextInt(word.length()));
	}
}
