package dev.chatspellcheck.ui;

import java.util.function.ToIntFunction;

/** Places the suggestion popover next to a word without leaving the screen. */
public final class PopoverLayout {
	static final int MARGIN = 2;
	static final String ELLIPSIS = "…";

	private PopoverLayout() {
	}

	/** The widest popover that fits on screen. */
	public static int maxWidth(int screenWidth) {
		return Math.max(1, screenWidth - 2 * MARGIN);
	}

	/**
	 * Prefers directly above the word (chat input sits at the bottom of the screen), flush with
	 * it so the pointer can travel from word to popover without crossing a gap; falls back to
	 * below, then clamps horizontally and vertically to the screen. A popover wider than the
	 * screen is narrowed to fit (its rows must be shortened with {@link #ellipsize}).
	 */
	public static Rect place(Rect anchor, int width, int height, int screenWidth, int screenHeight) {
		width = Math.min(width, maxWidth(screenWidth));
		int x = clamp(anchor.x() - 3, MARGIN, screenWidth - width - MARGIN);
		int y = anchor.y() - height;
		if (y < MARGIN) {
			int below = anchor.bottom();
			y = below + height <= screenHeight - MARGIN ? below : MARGIN;
		}
		y = clamp(y, MARGIN, screenHeight - height - MARGIN);
		return new Rect(x, y, width, height);
	}

	/** {@code text} cut to at most {@code maxWidth} (by {@code width}), ending in "…" if shortened. */
	public static String ellipsize(String text, int maxWidth, ToIntFunction<String> width) {
		if (width.applyAsInt(text) <= maxWidth) {
			return text;
		}
		int budget = maxWidth - width.applyAsInt(ELLIPSIS);
		int end = text.length();
		while (end > 0 && width.applyAsInt(text.substring(0, end)) > budget) {
			end = text.offsetByCodePoints(end, -1);
		}
		return text.substring(0, end) + ELLIPSIS;
	}

	private static int clamp(int v, int min, int max) {
		return max < min ? min : Math.max(min, Math.min(max, v));
	}
}
