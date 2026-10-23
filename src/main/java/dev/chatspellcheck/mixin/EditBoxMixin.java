package dev.chatspellcheck.mixin;

import dev.chatspellcheck.client.ChatInputHooks;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.MouseButtonEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Hooks for the chat input box only (every other EditBox has no listener or display set):
 * typed characters for autocorrect, and right-to-left display with matching click positions.
 */
@Mixin(EditBox.class)
public abstract class EditBoxMixin implements ChatInputHooks {
	@Unique
	private Runnable chatspellcheck$typingListener;
	@Unique
	private Display chatspellcheck$display;

	@Override
	public void chatspellcheck$setTypingListener(Runnable listener) {
		chatspellcheck$typingListener = listener;
	}

	@Override
	public void chatspellcheck$setDisplay(Display display) {
		chatspellcheck$display = display;
	}

	@Override
	public Display chatspellcheck$display() {
		return chatspellcheck$display;
	}

	@Inject(method = "charTyped", at = @At("RETURN"))
	private void chatspellcheck$charTyped(CharacterEvent event, CallbackInfoReturnable<Boolean> cir) {
		if (cir.getReturnValueZ() && chatspellcheck$typingListener != null) {
			chatspellcheck$typingListener.run();
		}
	}

	@Inject(method = "extractWidgetRenderState", at = @At("HEAD"), cancellable = true)
	private void chatspellcheck$render(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a, CallbackInfo ci) {
		if (chatspellcheck$display != null && chatspellcheck$display.render(graphics, mouseX, mouseY)) {
			ci.cancel();
		}
	}

	@Inject(method = "findClickedPositionInText", at = @At("HEAD"), cancellable = true)
	private void chatspellcheck$clickedPosition(MouseButtonEvent event, CallbackInfoReturnable<Integer> cir) {
		if (chatspellcheck$display != null) {
			int position = chatspellcheck$display.clickedPosition(event.x());
			if (position >= 0) {
				cir.setReturnValue(position);
			}
		}
	}
}
