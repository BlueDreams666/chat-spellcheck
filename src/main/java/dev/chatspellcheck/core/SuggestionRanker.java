package dev.chatspellcheck.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Re-ranks Hunspell's candidates. Hunspell finds good candidates but orders them without any
 * notion of how common a word is ("wich" → rich, wick, which…). We combine three signals:
 * a typo-aware edit distance, corpus frequency, and Hunspell's own order as a tie-breaker.
 *
 * <p>This is still dictionary-based, word-at-a-time correction: it cannot use sentence context
 * to choose between e.g. "their"/"there", and it never flags real-word errors.
 */
public final class SuggestionRanker {
	public static final int MAX_SUGGESTIONS = 5;

	private static final double DISTANCE_WEIGHT = 2.0;
	private static final double FREQUENCY_WEIGHT = 0.06; // per frequency-score point (= 0.6 per decade)
	private static final double ORDER_WEIGHT = 0.1;
	/** Bonus for Minecraft vocabulary: "crafing" should prefer "crafting" over "craving". */
	private static final double DOMAIN_BONUS = 1.0;
	/** Multi-word splits ("occur ed", "any ting") are only kept if every part is this common. */
	private static final int MIN_SPLIT_PART_FREQUENCY = 60;
	/** Contractions are under-counted by the Ngram tokenizer, so give them a typical-word score. */
	private static final int CONTRACTION_FREQUENCY = 75;
	/** Parts of a split suggestion this short must be real short words ("a lot", not "occur ed"). */
	private static final Set<String> SHORT_WORDS = Set.of(
		"a", "i", "an", "as", "at", "be", "by", "do", "go", "he", "if", "in", "is", "it", "me", "my", "no",
		"of", "on", "or", "so", "to", "up", "us", "we"
	);

	/** Apostrophe-less spellings that Hunspell rejects but rarely suggests well (e.g. "im"). */
	private static final Map<String, String> CONTRACTIONS = Map.ofEntries(
		Map.entry("im", "I'm"), Map.entry("ive", "I've"), Map.entry("youre", "you're"),
		Map.entry("theyre", "they're"), Map.entry("thats", "that's"), Map.entry("whats", "what's"),
		Map.entry("wheres", "where's"), Map.entry("theres", "there's"), Map.entry("heres", "here's"),
		Map.entry("whos", "who's"), Map.entry("hows", "how's"), Map.entry("isnt", "isn't"),
		Map.entry("arent", "aren't"), Map.entry("wasnt", "wasn't"), Map.entry("werent", "weren't"),
		Map.entry("dont", "don't"), Map.entry("doesnt", "doesn't"), Map.entry("didnt", "didn't"),
		Map.entry("cant", "can't"), Map.entry("couldnt", "couldn't"), Map.entry("wouldnt", "wouldn't"),
		Map.entry("shouldnt", "shouldn't"), Map.entry("havent", "haven't"), Map.entry("hasnt", "hasn't"),
		Map.entry("hadnt", "hadn't"), Map.entry("aint", "ain't"), Map.entry("youve", "you've"),
		Map.entry("weve", "we've"), Map.entry("theyve", "they've"), Map.entry("youll", "you'll"),
		Map.entry("theyll", "they'll"), Map.entry("itll", "it'll"), Map.entry("shes", "she's"),
		Map.entry("youd", "you'd"), Map.entry("theyd", "they'd"), Map.entry("wouldve", "would've"),
		Map.entry("couldve", "could've"), Map.entry("shouldve", "should've"), Map.entry("mustnt", "mustn't"),
		Map.entry("neednt", "needn't")
	);

	private final WordFrequency frequency;
	private final Predicate<String> domainWord;
	private final boolean english;

	public SuggestionRanker(WordFrequency frequency) {
		this(frequency, w -> false);
	}

	/** English ranker. @param domainWord tests lower-case words that are common in Minecraft chat */
	public SuggestionRanker(WordFrequency frequency, Predicate<String> domainWord) {
		this(frequency, domainWord, true);
	}

