package dev.chatspellcheck.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class WordReplacementTest {
	@Test
	void replacesOnlyTheWord() {
		String text = "I recieve teh diamonds!";
		Misspelling teh = new Misspelling(10, 13, "teh");
		WordReplacement.Edit edit = WordReplacement.apply(text, teh, "the", 256).orElseThrow();
		assertEquals("I recieve the diamonds!", edit.text());
	}

	@Test
	void theCaretStaysWhereThePlayerLeftIt() {
		String text = "😀 alot of ✨";
		Misspelling alot = new Misspelling(3, 7, "alot");
		WordReplacement.Edit edit = WordReplacement.apply(text, alot, "a lot", 256).orElseThrow();
		assertEquals("😀 a lot of ✨", edit.text());
		assertEquals(0, edit.map(0), "before the word: unchanged");
		assertEquals(3, edit.map(3), "at the word's start: unchanged");
		assertEquals(text.length() + 1, edit.map(text.length()), "at the end: still at the end");
		assertEquals(edit.text().indexOf(" of"), edit.map(text.indexOf(" of")), "after the word: same place in the text");
		assertEquals(5, edit.map(5), "inside the word: same offset");
		// A shorter replacement clamps a caret that was deep inside the word.
		WordReplacement.Edit shorter = WordReplacement.apply("so teeeh x", new Misspelling(3, 8, "teeeh"), "the", 256).orElseThrow();
		assertEquals(6, shorter.map(7));
		assertEquals("so the x".indexOf(" x"), shorter.map("so teeeh x".indexOf(" x")));
	}

	@Test
	void refusesWhenTheTextChangedUnderneath() {
		Misspelling teh = new Misspelling(10, 13, "teh");
		assertTrue(WordReplacement.apply("I recieve the diamonds!", teh, "the", 256).isEmpty());
		assertTrue(WordReplacement.apply("short", teh, "the", 256).isEmpty());
	}

	@Test
	void refusesRatherThanTruncatingAtTheChatLimit() {
		String text = "x".repeat(250) + " alot";
		Misspelling alot = new Misspelling(251, 255, "alot");
		assertTrue(WordReplacement.apply(text, alot, "a lot", 255).isEmpty());
		assertTrue(WordReplacement.apply(text, alot, "a lot", 256).isPresent());
	}
}
