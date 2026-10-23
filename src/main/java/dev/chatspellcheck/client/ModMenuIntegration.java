package dev.chatspellcheck.client;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import dev.chatspellcheck.ChatSpellcheckClient;

/** Adds a settings button for Chat Spellcheck to Mod Menu's mod list (only loaded if Mod Menu is installed). */
public final class ModMenuIntegration implements ModMenuApi {
	@Override
	public ConfigScreenFactory<?> getModConfigScreenFactory() {
		return parent -> ChatSpellcheckClient.service() == null ? null : new SpellcheckSettingsScreen(ChatSpellcheckClient.service(), parent);
	}
}
