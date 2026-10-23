package dev.chatspellcheck.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** Stale background results must never mark (or let us replace) the wrong characters. */
class CheckResultTest {
	private static Misspelling at(String text, String word) {
		int i = text.indexOf(word);
		return new Misspelling(i, i + word.length(), word);
	}

	private static CheckResult result(String text, String... words) {
		List<Misspelling> spans = new ArrayList<>();
		for (String w : words) {
			spans.add(at(text, w));
		}
		return new CheckResult(text, 1, spans);
	}

	@Test
	void identicalTextKeepsEverything() {
		CheckResult r = result("teh cat recieve", "teh", "recieve");
		assertEquals(r.misspellings(), r.remapTo("teh cat recieve"));
	}

	@Test
	void editBeforeShiftsLaterSpans() {
		CheckResult r = result("teh cat recieve", "teh", "recieve");
		String now = "teh big cat recieve";
		assertEquals(List.of(at(now, "teh"), at(now, "recieve")), r.remapTo(now));
	}

	@Test
	void editAfterKeepsEarlierSpans() {
		CheckResult r = result("teh cat", "teh");
		assertEquals(List.of(at("teh cat!!", "teh")), r.remapTo("teh cat!!"));
	}

	@Test
	void editInsideOrAtTheEdgeOfAWordDropsIt() {
		CheckResult r = result("so helo world", "helo");
		assertEquals(List.of(), r.remapTo("so hello world")); // inside
		assertEquals(List.of(), r.remapTo("so heloo world")); // grew at the end
		assertEquals(List.of(), r.remapTo("so ahelo world")); // grew at the start
		assertEquals(List.of(), r.remapTo("so heloworld")); // joined with the next word
		assertEquals(List.of(), r.remapTo("")); // cleared
	}

	@Test
	void replacedWholeTextDropsEverything() {
		CheckResult r = result("teh teh", "teh");
		assertEquals(List.of(), r.remapTo("something else"));
	}

	@Test
	void randomEditsNeverProduceAMismatchedSpan() {
		Random random = new Random(42);
		String alphabet = "abcdeh t";
		for (int round = 0; round < 20_000; round++) {
			String old = randomText(random, alphabet, 1 + random.nextInt(20));
			List<Misspelling> spans = new ArrayList<>();
			for (TextScanner.Word w : TextScanner.scan(old, false)) {
				spans.add(new Misspelling(w.start(), w.end(), w.text()));
			}
			CheckResult r = new CheckResult(old, round, spans);
			StringBuilder now = new StringBuilder(old);
			int edits = 1 + random.nextInt(3);
			for (int e = 0; e < edits; e++) {
				int pos = random.nextInt(now.length() + 1);
				if (random.nextBoolean() && pos < now.length()) {
					now.deleteCharAt(pos);
				} else {
					now.insert(pos, alphabet.charAt(random.nextInt(alphabet.length())));
				}
			}
			String current = now.toString();
			List<TextScanner.Word> currentWords = TextScanner.scan(current, false);
			for (Misspelling m : r.remapTo(current)) {
				assertTrue(m.matches(current), () -> old + " -> " + current + " gave " + m);
				// and it is still a whole word, not part of a longer one
				assertTrue(currentWords.stream().anyMatch(w -> w.start() == m.start() && w.end() == m.end()),
					() -> old + " -> " + current + " gave partial " + m);
			}
		}
	}

	private static String randomText(Random random, String alphabet, int length) {
		StringBuilder b = new StringBuilder();
		for (int i = 0; i < length; i++) {
			b.append(alphabet.charAt(random.nextInt(alphabet.length())));
		}
		return b.toString();
	}
}
