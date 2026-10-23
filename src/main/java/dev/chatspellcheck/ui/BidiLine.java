package dev.chatspellcheck.ui;

import java.text.Bidi;
import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;
import java.util.function.UnaryOperator;

/**
 * Display layout for one line of text that may contain right-to-left words (Hebrew, Arabic).
 * The text stays in typing ("logical") order; this computes the order it should be <em>shown</em>
 * in (Unicode bidi algorithm, via java.text.Bidi) and where each character lands on screen, so
 * the cursor, clicks, selection and underlines can be mapped between the two.
 *
 * <p>Characters are laid out in clusters (a letter plus its combining marks, or a surrogate
 * pair), which are never split or reversed internally.
 */
public final class BidiLine {
	private static final String MIRRORED = "()[]{}<>«»";

	private final String visual;
	private final int length;
	/** Per logical char: x of its cluster's left edge, the cluster's width, and whether it runs right-to-left. */
	private final int[] left;
	private final int[] width;
	private final boolean[] rtl;
	/** Clusters in visual order, as logical [start, end). */
	private final int[][] visualClusters;
	private final int totalWidth;

	private BidiLine(String visual, int length, int[] left, int[] width, boolean[] rtl, int[][] visualClusters, int totalWidth) {
		this.visual = visual;
		this.length = length;
		this.left = left;
		this.width = width;
		this.rtl = rtl;
		this.visualClusters = visualClusters;
		this.totalWidth = totalWidth;
	}

	/** True if {@code text} contains right-to-left characters and so needs reordering to display. */
	public static boolean needsReordering(String text) {
		char[] chars = text.toCharArray();
		return Bidi.requiresBidi(chars, 0, chars.length);
	}

	/**
	 * @param logical the text in typing order
	 * @param width   width of a short string (one cluster) on screen
	 * @param shaper  turns text into its display glyphs without changing its length (Arabic letter
	 *                joining); identity if not needed
	 */
	public static BidiLine layout(String logical, ToIntFunction<String> width, UnaryOperator<String> shaper) {
		int n = logical.length();
		String shaped = shaper.apply(logical);
		if (shaped == null || shaped.length() != n) {
			shaped = logical; // a shaper must never change positions; fall back to plain letters
		}
		// Paragraph direction from the first strong character: a Hebrew sentence reads right to left.
		Bidi bidi = new Bidi(logical, Bidi.DIRECTION_DEFAULT_LEFT_TO_RIGHT);

		List<int[]> clusters = new ArrayList<>();
		for (int i = 0; i < n; ) {
			int start = i;
			i += Character.charCount(logical.codePointAt(i));
			while (i < n && isMark(logical.codePointAt(i))) {
				i += Character.charCount(logical.codePointAt(i));
			}
			clusters.add(new int[] {start, i});
		}
		int count = clusters.size();
		byte[] levels = new byte[count];
		int[][] order = clusters.toArray(new int[0][]);
		for (int c = 0; c < count; c++) {
			levels[c] = (byte) bidi.getLevelAt(order[c][0]);
		}
		if (count > 0) {
			Bidi.reorderVisually(levels, 0, order, 0, count);
		}

		int[] left = new int[n];
		int[] widths = new int[n];
		boolean[] rtl = new boolean[n];
		StringBuilder visual = new StringBuilder(n);
		int x = 0;
		for (int[] cluster : order) {
			boolean odd = (bidi.getLevelAt(cluster[0]) & 1) == 1;
			String glyphs = shaped.substring(cluster[0], cluster[1]);
			if (odd && glyphs.length() == 1) {
				glyphs = String.valueOf(mirror(glyphs.charAt(0)));
			}
			int w = width.applyAsInt(glyphs);
			for (int i = cluster[0]; i < cluster[1]; i++) {
				left[i] = x;
				widths[i] = w;
				rtl[i] = odd;
			}
			visual.append(glyphs);
			x += w;
		}
		return new BidiLine(visual.toString(), n, left, widths, rtl, order, x);
	}

	private static boolean isMark(int cp) {
		int type = Character.getType(cp);
		return type == Character.NON_SPACING_MARK || type == Character.ENCLOSING_MARK || type == Character.COMBINING_SPACING_MARK;
	}

	private static char mirror(char c) {
		int i = MIRRORED.indexOf(c);
		return i < 0 ? c : MIRRORED.charAt(i ^ 1);
	}

	/** The text in display order (and shaped), ready to draw left to right. */
	public String visual() {
		return visual;
	}

	public int width() {
		return totalWidth;
	}

	/** x of the caret placed before logical index {@code pos} (0..length). */
	public int caretX(int pos) {
		if (length == 0) {
			return 0;
		}
		if (pos >= length) {
			int last = length - 1;
			return rtl[last] ? left[last] : left[last] + width[last]; // after the last character
		}
		pos = Math.max(0, pos);
		return rtl[pos] ? left[pos] + width[pos] : left[pos]; // before this character
	}

	/** The logical caret position closest to screen offset {@code x}. */
	public int hitTest(int x) {
		if (visualClusters.length == 0) {
			return 0;
		}
		if (x < 0) {
			int[] c = visualClusters[0];
			return rtl[c[0]] ? c[1] : c[0];
		}
		for (int[] c : visualClusters) {
			int l = left[c[0]];
			int w = width[c[0]];
			if (x < l + w) {
				boolean leftHalf = x < l + w / 2.0;
				return leftHalf == rtl[c[0]] ? c[1] : c[0];
			}
		}
		int[] c = visualClusters[visualClusters.length - 1];
		return rtl[c[0]] ? c[0] : c[1];
	}

	/** Screen x of the left edge of the character at logical index {@code i}. */
	public int left(int i) {
		return left[i];
	}

	/** Width of the cluster containing logical index {@code i}. */
	public int charWidth(int i) {
		return width[i];
	}

	/**
	 * The screen intervals {@code [x0, x1)} covered by logical range {@code [from, to)}, merged
	 * where they touch. A range crossing a direction change can map to several pieces.
	 */
	public List<int[]> ranges(int from, int to) {
		List<int[]> out = new ArrayList<>();
		for (int[] c : visualClusters) {
			if (c[0] >= from && c[0] < to) {
				int x0 = left[c[0]];
				int x1 = x0 + width[c[0]];
				if (!out.isEmpty() && out.getLast()[1] == x0) {
					out.getLast()[1] = x1;
				} else {
					out.add(new int[] {x0, x1});
				}
			}
		}
		return out;
	}
}
