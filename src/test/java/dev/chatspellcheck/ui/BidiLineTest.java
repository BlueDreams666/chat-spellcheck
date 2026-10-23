package dev.chatspellcheck.ui;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Random;
import java.util.function.ToIntFunction;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

class BidiLineTest {
	/** Monospace stand-in for the game font: every cluster is 6 pixels wide. */
	private static final ToIntFunction<String> MONO = s -> 6;
	private static final UnaryOperator<String> NO_SHAPING = s -> s;

	private static BidiLine layout(String s) {
		return BidiLine.layout(s, MONO, NO_SHAPING);
	}

	@Test
	void leftToRightTextIsUnchanged() {
		BidiLine line = layout("abc");
		assertFalse(BidiLine.needsReordering("abc"));
		assertEquals("abc", line.visual());
		assertEquals(0, line.caretX(0));
		assertEquals(18, line.caretX(3));
		assertEquals(1, line.hitTest(4));
	}

	@Test
	void hebrewIsShownRightToLeft() {
		String typed = "שלום";
		assertTrue(BidiLine.needsReordering(typed));
		BidiLine line = layout(typed);
		assertEquals("םולש", line.visual());
		// The first typed letter is at the right; the caret before it is the right edge.
		assertEquals(24, line.caretX(0));
		assertEquals(0, line.caretX(4));
		assertEquals(18, line.left(0));
		assertEquals(4, line.hitTest(1), "left edge of a right-to-left word is its end");
		assertEquals(0, line.hitTest(23), "right edge is its start");
	}

	@Test
	void mixedTextKeepsEachRunsOrder() {
		assertEquals("hi םולש ok", layout("hi שלום ok").visual());
		// A Hebrew sentence with an English word: the paragraph reads right to left.
		assertEquals("ok םולש", layout("שלום ok").visual());
		assertEquals("(םולש)", layout("(שלום)").visual(), "brackets are mirrored, not reversed into )(");
	}

	@Test
	void marksStayWithTheirLetter() {
		String typed = "שָׁלוֹם"; // shin+qamats+shin dot, lamed, vav+holam, final mem
		BidiLine line = layout(typed);
		assertEquals("םוֹלשָׁ", line.visual());
		assertEquals(line.left(0), line.left(1), "a letter and its marks share one place");
	}

	@Test
	void underlineRangesFollowTheWord() {
		BidiLine line = layout("hi שלום ok");
		// "שלום" is logical [3, 7): one block on screen at 18..42.
		assertEquals(1, line.ranges(3, 7).size());
		assertArrayEquals(new int[] {18, 42}, line.ranges(3, 7).getFirst());
		// "hi" and "ok" stay where they are.
		assertArrayEquals(new int[] {0, 12}, line.ranges(0, 2).getFirst());
		assertArrayEquals(new int[] {48, 60}, line.ranges(8, 10).getFirst());
	}

	@Test
	void shaperMayChangeGlyphsButNotPositions() {
		BidiLine line = BidiLine.layout("ab", MONO, s -> s.toUpperCase());
		assertEquals("AB", line.visual());
		BidiLine bad = BidiLine.layout("ab", MONO, s -> s + "!");
		assertEquals("ab", bad.visual(), "a shaper that changes the length is ignored");
	}

	@Test
	void caretAndHitTestAgreeEverywhere() {
		Random random = new Random(5);
		String alphabet = "ab שלו(1)";
		for (int round = 0; round < 2_000; round++) {
			StringBuilder b = new StringBuilder();
			for (int i = 0, len = random.nextInt(12); i < len; i++) {
				b.append(alphabet.charAt(random.nextInt(alphabet.length())));
			}
			String s = b.toString();
			BidiLine line = layout(s);
			assertEquals(s.length() * 6, line.width());
			assertEquals(s.length(), line.visual().length());
			// Every character appears exactly once on screen.
			List<int[]> all = line.ranges(0, s.length());
			int covered = all.stream().mapToInt(r -> r[1] - r[0]).sum();
			assertEquals(s.length() * 6, covered, s);
			// Each caret position is inside the line and a hit there maps to a valid position.
			for (int p = 0; p <= s.length(); p++) {
				int x = line.caretX(p);
				assertTrue(x >= 0 && x <= line.width(), s + " caret " + p);
				int hit = line.hitTest(x);
				assertTrue(hit >= 0 && hit <= s.length(), s + " hit " + hit);
			}
		}
	}
}
