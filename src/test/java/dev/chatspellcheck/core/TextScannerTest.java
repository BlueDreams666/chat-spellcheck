package dev.chatspellcheck.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.Character.UnicodeScript;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TextScannerTest {
	private static List<String> words(String text) {
		return words(text, false);
	}

	private static List<String> words(String text, boolean commands) {
		return TextScanner.scan(text, commands).stream().map(TextScanner.Word::text).toList();
	}

	@Test
	void plainSentence() {
		assertEquals(List.of("hello", "there", "how", "are", "you"), words("hello there, how are you?"));
	}

	@Test
	void offsetsPointAtTheWord() {
		String text = "😀 teh  end";
		List<TextScanner.Word> found = TextScanner.scan(text, false);
		assertEquals("teh", text.substring(found.get(0).start(), found.get(0).end()));
		assertEquals("end", text.substring(found.get(1).start(), found.get(1).end()));
	}

	@Test
	void skipsUrlsDomainsEmailsAndFiles() {
		assertEquals(List.of("see"), words("see https://exmaple.com/pathh"));
		assertEquals(List.of("join"), words("join play.hypixxel.net"));
		assertEquals(List.of("join"), words("join mc.servr.gg:25565"));
		assertEquals(List.of("mail"), words("mail me@exmple.org"));
		assertEquals(List.of("open"), words("open www.goggle"));
		assertEquals(List.of("edit"), words("edit options.txt"));
		// A missing space after a full stop is still prose.
		assertEquals(List.of("end", "Then"), words("end.Then"));
	}

	@Test
	void skipsCoordinatesNumbersAndOrdinals() {
		assertEquals(List.of("at"), words("at 120 64 -300"));
		assertEquals(List.of("at"), words("at x120 y64"));
		assertEquals(List.of("came"), words("came 1st"));
		assertEquals(List.of(), words("~ ~1 ~-2"));
	}

	@Test
	void slashCommandsAreLeftAloneByDefault() {
		assertEquals(List.of(), words("/gamemode creatve"));
		assertEquals(List.of(), words("  /msg Steve helo there"));
		assertEquals(List.of(), words("/msg Steve helo there", false));
	}

	@Test
	void messageCommandsCanBeCheckedWhenEnabled() {
		assertEquals(List.of("helo", "there"), words("/msg Steve helo there", true));
		assertEquals(List.of("waves"), words("/me waves", true));
		assertEquals(List.of(), words("/gamemode creatve", true));
		assertEquals(List.of(), words("/msg Steve", true));
	}

	@Test
	void skipsMentionsTagsEmojiAndColourCodes() {
		assertEquals(List.of("hi"), words("hi @Steeve #tagg :smiel:"));
		assertEquals(List.of("hi"), words("hi &aGren §bblu"));
		assertEquals(List.of("set"), words("set key=valu"));
		assertEquals(List.of("give"), words("give diamond_swrod"));
	}

	@Test
	void skipsAcronymsCamelCaseAndSingleLetters() {
		assertEquals(List.of("is"), words("TNT is OP"));
		assertEquals(List.of("ask"), words("ask xXSteveXx"));
		assertEquals(List.of("my"), words("my iPhone"));
		assertEquals(List.of("and"), words("a and b"));
	}

	@Test
	void handlesApostrophesAndHyphens() {
		assertEquals(List.of("don't", "players", "tis"), words("don't 'players' 'tis"));
		assertEquals(List.of("don’t"), words("don’t"));
		assertEquals(List.of("well", "known"), words("well-known"));
	}

	@Test
	void leavesNonLatinScriptsAlone() {
		assertEquals(List.of("hello"), words("hello привет こんにちは"));
		assertEquals(List.of("café", "naïve"), words("café naïve"));
	}

	@Test
	void hebrewAndArabicWhenTheirDictionariesAreActive() {
		Set<UnicodeScript> scripts = Set.of(UnicodeScript.LATIN, UnicodeScript.HEBREW, UnicodeScript.ARABIC);
		List<String> found = TextScanner.scan("hi שָׁלוֹם צה״ל مَرْحَبًا привет", false, scripts).stream().map(TextScanner.Word::text).toList();
		assertEquals(List.of("hi", "שָׁלוֹם", "צה״ל", "مَرْحَبًا"), found);
	}

	@Test
	void mixedScriptTokensAreSkipped() {
		Set<UnicodeScript> scripts = Set.of(UnicodeScript.LATIN, UnicodeScript.HEBREW);
		assertEquals(List.of(), TextScanner.scan("abcשלום", false, scripts));
	}

	@Test
	void hebrewAcronymsKeepTheirQuote() {
		Set<UnicodeScript> hebrew = Set.of(UnicodeScript.LATIN, UnicodeScript.HEBREW);
		List<String> found = TextScanner.scan("ראיתי את צה\"ל ואת צה״ל ב\"שלום\" ok\"go", false, hebrew)
			.stream().map(TextScanner.Word::text).toList();
		// The quote joins Hebrew letters only; around a word it is punctuation, and Latin "ok"go" splits.
		assertEquals(List.of("ראיתי", "את", "צה\"ל", "ואת", "צה״ל", "שלום", "ok", "go"), found);
	}

	@Test
	void invisibleDirectionMarksInsideAWordDontSplitIt() {
		Set<UnicodeScript> scripts = Set.of(UnicodeScript.LATIN, UnicodeScript.HEBREW, UnicodeScript.ARABIC);
		String text = "\u200Fשל\u200Fום مر\u200Cحبا";
		List<TextScanner.Word> found = TextScanner.scan(text, false, scripts);
		assertEquals(List.of("של\u200Fום", "مر\u200Cحبا"), found.stream().map(TextScanner.Word::text).toList());
		assertEquals("של\u200Fום", text.substring(found.get(0).start(), found.get(0).end()));
		assertEquals("צה\"ל", SpellService.lookupForm("צה״ל"));
		assertEquals("שלום", SpellService.lookupForm("של\u200Fום"));
	}
}
