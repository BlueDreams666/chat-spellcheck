package dev.chatspellcheck.core;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;

/**
 * Corpus frequency scores (10 × log10 of a Google Books Ngram count) used only to rank
 * suggestions that Hunspell has already produced. Higher is more common: "the" ≈ 104,
 * the rarest listed words ≈ 36.
 *
 * <p>Stored compactly (1.6 MB of arrays instead of 7.7 MB for a HashMap of 80k boxed entries): all
 * words as Latin-1 bytes in one array, a score byte per word, and an open-addressing table of
 * word indices.
 */
public final class WordFrequency {
	/** Score assumed for words missing from the list (rarer than anything listed). */
	public static final int UNKNOWN = 30;

	private final byte[] chars;
	/** Word {@code i} is {@code chars[offsets[i] .. offsets[i + 1])}. */
	private final int[] offsets;
	private final byte[] scores;
	/** Word index + 1, or 0 for an empty slot; length is a power of two. */
	private final int[] table;

	private WordFrequency(byte[] chars, int[] offsets, byte[] scores, int[] table) {
		this.chars = chars;
		this.offsets = offsets;
		this.scores = scores;
		this.table = table;
	}

	public static WordFrequency empty() {
		return new WordFrequency(new byte[0], new int[] {0}, new byte[0], new int[1]);
	}

	public static WordFrequency load(InputStream in) {
		Builder b = new Builder();
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
			String line;
			while ((line = reader.readLine()) != null) {
				if (line.isEmpty() || line.charAt(0) == '#') {
					continue;
				}
				int space = line.indexOf(' ');
				if (space > 0) {
					b.put(line.substring(0, space), Integer.parseInt(line, space + 1, line.length(), 10));
				}
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		return b.build();
	}

	/** Score for a single word or, for a multi-word suggestion like "a lot", its rarest part. */
	public int score(String phrase) {
		String lower = phrase.toLowerCase(Locale.ROOT).replace('’', '\'');
		if (lower.indexOf(' ') < 0 && lower.indexOf('-') < 0) {
			return lookup(lower);
		}
		int min = Integer.MAX_VALUE;
		for (String part : lower.split("[ -]")) {
			if (!part.isEmpty()) {
				min = Math.min(min, lookup(part));
			}
		}
		return min == Integer.MAX_VALUE ? UNKNOWN : min;
	}

	private int lookup(String word) {
		int index = find(word);
		return index < 0 ? UNKNOWN : scores[index];
	}

	private int find(String word) {
		int h = hash(word);
		if (h == -1) {
			return -1; // contains a character outside Latin-1: not in the list
		}
		int mask = table.length - 1;
		for (int slot = h & mask; ; slot = (slot + 1) & mask) {
			int entry = table[slot];
			if (entry == 0) {
				return -1;
			}
			if (equalsWord(entry - 1, word)) {
				return entry - 1;
			}
		}
	}

	private boolean equalsWord(int index, String word) {
		int start = offsets[index];
		if (offsets[index + 1] - start != word.length()) {
			return false;
		}
		for (int i = 0; i < word.length(); i++) {
			if ((chars[start + i] & 0xFF) != word.charAt(i)) {
				return false;
			}
		}
		return true;
	}

	/** Non-negative hash of a Latin-1 string, or -1 if it has other characters. */
	private static int hash(CharSequence s) {
		int h = 0;
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (c > 0xFF) {
				return -1;
			}
			h = 31 * h + c;
		}
		h ^= h >>> 16;
		return (h * 0x9E3779B9) & 0x7FFFFFFF;
	}

	private static final class Builder {
		private byte[] chars = new byte[1 << 20];
		private int[] offsets = new int[1 << 17];
		private byte[] scores = new byte[1 << 17];
		private int count;
		private int length;

		void put(String word, int score) {
			for (int i = 0; i < word.length(); i++) {
				if (word.charAt(i) > 0xFF) {
					return; // can never be looked up
				}
			}
			if (count + 2 > offsets.length) {
				offsets = Arrays.copyOf(offsets, offsets.length * 2);
				scores = Arrays.copyOf(scores, scores.length * 2);
			}
			if (length + word.length() > chars.length) {
				chars = Arrays.copyOf(chars, Math.max(chars.length * 2, length + word.length()));
			}
			offsets[count] = length;
			for (int i = 0; i < word.length(); i++) {
				chars[length++] = (byte) word.charAt(i);
			}
			scores[count] = (byte) Math.clamp(score, 0, 127);
			count++;
			offsets[count] = length;
		}

		WordFrequency build() {
			// Smallest power of two keeping the table at most 70% full (80k words: 131,072 slots).
			int size = Integer.highestOneBit(Math.max(2, (int) Math.ceil(count / 0.7) - 1)) << 1;
			WordFrequency f = new WordFrequency(Arrays.copyOf(chars, length), Arrays.copyOf(offsets, count + 1),
				Arrays.copyOf(scores, count), new int[size]);
			int mask = size - 1;
			for (int i = 0; i < count; i++) {
				String word = new String(f.chars, f.offsets[i], f.offsets[i + 1] - f.offsets[i], StandardCharsets.ISO_8859_1);
				int slot = hash(word) & mask;
				while (true) {
					int entry = f.table[slot];
					if (entry == 0) {
						f.table[slot] = i + 1;
						break;
					}
					if (f.equalsWord(entry - 1, word)) {
						f.table[slot] = i + 1; // duplicate: the later line wins, as with Map.put
						break;
					}
					slot = (slot + 1) & mask;
				}
			}
			return f;
		}
	}
}
