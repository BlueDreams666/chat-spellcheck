package dev.chatspellcheck.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * The optimised TypoDistance and WordFrequency must give exactly the results of the original,
 * straightforward implementations (kept here as references), so ranking cannot change.
 */
class EquivalenceTest {
	@Test
	void typoDistanceMatchesTheFullTableImplementation() {
		Random random = new Random(7);
		String alphabet = "aeiouyqwrtsdfgzxcvbnm'éèüößçñםמןנךכשלויבהעג";
		for (int round = 0; round < 50_000; round++) {
			String a = randomWord(random, alphabet, random.nextInt(14));
			String b = random.nextInt(4) == 0 ? a : randomWord(random, alphabet, random.nextInt(14));
			double expected = Reference.between(a, b);
			double actual = TypoDistance.between(a, b);
			assertEquals(Double.doubleToLongBits(expected), Double.doubleToLongBits(actual), a + " / " + b);
		}
		// Long inputs exercise the growing row buffer.
		String longA = "x".repeat(300) + "abc";
		String longB = "x".repeat(299) + "acb";
		assertEquals(Reference.between(longA, longB), TypoDistance.between(longA, longB));
		for (char a = 0; a < 128; a++) {
			for (char b = 0; b < 128; b++) {
				assertEquals(Reference.adjacentKeys(a, b), TypoDistance.adjacentKeys(a, b), a + " " + b);
			}
		}

	}

	@Test
	void wordFrequencyMatchesAHashMap() throws Exception {
		Map<String, Integer> reference = new HashMap<>();
		List<String> words = new ArrayList<>();
		try (BufferedReader r = new BufferedReader(new InputStreamReader(TestResources.open("frequency/en_word_frequency.txt"), StandardCharsets.UTF_8))) {
			String line;
			while ((line = r.readLine()) != null) {
				int space = line.indexOf(' ');
				if (!line.isEmpty() && line.charAt(0) != '#' && space > 0) {
					reference.put(line.substring(0, space), Integer.parseInt(line.substring(space + 1)));
					words.add(line.substring(0, space));
				}
			}
		}
		WordFrequency f = TestResources.frequency();
		for (String w : words) {
			assertEquals((int) reference.get(w), f.score(w), w);
		}
		Random random = new Random(3);
		for (int i = 0; i < 50_000; i++) {
			String w = words.get(random.nextInt(words.size()));
			String q = w.substring(0, random.nextInt(w.length() + 1)) + (char) ('a' + random.nextInt(26));
			assertEquals((int) reference.getOrDefault(q, WordFrequency.UNKNOWN), f.score(q), q);
		}
		assertEquals(WordFrequency.UNKNOWN, f.score("שלום"));
		assertEquals(WordFrequency.UNKNOWN, f.score(""));
		assertEquals(Math.min(reference.get("a"), reference.get("lot")), f.score("a lot"));
		assertEquals(f.score("the"), f.score("The"));
		assertEquals(WordFrequency.UNKNOWN, WordFrequency.empty().score("the"));
	}

	private static String randomWord(Random random, String alphabet, int length) {
		StringBuilder b = new StringBuilder();
		for (int i = 0; i < length; i++) {
			b.append(alphabet.charAt(random.nextInt(alphabet.length())));
		}
		return b.toString();
	}

	/** The 1.0.1 implementation, verbatim apart from names. */
	private static final class Reference {
		private static final String HEBREW_FINALS = "ךכםמןנףפץצ";
		private static final String[] QWERTY_ROWS = {"1234567890", "qwertyuiop", "asdfghjkl", "zxcvbnm"};

		static double between(String a, String b) {
			a = a.replace("ß", "ss");
			b = b.replace("ß", "ss");
			int n = a.length();
			int m = b.length();
			double[][] d = new double[n + 1][m + 1];
			for (int i = 1; i <= n; i++) {
				d[i][0] = d[i - 1][0] + indelCost(a, i - 1);
			}
			for (int j = 1; j <= m; j++) {
				d[0][j] = d[0][j - 1] + indelCost(b, j - 1);
			}
			for (int i = 1; i <= n; i++) {
				for (int j = 1; j <= m; j++) {
					char ca = a.charAt(i - 1);
					char cb = b.charAt(j - 1);
					double best = d[i - 1][j - 1] + substitutionCost(ca, cb);
					best = Math.min(best, d[i - 1][j] + indelCost(a, i - 1));
					best = Math.min(best, d[i][j - 1] + indelCost(b, j - 1));
					if (i > 1 && j > 1 && ca == b.charAt(j - 2) && a.charAt(i - 2) == cb && ca != cb) {
						best = Math.min(best, d[i - 2][j - 2] + 0.6);
					}
					d[i][j] = best;
				}
			}
			return d[n][m];
		}

		private static double indelCost(String s, int i) {
			char c = s.charAt(i);
			if (c == '\'') {
				return 0.3;
			}
			boolean doubled = (i > 0 && s.charAt(i - 1) == c) || (i + 1 < s.length() && s.charAt(i + 1) == c);
			return doubled ? 0.5 : 1.0;
		}

		private static double substitutionCost(char a, char b) {
			if (a == b) {
				return 0;
			}
			if (baseLetter(a) == baseLetter(b)) {
				return 0.3;
			}
			if (("aeiouy".indexOf(a) >= 0 && "aeiouy".indexOf(b) >= 0) || adjacentKeys(a, b)) {
				return 0.6;
			}
			return 1.0;
		}

		private static char baseLetter(char c) {
			int hebrew = HEBREW_FINALS.indexOf(c);
			if (hebrew >= 0) {
				return HEBREW_FINALS.charAt(hebrew | 1);
			}
			if (c < 0x80) {
				return c;
			}
			return java.text.Normalizer.normalize(String.valueOf(c), java.text.Normalizer.Form.NFD).charAt(0);
		}

		static boolean adjacentKeys(char a, char b) {
			int[] pa = keyPosition(a);
			int[] pb = keyPosition(b);
			if (pa == null || pb == null) {
				return false;
			}
			int dRow = pb[0] - pa[0];
			if (dRow == 0) {
				return Math.abs(pb[1] - pa[1]) == 1;
			}
			if (dRow == 1) {
				return pb[1] == pa[1] || pb[1] == pa[1] - 1;
			}
			if (dRow == -1) {
				return pb[1] == pa[1] || pb[1] == pa[1] + 1;
			}
			return false;
		}

		private static int[] keyPosition(char c) {
			String[] rows = QWERTY_ROWS;
			for (int row = 0; row < rows.length; row++) {
				int col = rows[row].indexOf(c);
				if (col >= 0) {
					return new int[] {row, col};
				}
			}
			return null;
		}
	}
}
