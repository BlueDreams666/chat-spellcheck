package dev.chatspellcheck.gametest.mixin;

import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.core.ClientAsset;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.entity.player.PlayerSkin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Test-only: the screenshot player wears a Batman skin instead of the default one. */
@Mixin(AbstractClientPlayer.class)
public abstract class ScreenshotSkinMixin {
	private static final PlayerSkin CHATSPELLCHECK_TEST$SKIN = PlayerSkin.insecure(
		new ClientAsset.ResourceTexture(Identifier.fromNamespaceAndPath("chatspellcheck-gametest", "skin/batman")),
		null, null, PlayerModelType.WIDE);

	@Inject(method = "getSkin", at = @At("HEAD"), cancellable = true)
	private void chatspellcheckTest$skin(CallbackInfoReturnable<PlayerSkin> cir) {
		cir.setReturnValue(CHATSPELLCHECK_TEST$SKIN);
	}
}
