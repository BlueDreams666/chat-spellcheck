package dev.chatspellcheck.core;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.function.BiFunction;
import org.junit.jupiter.api.Test;

/**
 * Autocorrect accuracy for the non-English languages, on typos generated from common words: a
 * missing accent (or letter variant), a neighbouring key, swapped, missing and doubled letters.
 * The word lists and the generator were written before measuring. Which rule each language uses
 * in the game ({@link Autocorrect#policy}) must stay at 95%+ here.
 */
class MultilingualAutocorrectTest {
	static final Map<Language, String[]> WORDS = new LinkedHashMap<>();

	static {
		WORDS.put(Language.ES_ES, ("también está qué cómo después mañana años niño corazón canción además jugar casa mundo gracias amigo "
			+ "tiempo siempre nunca ahora bueno grande pequeño noche ciudad comer hablar trabajo dinero verdad mucho porque entonces "
			+ "todavía difícil fácil rápido último música número").split(" "));
		WORDS.put(Language.FR_FR, ("très été déjà où ça être français école fenêtre château garçon bientôt maison monde bonjour merci "
			+ "toujours jamais maintenant beaucoup petit grand nuit ville manger parler travail argent vérité alors encore difficile "
			+ "facile rapide dernier musique nombre après première problème").split(" "));
		WORDS.put(Language.DE_DE, ("über schön für grün müde hören können möchte natürlich später Straße groß heißen Fußball Mädchen "
			+ "Bäume Häuser Tür Schlüssel Welt Haus Freund heute morgen immer jetzt viel gut klein Nacht Stadt essen sprechen Arbeit "
			+ "Geld Wahrheit weil dann schwierig einfach schnell letzte Musik").split(" "));
		WORDS.put(Language.DE_CH, ("über schön für grün müde hören können möchte natürlich später Strasse gross heissen Fussball "
			+ "Mädchen Bäume Häuser Tür Schlüssel Welt Haus Freund heute morgen immer jetzt viel gut klein Nacht Stadt essen sprechen "
			+ "Arbeit Geld Wahrheit weil dann schwierig einfach schnell").split(" "));
		WORDS.put(Language.IT_IT, ("perché città più già così però università caffè venerdì casa mondo grazie amico tempo sempre adesso "
			+ "buono grande piccolo notte mangiare parlare lavoro soldi verità molto allora ancora difficile facile veloce ultimo musica "
			+ "numero giocare domani oggi ragazzo scuola").split(" "));
		WORDS.put(Language.NL_NL, ("huis wereld vriend tijd altijd nooit goed groot klein nacht stad eten praten werk geld waarheid veel "
			+ "omdat moeilijk makkelijk snel laatste muziek nummer spelen morgen vandaag jongen school café één ideeën zeggen vragen "
			+ "kijken denken vrienden").split(" "));
		WORDS.put(Language.DA_DK, ("hus verden tak tid altid aldrig god stor lille nat spise tale arbejde penge sandhed meget fordi svær "
			+ "nem hurtig sidste musik nummer spille morgen dreng skole være også høre kører grøn blå både år søster bøger").split(" "));
		WORDS.put(Language.NB_NO, ("hus verden takk venn tid alltid aldri god stor liten natt spise snakke arbeid penger sannhet mye "
			+ "fordi vanskelig lett rask siste musikk nummer spille morgen gutt skole være også høre kjøre grønn blå både søster bøker").split(" "));
		WORDS.put(Language.AR, ("مرحبا شكرا صديق بيت عالم كتاب مدرسة سيارة مدينة وقت دائما الآن جميل كبير صغير ليلة طعام عمل "
			+ "مال حقيقة كثير لأن صعب سهل سريع أخير موسيقى رقم لعبة غدا اليوم أنا أنت نحن إلى على أين متى كيف لماذا أريد أعرف أحب "
			+ "أسرة إسلام").split(" "));
	}

	private static final String[] QWERTY = {"qwertyuiop", "asdfghjkl", "zxcvbnm"};
	private static final String ARABIC_LETTERS = "ابتثجحخدذرزسشصضطظعغفقكلمنهوي";

