package dev.chatspellcheck.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PopoverLayoutTest {
	private static final int W = 427; // 1280x720 at GUI scale 3
	private static final int H = 240;

	@Test
	void sitsFlushAboveTheWord() {
		Rect word = new Rect(100, H - 13, 20, 11);
		Rect p = PopoverLayout.place(word, 80, 60, W, H);
		assertEquals(word.y(), p.bottom(), "no gap for the pointer to fall through");
		assertEquals(97, p.x());
	}

	@Test
	void staysOnScreenNearTheRightEdge() {
		Rect word = new Rect(W - 10, H - 13, 9, 11);
		Rect p = PopoverLayout.place(word, 90, 60, W, H);
		assertEquals(W - PopoverLayout.MARGIN, p.right());
	}

	@Test
	void staysOnScreenNearTheLeftEdge() {
		Rect word = new Rect(0, H - 13, 9, 11);
		assertEquals(PopoverLayout.MARGIN, PopoverLayout.place(word, 90, 60, W, H).x());
	}

	@Test
	void flipsBelowWhenThereIsNoRoomAbove() {
		Rect word = new Rect(50, 10, 20, 11);
		Rect p = PopoverLayout.place(word, 80, 60, W, H);
		assertEquals(word.bottom(), p.y());
	}

	@Test
	void tinyScreensStillClampInside() {
		Rect word = new Rect(50, 40, 20, 11);
		Rect p = PopoverLayout.place(word, 200, 100, 120, 80);
		assertTrue(p.x() >= PopoverLayout.MARGIN && p.y() >= PopoverLayout.MARGIN);
	}

	@Test
	void unionCoversTheBridge() {
		Rect word = new Rect(100, 200, 20, 11);
		Rect popover = new Rect(97, 140, 80, 60);
		Rect zone = popover.union(word);
		assertTrue(zone.contains(110, 199.5));
		assertTrue(zone.contains(98, 205));
	}

	@Test
	void widerThanTheScreenIsNarrowedToFit() {
		Rect word = new Rect(50, 100, 20, 11);
		Rect p = PopoverLayout.place(word, 900, 60, 320, 240);
		assertEquals(PopoverLayout.MARGIN, p.x());
		assertEquals(320 - PopoverLayout.MARGIN, p.right());
	}

	@Test
	void ellipsizeShortensOnlyWhenNeeded() {
		java.util.function.ToIntFunction<String> width = s -> s.length() * 6; // monospace stand-in
		assertEquals("short", PopoverLayout.ellipsize("short", 60, width));
		String cut = PopoverLayout.ellipsize("antidisestablishmentarianism", 60, width);
		assertEquals("antidisest" .substring(0, 9) + "…", cut);
		assertTrue(width.applyAsInt(cut) <= 60);
		// Never splits a surrogate pair.
		String emoji = PopoverLayout.ellipsize("😀😀😀😀😀😀😀😀", 30, width);
		assertTrue(emoji.endsWith("…") && !Character.isHighSurrogate(emoji.charAt(emoji.length() - 2)));
	}
}
