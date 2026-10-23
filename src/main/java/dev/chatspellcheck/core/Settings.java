package dev.chatspellcheck.core;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.stream.Collectors;

/** Small properties-file config. Fields are volatile because background threads read them. */
public final class Settings {
	private final Path file;
	public volatile boolean enabled = true;
	/** Active dictionaries in priority order; a word is correct if any of them accepts it. */
	public volatile List<Language> languages = List.of(Language.EN_US);
	/** When true, the message text of /msg, /tell, /w, /me, /say, /r, /teammsg is checked. */
	public volatile boolean checkMessageCommands = false;
	/** Fix a misspelled word as soon as it is finished, when the fix is near-certain. Off by default. */
	public volatile boolean autocorrect = false;

	public Settings(Path file) {
		this.file = file;
	}

	/**
	 * Reads the file, keeping defaults for anything missing or unreadable. A hand-edited file
	 * with bad values, a broken unicode escape or non-UTF-8 bytes never stops the mod from
	 * starting; the file is left as it is until a setting is changed. Creates the file with
	 * defaults if it doesn't exist.
	 *
	 * @throws IOException only if the file exists but cannot be read, or cannot be created
	 */
	public void load() throws IOException {
		if (!Files.exists(file)) {
			save();
			return;
		}
		Properties p = new Properties();
		try {
			p.load(new StringReader(TextFiles.read(file)));
		} catch (IllegalArgumentException e) {
			// Malformed unicode escape (backslash-u): Properties gives up on the whole file.
			throw new IOException("Malformed settings file " + file + ": " + e.getMessage(), e);
		}
		enabled = parseBoolean(p.getProperty("enabled"), true);
		// "variant" is the 1.0.0 single-language key.
		List<Language> parsed = Language.parseList(p.getProperty("languages", p.getProperty("variant")));
		languages = parsed.isEmpty() ? List.of(Language.EN_US) : List.copyOf(parsed);
		checkMessageCommands = parseBoolean(p.getProperty("checkMessageCommands"), false);
		autocorrect = parseBoolean(p.getProperty("autocorrect"), false);
	}

	/** "true/on/yes/1" and "false/off/no/0", any case; anything else keeps the default. */
	static boolean parseBoolean(String raw, boolean fallback) {
		if (raw == null) {
			return fallback;
		}
		return switch (raw.strip().toLowerCase(Locale.ROOT)) {
			case "true", "on", "yes", "1" -> true;
			case "false", "off", "no", "0" -> false;
			default -> fallback;
		};
	}

	/** Writes a snapshot of the current values atomically, so a crash or a concurrent save can't leave half a file. */
	public synchronized void save() throws IOException {
		Properties p = new Properties();
		p.setProperty("enabled", Boolean.toString(enabled));
		p.setProperty("languages", languages.stream().map(Language::code).collect(Collectors.joining(",")));
		p.setProperty("checkMessageCommands", Boolean.toString(checkMessageCommands));
		p.setProperty("autocorrect", Boolean.toString(autocorrect));
		StringWriter w = new StringWriter();
		p.store(w, "Chat Spellcheck. languages: comma-separated, e.g. en_US,es_ES. Available: en_US en_GB en_CA en_AU da_DK nl_NL fr_FR de_DE de_CH it_IT nb_NO es_ES he_IL ar. autocorrect: true/false (off by default).");
		TextFiles.writeAtomically(file, w.toString().getBytes(StandardCharsets.UTF_8));
	}
}
