package dev.chatspellcheck.client;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.chatspellcheck.core.Language;
import dev.chatspellcheck.core.Settings;
import dev.chatspellcheck.core.SpellService;
import dev.chatspellcheck.ui.Rect;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.Util;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * In-game picker: toggle any number of dictionaries (a word is correct if any of them accepts
 * it), switch checking on/off, and choose whether /msg-style commands are checked. Changes are
 * applied when the screen closes. Each language shows whether its dictionary is loaded.
 */
public final class SpellcheckSettingsScreen extends Screen {
	private static final Logger LOG = LoggerFactory.getLogger("ChatSpellcheck");
	private static final int BUTTON_WIDTH = 130;
	private static final int BUTTON_HEIGHT = 20;
	private static final int GAP = 4;
	private static final int LABEL_HEIGHT = 11;

	private static final int TEXT_TITLE = 0xFFFFFFFF;
	private static final int TEXT_HINT = 0xFFA8ADBA;
	private static final int TEXT_LABEL = 0xFF8A8A96;
	private static final int TEXT_OFF = 0xFFB4B4B4;
	private static final int ON = 0xFF7BD88F;
	private static final int OFF = 0xFFFF8A80;
	private static final int DOT_READY = 0xFF5BD47A;
	private static final int DOT_LOADING = 0xFFFFC266;
	private static final int DOT_FAILED = 0xFFFF5C5C;
	private static final int DOT_PENDING = 0xFF9AA0B0;
	private static final int CHECK_ON = Paint.ACCENT;
	private static final int CHECK_BORDER = 0xFF6A6F7C;
	private static final int CHECK_OFF = 0xFF2A2D35;

	private final SpellService service;
	/** Where Done/Escape returns to: the mod list when opened from Mod Menu, else the game. */
	private final Screen parent;
	private final List<Language> selected;
	private final Map<Language, Button> languageButtons = new LinkedHashMap<>();
	private boolean enabled;
	private boolean checkCommands;
	private boolean autocorrect;
	private Rect panel;
	private int languagesLabelY;
	private int summaryY;
	private int tooltipsVersion = -1;

	public SpellcheckSettingsScreen(SpellService service) {
		this(service, null);
	}

	public SpellcheckSettingsScreen(SpellService service, Screen parent) {
		super(Component.translatable("chatspellcheck.settings.title"));
		this.service = service;
		this.parent = parent;
		Settings s = service.settings();
		this.selected = new ArrayList<>(s.languages);
		this.enabled = s.enabled;
		this.checkCommands = s.checkMessageCommands;
		this.autocorrect = s.autocorrect;
	}

