package dev.chatspellcheck.core;

import java.util.ArrayList;
import java.util.List;

/**
 * The outcome of checking one exact snapshot of the chat text. Results are produced on a
 * background thread, so by the time they arrive the player may have kept typing.
 * {@link #remapTo(String)} is the only way the UI obtains spans for the <em>current</em> text.
 */
public record CheckResult(String text, long generation, List<Misspelling> misspellings) {
	public CheckResult {
		misspellings = List.copyOf(misspellings);
	}

	public static CheckResult empty() {
		return new CheckResult("", -1, List.of());
	}

	/**
	 * Translates spans from {@link #text()} to {@code current}. Text before the first changed
	 * character keeps its offsets, text after the last changed character shifts by the length
	 * difference, and any span touching the edited region (including its boundary characters,
	 * since a word may have grown) is dropped. Every returned span is verified against
	 * {@code current}, so a stale result can never mark or replace the wrong characters.
	 */
	public List<Misspelling> remapTo(String current) {
		if (current.equals(text)) {
			return misspellings;
		}
		int oldLen = text.length();
		int newLen = current.length();
		int prefix = 0;
		int maxPrefix = Math.min(oldLen, newLen);
		while (prefix < maxPrefix && text.charAt(prefix) == current.charAt(prefix)) {
			prefix++;
		}
		int suffix = 0;
		int maxSuffix = Math.min(oldLen, newLen) - prefix;
		while (suffix < maxSuffix && text.charAt(oldLen - 1 - suffix) == current.charAt(newLen - 1 - suffix)) {
			suffix++;
		}
		int oldChangeEnd = oldLen - suffix; // first index of the unchanged suffix in the old text
		int delta = newLen - oldLen;

		List<Misspelling> out = new ArrayList<>(misspellings.size());
		for (Misspelling m : misspellings) {
			Misspelling mapped = null;
			if (m.end() < prefix) {
				mapped = m; // the character right after the word is unchanged too
			} else if (m.start() > oldChangeEnd) {
				mapped = m.shift(delta); // the character right before the word is unchanged too
			}
			if (mapped != null && mapped.matches(current)) {
				out.add(mapped);
			}
		}
		return out;
	}
}
