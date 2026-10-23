package dev.chatspellcheck.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Finds the words in a chat line that are worth spell-checking. Everything here is purely
 * syntactic; dictionary and allow-list lookups happen later in {@link SpellService}.
 *
 * <p>Skipped: slash commands (by default), URLs, domains, file names, e-mail addresses,
 * @mentions, #tags, :emoji: codes, colour codes, tokens containing digits (coordinates,
 * "1st", "x64"), single letters, ALL-CAPS acronyms, camelCase/brand-style words, and words
 * in a script none of the active dictionaries covers.
 */
public final class TextScanner {
	/** Commands whose trailing argument is free text, mapped to how many leading tokens to skip. */
	private static final java.util.Map<String, Integer> MESSAGE_COMMANDS = java.util.Map.of(
		"msg", 2, "tell", 2, "w", 2, "me", 1, "say", 1, "teammsg", 1, "tm", 1, "r", 1, "reply", 1
	);

	private static final Set<String> TLDS = Set.of(
		"com", "net", "org", "io", "gg", "co", "uk", "me", "tv", "dev", "xyz", "us", "de", "fr", "ru",
		"info", "edu", "gov", "app", "ly", "be", "ca", "au", "nz", "jp", "cn", "eu", "nl", "pl", "br",
		"fm", "to", "sh", "ai", "club", "online", "site", "store", "png", "jpg", "jpeg", "gif", "txt",
		"json", "jar", "zip", "exe", "mcfunction", "mcmeta", "nbt", "toml", "yml", "yaml", "log", "html"
	);
	private static final Pattern DOMAIN_LIKE = Pattern.compile("^[\\p{L}\\p{N}_-]+(\\.[\\p{L}\\p{N}_-]+)+(:\\d+)?(/\\S*)?$");
	private static final Pattern EMOJI_CODE = Pattern.compile(":[\\w+-]+:");
	private static final Pattern COLOUR_CODE = Pattern.compile("[&§][0-9a-fk-orA-FK-OR#]");
	private static final String TRAILING_PUNCTUATION = ".,!?;:)]}\"'’»…";

	private TextScanner() {
	}

	/** A candidate word: {@code [start, end)} are UTF-16 indices into the scanned text. */
	public record Word(int start, int end, String text) {
	}

	/** Latin-script scan; convenience for English-only callers and tests. */
	public static List<Word> scan(String text, boolean checkMessageCommands) {
		return scan(text, checkMessageCommands, Set.of(Character.UnicodeScript.LATIN));
	}

	/** @param scripts writing systems of the active dictionaries; words in other scripts are skipped */
	public static List<Word> scan(String text, boolean checkMessageCommands, Set<Character.UnicodeScript> scripts) {
		List<Word> out = new ArrayList<>();
		int from = 0;
		String trimmed = text.stripLeading();
		if (trimmed.startsWith("/")) {
			if (!checkMessageCommands) {
				return out;
			}
			from = messageStart(text);
			if (from < 0) {
				return out;
			}
		}

		int i = from;
		int n = text.length();
		while (i < n) {
			int cp = text.codePointAt(i);
			if (Character.isWhitespace(cp)) {
				i += Character.charCount(cp);
				continue;
			}
			int chunkStart = i;
			while (i < n) {
				int c = text.codePointAt(i);
				if (Character.isWhitespace(c)) {
					break;
				}
				i += Character.charCount(c);
			}
			scanChunk(text, chunkStart, i, scripts, out);
		}
		return out;
	}

	/** Index where the free-text part of a messaging command begins, or -1 if not one. */
	private static int messageStart(String text) {
		int i = text.indexOf('/') + 1;
		int tokens = 0;
		Integer skip = null;
		while (i < text.length()) {
			while (i < text.length() && Character.isWhitespace(text.charAt(i))) {
				i++;
			}
			if (skip != null && tokens == skip) {
				return i;
			}
			int start = i;
			while (i < text.length() && !Character.isWhitespace(text.charAt(i))) {
				i++;
			}
			if (tokens == 0) {
				skip = MESSAGE_COMMANDS.get(text.substring(start, i).toLowerCase(Locale.ROOT));
				if (skip == null) {
					return -1;
				}
			}
			tokens++;
		}
		return -1;
	}

	private static void scanChunk(String text, int start, int end, Set<Character.UnicodeScript> scripts, List<Word> out) {
		String chunk = text.substring(start, end);
		if (isNonProse(chunk)) {
			return;
		}
		// Within a chunk, split on anything that is not a letter, digit or apostrophe; hyphenated
		// words are therefore checked part by part ("well-known" → "well", "known").
		int i = start;
		while (i < end) {
			int cp = text.codePointAt(i);
			if (!isWordChar(cp)) {
				i += Character.charCount(cp);
				continue;
			}
			int tokenStart = i;
			while (i < end) {
				int c = text.codePointAt(i);
				if (!isWordChar(c) && !joinsWord(text, i, start, end)) {
					break;
				}
				i += Character.charCount(c);
			}
			addToken(text, tokenStart, i, scripts, out);
		}
	}

