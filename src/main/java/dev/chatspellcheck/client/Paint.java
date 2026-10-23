package dev.chatspellcheck.client;

import dev.chatspellcheck.ui.Rect;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/** Drawing helpers shared by the popover, notices and the settings screen. */
final class Paint {
	/** Shared accent for panels and selection highlights. */
	static final int ACCENT = 0xFFFF2E4D;

	private Paint() {
	}

	/** {@code argb} with its alpha multiplied by {@code factor} (0..1). */
	static int fade(int argb, float factor) {
		int a = Math.round((argb >>> 24) * Math.clamp(factor, 0f, 1f));
		return (a << 24) | (argb & 0x00FFFFFF);
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
}
