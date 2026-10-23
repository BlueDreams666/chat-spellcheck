package dev.chatspellcheck.core;

import java.util.Optional;

/** Pure text edit for applying a suggestion; the UI applies the result to the edit box. */
public final class WordReplacement {
	private WordReplacement() {
	}

	/** The new text, and the replaced word's range so other positions can be carried across. */
	public record Edit(String text, Misspelling target, String replacement) {
		/**
		 * Where a position in the old text ends up in the new one, so the player's caret,
		 * selection and scroll position stay put: before the word it is unchanged, after the word
		 * it moves with the text, and inside the word it keeps its offset (clamped to the new word).
		 */
		public int map(int position) {
			if (position <= target.start()) {
				return position;
			}
			if (position >= target.end()) {
				return position + replacement.length() - target.word().length();
			}
			return target.start() + Math.min(position - target.start(), replacement.length());
		}
	}

	/**
	 * Replaces exactly {@code target}'s range with {@code replacement}. Returns empty if the text
	 * no longer contains that word at that range (stale) or the result would exceed
	 * {@code maxLength} — we never truncate the player's message.
	 */
	public static Optional<Edit> apply(String text, Misspelling target, String replacement, int maxLength) {
		if (!target.matches(text)) {
			return Optional.empty();
		}
		String result = text.substring(0, target.start()) + replacement + text.substring(target.end());
		if (result.length() > maxLength) {
			return Optional.empty();
		}
		return Optional.of(new Edit(result, target, replacement));
	}
}
