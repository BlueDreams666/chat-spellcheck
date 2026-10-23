package dev.chatspellcheck.client;

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import dev.chatspellcheck.ui.PopoverLayout;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.Util;

/**
 * The settings screen's pink buttons. They are ordinary {@link Button}s (keyboard focus,
 * narration and click sounds work as usual) that draw themselves as rounded cards, and can live
 * in a scrolling area: outside its {@link Viewport} they are neither drawn nor clickable.
 */
final class CuteButtons {
	static final int BODY = 0xF0231820;
	static final int BODY_ON = 0xF04A2338;
	static final int FRAME = 0xFF4A3A45;
	static final int TEXT = 0xFFFFFFFF;
	static final int TEXT_OFF = 0xFFB9AEB5;
	static final int TEXT_DISABLED = 0xFF6E6269;

	private CuteButtons() {
	}

	/** The vertical band a scrolled button can be seen in; unbounded for fixed buttons. */
	static final class Viewport {
		static final Viewport ALL = new Viewport();

		int top = Integer.MIN_VALUE;
		int bottom = Integer.MAX_VALUE;

		boolean contains(double y) {
			return y >= top && y < bottom;
		}
	}

	abstract static class Cute extends Button {
		final Viewport viewport;

		Cute(int width, int height, Component message, OnPress onPress, Viewport viewport) {
			super(0, 0, width, height, message, onPress, DEFAULT_NARRATION);
			this.viewport = viewport;
		}

		@Override
		public boolean isMouseOver(double x, double y) {
			return super.isMouseOver(x, y) && viewport.contains(y);
		}

		@Override
		protected final void extractContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
			boolean clip = viewport != Viewport.ALL;
			if (clip) {
				g.enableScissor(getX() - 1, viewport.top, getRight() + 1, viewport.bottom);
			}
			boolean hot = active && (isHovered() && viewport.contains(mouseY) || isFocused());
			draw(g, Minecraft.getInstance().font, hot);
			if (clip) {
				g.disableScissor();
			}
		}

		abstract void draw(GuiGraphicsExtractor g, Font font, boolean hot);

		/** The card behind the button; pink frame while hovered or focused. */
		void body(GuiGraphicsExtractor g, int body, int frame, boolean hot) {
			Paint.card(g, getX(), getY(), getWidth(), getHeight(), body, hot ? Paint.PINK : frame);
		}

