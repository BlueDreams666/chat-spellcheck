package dev.chatspellcheck.gametest.mixin;

import dev.chatspellcheck.gametest.InputTiming;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Test-only: times the whole draw of one widget (the chat input box) per frame. */
@Mixin(AbstractWidget.class)
public abstract class InputTimingMixin {
	@Inject(method = "extractRenderState", at = @At("HEAD"))
	private void chatspellcheckTest$start(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a, CallbackInfo ci) {
		if ((Object) this == InputTiming.target) {
			InputTiming.TIMING.start();
		}
	}

	@Inject(method = "extractRenderState", at = @At("RETURN"))
	private void chatspellcheckTest$end(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a, CallbackInfo ci) {
		if ((Object) this == InputTiming.target) {
			InputTiming.TIMING.end();
		}
	}
}
