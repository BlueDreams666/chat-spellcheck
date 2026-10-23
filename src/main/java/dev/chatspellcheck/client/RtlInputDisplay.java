package dev.chatspellcheck.client;

import com.ibm.icu.text.ArabicShaping;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import dev.chatspellcheck.mixin.EditBoxAccessor;
import dev.chatspellcheck.ui.BidiLine;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.TextCursorUtils;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;

/**
 * Shows the chat line the way right-to-left text should look while typing: Hebrew and Arabic
 * words in reading order, Arabic letters joined. Vanilla draws the characters in typing order,
 * so "שלום" shows as "םולש". Only the display changes; the message sent is exactly what was typed.
 *
 * <p>Takes over drawing only when the visible text contains right-to-left characters and no IME
 * composition is in progress; otherwise vanilla draws the box as usual. The cursor, selection
 * and mouse clicks are mapped through the same layout, so they land where the player expects.
 */
final class RtlInputDisplay implements ChatInputHooks.Display {
	private static final int LINE_HEIGHT = 9;

	private final EditBox input;
	private final EditBoxAccessor box;
	private String cachedText;
	private Font cachedFont;
	private BidiLine cachedLine;

	RtlInputDisplay(EditBox input) {
		this.input = input;
		this.box = (EditBoxAccessor) input;
	}

	/** The visible part of the text, exactly as EditBox computes it. */
	private String visibleText() {
		String value = input.getValue();
		int displayPos = Math.min(box.chatspellcheck$displayPos(), value.length());
		return box.chatspellcheck$font().plainSubstrByWidth(value.substring(displayPos), input.getInnerWidth());
	}

	/** The layout of the visible text if it needs reordering, else null (vanilla is right). */
	BidiLine activeLine() {
		String visible = visibleText();
		return BidiLine.needsReordering(visible) ? lineFor(visible, box.chatspellcheck$font()) : null;
	}

	private BidiLine lineFor(String visible, Font font) {
		if (!visible.equals(cachedText) || font != cachedFont) {
			cachedText = visible;
			cachedFont = font;
			cachedLine = layout(visible, font);
		}
		return cachedLine;
	}

	static BidiLine layout(String visible, Font font) {
		return BidiLine.layout(visible, font::width, RtlInputDisplay::shapeArabic);
	}

	/**
	 * Arabic letters change shape with their neighbours; Minecraft's font has the joined forms
	 * but doesn't pick them. Lam-alef ligatures keep a space in place of the alef so every
	 * character keeps its position.
	 */
	static String shapeArabic(String text) {
		boolean arabic = false;
		for (int i = 0; i < text.length() && !arabic; i++) {
			arabic = Character.UnicodeScript.of(text.charAt(i)) == Character.UnicodeScript.ARABIC;
		}
		if (!arabic) {
			return text;
		}
		try {
			return new ArabicShaping(ArabicShaping.LETTERS_SHAPE | ArabicShaping.TEXT_DIRECTION_LOGICAL
				| ArabicShaping.LENGTH_FIXED_SPACES_NEAR).shape(text);
		} catch (Exception | LinkageError e) {
			return text; // unshaped letters are still readable
		}
	}

	@Override
	public boolean render(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		if (!input.isVisible() || input.isBordered() || box.chatspellcheck$preeditOverlay() != null) {
			return false; // leave borders and IME composition to vanilla
		}
		BidiLine line = activeLine();
		if (line == null) {
			return false;
		}
		Font font = box.chatspellcheck$font();
		String value = input.getValue();
		int displayPos = Math.min(box.chatspellcheck$displayPos(), value.length());
		int visibleLength = cachedText.length();
		int textX = box.chatspellcheck$textX();
		int textY = box.chatspellcheck$textY();
		int color = box.chatspellcheck$isEditable() ? box.chatspellcheck$textColor() : box.chatspellcheck$textColorUneditable();
		boolean shadow = box.chatspellcheck$textShadow();

		graphics.text(font, box.chatspellcheck$applyFormat(line.visual(), displayPos), textX, textY, color, shadow);

		int cursor = input.getCursorPosition();
		int relCursor = cursor - displayPos;
		boolean cursorOnScreen = relCursor >= 0 && relCursor <= visibleLength;
		int cursorX = cursorOnScreen ? textX + line.caretX(relCursor) : relCursor > 0 ? textX + input.getWidth() : textX;
		boolean insert = cursor < value.length() || value.length() >= box.chatspellcheck$maxLength();

		String suggestion = box.chatspellcheck$suggestion();
		if (!insert && suggestion != null) {
			graphics.text(font, suggestion, textX + line.width(), textY, 0xFF808080, shadow);
		}

		int highlight = Mth.clamp(box.chatspellcheck$highlightPos() - displayPos, 0, visibleLength);
		int selFrom = Math.min(Mth.clamp(relCursor, 0, visibleLength), highlight);
		int selTo = Math.max(Mth.clamp(relCursor, 0, visibleLength), highlight);
		if (selFrom < selTo) {
			int right = input.getX() + input.getWidth();
			for (int[] r : line.ranges(selFrom, selTo)) {
				graphics.textHighlight(Math.min(textX + r[0], right), textY - 1, Math.min(textX + r[1] - 1, right),
					textY + 1 + LINE_HEIGHT, box.chatspellcheck$invertHighlightedTextColor());
			}
		}

		boolean showCursor = input.isFocused() && cursorOnScreen
			&& TextCursorUtils.isCursorVisible(Util.getMillis() - box.chatspellcheck$focusedTime());
		if (showCursor) {
			if (insert || line.caretX(relCursor) < line.width()) {
				TextCursorUtils.extractInsertCursor(graphics, cursorX, textY, color, LINE_HEIGHT + 1);
			} else {
				TextCursorUtils.extractAppendCursor(graphics, font, cursorX + 1, textY, color, shadow);
			}
		}
		if (input.isHovered()) {
			graphics.requestCursor(box.chatspellcheck$isEditable() ? CursorTypes.IBEAM : CursorTypes.NOT_ALLOWED);
		}
		if (input.isFocused() && box.chatspellcheck$isEditable()) {
			// Where the system IME candidate window should appear.
			Minecraft.getInstance().textInputManager().setTextInputArea(cursorX, textY, cursorX + 1, textY + LINE_HEIGHT + 1);
		}
		return true;
	}

	@Override
	public int clickedPosition(double x) {
		BidiLine line = activeLine();
		if (line == null) {
			return -1;
		}
		int offset = Math.min(Mth.floor(x) - box.chatspellcheck$textX(), input.getInnerWidth());
		int displayPos = Math.min(box.chatspellcheck$displayPos(), input.getValue().length());
		return displayPos + line.hitTest(offset);
	}
}
