package dev.chatspellcheck.core;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Decides whether a misspelled word may be corrected <em>without asking</em>. This is much
 * stricter than offering suggestions: a wrong automatic change is worse than none, so a word is
 * only corrected when the best suggestion is one small typo away and clearly ahead of the next.
 * Measured precision on the typo lists is in AutocorrectTest.
 */
public final class Autocorrect {
	/**
	 * Words of 5+ letters may be one full edit away (a wrong, missing or extra letter). Shorter
	 * words only get "cheap" fixes (swapped letters, a neighbouring key, a doubled letter, a missing
	 * apostrophe): short unknown words are often slang or names ("oof", "sus"), not typos.
	 */
	static final double MAX_DISTANCE = 1.0;
	static final double MAX_DISTANCE_SHORT = 0.6;
	static final int LONG_WORD = 5;
	/** The runner-up must be this much further away... */
	static final double DISTANCE_MARGIN = 0.3;
	/** ...or (English) the best must be this many frequency points more common (10 = 10× as common). */
	static final int FREQUENCY_MARGIN = 10;
	static final int MIN_LENGTH = 3;

	private Autocorrect() {
	}

	/** What autocorrect may fix in each language, chosen by measurement (see the tests). */
	public enum Policy {
		/** Any near-certain typo ({@link #choose}). */
		GENERAL,
		/** Only missing accents and letter variants ({@link #equivalentSpelling}). */
		ACCENTS,
		/** Only Hebrew final letters ({@link #hebrewFinalLetter}). */
		HEBREW_FINALS,
		/** Never: no rule measured reliable enough. */
		OFF,
		/** Final-letter fixes and unambiguous single-edit Hebrew typos ({@link #hebrewTypo}). */
		HEBREW_TYPOS
	}

	/**
	 * Measured on generated typos of common words (MultilingualAutocorrectTest), the general rule
	 * is right only 61–93% of the time outside English, so other languages get only the narrow fix
	 * that measured 95%+. Swiss German (90%), Danish (75%) and Arabic (40%) missed even that.
	 * Hebrew uses a separate single-edit ambiguity check (HebrewAutocorrectTest).
	 */
	public static Policy policy(Language language) {
		if (language.isEnglish()) {
			return Policy.GENERAL;
		}
		return switch (language) {
			case ES_ES, FR_FR, DE_DE, IT_IT, NL_NL, NB_NO -> Policy.ACCENTS;
			case HE_IL -> Policy.HEBREW_TYPOS;
			default -> Policy.OFF;
		};
	}

	/**
	 * @param word       the misspelled word as typed
	 * @param ranked     ranked suggestions for it, best first
	 * @param frequency  English word frequencies, or null for other languages
	 * @return the replacement, in the word's letter case, if it is safe to apply automatically
	 */
	public static Optional<String> choose(String word, List<String> ranked, WordFrequency frequency) {
		double maxDistance = word.codePointCount(0, word.length()) >= LONG_WORD ? MAX_DISTANCE : MAX_DISTANCE_SHORT;
		return choose(word, ranked, frequency, maxDistance, DISTANCE_MARGIN);
	}

