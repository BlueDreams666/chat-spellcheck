package dev.chatspellcheck.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Words the player added with "Add to dictionary", stored one per line in a UTF-8 text file
 * so it is easy to edit by hand. Lookups are case-insensitive.
 */
public final class UserDictionary {
	private final Path file;
	private final Set<String> words = ConcurrentHashMap.newKeySet();
	/** Set if the file exists but couldn't be read: never overwrite what we couldn't load. */
	private volatile IOException unreadable;

	public UserDictionary(Path file) {
		this.file = file;
	}

	/**
	 * The key a word is stored and matched under: the same form the checker looks words up in
	 * (NFC, no invisible direction marks, Hebrew ״ ׳ as " '), with ’ as ' and in lower case. So a
	 * word added as "צה״ל" also matches "צה\"ל".
	 */
	public static String normalize(String word) {
		return SpellService.lookupForm(word).replace('’', '\'').toLowerCase(Locale.ROOT);
	}

	public void load() throws IOException {
		words.clear();
		unreadable = null;
		if (!Files.exists(file)) {
			return;
		}
		String text;
		try {
			text = TextFiles.read(file);
		} catch (IOException e) {
			unreadable = e;
			throw e;
		}
		for (String line : text.split("\\R")) {
			String w = line.strip();
			if (!w.isEmpty() && !w.startsWith("#")) {
				words.add(normalize(w));
			}
		}
	}

	public boolean contains(String word) {
		return words.contains(normalize(word));
	}

	public int size() {
		return words.size();
	}

	/**
	 * @return true if the word was new; the file is rewritten atomically. If saving fails the
	 *         word is still accepted for this session.
	 */
	public synchronized boolean add(String word) throws IOException {
		boolean added = words.add(normalize(word));
		if (added) {
			save();
		}
		return added;
	}

	public synchronized boolean remove(String word) throws IOException {
		boolean removed = words.remove(normalize(word));
		if (removed) {
			save();
		}
		return removed;
	}

	private void save() throws IOException {
		if (unreadable != null) {
			throw new IOException("Not overwriting " + file + ", which could not be read", unreadable);
		}
		StringBuilder b = new StringBuilder("# Chat Spellcheck custom dictionary: one word per line, case-insensitive.\n");
		for (String w : new TreeSet<>(words)) {
			b.append(w).append('\n');
		}
		TextFiles.writeAtomically(file, b.toString().getBytes(StandardCharsets.UTF_8));
	}
}
