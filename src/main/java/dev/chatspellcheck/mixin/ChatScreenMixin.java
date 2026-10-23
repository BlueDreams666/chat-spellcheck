package dev.chatspellcheck.mixin;

import dev.chatspellcheck.ChatSpellcheckClient;
import dev.chatspellcheck.client.ChatSpellcheckHost;
import dev.chatspellcheck.client.ChatSpellcheckOverlay;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.CommandSuggestions;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ChatScreen.class)
public abstract class ChatScreenMixin implements ChatSpellcheckHost {
	@Shadow
	protected EditBox input;

	@Shadow
	private CommandSuggestions commandSuggestions;

	@Unique
	private ChatSpellcheckOverlay chatspellcheck$overlay;

	@Override
	public ChatSpellcheckOverlay chatspellcheck$overlay() {
		return chatspellcheck$overlay;
	}

	@Inject(method = "init", at = @At("TAIL"))
	private void chatspellcheck$init(CallbackInfo ci) {
		// init() also runs on resize/GUI-scale change (and when a preserved chat screen is
		// restored), recreating the EditBox. Keep results and popover state across that.
		if (ChatSpellcheckClient.service() == null) {
			chatspellcheck$overlay = null;
		} else if (chatspellcheck$overlay == null) {
			chatspellcheck$overlay = new ChatSpellcheckOverlay(ChatSpellcheckClient.service(), input);
		} else {
			chatspellcheck$overlay.rebind(input);
		}
	}

	@Inject(method = "extractRenderState", at = @At("TAIL"))
	private void chatspellcheck$render(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a, CallbackInfo ci) {
		if (chatspellcheck$overlay != null) {
			// isDragging: a selection drag that started in this screen (MouseHandler's pressed flags
			// are only updated while no screen is open, so they can be stale here).
			boolean dragging = ((ChatScreen) (Object) this).isDragging();
			chatspellcheck$overlay.extract(graphics, mouseX, mouseY, commandSuggestions.isVisible(), dragging);
		}
	}

	// HEAD, so that while our popover owns the keyboard, Up/Down/Enter/Escape don't also scroll
	// history, send the message, or close chat.
	@Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
	private void chatspellcheck$keyPressed(KeyEvent event, CallbackInfoReturnable<Boolean> cir) {
		if (chatspellcheck$overlay == null) {
			return;
		}
		if (ChatSpellcheckOverlay.isShortcut(event) && commandSuggestions.isVisible()) {
			// An explicit request for spelling suggestions replaces the Tab-completion list,
			// which would otherwise close our popover on the next frame.
			commandSuggestions.hide();
		}
		if (chatspellcheck$overlay.keyPressed(event)) {
			cir.setReturnValue(true);
		}
	}

	@Inject(method = "handleChatInput", at = @At("HEAD"))
	private void chatspellcheck$send(String message, boolean addToHistory, CallbackInfo ci) {
		if (chatspellcheck$overlay != null) {
			chatspellcheck$overlay.messageSent(message);
		}
	}

	@Inject(method = "removed", at = @At("TAIL"))
	private void chatspellcheck$removed(CallbackInfo ci) {
		if (chatspellcheck$overlay != null) {
			chatspellcheck$overlay.closed();
		}
	}

	@Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
	private void chatspellcheck$mouseClicked(MouseButtonEvent event, boolean doubleClick, CallbackInfoReturnable<Boolean> cir) {
		if (chatspellcheck$overlay != null && chatspellcheck$overlay.mouseClicked(event)) {
			cir.setReturnValue(true);
		}
	}
}
