package dev.chatspellcheck.mixin;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.IMEPreeditOverlay;
import org.spongepowered.asm.mixin.Mixin;
import net.minecraft.util.FormattedCharSequence;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Read-only access to the EditBox layout state needed to place underlines exactly. */
@Mixin(EditBox.class)
public interface EditBoxAccessor {
	@Accessor("font")
	Font chatspellcheck$font();

	@Accessor("displayPos")
	int chatspellcheck$displayPos();

	@Accessor("textX")
	int chatspellcheck$textX();

	@Accessor("textY")
	int chatspellcheck$textY();

	@Accessor("focusedTime")
	long chatspellcheck$focusedTime();

	@Accessor("textColor")
	int chatspellcheck$textColor();

	@Accessor("textColorUneditable")
	int chatspellcheck$textColorUneditable();

	@Accessor("isEditable")
	boolean chatspellcheck$isEditable();

	@Accessor("textShadow")
	boolean chatspellcheck$textShadow();

	@Accessor("suggestion")
	String chatspellcheck$suggestion();

	@Accessor("invertHighlightedTextColor")
	boolean chatspellcheck$invertHighlightedTextColor();

	@Invoker("applyFormat")
	FormattedCharSequence chatspellcheck$applyFormat(String text, int offset);

	@Accessor("highlightPos")
	int chatspellcheck$highlightPos();

	@Accessor("highlightPos")
	void chatspellcheck$setHighlightPos(int pos);

	@Accessor("cursorPos")
	void chatspellcheck$setCursorPos(int pos);

	@Accessor("displayPos")
	void chatspellcheck$setDisplayPos(int pos);

	@Accessor("maxLength")
	int chatspellcheck$maxLength();

	@Accessor("preeditOverlay")
	IMEPreeditOverlay chatspellcheck$preeditOverlay();
}