	/**
	 * @param english enables English-only contraction suggestions ("dont" → "don't"). Every
	 *                language with a frequency list is ranked by it; the others rank by typo
	 *                distance and Hunspell's order.
	 */
	public SuggestionRanker(WordFrequency frequency, Predicate<String> domainWord, boolean english) {
		this.frequency = frequency;
		this.domainWord = domainWord;
		this.english = english;
	}

	/**
	 * @param word      the misspelled word exactly as typed
	 * @param hunspell  Hunspell's suggestions for {@code word} (or for its lower-case form)
	 * @return at most {@link #MAX_SUGGESTIONS} suggestions, best first, in the word's letter case
	 */
	public List<String> rank(String word, List<String> hunspell) {
		String lowerWord = word.toLowerCase(Locale.ROOT).replace('’', '\'');
		Casing casing = Casing.of(word);

		Set<String> candidates = new LinkedHashSet<>();
		String contraction = english ? CONTRACTIONS.get(lowerWord) : null;
		if (contraction != null) {
			candidates.add(contraction);
		}
		candidates.addAll(hunspell);

		List<Scored> scored = new ArrayList<>();
		int order = 0;
		for (String candidate : candidates) {
			int index = order++;
			String lowerCandidate = candidate.toLowerCase(Locale.ROOT);
			if (lowerCandidate.equals(lowerWord) || !keepSplit(candidate)) {
				continue;
			}
			int freq = candidate.equals(contraction)
				? Math.max(CONTRACTION_FREQUENCY, frequency.score(candidate))
				: frequency.score(candidate);
			double distance = TypoDistance.between(lowerWord, lowerCandidate);
			// Languages without a frequency list rank by typo distance and Hunspell's own order.
			double score = -DISTANCE_WEIGHT * distance - ORDER_WEIGHT * index
				+ (frequency.isEmpty() ? 0 : FREQUENCY_WEIGHT * freq);
			if (!isSplit(candidate) && domainWord.test(lowerCandidate)) {
				score += DOMAIN_BONUS;
			}
			scored.add(new Scored(candidate, score));
		}
		scored.sort(Comparator.comparingDouble(Scored::score).reversed());

		Set<String> out = new LinkedHashSet<>();
		for (Scored s : scored) {
			out.add(casing.apply(s.text()));
			if (out.size() == MAX_SUGGESTIONS) {
				break;
			}
		}
		return List.copyOf(out);
	}

	private static boolean isSplit(String candidate) {
		return candidate.indexOf(' ') >= 0 || candidate.indexOf('-') >= 0;
	}

	private boolean keepSplit(String candidate) {
		if (!isSplit(candidate)) {
			return true;
		}
		for (String part : candidate.split("[ -]")) {
			if (part.length() <= 2 ? !SHORT_WORDS.contains(part.toLowerCase(Locale.ROOT)) : frequency.score(part) < MIN_SPLIT_PART_FREQUENCY) {
				return false;
			}
		}
		return true;
	}

	private record Scored(String text, double score) {
	}

	/** Letter case of the typed word, re-applied to suggestions ("Teh" → "The"). */
	enum Casing {
		LOWER, CAPITALIZED, OTHER;

		static Casing of(String word) {
			if (word.isEmpty()) {
				return OTHER;
			}
			int first = word.codePointAt(0);
			String rest = word.substring(Character.charCount(first));
			boolean restLower = rest.equals(rest.toLowerCase(Locale.ROOT));
			if (Character.isLowerCase(first) && restLower) {
				return LOWER;
			}
			if (Character.isUpperCase(first) && restLower) {
				return CAPITALIZED;
			}
			return OTHER;
		}

		String apply(String suggestion) {
			if (this == CAPITALIZED && !suggestion.isEmpty()) {
				int first = suggestion.codePointAt(0);
				return new StringBuilder().appendCodePoint(Character.toUpperCase(first))
					.append(suggestion, Character.charCount(first), suggestion.length()).toString();
			}
			return suggestion;
		}
	}
}
