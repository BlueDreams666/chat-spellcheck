package dev.chatspellcheck.client;

import dev.chatspellcheck.core.Settings;
import dev.chatspellcheck.ui.Rect;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/** Drawing helpers shared by the popover, notices and the settings screen. */
final class Paint {
	/** Shared accent for panels and selection highlights. */
	static final int ACCENT = 0xFFFF2E4D;
	/** The icon's pinks, for the welcome tip and the settings screen. */
	static final int PINK = 0xFFFF6FB5;
	static final int PINK_LIGHT = 0xFFFFC2E2;
	static final int PINK_DEEP = 0xFFE95B8A;
	/** The star on the icon. */
	static final int GOLD = 0xFFFFC94A;

	private Paint() {
	}

	/** {@code argb} with its alpha multiplied by {@code factor} (0..1). */
	static int fade(int argb, float factor) {
		int a = Math.round((argb >>> 24) * Math.clamp(factor, 0f, 1f));
		return (a << 24) | (argb & 0x00FFFFFF);
	}

	/** Mixes two colours, {@code t} = 0 gives {@code from}, 1 gives {@code to}. */
	static int mix(int from, int to, float t) {
		t = Math.clamp(t, 0f, 1f);
		int out = 0;
		for (int shift = 0; shift < 32; shift += 8) {
			int a = (from >>> shift) & 0xFF;
			int b = (to >>> shift) & 0xFF;
			out |= Math.round(a + (b - a) * t) << shift;
		}
		return out;
	}

	/**
	 * A floating panel: hard 2-pixel black shadow down and right, near-black body, 1-pixel
	 * frame, and a 2-pixel accent bar on the left.
	 */
	static void panel(GuiGraphicsExtractor g, Rect r, int background, int frame, int accent, float alpha) {
		int x0 = r.x(), y0 = r.y(), x1 = r.right(), y1 = r.bottom();
		g.fill(x0 + 2, y1, x1 + 2, y1 + 2, fade(0xD0000000, alpha));
		g.fill(x1, y0 + 2, x1 + 2, y1, fade(0xD0000000, alpha));
		g.fill(x0, y0, x1, y1, fade(background, alpha));
		g.outline(x0, y0, x1 - x0, y1 - y0, fade(frame, alpha));
		g.fill(x0, y0, x0 + 2, y1, fade(accent, alpha));
	}

	/** A rectangle with its corner pixels cut off, which reads as rounded at GUI scale. */
	static void rounded(GuiGraphicsExtractor g, int x, int y, int w, int h, int color) {
		g.fill(x + 1, y, x + w - 1, y + h, color);
		g.fill(x, y + 1, x + 1, y + h - 1, color);
		g.fill(x + w - 1, y + 1, x + w, y + h - 1, color);
	}

	/** A 1-pixel frame with cut corners, matching {@link #rounded}. */
	static void roundedOutline(GuiGraphicsExtractor g, int x, int y, int w, int h, int color) {
		g.fill(x + 1, y, x + w - 1, y + 1, color);
		g.fill(x + 1, y + h - 1, x + w - 1, y + h, color);
		g.fill(x, y + 1, x + 1, y + h - 1, color);
		g.fill(x + w - 1, y + 1, x + w, y + h - 1, color);
	}

	/** A rounded card: soft body, frame, and a 1-pixel highlight along the top edge. */
	static void card(GuiGraphicsExtractor g, int x, int y, int w, int h, int body, int frame) {
		rounded(g, x, y, w, h, body);
		roundedOutline(g, x, y, w, h, frame);
		g.fill(x + 2, y + 1, x + w - 2, y + 2, 0x18FFFFFF);
	}

	/**
	 * The underline under a misspelled word, in GUI pixels so it scales with the GUI. Only the
	 * first {@code progress} (0..1) of it is drawn, so it can grow in from the left.
	 *
	 * @param y the bottom row of the word's box
	 */
	static void underline(GuiGraphicsExtractor g, int x0, int x1, int y, Settings.UnderlineStyle style, int color, float progress) {
		int end = progress >= 1f ? x1 : x0 + Math.round((x1 - x0) * Math.max(0f, progress));
		switch (style) {
			case SQUIGGLE -> {
				// A 2-pixel zig-zag under the glyphs.
				for (int x = x0; x < end; x += 2) {
					int dy = ((x - x0) / 2) % 2 == 0 ? 0 : -1;
					g.fill(x, y + dy, Math.min(x + 2, end), y + dy + 1, color);
				}
			}
			case STRAIGHT -> g.fill(x0, y, end, y + 1, color);
			case DOTTED -> {
				for (int x = x0; x < end; x += 2) {
					g.fill(x, y, x + 1, y + 1, color);
				}
			}
		}
	}
}