		int textY() {
			return getY() + (getHeight() - 8) / 2;
		}
	}

	/** A dictionary: checkbox, name, loading dot, and a gold heart for the game's language. */
	static final class LanguageChip extends Cute {
		private final BooleanSupplier selected;
		private final Supplier<Integer> dot;
		private final boolean gameLanguage;

		/** @param dot the status dot colour, or 0 for none */
		LanguageChip(int width, int height, Component name, BooleanSupplier selected, Supplier<Integer> dot, boolean gameLanguage,
			OnPress onPress, Viewport viewport) {
			super(width, height, name, onPress, viewport);
			this.selected = selected;
			this.dot = dot;
			this.gameLanguage = gameLanguage;
		}

		@Override
		void draw(GuiGraphicsExtractor g, Font font, boolean hot) {
			boolean on = selected.getAsBoolean();
			body(g, on ? BODY_ON : BODY, on ? Paint.PINK_DEEP : FRAME, hot);
			int bx = getX() + 5;
			int by = getY() + (getHeight() - 9) / 2;
			if (on) {
				Paint.rounded(g, bx, by, 9, 9, Paint.PINK);
				g.text(font, "✔", bx + 1, by + 1, TEXT, false);
			} else {
				Paint.rounded(g, bx, by, 9, 9, 0xFF2A2128);
				Paint.roundedOutline(g, bx, by, 9, 9, 0xFF7A6A75);
			}
			int right = getRight() - 9;
			int color = dot.get();
			if (color != 0) {
				g.fill(right + 2, getY() + getHeight() / 2 - 2, right + 6, getY() + getHeight() / 2 + 2, color);
			}
			if (gameLanguage) {
				right -= 9;
				g.text(font, "♥", right + 1, textY(), Paint.GOLD, false);
			}
			int textX = bx + 13;
			String name = PopoverLayout.ellipsize(getMessage().getString(), right - textX - 1, font::width);
			g.text(font, name, textX, textY(), on ? TEXT : TEXT_OFF, false);
		}
	}

	/** An on/off switch with its label on the left and a sliding pill on the right. */
	static final class Toggle extends Cute {
		private static final long SLIDE_MS = 120;
		private final BooleanSupplier on;
		private boolean shownOn;
		private long changedAt; // 0: long ago, so the knob starts in place

		Toggle(int width, int height, Component label, BooleanSupplier on, OnPress onPress, Viewport viewport) {
			super(width, height, label, onPress, viewport);
			this.on = on;
			this.shownOn = on.getAsBoolean();
		}

		@Override
		protected MutableComponent createNarrationMessage() {
			return CommonComponents.optionNameValue(getMessage(), on.getAsBoolean() ? CommonComponents.OPTION_ON : CommonComponents.OPTION_OFF).copy();
		}

		@Override
		void draw(GuiGraphicsExtractor g, Font font, boolean hot) {
			boolean now = on.getAsBoolean();
			long time = Util.getMillis();
			if (now != shownOn) {
				shownOn = now;
				changedAt = time;
			}
			float t = Math.min(1f, (time - changedAt) / (float) SLIDE_MS);
			float knob = now ? t : 1 - t; // 0 = left (off), 1 = right (on)
			body(g, BODY, FRAME, hot);
			int pillW = 20;
			int px = getRight() - pillW - 5;
			int py = getY() + (getHeight() - 10) / 2;
			Paint.rounded(g, px, py, pillW, 10, Paint.mix(0xFF4A3A45, Paint.PINK, knob));
			int kx = px + 1 + Math.round(knob * (pillW - 10));
			Paint.rounded(g, kx, py + 1, 8, 8, TEXT);
			String label = PopoverLayout.ellipsize(getMessage().getString(), px - getX() - 10, font::width);
			g.text(font, label, getX() + 6, textY(), active ? TEXT : TEXT_DISABLED, false);
		}
	}

	/** One of a group of choices; the chosen one is pink. Optionally shows a colour swatch. */
	static final class Choice extends Cute {
		private final BooleanSupplier chosen;
		private final int swatch;

		/** @param swatch a colour shown before the label, or 0 for none */
		Choice(int width, int height, Component label, int swatch, BooleanSupplier chosen, OnPress onPress, Viewport viewport) {
			super(width, height, label, onPress, viewport);
			this.chosen = chosen;
			this.swatch = swatch;
		}

		@Override
		protected MutableComponent createNarrationMessage() {
			MutableComponent text = super.createNarrationMessage();
			return chosen.getAsBoolean() ? text.append(", ").append(Component.translatable("chatspellcheck.settings.chosen")) : text;
		}

		@Override
		void draw(GuiGraphicsExtractor g, Font font, boolean hot) {
			boolean on = chosen.getAsBoolean();
			if (on) {
				Paint.rounded(g, getX(), getY(), getWidth(), getHeight(), Paint.PINK_DEEP);
				Paint.roundedOutline(g, getX(), getY(), getWidth(), getHeight(), hot ? Paint.PINK_LIGHT : Paint.PINK);
			} else {
				body(g, BODY, FRAME, hot);
			}
			int swatchW = swatch == 0 ? 0 : 10;
			String label = PopoverLayout.ellipsize(getMessage().getString(), getWidth() - 8 - swatchW, font::width);
			int x = getX() + (getWidth() - font.width(label) - swatchW) / 2;
			if (swatch != 0) {
				Paint.rounded(g, x, getY() + (getHeight() - 7) / 2, 7, 7, swatch);
				x += swatchW;
			}
			g.text(font, label, x, textY(), on ? TEXT : TEXT_OFF, false);
		}
	}

	/** A plain action. The primary one (Done) is filled pink. */
	static final class Action extends Cute {
		private final boolean primary;

		Action(int width, int height, Component label, boolean primary, OnPress onPress, Viewport viewport) {
			super(width, height, label, onPress, viewport);
			this.primary = primary;
		}

		@Override
		void draw(GuiGraphicsExtractor g, Font font, boolean hot) {
			if (primary && active) {
				Paint.rounded(g, getX(), getY(), getWidth(), getHeight(), hot ? Paint.PINK : Paint.PINK_DEEP);
				Paint.roundedOutline(g, getX(), getY(), getWidth(), getHeight(), hot ? Paint.PINK_LIGHT : Paint.PINK);
				g.fill(getX() + 2, getY() + 1, getRight() - 2, getY() + 2, 0x30FFFFFF);
			} else {
				body(g, BODY, active ? Paint.PINK_DEEP : FRAME, hot);
			}
			String label = PopoverLayout.ellipsize(getMessage().getString(), getWidth() - 8, font::width);
			g.centeredText(font, label, getX() + getWidth() / 2, textY(), active ? TEXT : TEXT_DISABLED);
		}
	}
}
