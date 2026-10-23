package dev.chatspellcheck.core;

/**
 * Weighted Damerau–Levenshtein (optimal string alignment) distance tuned for typing errors:
 * neighbouring keys, vowel swaps, doubled letters, transpositions and missing apostrophes
 * are cheaper than arbitrary edits. Also aware of a few non-English equivalences: ß = ss,
 * accented letters are close to their base letter (é ≈ e, ü ≈ u), and Hebrew final letter
 * forms match their regular forms (ם ≈ מ). Inputs are expected in lower case.
 */
final class TypoDistance {
	private static final double FULL = 1.0;
	private static final double CHEAP_SUBSTITUTION = 0.6; // adjacent key or vowel-for-vowel
	private static final double TRANSPOSITION = 0.6;
	private static final double DOUBLED_LETTER = 0.5; // "comming" ↔ "coming"
	private static final double APOSTROPHE = 0.3; // "dont" ↔ "don't"
	private static final double SAME_BASE_LETTER = 0.3; // "e" ↔ "é", "u" ↔ "ü", "מ" ↔ "ם"
	private static final String HEBREW_FINALS = "ךכםמןנףפץצ"; // pairs: final, regular

	private static final String[] QWERTY_ROWS = {"1234567890", "qwertyuiop", "asdfghjkl", "zxcvbnm"};


	private TypoDistance() {
	}

	/** Three rolling rows (i-2, i-1, i) per thread, grown as needed; between() is called often while ranking. */
	private static final ThreadLocal<double[][]> ROWS = ThreadLocal.withInitial(() -> new double[3][32]);

	static double between(String a, String b) {
		a = a.replace("ß", "ss");
		b = b.replace("ß", "ss");
		int n = a.length();
		int m = b.length();
		double[] indelA = indelCosts(a);
		double[] indelB = indelCosts(b);
		char[] baseA = baseLetters(a);
		char[] baseB = baseLetters(b);
		double[][] rows = ROWS.get();
		if (rows[0].length < m + 1) {
			rows = new double[3][Math.max(m + 1, rows[0].length * 2)];
			ROWS.set(rows);
		}
		// Same recurrence and evaluation order as a full (n+1)×(m+1) table, so results are identical.
		double[] prev2 = rows[0];
		double[] prev = rows[1];
		double[] cur = rows[2];
		prev[0] = 0;
		for (int j = 1; j <= m; j++) {
			prev[j] = prev[j - 1] + indelB[j - 1];
		}
		for (int i = 1; i <= n; i++) {
			char ca = a.charAt(i - 1);
			cur[0] = prev[0] + indelA[i - 1];
			for (int j = 1; j <= m; j++) {
				char cb = b.charAt(j - 1);
				double best = prev[j - 1] + substitutionCost(ca, cb, baseA[i - 1], baseB[j - 1]);
				best = Math.min(best, prev[j] + indelA[i - 1]);
				best = Math.min(best, cur[j - 1] + indelB[j - 1]);
				if (i > 1 && j > 1 && ca == b.charAt(j - 2) && a.charAt(i - 2) == cb && ca != cb) {
					best = Math.min(best, prev2[j - 2] + TRANSPOSITION);
				}
				cur[j] = best;
			}
			double[] t = prev2;
			prev2 = prev;
			prev = cur;
			cur = t;
		}
		return prev[m];
	}

	private static double[] indelCosts(String s) {
		double[] out = new double[s.length()];
		for (int i = 0; i < out.length; i++) {
			out[i] = indelCost(s, i);
		}
		return out;
	}

	private static char[] baseLetters(String s) {
		char[] out = new char[s.length()];
		for (int i = 0; i < out.length; i++) {
			out[i] = baseLetter(s.charAt(i));
		}
		return out;
	}

	/** Cost of inserting or deleting {@code s.charAt(i)}. */
	private static double indelCost(String s, int i) {
		char c = s.charAt(i);
		if (c == '\'') {
			return APOSTROPHE;
		}
		boolean doubled = (i > 0 && s.charAt(i - 1) == c) || (i + 1 < s.length() && s.charAt(i + 1) == c);
		return doubled ? DOUBLED_LETTER : FULL;
	}

	private static double substitutionCost(char a, char b, char baseA, char baseB) {
		if (a == b) {
			return 0;
		}
		if (baseA == baseB) {
			return SAME_BASE_LETTER;
		}
		if ((isVowel(a) && isVowel(b)) || adjacentKeys(a, b)) {
			return CHEAP_SUBSTITUTION;
		}
		return FULL;
	}

	/** The letter without accents/umlauts, or the regular form of a Hebrew final letter. */
	private static char baseLetter(char c) {
		int hebrew = HEBREW_FINALS.indexOf(c);
		if (hebrew >= 0) {
			return HEBREW_FINALS.charAt(hebrew | 1);
		}
		if (c < 0x80) {
			return c;
		}
		String decomposed = java.text.Normalizer.normalize(String.valueOf(c), java.text.Normalizer.Form.NFD);
		return decomposed.charAt(0);
	}

	private static boolean isVowel(char c) {
		return "aeiouy".indexOf(c) >= 0;
	}

	/** Row and column of each ASCII key on a QWERTY layout, or -1. */
	private static final byte[] KEY_ROW = new byte[128];
	private static final byte[] KEY_COL = new byte[128];

	static {
		java.util.Arrays.fill(KEY_ROW, (byte) -1);
		for (int row = 0; row < QWERTY_ROWS.length; row++) {
			for (int col = 0; col < QWERTY_ROWS[row].length(); col++) {
				char c = QWERTY_ROWS[row].charAt(col);
				KEY_ROW[c] = (byte) row;
				KEY_COL[c] = (byte) col;
			}
		}
	}

	static boolean adjacentKeys(char a, char b) {
		if (a >= 128 || b >= 128 || KEY_ROW[a] < 0 || KEY_ROW[b] < 0) {
			return false;
		}
		int dRow = KEY_ROW[b] - KEY_ROW[a];
		int ca = KEY_COL[a];
		int cb = KEY_COL[b];
		// Staggered rows: a key touches the two keys diagonally below-left/below at col, col-1.
		if (dRow == 0) {
			return Math.abs(cb - ca) == 1;
		}
		if (dRow == 1) {
			return cb == ca || cb == ca - 1;
		}
		if (dRow == -1) {
			return cb == ca || cb == ca + 1;
		}
		return false;
	}
}
