package dev.chatspellcheck.core;

import java.lang.Character.UnicodeScript;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Bundled Hunspell dictionaries. See THIRD_PARTY_NOTICES.md for each one's source and licence. */
public enum Language {
	EN_US("en_US", "English (US)", "English (US)", "SCOWL 2026.02.25 · permissive", UnicodeScript.LATIN),
	EN_GB("en_GB", "English (UK)", "English (UK)", "SCOWL 2026.02.25 · permissive", UnicodeScript.LATIN),
	EN_CA("en_CA", "English (Canada)", "English (Canada)", "SCOWL 2026.02.25 · permissive", UnicodeScript.LATIN),
	EN_AU("en_AU", "English (Australia)", "English (Australia)", "SCOWL 2026.02.25 · permissive", UnicodeScript.LATIN),
	DA_DK("da_DK", "Dansk", "Danish", "Stavekontrolden · GPL-2.0 / LGPL-2.1 / MPL-1.1", UnicodeScript.LATIN),
	NL_NL("nl_NL", "Nederlands", "Dutch", "OpenTaal · BSD-3-Clause / CC BY 3.0", UnicodeScript.LATIN),
	FR_FR("fr_FR", "Français", "French", "Dicollecte · MPL-2.0", UnicodeScript.LATIN),
	DE_DE("de_DE", "Deutsch", "German", "igerman98 · GPL-2.0 / GPL-3.0", UnicodeScript.LATIN),
	DE_CH("de_CH", "Deutsch (Schweiz)", "Swiss German", "igerman98 · GPL-2.0 / GPL-3.0", UnicodeScript.LATIN),
	IT_IT("it_IT", "Italiano", "Italian", "LibreItalia · GPL-3.0", UnicodeScript.LATIN),
	NB_NO("nb_NO", "Norsk bokmål", "Norwegian (Bokmål)", "Norsk Ordbank · CC BY 4.0", UnicodeScript.LATIN),
	ES_ES("es_ES", "Español", "Spanish", "RLA-ES · GPL-3.0+ / LGPL-3.0+ / MPL-1.1+", UnicodeScript.LATIN),
	HE_IL("he_IL", "עברית", "Hebrew", "Hspell · AGPL-3.0", UnicodeScript.HEBREW),
	AR("ar", "العربية", "Arabic", "Ayaspell · GPL-2.0+ / LGPL-2.1+ / MPL-1.1+", UnicodeScript.ARABIC);

	private final String code;
	private final String displayName;
	private final String englishName;
	private final String dictionary;
	private final UnicodeScript script;

	Language(String code, String displayName, String englishName, String dictionary, UnicodeScript script) {
		this.code = code;
		this.displayName = displayName;
		this.englishName = englishName;
		this.dictionary = dictionary;
		this.script = script;
	}

	/** Dictionary file base name and config value, e.g. "de_CH". */
	public String code() {
		return code;
	}

	/** The language's own name for itself. */
	public String displayName() {
		return displayName;
	}

	/** The language's name in English, e.g. "Swiss German". */
	public String englishName() {
		return englishName;
	}

	/** Where the bundled dictionary comes from and its licence(s); see THIRD_PARTY_NOTICES.md. */
	public String dictionary() {
		return dictionary;
	}

	public UnicodeScript script() {
		return script;
	}

	/** English dictionaries get frequency-based ranking and contraction suggestions. */
	public boolean isEnglish() {
		return code.startsWith("en_");
	}

	/**
	 * The bundled word-frequency list used to rank this language's suggestions
	 * ({@code frequency/<name>_word_frequency.txt}), or null if there isn't one. Swiss German
	 * shares the German list.
	 */
	public String frequencyList() {
		return switch (this) {
			case EN_US, EN_GB, EN_CA, EN_AU -> "en";
			case DE_DE, DE_CH -> "de";
			case ES_ES -> "es";
			case FR_FR -> "fr";
			case IT_IT -> "it";
			case HE_IL -> "he";
			default -> null;
		};
	}

	/**
	 * Accepts "de_CH", "de-ch", "DE_CH"; a bare language ("de" → de_DE, "en" → en_US); or a
	 * region that is unambiguous among the bundled dictionaries ("gb", "ch", "no").
	 */
	public static Optional<Language> parse(String raw) {
		if (raw == null) {
			return Optional.empty();
		}
		String key = raw.trim().toLowerCase(Locale.ROOT).replace('-', '_');
		for (Language l : values()) {
			if (l.code.toLowerCase(Locale.ROOT).equals(key)) {
				return Optional.of(l);
			}
		}
		for (Language l : values()) {
			if (l.code.toLowerCase(Locale.ROOT).startsWith(key + "_")) {
				return Optional.of(l);
			}
		}
		Language byRegion = null;
		for (Language l : values()) {
			int u = l.code.indexOf('_');
			if (u > 0 && l.code.substring(u + 1).toLowerCase(Locale.ROOT).equals(key)) {
				if (byRegion != null) {
					return Optional.empty();
				}
				byRegion = l;
			}
		}
		return Optional.ofNullable(byRegion);
	}

	/**
	 * The dictionary for a Minecraft language code such as "es_mx" or "en_gb", or empty if
	 * none is bundled. Regional variants map to the closest dictionary.
	 */
	public static Optional<Language> forGameLanguage(String gameCode) {
		if (gameCode == null) {
			return Optional.empty();
		}
		String code = gameCode.strip().toLowerCase(Locale.ROOT).replace('-', '_');
		return Optional.ofNullable(switch (code) {
			case "en_us" -> EN_US;
			case "en_gb", "en_nz", "en_ie", "en_ud", "enws", "en_pt" -> EN_GB;
			case "en_ca" -> EN_CA;
			case "en_au" -> EN_AU;
			case "de_ch" -> DE_CH;
			case "nb_no", "no_no", "nn_no" -> NB_NO;
			case "ar_sa" -> AR;
			default -> {
				String lang = code.contains("_") ? code.substring(0, code.indexOf('_')) : code;
				yield switch (lang) {
					case "en" -> EN_US;
					case "da" -> DA_DK;
					case "nl" -> NL_NL;
					case "fr" -> FR_FR;
					case "de", "nds" -> DE_DE;
					case "it" -> IT_IT;
					case "es" -> ES_ES;
					case "he" -> HE_IL;
					case "ar" -> AR;
					default -> null;
				};
			}
		});
	}

	/**
	 * The languages to start with for a Minecraft language code: its dictionary, plus English
	 * (US) for other languages since so much server chat is in English; English (US) alone if
	 * none is bundled.
	 */
	public static List<Language> defaultsForGameLanguage(String gameCode) {
		Language l = forGameLanguage(gameCode).orElse(EN_US);
		return l.isEnglish() ? List.of(l) : List.of(l, EN_US);
	}

	/** Parses a comma-separated list, skipping unknown entries and duplicates. */
	public static List<Language> parseList(String raw) {
		List<Language> out = new ArrayList<>();
		if (raw != null) {
			for (String part : raw.split(",")) {
				parse(part).filter(l -> !out.contains(l)).ifPresent(out::add);
			}
		}
		return out;
	}
}