	@Override
	protected void init() {
		languageButtons.clear();
		Language[] all = Language.values();
		// Three columns when they fit, so everything stays on screen at large GUI scales.
		int columns = width >= 3 * BUTTON_WIDTH + 2 * GAP + 16 ? 3 : 2;
		int rows = (all.length + columns - 1) / columns;
		int gridWidth = BUTTON_WIDTH * columns + GAP * (columns - 1);
		int left = (width - gridWidth) / 2;
		int row = BUTTON_HEIGHT + GAP;
		int contentHeight = LABEL_HEIGHT + rows * row + GAP + row + LABEL_HEIGHT + 2 + row;
		int top = Math.max(40, (height - contentHeight) / 2 + 8);

		languagesLabelY = top;
		int gridTop = top + LABEL_HEIGHT;
		for (int i = 0; i < all.length; i++) {
			Language language = all[i];
			int x = left + (i % columns) * (BUTTON_WIDTH + GAP);
			int y = gridTop + (i / columns) * row;
			Button button = addRenderableWidget(Button.builder(languageLabel(language), b -> {
				toggle(language);
				b.setMessage(languageLabel(language));
				b.setTooltip(languageTooltip(language));
			}).bounds(x, y, BUTTON_WIDTH, BUTTON_HEIGHT).tooltip(languageTooltip(language)).build());
			languageButtons.put(language, button);
		}
		panel = new Rect(left - 6, top - 4, gridWidth + 12, LABEL_HEIGHT + rows * row + 6);

		int y = gridTop + rows * row + GAP + 2;
		int half = (gridWidth - GAP) / 2;
		int third = (gridWidth - 2 * GAP) / 3;
		addRenderableWidget(Button.builder(enabledLabel(), b -> {
			enabled = !enabled;
			b.setMessage(enabledLabel());
		}).bounds(left, y, third, BUTTON_HEIGHT).tooltip(Tooltip.create(Component.translatable("chatspellcheck.settings.enabled.tooltip"))).build());
		addRenderableWidget(Button.builder(autocorrectLabel(), b -> {
			autocorrect = !autocorrect;
			b.setMessage(autocorrectLabel());
		}).bounds(left + third + GAP, y, third, BUTTON_HEIGHT).tooltip(Tooltip.create(Component.translatable("chatspellcheck.settings.autocorrect.tooltip"))).build());
		addRenderableWidget(Button.builder(commandsLabel(), b -> {
			checkCommands = !checkCommands;
			b.setMessage(commandsLabel());
		}).bounds(left + 2 * (third + GAP), y, gridWidth - 2 * (third + GAP), BUTTON_HEIGHT).tooltip(Tooltip.create(Component.translatable("chatspellcheck.settings.commands.tooltip"))).build());

		summaryY = y + row + 1;
		int bottom = Math.min(summaryY + LABEL_HEIGHT + 2, height - BUTTON_HEIGHT - 4);
		Button wordList = addRenderableWidget(Button.builder(Component.translatable("chatspellcheck.settings.word_list"), b -> openConfigFolder())
			.bounds(left, bottom, half, BUTTON_HEIGHT)
			.tooltip(Tooltip.create(Component.translatable("chatspellcheck.settings.word_list.tooltip"))).build());
		wordList.active = FolderOpener.available();
		addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose())
			.bounds(left + half + GAP, bottom, half, BUTTON_HEIGHT).build());
	}

	private void toggle(Language language) {
		if (selected.contains(language)) {
			if (selected.size() > 1) { // keep at least one dictionary
				selected.remove(language);
			}
		} else {
			selected.add(language);
		}
	}

	/** The name in white when selected, grey when not; the checkbox is drawn separately. */
	private Component languageLabel(Language language) {
		return Component.literal(menuName(language)).withColor((selected.contains(language) ? TEXT_TITLE : TEXT_OFF) & 0xFFFFFF);
	}

	/**
	 * The language's own name, except where Minecraft's font can't draw it properly: it has no
	 * Arabic letter shaping, so "العربية" would show as disconnected letters.
	 */
	public static String menuName(Language language) {
		return language.script() == Character.UnicodeScript.ARABIC ? language.englishName() : language.displayName();
	}

	private Tooltip languageTooltip(Language language) {
		MutableComponent text = Component.literal(language.englishName()).withStyle(ChatFormatting.BOLD);
		if (!language.englishName().equals(menuName(language))) {
			text.append(Component.literal(" · " + language.displayName()).withStyle(style -> style.withBold(false)));
		}
		text.append(Component.literal("\n"))
			.append(Component.translatable("chatspellcheck.settings.dictionary", language.dictionary()).withStyle(ChatFormatting.GRAY));
		Component status = statusText(language);
		if (status != null) {
			text.append(Component.literal("\n")).append(status);
		}
		return Tooltip.create(text);
	}

	private Component statusText(Language language) {
		boolean wanted = selected.contains(language);
		return switch (service.status(language)) {
			case READY -> wanted ? Component.translatable("chatspellcheck.settings.status.ready").withColor(DOT_READY & 0xFFFFFF)
				: Component.translatable("chatspellcheck.settings.status.unloads").withStyle(ChatFormatting.GRAY);
			case LOADING -> Component.translatable("chatspellcheck.settings.status.loading").withColor(DOT_LOADING & 0xFFFFFF);
			case FAILED -> Component.translatable("chatspellcheck.settings.status.failed", String.valueOf(service.loadError())).withColor(DOT_FAILED & 0xFFFFFF);
			case INACTIVE -> wanted ? Component.translatable("chatspellcheck.settings.status.pending").withStyle(ChatFormatting.GRAY) : null;
		};
	}

	private Component enabledLabel() {
		return Component.translatable(enabled ? "chatspellcheck.settings.enabled" : "chatspellcheck.settings.disabled")
			.withColor((enabled ? ON : OFF) & 0xFFFFFF);
	}

	private Component autocorrectLabel() {
		return Component.translatable(autocorrect ? "chatspellcheck.settings.autocorrect_on" : "chatspellcheck.settings.autocorrect_off")
			.withColor((autocorrect ? ON : TEXT_OFF) & 0xFFFFFF);
	}

	private Component commandsLabel() {
		return Component.translatable(checkCommands ? "chatspellcheck.settings.commands_on" : "chatspellcheck.settings.commands_off")
			.withColor((checkCommands ? ON : TEXT_OFF) & 0xFFFFFF);
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		super.extractBackground(graphics, mouseX, mouseY, a);
		// A panel behind the language grid, on top of the (blurred) game.
		Paint.panel(graphics, panel, 0xC80B0B0F, 0xFF2B2B34, Paint.ACCENT, 1f);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		super.extractRenderState(graphics, mouseX, mouseY, a);
		Component bigTitle = title.copy().withStyle(ChatFormatting.BOLD);
		graphics.centeredText(font, bigTitle, width / 2, 10, TEXT_TITLE);
		int rule = font.width(bigTitle) / 2 + 4;
		graphics.fill(width / 2 - rule, 20, width / 2 + rule, 21, Paint.ACCENT);
		graphics.centeredText(font, Component.translatable("chatspellcheck.settings.hint"), width / 2, 25, TEXT_HINT);
		graphics.text(font, Component.translatable("chatspellcheck.settings.languages").getString().toUpperCase(java.util.Locale.ROOT),
			panel.x() + 7, languagesLabelY, TEXT_LABEL, false);
		if (service.lexiconVersion() != tooltipsVersion) {
			// A dictionary finished loading (or failed) while the screen is open.
			tooltipsVersion = service.lexiconVersion();
			languageButtons.forEach((language, button) -> button.setTooltip(languageTooltip(language)));
		}
		long now = Util.getMillis();
		graphics.nextStratum(); // above the button sprites
		for (Map.Entry<Language, Button> e : languageButtons.entrySet()) {
			drawStatusDot(graphics, e.getKey(), e.getValue(), now);
		}
		int words = service.userDictionary().size();
		Component summary = (selected.size() == 1 ? Component.translatable("chatspellcheck.settings.summary.languages.one")
			: Component.translatable("chatspellcheck.settings.summary.languages.many", selected.size()))
			.append("  ·  ")
			.append(words == 1 ? Component.translatable("chatspellcheck.settings.summary.words.one")
				: Component.translatable("chatspellcheck.settings.summary.words.many", words));
		graphics.centeredText(font, summary, width / 2, summaryY, TEXT_LABEL);
	}

	/** A checkbox at the left of each language and a status dot at the right. */
	private void drawStatusDot(GuiGraphicsExtractor graphics, Language language, Button button, long now) {
		boolean wanted = selected.contains(language);
		int bx = button.getX() + 6;
		int by = button.getY() + (button.getHeight() - 9) / 2;
		if (wanted) {
			graphics.fill(bx, by, bx + 9, by + 9, CHECK_ON);
			graphics.text(font, "✔", bx + 1, by + 1, 0xFFFFFFFF, false);
		} else {
			graphics.fill(bx, by, bx + 9, by + 9, CHECK_OFF);
			graphics.outline(bx, by, 9, 9, CHECK_BORDER);
		}
		int color;
		switch (service.status(language)) {
			case READY -> color = wanted ? DOT_READY : Paint.fade(DOT_READY, 0.35f);
			case LOADING -> color = Paint.fade(DOT_LOADING, 0.55f + 0.45f * (float) Math.abs(Math.sin(now / 250.0)));
			case FAILED -> color = DOT_FAILED;
			default -> {
				if (!wanted) {
					return;
				}
				color = DOT_PENDING;
			}
		}
		int x = button.getX() + button.getWidth() - 9;
		int y = button.getY() + (button.getHeight() - 4) / 2;
		graphics.fill(x, y, x + 4, y + 4, color);
	}

	/** Whether "Open word list" can open a folder on this Minecraft version. */
	public static boolean canOpenConfigFolder() {
		return FolderOpener.available();
	}

	private void openConfigFolder() {
		Path dir = FabricLoader.getInstance().getConfigDir().resolve("chatspellcheck");
		try {
			java.nio.file.Files.createDirectories(dir);
			FolderOpener.open(dir);
		} catch (Exception e) {
			LOG.warn("Could not open {}", dir, e);
		}
	}

	@Override
	public void onClose() {
		Settings s = service.settings();
		boolean languagesChanged = !selected.equals(s.languages);
		s.enabled = enabled;
		s.checkMessageCommands = checkCommands;
		s.autocorrect = autocorrect;
		if (languagesChanged) {
			service.setLanguages(selected); // saves settings once loaded
		} else {
			service.saveSettingsQuietly();
			service.refresh();
		}
		if (parent != null) {
			minecraft.gui.setScreen(parent);
		} else {
			super.onClose();
		}
	}

	/**
	 * Opens a folder in the system file manager. 26.3 moved this from Util.getPlatform() to
	 * Blaze3D; both builds ship unobfuscated, so the method is looked up by name.
	 */
	static final class FolderOpener {
		private static final Method METHOD = find();
		private static final Object TARGET = target();

		private FolderOpener() {
		}

		static boolean available() {
			return METHOD != null;
		}

		static void open(Path dir) throws ReflectiveOperationException {
			if (METHOD != null) {
				METHOD.invoke(TARGET, dir);
			}
		}

		private static Method find() {
			try {
				return Class.forName("com.mojang.blaze3d.Blaze3D").getMethod("openPath", Path.class); // 26.3
			} catch (ReflectiveOperationException | LinkageError ignored) {
			}
			try {
				return Util.getPlatform().getClass().getMethod("openPath", Path.class); // 26.2
			} catch (ReflectiveOperationException | LinkageError ignored) {
				return null;
			}
		}

		private static Object target() {
			return METHOD == null || java.lang.reflect.Modifier.isStatic(METHOD.getModifiers()) ? null : Util.getPlatform();
		}
	}
}
