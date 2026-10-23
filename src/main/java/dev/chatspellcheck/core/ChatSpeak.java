package dev.chatspellcheck.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Chat spellings that are on purpose rather than typos: laughs ("hahaha", "jajaja", "חחחח")
 * and stretched words ("sooo", "yesss", "lmaooo"), which are fine when the shorter word is.
 */
final class ChatSpeak {
	/** Runs of this many of the same letter count as stretching; "ll" in "untill" does not. */
	private static final int STRETCH = 3;
	/** At most this many stretched runs are undone, so a word yields at most 2^4 shorter forms. */
	private static final int MAX_RUNS = 4;

	private static final Pattern LAUGH = Pattern.compile(
		"a?(ha){2,}h?|(he){2,}h?|(hi){2,}h?|(ja){2,}j?|(je){2,}j?|(ka){2,}k?|(xd)+|l(o+l+)+|ח{3,}|ه{3,}|k{4,}|w{3,}");

	private ChatSpeak() {
	}

	/** True for laughs of three or more letters, in any case. */
	static boolean isLaugh(String word) {
		return word.length() >= 3 && LAUGH.matcher(word.toLowerCase(Locale.ROOT)).matches();
	}

	/**
	 * The word with each run of three or more of the same letter cut to one and to two letters
	 * ("sooo" → "so", "soo"; "coool" → "col", "cool"), or an empty list if nothing is stretched.
	 */
	static List<String> unstretched(String word) {
		List<int[]> runs = new ArrayList<>();
		int[] cps = word.codePoints().toArray();
		for (int i = 0; i < cps.length; ) {
			int j = i;
			while (j < cps.length && cps[j] == cps[i]) {
				j++;
			}
			if (j - i >= STRETCH && Character.isLetter(cps[i])) {
				if (runs.size() == MAX_RUNS) {
					return List.of();
				}
				runs.add(new int[] {i, j});
			}
			i = j;
		}
		if (runs.isEmpty()) {
			return List.of();
		}
		List<String> out = new ArrayList<>(1 << runs.size());
		for (int mask = 0; mask < 1 << runs.size(); mask++) {
			StringBuilder b = new StringBuilder(word.length());
			int at = 0;
			for (int r = 0; r < runs.size(); r++) {
				int[] run = runs.get(r);
				for (int k = at; k < run[0]; k++) {
					b.appendCodePoint(cps[k]);
				}
				int keep = (mask & (1 << r)) == 0 ? 1 : 2;
				for (int k = 0; k < keep; k++) {
					b.appendCodePoint(cps[run[0]]);
				}
				at = run[1];
			}
			for (int k = at; k < cps.length; k++) {
				b.appendCodePoint(cps[k]);
			}
			out.add(b.toString());
		}
		return out;
	}
}
