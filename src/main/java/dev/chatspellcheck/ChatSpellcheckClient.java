package dev.chatspellcheck;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import dev.chatspellcheck.client.RegistryTerms;
import dev.chatspellcheck.client.SpellcheckSettingsScreen;
import dev.chatspellcheck.core.Language;
import dev.chatspellcheck.core.Settings;
import dev.chatspellcheck.core.SpellService;
import dev.chatspellcheck.core.UserDictionary;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ChatSpellcheckClient implements ClientModInitializer {
	public static final String MOD_ID = "chatspellcheck";
	private static final Logger LOG = LoggerFactory.getLogger("ChatSpellcheck");
	private static SpellService service;

	/** Null only if initialisation failed; the chat screen then behaves exactly like vanilla. */
	public static SpellService service() {
		return service;
	}

	@Override
	public void onInitializeClient() {
		Path dir = FabricLoader.getInstance().getConfigDir().resolve(MOD_ID);
		Settings settings = new Settings(dir.resolve("settings.properties"));
		UserDictionary dictionary = new UserDictionary(dir.resolve("custom-words.txt"));
		// Separately, so a broken or unreadable settings file can't stop the word list loading
		// (or vice versa), and nothing here can stop the game from starting.
		try {
			settings.load();
		} catch (IOException | RuntimeException e) {
			LOG.warn("Could not read {}; using default settings", dir.resolve("settings.properties"), e);
		}
		if (settings.firstRun) {
			// First launch: start with the dictionary for the game's language.
			settings.languages = Language.defaultsForGameLanguage(gameLanguage());
			try {
				settings.save();
			} catch (IOException e) {
				LOG.warn("Could not save {}", dir.resolve("settings.properties"), e);
			}
		}
		try {
			dictionary.load();
		} catch (IOException | RuntimeException e) {
			LOG.warn("Could not read {}; added words will work this session but won't be saved", dir.resolve("custom-words.txt"), e);
		}
		service = new SpellService(settings, dictionary,
			name -> ChatSpellcheckClient.class.getResourceAsStream("/assets/" + MOD_ID + "/" + name));
		service.start();

		ClientLifecycleEvents.CLIENT_STARTED.register(client -> service.addDomainTerms(RegistryTerms.collect()));
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> service.close());
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) -> dispatcher.register(
			ClientCommands.literal("spellcheck")
				.executes(ChatSpellcheckClient::openSettings)
				.then(ClientCommands.literal("status").executes(ChatSpellcheckClient::status))
				.then(ClientCommands.literal("on").executes(ctx -> setEnabled(ctx, true)))
				.then(ClientCommands.literal("off").executes(ctx -> setEnabled(ctx, false)))
				.then(ClientCommands.literal("language")
					.then(ClientCommands.argument("languages", StringArgumentType.greedyString())
						.suggests(ChatSpellcheckClient::suggestLanguages)
						.executes(ChatSpellcheckClient::setLanguages)))
				.then(ClientCommands.literal("add")
					.then(ClientCommands.argument("language", StringArgumentType.word())
						.suggests(ChatSpellcheckClient::suggestLanguages)
						.executes(ctx -> changeLanguage(ctx, true))))
				.then(ClientCommands.literal("remove")
					.then(ClientCommands.argument("language", StringArgumentType.word())
						.suggests(ChatSpellcheckClient::suggestLanguages)
						.executes(ctx -> changeLanguage(ctx, false))))
				.then(ClientCommands.literal("autocorrect")
					.then(ClientCommands.literal("on").executes(ctx -> setAutocorrect(ctx, true)))
					.then(ClientCommands.literal("off").executes(ctx -> setAutocorrect(ctx, false))))
				.then(ClientCommands.literal("commands")
					.then(ClientCommands.literal("on").executes(ctx -> setCheckCommands(ctx, true)))
					.then(ClientCommands.literal("off").executes(ctx -> setCheckCommands(ctx, false))))
				.then(ClientCommands.literal("forget")
					.then(ClientCommands.argument("word", StringArgumentType.greedyString())
						.executes(ChatSpellcheckClient::forget)))));
	}

	/**
	 * The game's language code ("es_mx"), read from options.txt because the game's own options
	 * aren't loaded yet when mods start. A brand-new install has no options.txt; the game then
	 * starts in the computer's language, so use that.
	 */
	private static String gameLanguage() {
		Path options = FabricLoader.getInstance().getGameDir().resolve("options.txt");
		try {
			for (String line : java.nio.file.Files.readAllLines(options, java.nio.charset.StandardCharsets.UTF_8)) {
				if (line.startsWith("lang:")) {
					return line.substring(5).strip();
				}
			}
		} catch (IOException | RuntimeException ignored) {
			// no options.txt yet
		}
		java.util.Locale locale = java.util.Locale.getDefault();
		return locale.getLanguage() + "_" + locale.getCountry();
	}

	private static java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> suggestLanguages(
		CommandContext<FabricClientCommandSource> ctx, com.mojang.brigadier.suggestion.SuggestionsBuilder builder) {
		for (Language l : Language.values()) {
			builder.suggest(l.code());
		}
		return builder.buildFuture();
	}

	private static int openSettings(CommandContext<FabricClientCommandSource> ctx) {
		Minecraft client = ctx.getSource().getClient();
		// Queue it: the chat screen closes right after the command runs.
		client.schedule(() -> client.gui.setScreen(new SpellcheckSettingsScreen(service)));
		return 1;
	}

	private static String languageNames(List<Language> languages) {
		return languages.stream().map(SpellcheckSettingsScreen::menuName).collect(Collectors.joining(", "));
	}

	private static int status(CommandContext<FabricClientCommandSource> ctx) {
		Settings s = service.settings();
		String state = !s.enabled ? "off" : !service.isReady() ? "loading" : service.loadError() != null ? "error: " + service.loadError() : "on";
		ctx.getSource().sendFeedback(Component.translatable("chatspellcheck.command.status",
			state, languageNames(s.languages), service.userDictionary().size(), s.checkMessageCommands ? "on" : "off"));
		return 1;
	}

	private static int setLanguages(CommandContext<FabricClientCommandSource> ctx) {
		String raw = StringArgumentType.getString(ctx, "languages");
		List<Language> languages = Language.parseList(raw.replace(' ', ','));
		if (languages.isEmpty()) {
			ctx.getSource().sendError(Component.translatable("chatspellcheck.command.unknown_language", raw));
			return 0;
		}
		service.setLanguages(languages);
		ctx.getSource().sendFeedback(Component.translatable("chatspellcheck.command.language", languageNames(languages)));
		return 1;
	}

	private static int changeLanguage(CommandContext<FabricClientCommandSource> ctx, boolean add) {
		String raw = StringArgumentType.getString(ctx, "language");
		Language language = Language.parse(raw).orElse(null);
		if (language == null) {
			ctx.getSource().sendError(Component.translatable("chatspellcheck.command.unknown_language", raw));
			return 0;
		}
		List<Language> languages = new ArrayList<>(service.settings().languages);
		if (add && !languages.contains(language)) {
			languages.add(language);
		} else if (!add) {
			languages.remove(language);
			if (languages.isEmpty()) {
				ctx.getSource().sendError(Component.translatable("chatspellcheck.command.last_language"));
				return 0;
			}
		}
		service.setLanguages(languages);
		ctx.getSource().sendFeedback(Component.translatable("chatspellcheck.command.language", languageNames(languages)));
		return 1;
	}

	private static int setEnabled(CommandContext<FabricClientCommandSource> ctx, boolean enabled) {
		service.settings().enabled = enabled;
		service.saveSettingsQuietly();
		service.refresh();
		ctx.getSource().sendFeedback(Component.translatable(enabled ? "chatspellcheck.command.enabled" : "chatspellcheck.command.disabled"));
		return 1;
	}

	private static int setCheckCommands(CommandContext<FabricClientCommandSource> ctx, boolean on) {
		service.settings().checkMessageCommands = on;
		service.saveSettingsQuietly();
		service.refresh();
		ctx.getSource().sendFeedback(Component.translatable(on ? "chatspellcheck.command.commands_on" : "chatspellcheck.command.commands_off"));
		return 1;
	}

	private static int setAutocorrect(CommandContext<FabricClientCommandSource> ctx, boolean on) {
		service.settings().autocorrect = on;
		service.saveSettingsQuietly();
		ctx.getSource().sendFeedback(Component.translatable(on ? "chatspellcheck.command.autocorrect_on" : "chatspellcheck.command.autocorrect_off"));
		return 1;
	}

	private static int forget(CommandContext<FabricClientCommandSource> ctx) {
		String word = StringArgumentType.getString(ctx, "word").strip();
		FabricClientCommandSource source = ctx.getSource();
		service.removeFromDictionary(word).thenAccept(removed -> source.getClient().execute(() ->
			source.sendFeedback(Component.translatable(removed ? "chatspellcheck.command.forgot" : "chatspellcheck.command.not_found", word))));
		return 1;
	}
}