	static Optional<String> choose(String word, List<String> ranked, WordFrequency frequency, double maxDistance, double distanceMargin) {
		if (ranked.isEmpty() || word.codePointCount(0, word.length()) < MIN_LENGTH || !isPlainWord(word)) {
			return Optional.empty();
		}
		SuggestionRanker.Casing casing = SuggestionRanker.Casing.of(word);
		if (casing == SuggestionRanker.Casing.OTHER && hasCasedLetter(word)) {
			return Optional.empty(); // "iPhone", "LOL": not something to second-guess (Hebrew has no case)
		}
		String lower = word.toLowerCase(Locale.ROOT).replace('’', '\'');
		String best = ranked.getFirst();
		if (best.indexOf(' ') >= 0 || best.indexOf('-') >= 0) {
			return Optional.empty(); // splits ("a lot") are offered, never forced
		}
		double d0 = TypoDistance.between(lower, best.toLowerCase(Locale.ROOT));
		if (d0 > maxDistance) {
			return Optional.empty();
		}
		if (ranked.size() > 1) {
			String second = ranked.get(1);
			double d1 = TypoDistance.between(lower, second.toLowerCase(Locale.ROOT));
			boolean clearlyCloser = d1 - d0 >= distanceMargin;
			boolean clearlyCommoner = frequency != null && frequency.score(best) - frequency.score(second) >= FREQUENCY_MARGIN;
			if (!clearlyCloser && !clearlyCommoner) {
				return Optional.empty(); // "wnat": want or what? don't guess
			}
		}
		return Optional.of(best);
	}

	private static boolean hasCasedLetter(String word) {
		return word.codePoints().anyMatch(cp -> Character.isUpperCase(cp) || Character.isLowerCase(cp));
	}

	/** Regular Hebrew letters that must be written in final form at the end of a word, and those forms. */
	private static final String HEBREW_REGULAR = "מנפצ";
	private static final String HEBREW_FINAL = "םןףץ";

	/**
	 * Fixes a regular letter at the end of a Hebrew word where spelling requires its final form
	 * ("שלומ" → "שלום", "ילדימ" → "ילדים"), when the result is a word. General Hebrew autocorrect was measured at 69–78% right (see AutocorrectTest), which is
	 * too often wrong to apply without asking. כ is left out: it sits next to ב on the Hebrew
	 * keyboard, so a final כ is as often a mistyped ב ("חשוכ" for "חשוב") as a missing ך.
	 */
	public static Optional<String> hebrewFinalLetter(String word, java.util.function.Predicate<String> isWord) {
		if (word.length() < 2) {
			return Optional.empty();
		}
		int i = HEBREW_REGULAR.indexOf(word.charAt(word.length() - 1));
		if (i < 0) {
			return Optional.empty();
		}
		String fixed = word.substring(0, word.length() - 1) + HEBREW_FINAL.charAt(i);
		return isWord.test(fixed) ? Optional.of(fixed) : Optional.empty();
	}

	/**
	 * Final-letter fixes, then a unique dictionary word one edit away from a Hebrew typo.
	 * General fixes require 5–32 plain Hebrew letters. Enumerate the full one-edit
	 * neighbourhood so a truncated suggestion list cannot hide an equally plausible word.
	 */
	static Optional<String> hebrewTypo(String word, java.util.function.Predicate<String> isWord) {
		if (word.isEmpty() || !word.chars().allMatch(c -> c >= 'א' && c <= 'ת') || isWord.test(word)) {
			return Optional.empty();
		}
		Optional<String> finalLetter = hebrewFinalLetter(word, isWord);
		if (finalLetter.isPresent()) {
			return finalLetter;
		}
		if (word.length() < 5 || word.length() > 32) {
			return Optional.empty();
		}
		HebrewCandidates candidates = new HebrewCandidates(isWord);
		char[] letters = word.toCharArray();
		char[] edited = letters.clone();
		for (int i = 0; i < letters.length; i++) {
			for (char letter = 'א'; letter <= 'ת'; letter++) {
				if (letter == letters[i]) {
					continue;
				}
				edited[i] = letter;
				if (!candidates.add(new String(edited))) {
					return Optional.empty();
				}
			}
			edited[i] = letters[i];
		}
		char[] inserted = new char[letters.length + 1];
		for (int i = 0; i <= letters.length; i++) {
			System.arraycopy(letters, 0, inserted, 0, i);
			System.arraycopy(letters, i, inserted, i + 1, letters.length - i);
			for (char letter = 'א'; letter <= 'ת'; letter++) {
				if (i > 0 && letter == letters[i - 1]) {
					continue; // inserting on either side of the same letter gives the same word
				}
				inserted[i] = letter;
				if (!candidates.add(new String(inserted))) {
					return Optional.empty();
				}
			}
		}
		char[] deleted = new char[letters.length - 1];
		for (int i = 0; i < letters.length; i++) {
			if (i == 0 || letters[i] != letters[i - 1]) {
				System.arraycopy(letters, 0, deleted, 0, i);
				System.arraycopy(letters, i + 1, deleted, i, letters.length - i - 1);
				if (!candidates.add(new String(deleted))) {
					return Optional.empty();
				}
			}
			if (i + 1 < letters.length && letters[i] != letters[i + 1]) {
				edited[i] = letters[i + 1];
				edited[i + 1] = letters[i];
				if (!candidates.add(new String(edited))) {
					return Optional.empty();
				}
				edited[i] = letters[i];
				edited[i + 1] = letters[i + 1];
			}
		}
		return Optional.ofNullable(candidates.match);
	}