	/** Typos of one word, in a fixed order with a fixed seed: {kind, typo}. */
	static List<String[]> typos(String word, Random random) {
		List<String[]> out = new ArrayList<>();
		boolean arabic = Character.UnicodeScript.of(word.charAt(0)) == Character.UnicodeScript.ARABIC;
		String plain = arabic ? arabicVariant(word) : stripAccents(word);
		if (!plain.equals(word)) {
			out.add(new String[] {arabic ? "letter variant" : "missing accent", plain});
		}
		int n = word.length();
		if (n >= 3) {
			int i = 1 + random.nextInt(n - 2);
			char c = Character.toLowerCase(word.charAt(i));
			char sub = arabic ? ARABIC_LETTERS.charAt(random.nextInt(ARABIC_LETTERS.length())) : neighbour(c, random);
			if (sub != 0 && sub != c) {
				out.add(new String[] {arabic ? "wrong letter" : "neighbouring key", word.substring(0, i) + sub + word.substring(i + 1)});
			}
			int j = random.nextInt(n - 1);
			if (word.charAt(j) != word.charAt(j + 1)) {
				out.add(new String[] {"swapped", word.substring(0, j) + word.charAt(j + 1) + word.charAt(j) + word.substring(j + 2)});
			}
			int k = 1 + random.nextInt(n - 2);
			out.add(new String[] {"missing letter", word.substring(0, k) + word.substring(k + 1)});
			int d = random.nextInt(n);
			out.add(new String[] {"doubled letter", word.substring(0, d + 1) + word.charAt(d) + word.substring(d + 1)});
		}
		return out;
	}

	static String stripAccents(String s) {
		StringBuilder b = new StringBuilder();
		for (char c : java.text.Normalizer.normalize(s.replace("ß", "ss").replace("ø", "o").replace("Ø", "O").replace("æ", "ae"),
			java.text.Normalizer.Form.NFD).toCharArray()) {
			if (Character.getType(c) != Character.NON_SPACING_MARK) {
				b.append(c);
			}
		}
		return b.toString();
	}

	/** The common Arabic shortcut: plain alef for hamza forms, ه for ة, ي for ى. */
	static String arabicVariant(String s) {
		return s.replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا').replace('ة', 'ه').replace('ى', 'ي');
	}

	private static char neighbour(char c, Random random) {
		for (int row = 0; row < QWERTY.length; row++) {
			int col = QWERTY[row].indexOf(c);
			if (col >= 0) {
				List<Character> near = new ArrayList<>();
				if (col > 0) near.add(QWERTY[row].charAt(col - 1));
				if (col + 1 < QWERTY[row].length()) near.add(QWERTY[row].charAt(col + 1));
				return near.get(random.nextInt(near.size()));
			}
		}
		return 0;
	}

	private static final SuggestionRanker RANKER = new SuggestionRanker(WordFrequency.empty(), w -> false, false);

	/** Ranked suggestions as the game computes them for one language. */
	static List<String> ranked(HunspellChecker checker, String word) {
		String query = SpellService.lookupForm(word);
		if (SuggestionRanker.Casing.of(query) == SuggestionRanker.Casing.CAPITALIZED) {
			query = query.toLowerCase(Locale.ROOT);
		}
		return RANKER.rank(word, checker.suggest(query));
	}

	/** @return {applied, right, total} */
	static int[] measure(Language language, String ruleName, BiFunction<String, List<String>, Optional<String>> rule) {
		HunspellChecker checker = TestResources.checker(language);
		Random random = new Random(language.ordinal() * 31L + 7);
		int applied = 0, right = 0, total = 0;
		List<String> wrong = new ArrayList<>();
		for (String word : WORDS.get(language)) {
			for (String[] t : typos(word, random)) {
				if (checker.isCorrect(t[1]) || TestResources.checker(language).isCorrect(t[1].toLowerCase(Locale.ROOT))) {
					continue; // the "typo" is itself a word
				}
				total++;
				Optional<String> fix = rule.apply(t[1], ranked(checker, t[1]));
				if (fix.isPresent()) {
					applied++;
					if (fix.get().equals(word)) {
						right++;
					} else if (wrong.size() < 6) {
						wrong.add(t[1] + "→" + fix.get() + " (" + word + ", " + t[0] + ")");
					}
				}
			}
		}
		System.out.printf("AUTOCORRECT %-6s %-12s applied %3d/%3d, right %3d (precision %3.0f%%) e.g. wrong: %s%n",
			language.code(), ruleName, applied, total, right, applied == 0 ? 100.0 : 100.0 * right / applied, wrong);
		return new int[] {applied, right, total};
	}

	/** The rule each language uses in the game must stay at 95%+ on these typos. */
	@Test
	void eachLanguagesRuleIsReliable() {
		for (Language language : WORDS.keySet()) {
			int[] r = switch (Autocorrect.policy(language)) {
				case ACCENTS -> measure(language, "accents", Autocorrect::equivalentSpelling);
				case GENERAL -> measure(language, "general", (w, ranked) -> Autocorrect.choose(w, ranked, null));
				default -> null;
			};
			if (r != null) {
				assertTrue(r[1] >= r[0] * 0.95, language + " precision " + r[1] + "/" + r[0]);
			}
		}
	}

	/** For the record: why the general rule is English-only, and why some languages are off. */
	@Test
	void measureEveryLanguage() {
		for (Language language : WORDS.keySet()) {
			measure(language, "general", (w, r) -> Autocorrect.choose(w, r, null));
			measure(language, "accents", Autocorrect::equivalentSpelling);
		}
	}
}
