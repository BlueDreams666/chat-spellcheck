package dev.chatspellcheck.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/** Implemented by EditBox via mixin; the chat overlay attaches to its own input box only. */
public interface ChatInputHooks {
	/** @param listener called after each typed character, or null */
	void chatspellcheck$setTypingListener(Runnable listener);

	/** @param display takes over drawing and click mapping when the text needs it, or null */
	void chatspellcheck$setDisplay(Display display);

	Display chatspellcheck$display();

	interface Display {
		/** Draws the box itself and returns true, or returns false to let vanilla draw it. */
		boolean render(GuiGraphicsExtractor graphics, int mouseX, int mouseY);

		/** The text position under screen x {@code x}, or -1 to let vanilla decide. */
		int clickedPosition(double x);
	}
}
