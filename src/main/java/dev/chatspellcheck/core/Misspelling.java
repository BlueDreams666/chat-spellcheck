package dev.chatspellcheck.core;

/** A misspelled word at UTF-16 range {@code [start, end)} of the text it was found in. */
public record Misspelling(int start, int end, String word) {
	public Misspelling {
		if (start < 0 || end <= start || word.length() != end - start) {
			throw new IllegalArgumentException("Bad span " + start + ".." + end + " for '" + word + "'");
		}
	}

	public boolean contains(int index) {
		return index >= start && index <= end;
	}

	/** True if this exact word is still present at the same range of {@code text}. */
	public boolean matches(String text) {
		return end <= text.length() && text.regionMatches(start, word, 0, word.length());
	}

	Misspelling shift(int delta) {
		return new Misspelling(start + delta, end + delta, word);
	}
}