	/** Stop at the second distinct match; no need to allocate the remaining candidates. */
	private static final class HebrewCandidates {
		private final java.util.function.Predicate<String> isWord;
		private String match;

		private HebrewCandidates(java.util.function.Predicate<String> isWord) {
			this.isWord = isWord;
		}

		private boolean add(String candidate) {
			if (!candidate.equals(match) && isWord.test(candidate)) {
				if (match != null) {
					return false;
				}
				match = candidate;
			}
			return true;
		}
	}

	/**
	 * The fix when the typed word differs from exactly one suggestion only by accents or by a
	 * letter variant that is easy to type without: "cafe" → "café", "uber" → "über", "Strasse" →
	 * "Straße", "gron" → "grøn", Arabic "اسرة" → "أسرة" (hamza) or "مدرسه" → "مدرسة" (ta marbuta).
	 * The typed word must be the plain form (no accents or variants): fixes only ever add what was
	 * left out. If two suggestions both fit (e.g. "e" could be "é" or "è"), nothing is done.
	 */
	public static Optional<String> equivalentSpelling(String word, List<String> ranked) {
		if (word.codePointCount(0, word.length()) < 2 || !isPlainWord(word)) {
			return Optional.empty();
		}
		String key = equivalenceKey(word);
		if (!key.equals(word.toLowerCase(Locale.ROOT))) {
			return Optional.empty(); // only add what was left out ("cafe" → "café"), never take accents away
		}
		String found = null;
		for (String s : ranked) {
			if (!s.equals(word) && equivalenceKey(s).equals(key)) {
				if (found != null && !found.equals(s)) {
					return Optional.empty(); // ambiguous
				}
				found = s;
			}
		}
		return Optional.ofNullable(found);
	}

	/** Lower case, without accents and with easy-to-skip letter variants folded together. */
	static String equivalenceKey(String word) {
		String lower = word.toLowerCase(Locale.ROOT)
			.replace("ß", "ss").replace("æ", "ae").replace("œ", "oe").replace('ø', 'o').replace('ł', 'l')
			.replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا').replace('ٱ', 'ا')
			.replace('ة', 'ه').replace('ى', 'ي').replace("ـ", "");
		StringBuilder out = new StringBuilder(lower.length());
		for (char c : java.text.Normalizer.normalize(lower, java.text.Normalizer.Form.NFD).toCharArray()) {
			if (Character.getType(c) != Character.NON_SPACING_MARK) {
				out.append(c);
			}
		}
		return out.toString();
	}

	/** Letters (and apostrophes inside) only. */
	private static boolean isPlainWord(String word) {
		for (int i = 0; i < word.length(); ) {
			int cp = word.codePointAt(i);
			if (!Character.isLetter(cp) && cp != '\'' && cp != '’' && Character.getType(cp) != Character.NON_SPACING_MARK) {
				return false;
			}
			i += Character.charCount(cp);
		}
		return true;
	}
}
