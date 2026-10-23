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
	/** Don't flag capitalised words in the middle of a sentence; they are probably names. */
	public volatile boolean skipNames = true;
	public volatile UnderlineStyle underlineStyle = UnderlineStyle.SQUIGGLE;
	public volatile UnderlineColor underlineColor = UnderlineColor.RED;
	/** How many more times the welcome tip shows when chat opens; 0 once it is done. */
	public volatile int welcomeTips = WELCOME_TIPS;
	/** True when there was no settings file yet, i.e. the mod's first launch. Not saved. */
	public volatile boolean firstRun;

	public static final int WELCOME_TIPS = 3;

	public enum UnderlineStyle { SQUIGGLE, STRAIGHT, DOTTED }

	/** Underline colours; blue stays visible with red-green colour blindness. */
	public enum UnderlineColor {
		RED(0xFFFF4A4A), PINK(0xFFFF6FB5), ORANGE(0xFFFFA43A), BLUE(0xFF4AA8FF);

		public final int argb;

		UnderlineColor(int argb) {
			this.argb = argb;
		}
	}

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
			firstRun = true;
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
		skipNames = parseBoolean(p.getProperty("skipNames"), true);
		underlineStyle = parseEnum(p.getProperty("underlineStyle"), UnderlineStyle.SQUIGGLE);
		underlineColor = parseEnum(p.getProperty("underlineColor"), UnderlineColor.RED);
		try {
			welcomeTips = Math.clamp(Integer.parseInt(p.getProperty("welcomeTips", "").strip()), 0, WELCOME_TIPS);
		} catch (NumberFormatException e) {
			welcomeTips = WELCOME_TIPS;
		}
	}

	/** The constant named {@code raw} in any case; anything else keeps the default. */
	static <E extends Enum<E>> E parseEnum(String raw, E fallback) {
		if (raw != null) {
			for (E e : fallback.getDeclaringClass().getEnumConstants()) {
				if (e.name().equalsIgnoreCase(raw.strip())) {
					return e;
				}
			}
		}
		return fallback;
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
		p.setProperty("skipNames", Boolean.toString(skipNames));
		p.setProperty("underlineStyle", underlineStyle.name().toLowerCase(Locale.ROOT));
		p.setProperty("underlineColor", underlineColor.name().toLowerCase(Locale.ROOT));
		p.setProperty("welcomeTips", Integer.toString(welcomeTips));
		StringWriter w = new StringWriter();
		p.store(w, "Chat Spellcheck. languages: comma-separated, e.g. en_US,es_ES. Available: en_US en_GB en_CA en_AU da_DK nl_NL fr_FR de_DE de_CH it_IT nb_NO es_ES he_IL ar. autocorrect: true/false (off by default). underlineStyle: squiggle/straight/dotted. underlineColor: red/pink/orange/blue.");
		TextFiles.writeAtomically(file, w.toString().getBytes(StandardCharsets.UTF_8));
	}
}
