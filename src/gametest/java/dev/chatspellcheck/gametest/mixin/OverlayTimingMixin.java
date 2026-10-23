package dev.chatspellcheck.gametest.mixin;

import dev.chatspellcheck.client.ChatSpellcheckOverlay;
import dev.chatspellcheck.gametest.OverlayTiming;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Test-only: times each overlay frame so the render-thread cost can be measured in game. */
@Mixin(value = ChatSpellcheckOverlay.class, remap = false)
public abstract class OverlayTimingMixin {
	@Inject(method = "extract", at = @At("HEAD"))
	private void chatspellcheckTest$start(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean suggestions, boolean dragging, CallbackInfo ci) {
		OverlayTiming.start();
	}

	@Inject(method = "extract", at = @At("RETURN"))
	private void chatspellcheckTest$end(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean suggestions, boolean dragging, CallbackInfo ci) {
		OverlayTiming.end();
	}
}
