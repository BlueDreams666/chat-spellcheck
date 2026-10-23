package dev.chatspellcheck.client;

/** Implemented by ChatScreen via mixin; lets automated in-game tests inspect the overlay. */
public interface ChatSpellcheckHost {
	ChatSpellcheckOverlay chatspellcheck$overlay();
}