	/**
	 * Characters that belong to a word only <em>between</em> two letters: an ASCII double quote
	 * between Hebrew letters is the gershayim of an acronym (צה"ל), and invisible direction marks
	 * or joiners that some keyboards insert inside right-to-left words.
	 */
	private static boolean joinsWord(String text, int i, int start, int end) {
		char c = text.charAt(i);
		if (i == start || i + 1 >= end) {
			return false;
		}
		if (c == '"') {
			// ...unless a closing quote follows: ב"שלום" is a prefix plus a quotation, not an acronym.
			int close = text.indexOf('"', i + 1);
			return isHebrewLetter(text.codePointBefore(i)) && isHebrewLetter(text.codePointAt(i + 1))
				&& (close < 0 || close >= end);
		}
		return isInvisibleMark(c) && Character.isLetter(text.codePointBefore(i)) && isWordChar(text.codePointAt(i + 1));
	}

	private static boolean isHebrewLetter(int cp) {
		return Character.isLetter(cp) && Character.UnicodeScript.of(cp) == Character.UnicodeScript.HEBREW;
	}

	/** Zero-width joiners and left-to-right/right-to-left marks; ignored when looking words up. */
	static boolean isInvisibleMark(char c) {
		return (c >= '‌' && c <= '‏') || (c >= '‪' && c <= '‮') || (c >= '⁦' && c <= '⁩');
	}

	private static boolean isNonProse(String chunk) {
		if (EMOJI_CODE.matcher(chunk).find()) {
			return true;
		}
		String bare = stripTrailingPunctuation(chunk);
		if (bare.isEmpty()) {
			return true;
		}
		char first = bare.charAt(0);
		if (first == '@' || first == '#' || first == '/' || first == '\\' || first == '$' || first == '~' || first == '^') {
			return true;
		}
		if (bare.contains("://") || bare.regionMatches(true, 0, "www.", 0, 4) || bare.indexOf('@') > 0) {
			return true;
		}
		if (bare.indexOf('_') >= 0 || bare.indexOf('=') >= 0 || bare.indexOf('<') >= 0 || bare.indexOf('{') >= 0) {
			return true; // identifiers, key=value, markup, NBT/JSON
		}
		if (COLOUR_CODE.matcher(bare).find()) {
			return true;
		}
		if (DOMAIN_LIKE.matcher(bare).matches()) {
			int slash = bare.indexOf('/');
			String host = slash >= 0 ? bare.substring(0, slash) : bare;
			int colon = host.indexOf(':');
			host = colon >= 0 ? host.substring(0, colon) : host;
			String tld = host.substring(host.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
			return slash >= 0 || TLDS.contains(tld);
		}
		return false;
	}

	private static String stripTrailingPunctuation(String s) {
		int end = s.length();
		while (end > 0 && TRAILING_PUNCTUATION.indexOf(s.charAt(end - 1)) >= 0) {
			end--;
		}
		return s.substring(0, end);
	}

	private static void addToken(String text, int start, int end, Set<Character.UnicodeScript> scripts, List<Word> out) {
		// Trim leading/trailing apostrophes: 'quoted', players', 'tis.
		while (start < end && isApostrophe(text.charAt(start))) {
			start++;
		}
		while (end > start && isApostrophe(text.charAt(end - 1))) {
			end--;
		}
		if (start >= end) {
			return;
		}
		String token = text.substring(start, end);
		if (token.codePointCount(0, token.length()) < 2) {
			return;
		}
		int upper = 0;
		int letters = 0;
		boolean innerUpper = false;
		Character.UnicodeScript wordScript = null;
		for (int i = 0; i < token.length(); ) {
			int cp = token.codePointAt(i);
			if (Character.isDigit(cp)) {
				return; // coordinates, ordinals, versions, "x64"
			}
			if (Character.isLetter(cp)) {
				Character.UnicodeScript script = Character.UnicodeScript.of(cp);
				// COMMON/INHERITED letters (e.g. Arabic tatweel) belong to no single script.
				if (script != Character.UnicodeScript.COMMON && script != Character.UnicodeScript.INHERITED) {
					if (!scripts.contains(script)) {
						return; // no active dictionary for this writing system
					}
					if (wordScript != null && wordScript != script) {
						return; // mixed scripts: not a word in any one dictionary
					}
					wordScript = script;
				}
				letters++;
				if (Character.isUpperCase(cp)) {
					upper++;
					if (i > 0) {
						innerUpper = true;
					}
				}
			}
			i += Character.charCount(cp);
		}
		if (letters >= 2 && upper == letters) {
			return; // acronyms and shouting: TNT, AFK, OMG
		}
		if (innerUpper) {
			return; // camelCase, brand names, player names: iPhone, McQueen, xXSteveXx
		}
		out.add(new Word(start, end, token));
	}

	private static boolean isWordChar(int cp) {
		return Character.isLetterOrDigit(cp) || cp == '\'' || cp == '’'
			|| cp == '\u05F3' || cp == '\u05F4' // Hebrew geresh and gershayim (abbreviations)
			|| Character.getType(cp) == Character.NON_SPACING_MARK; // niqqud, harakat, combining accents
	}

	private static boolean isApostrophe(char c) {
		return c == '\'' || c == '’';
	}
}
