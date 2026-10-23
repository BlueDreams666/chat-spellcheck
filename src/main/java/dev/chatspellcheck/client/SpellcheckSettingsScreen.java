package dev.chatspellcheck.client;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import dev.chatspellcheck.core.Language;
import dev.chatspellcheck.core.Settings;
import dev.chatspellcheck.core.SpellService;
import dev.chatspellcheck.core.Stats;
import dev.chatspellcheck.ui.PopoverLayout;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The settings screen, in the mod's pink style: a live preview, languages, checking options,
 * underline look, and a stats card, in rounded sections that scroll when they don't fit (large
 * GUI scales, small windows). Changes apply when the screen closes; the preview shows them
 * straight away. Each language shows whether its dictionary is loaded.
 */
public final class SpellcheckSettingsScreen extends Screen {
	private static final Logger LOG = LoggerFactory.getLogger("ChatSpellcheck");
	private static final Identifier ICON = Identifier.fromNamespaceAndPath("chatspellcheck", "icon.png");

	private static final int MAX_CONTENT_WIDTH = 400;
	private static final int HEADER = 38;
	private static final int FOOTER = 30;
	private static final int ROW = 18;
	private static final int GAP = 4;
	private static final int PAD = 6;
	private static final int SECTION_TITLE = 12;
	private static final int SECTION_GAP = 8;
	private static final int SCROLL_STEP = 18;

	private static final int CARD = 0xC8121014;
	private static final int CARD_FRAME = 0xFF3C343A;
	private static final int TEXT_TITLE = 0xFFFFFFFF;
	private static final int TEXT_SECTION = 0xFFE6DCE0;
	private static final int TEXT_HINT = 0xFFBDB3B8;
	private static final int TEXT_DIM = 0xFF8A8287;
	private static final int DOT_READY = 0xFF5BD47A;
	private static final int DOT_LOADING = 0xFFFFC266;
	private static final int DOT_FAILED = 0xFFFF5C5C;
	private static final int DOT_PENDING = 0xFF9AA0B0;
	private static final int PREVIEW_BAR = 0x80000000;
	private static final int NOTICE_OK = 0xFF7BD88F;

	/** The preview line; "Zorblax" shows what Skip names does, "sooo" that stretched words are fine. */
	private static final String PREVIEW_BEFORE_NAME = "hey ";
	private static final String PREVIEW_NAME = "Zorblax";
	private static final String PREVIEW_MIDDLE = ", this mod is sooo ";
	private static final String PREVIEW_TYPO = "helpfull";
	private static final String PREVIEW_FIX = "helpful";
	private static final String PREVIEW_END = " ♥";

	private final SpellService service;
	/** Where Done/Escape returns to: the mod list when opened from Mod Menu, else the game. */
	private final Screen parent;
	private final List<Language> selected;
	private final Language gameLanguage;
	private boolean enabled;
	private boolean checkCommands;
	private boolean autocorrect;
	private boolean skipNames;
	private Settings.UnderlineStyle style;
	private Settings.UnderlineColor color;
	private boolean showTipsAgain;
	/** When the preview's look last changed, so its underline grows in again. */
	private long previewChangedAt = Util.getMillis();

	private final Map<Language, CuteButtons.LanguageChip> languageChips = new LinkedHashMap<>();
	private final CuteButtons.Viewport viewport = new CuteButtons.Viewport();
	/** Widgets in the scrolling area, with their y in unscrolled content coordinates. */
	private final List<Scrolled> scrolled = new ArrayList<>();
	private final List<Section> sections = new ArrayList<>();
	private int contentLeft;
	private int contentWidth;
	private int contentHeight;
	private int scroll;
	private int tooltipsVersion = -1;

	private record Scrolled(AbstractWidget widget, int y) {
	}

	private enum Kind { PREVIEW, LANGUAGES, CHECKING, LOOK, STATS, MORE }

	/** An on/off option; its label and tooltip are {@code chatspellcheck.settings.toggle.<key>} and {@code ...<key>.tooltip}. */
	private record ToggleSpec(String key, java.util.function.BooleanSupplier value, Runnable flip) {
	}

	/** @param y top of the section title, in content coordinates; @param height title plus card */
	private record Section(Kind kind, String icon, String titleKey, int y, int height) {
		int cardY() {
			return y + SECTION_TITLE;
		}

		int cardHeight() {
			return height - SECTION_TITLE;
		}
	}

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
		this.skipNames = s.skipNames;
		this.style = s.underlineStyle;
		this.color = s.underlineColor;
		net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
		this.gameLanguage = mc == null || mc.options == null ? null : Language.forGameLanguage(mc.options.languageCode).orElse(null);
	}

	// ---------------------------------------------------------------- layout

	@Override
	protected void init() {
		languageChips.clear();
		scrolled.clear();
		sections.clear();
		contentWidth = Math.min(MAX_CONTENT_WIDTH, width - 20);
		contentLeft = (width - contentWidth) / 2;
		int inner = contentWidth - 2 * PAD;
		int y = 0;

		y = section(Kind.PREVIEW, "✎", "chatspellcheck.settings.section.preview", y, PAD + 14 + 4 + 9 + PAD);

		Language[] all = Language.values();
		int columns = inner >= 3 * 110 + 2 * GAP ? 3 : inner >= 2 * 100 + GAP ? 2 : 1;
		int chipWidth = (inner - (columns - 1) * GAP) / columns;
		int rows = (all.length + columns - 1) / columns;
		int top = y + SECTION_TITLE + PAD;
		for (int i = 0; i < all.length; i++) {
			Language language = all[i];
			CuteButtons.LanguageChip chip = new CuteButtons.LanguageChip(chipWidth, ROW, Component.literal(menuName(language)),
				() -> selected.contains(language), () -> dotColor(language), language == gameLanguage, b -> {
					toggle(language);
					b.setTooltip(languageTooltip(language));
				}, viewport);
			chip.setTooltip(languageTooltip(language));
			place(chip, contentLeft + PAD + (i % columns) * (chipWidth + GAP), top + (i / columns) * (ROW + GAP));
			languageChips.put(language, chip);
		}
		y = section(Kind.LANGUAGES, "✿", "chatspellcheck.settings.languages", y, PAD + rows * (ROW + GAP) - GAP + 4 + 9 + PAD);

		int toggleColumns = inner >= 2 * 140 + GAP ? 2 : 1;
		int toggleWidth = (inner - (toggleColumns - 1) * GAP) / toggleColumns;
		top = y + SECTION_TITLE + PAD;
		List<ToggleSpec> toggles = List.of(
			new ToggleSpec("enabled", () -> enabled, () -> enabled = !enabled),
			new ToggleSpec("autocorrect", () -> autocorrect, () -> autocorrect = !autocorrect),
			new ToggleSpec("names", () -> skipNames, () -> skipNames = !skipNames),
			new ToggleSpec("commands", () -> checkCommands, () -> checkCommands = !checkCommands));
		for (int i = 0; i < toggles.size(); i++) {
			ToggleSpec spec = toggles.get(i);
			CuteButtons.Toggle toggle = new CuteButtons.Toggle(toggleWidth, ROW, Component.translatable("chatspellcheck.settings.toggle." + spec.key()),
				spec.value(), b -> {
					spec.flip().run();
					previewChangedAt = Util.getMillis();
				}, viewport);
			toggle.setTooltip(Tooltip.create(Component.translatable("chatspellcheck.settings." + spec.key() + ".tooltip")));
			place(toggle, contentLeft + PAD + (i % toggleColumns) * (toggleWidth + GAP), top + (i / toggleColumns) * (ROW + GAP));
		}
		int toggleRows = (toggles.size() + toggleColumns - 1) / toggleColumns;
		y = section(Kind.CHECKING, "✔", "chatspellcheck.settings.section.checking", y, PAD + toggleRows * (ROW + GAP) - GAP + PAD);

		// Underline style and colour: a label then a row of choices, or the label above the row when narrow.
		boolean labelsAbove = inner < 300;
		int labelWidth = labelsAbove ? 0 : 58;
		int rowStep = labelsAbove ? 11 + ROW + GAP : ROW + GAP;
		top = y + SECTION_TITLE + PAD + (labelsAbove ? 11 : 0);
		Settings.UnderlineStyle[] styles = Settings.UnderlineStyle.values();
		int styleWidth = (inner - labelWidth - (styles.length - 1) * GAP) / styles.length;
		for (int i = 0; i < styles.length; i++) {
			Settings.UnderlineStyle s = styles[i];
			place(new CuteButtons.Choice(styleWidth, ROW, Component.translatable("chatspellcheck.settings.style." + s.name().toLowerCase(Locale.ROOT)), 0,
				() -> style == s, b -> {
					style = s;
					previewChangedAt = Util.getMillis();
				}, viewport), contentLeft + PAD + labelWidth + i * (styleWidth + GAP), top);
		}
		Settings.UnderlineColor[] colors = Settings.UnderlineColor.values();
		int colorWidth = (inner - labelWidth - (colors.length - 1) * GAP) / colors.length;
		for (int i = 0; i < colors.length; i++) {
			Settings.UnderlineColor c = colors[i];
			CuteButtons.Choice choice = new CuteButtons.Choice(colorWidth, ROW, Component.translatable("chatspellcheck.settings.color." + c.name().toLowerCase(Locale.ROOT)), c.argb,
				() -> color == c, b -> {
					color = c;
					previewChangedAt = Util.getMillis();
				}, viewport);
			if (c == Settings.UnderlineColor.BLUE) {
				choice.setTooltip(Tooltip.create(Component.translatable("chatspellcheck.settings.color.blue.tooltip")));
			}
			place(choice, contentLeft + PAD + labelWidth + i * (colorWidth + GAP), top + rowStep);
		}
		y = section(Kind.LOOK, "✧", "chatspellcheck.settings.section.look", y, PAD + (labelsAbove ? 11 : 0) + rowStep + ROW + PAD);

		y = section(Kind.STATS, "♥", "chatspellcheck.settings.section.stats", y, PAD + 28 + 8 + 10 + 30 + 10 + 4 + 9 + PAD);

		boolean stacked = inner < 2 * 130 + GAP;
		int actionWidth = stacked ? inner : (inner - GAP) / 2;
		top = y + SECTION_TITLE + PAD;
		CuteButtons.Action wordList = new CuteButtons.Action(actionWidth, ROW + 2, Component.translatable("chatspellcheck.settings.word_list"), false,
			b -> openConfigFolder(), viewport);
		wordList.setTooltip(Tooltip.create(Component.translatable("chatspellcheck.settings.word_list.tooltip")));
		wordList.active = FolderOpener.available();
		place(wordList, contentLeft + PAD, top);
		CuteButtons.Action tips = new CuteButtons.Action(actionWidth, ROW + 2, tipsLabel(), false, b -> {
			showTipsAgain = !showTipsAgain;
			b.setMessage(tipsLabel());
		}, viewport);
		tips.setTooltip(Tooltip.create(Component.translatable("chatspellcheck.settings.tips.tooltip")));
		place(tips, stacked ? contentLeft + PAD : contentLeft + PAD + actionWidth + GAP, stacked ? top + ROW + 2 + GAP : top);
		y = section(Kind.MORE, "☁", "chatspellcheck.settings.section.more", y, PAD + (stacked ? 2 * (ROW + 2) + GAP : ROW + 2) + PAD);
		contentHeight = y - SECTION_GAP;

		int doneWidth = Math.min(200, contentWidth);
		CuteButtons.Action done = new CuteButtons.Action(doneWidth, 20, Component.translatable("gui.done"), true, b -> onClose(), CuteButtons.Viewport.ALL);
		done.setPosition((width - doneWidth) / 2, height - FOOTER + 5);
		addRenderableWidget(done);

		viewport.top = HEADER;
		viewport.bottom = height - FOOTER;
		setScroll(scroll); // keeps the position across a resize, within the new limits
	}

	/** Adds a widget to the scrolling area at {@code y} in content coordinates. */
	private void place(AbstractWidget widget, int x, int y) {
		widget.setX(x);
		scrolled.add(new Scrolled(widget, y));
		addRenderableWidget(widget);
	}

	private int section(Kind kind, String icon, String titleKey, int y, int cardHeight) {
		sections.add(new Section(kind, icon, titleKey, y, SECTION_TITLE + cardHeight));
		return y + SECTION_TITLE + cardHeight + SECTION_GAP;
	}

	private int maxScroll() {
		return Math.max(0, contentHeight - (viewport.bottom - viewport.top) + 4);
	}

	private void setScroll(int value) {
		scroll = Math.clamp(value, 0, maxScroll());
		int origin = viewport.top + 2 - scroll;
		for (Scrolled s : scrolled) {
			AbstractWidget w = s.widget();
			w.setY(origin + s.y());
			w.visible = w.getBottom() > viewport.top && w.getY() < viewport.bottom;
		}
	}

	/** Top of the content, in screen coordinates. */
	private int originY() {
		return viewport.top + 2 - scroll;
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		if (maxScroll() > 0 && scrollY != 0) {
			setScroll(scroll - (int) Math.round(scrollY * SCROLL_STEP));
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
	}

	/** Keyboard focus (Tab, arrows) scrolls the focused widget into view. */
	@Override
	public void setFocused(GuiEventListener focused) {
		super.setFocused(focused);
		for (Scrolled s : scrolled) {
			if (s.widget() == focused) {
				int area = viewport.bottom - viewport.top;
				int top = s.y();
				int bottom = top + s.widget().getHeight();
				if (top < scroll + 4) {
					setScroll(top - 4);
				} else if (bottom > scroll + area - 8) {
					setScroll(bottom - area + 8);
				}
			}
		}
	}

	/** For tests: scrolls the language's button into view and returns it. */
	public AbstractWidget showLanguage(Language language) {
		AbstractWidget chip = languageChips.get(language);
		setFocused(chip);
		return chip;
	}

	/** For tests: the part of {@code widget} that can be seen, or null if none. */
	public net.minecraft.client.gui.navigation.ScreenRectangle shownRect(AbstractWidget widget) {
		if (!widget.visible) {
			return null;
		}
		for (Scrolled s : scrolled) {
			if (s.widget() == widget) {
				return widget.getRectangle().intersection(new net.minecraft.client.gui.navigation.ScreenRectangle(0, viewport.top, width, viewport.bottom - viewport.top));
			}
		}
		return widget.getRectangle();
	}

	/** For tests: how far the content can scroll (0 if it all fits). */
	public int scrollRange() {
		return maxScroll();
	}

	/** For tests: scrolls to {@code fraction} (0 = top, 1 = bottom) of the way down. */
	public void scrollTo(double fraction) {
		setScroll((int) Math.round(maxScroll() * fraction));
	}

	// ---------------------------------------------------------------- state

	private void toggle(Language language) {
		if (selected.contains(language)) {
			if (selected.size() > 1) { // keep at least one dictionary
				selected.remove(language);
			}
		} else {
			selected.add(language);
		}
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
		if (language == gameLanguage) {
			text.append(Component.literal("\n")).append(Component.translatable("chatspellcheck.settings.game_language").withColor(Paint.GOLD & 0xFFFFFF));
		}
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

	/** The small dot at the right of each language: loaded, loading (pulsing), failed, or pending. */
	private int dotColor(Language language) {
		boolean wanted = selected.contains(language);
		return switch (service.status(language)) {
			case READY -> wanted ? DOT_READY : Paint.fade(DOT_READY, 0.35f);
			case LOADING -> Paint.fade(DOT_LOADING, 0.55f + 0.45f * (float) Math.abs(Math.sin(Util.getMillis() / 250.0)));
			case FAILED -> DOT_FAILED;
			case INACTIVE -> wanted ? DOT_PENDING : 0;
		};
	}

	private Component tipsLabel() {
		return Component.translatable(showTipsAgain ? "chatspellcheck.settings.tips.on" : "chatspellcheck.settings.tips");
	}

	// ---------------------------------------------------------------- drawing

	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		super.extractBackground(graphics, mouseX, mouseY, a);
		// A gentle shade over the blurred game, then the section cards (clipped to the scrolling area).
		graphics.fillGradient(0, 0, width, height, 0x30000000, 0x50000000);
		graphics.enableScissor(0, viewport.top, width, viewport.bottom);
		int origin = originY();
		for (Section s : sections) {
			int titleY = origin + s.y();
			if (titleY > viewport.bottom || titleY + s.height() < viewport.top) {
				continue;
			}
			graphics.text(font, s.icon(), contentLeft + 2, titleY + 1, Paint.PINK, false);
			graphics.text(font, Component.translatable(s.titleKey()).getString().toUpperCase(Locale.ROOT), contentLeft + 13, titleY + 1, TEXT_SECTION, false);
			int cardY = origin + s.cardY();
			Paint.card(graphics, contentLeft, cardY, contentWidth, s.cardHeight(), CARD, CARD_FRAME);
			switch (s.kind()) {
				case PREVIEW -> drawPreview(graphics, cardY);
				case LANGUAGES -> drawLanguagesHint(graphics, cardY + s.cardHeight());
				case LOOK -> drawLookLabels(graphics, cardY);
				case STATS -> drawStats(graphics, cardY);
				default -> {
				}
			}
		}
		graphics.disableScissor();
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		if (service.lexiconVersion() != tooltipsVersion) {
			// A dictionary finished loading (or failed) while the screen is open.
			tooltipsVersion = service.lexiconVersion();
			languageChips.forEach((language, chip) -> chip.setTooltip(languageTooltip(language)));
		}
		super.extractRenderState(graphics, mouseX, mouseY, a);
		graphics.nextStratum();
		drawHeader(graphics);
		drawScrollbar(graphics);
	}

	/** The icon and title, with a pink rule and a short hint. */
	private void drawHeader(GuiGraphicsExtractor graphics) {
		Component bigTitle = title.copy().withStyle(ChatFormatting.BOLD);
		int titleWidth = font.width(bigTitle);
		int groupWidth = 16 + 5 + titleWidth;
		int x = (width - groupWidth) / 2;
		graphics.blit(RenderPipelines.GUI_TEXTURED, ICON, x, 5, 0f, 0f, 16, 16, 128, 128, 128, 128);
		graphics.text(font, bigTitle, x + 21, 9, TEXT_TITLE, true);
		graphics.text(font, "♥", x + 21 + titleWidth + 4, 9, Paint.PINK, true);
		int rule = groupWidth / 2 + 6;
		graphics.fill(width / 2 - rule, 23, width / 2 + rule, 24, Paint.PINK_DEEP);
		String hint = PopoverLayout.ellipsize(Component.translatable("chatspellcheck.settings.hint").getString(), width - 16, font::width);
		graphics.centeredText(font, hint, width / 2, 27, TEXT_HINT);
	}

	private void drawScrollbar(GuiGraphicsExtractor graphics) {
		int max = maxScroll();
		if (max == 0) {
			return;
		}
		int area = viewport.bottom - viewport.top;
		int thumb = Math.max(12, area * area / (area + max));
		int y = viewport.top + (area - thumb) * scroll / max;
		int x = Math.min(width - 4, contentLeft + contentWidth + 4);
		Paint.rounded(graphics, x, viewport.top, 3, area, 0x40FFFFFF);
		Paint.rounded(graphics, x, y, 3, thumb, Paint.PINK);
	}

	/**
	 * A pretend chat box showing the current choices: the typo underlined in the chosen style
	 * and colour (growing in after each change), the name underlined only when Skip names is
	 * off, the typo already fixed when autocorrect is on, and nothing when spellcheck is off.
	 */
	private void drawPreview(GuiGraphicsExtractor graphics, int cardY) {
		int x = contentLeft + PAD;
		int y = cardY + PAD;
		int barWidth = contentWidth - 2 * PAD;
		graphics.fill(x, y, x + barWidth, y + 14, PREVIEW_BAR);
		boolean fixed = enabled && autocorrect;
		String typo = fixed ? PREVIEW_FIX : PREVIEW_TYPO;
		int tx = x + 4;
		int ty = y + 3;
		int nameX = tx + font.width(PREVIEW_BEFORE_NAME);
		int typoX = nameX + font.width(PREVIEW_NAME + PREVIEW_MIDDLE);
		String line = PREVIEW_BEFORE_NAME + PREVIEW_NAME + PREVIEW_MIDDLE + typo + PREVIEW_END;
		graphics.enableScissor(x, y, x + barWidth, y + 14);
		graphics.text(font, line, tx, ty, 0xFFFFFFFF, true);
		long now = Util.getMillis();
		float t = Math.min(1f, (now - previewChangedAt) / 300f);
		float progress = 1 - (1 - t) * (1 - t);
		if (enabled && !fixed) {
			Paint.underline(graphics, typoX, typoX + font.width(PREVIEW_TYPO), ty + 9, style, color.argb, progress);
		}
		if (enabled && !skipNames) {
			Paint.underline(graphics, nameX, nameX + font.width(PREVIEW_NAME), ty + 9, style, color.argb, progress);
		}
		// A blinking caret at the end, like the real chat box.
		if ((now / 500) % 2 == 0) {
			int caretX = tx + font.width(line) + 1;
			graphics.fill(caretX, ty - 1, caretX + 1, ty + 9, 0xFFD0D0D0);
		}
		graphics.disableScissor();

		Component caption;
		int captionColor;
		if (!enabled) {
			caption = Component.translatable("chatspellcheck.settings.preview.off");
			captionColor = TEXT_DIM;
		} else if (fixed) {
			caption = Component.translatable("chatspellcheck.settings.preview.fixed", PREVIEW_TYPO, PREVIEW_FIX);
			captionColor = NOTICE_OK;
		} else {
			String shortcut = Util.getPlatform() == Util.OS.OSX ? "Cmd+." : "Ctrl+.";
			caption = Component.translatable("chatspellcheck.settings.preview.hint", shortcut);
			captionColor = TEXT_HINT;
		}
		String shown = PopoverLayout.ellipsize(caption.getString(), barWidth, font::width);
		graphics.text(font, shown, x, y + 18, captionColor, false);
	}

	private void drawLanguagesHint(GuiGraphicsExtractor graphics, int cardBottom) {
		int count = selected.size();
		String text = (count == 1 ? Component.translatable("chatspellcheck.settings.summary.languages.one")
			: Component.translatable("chatspellcheck.settings.summary.languages.many", count)).getString()
			+ " · " + Component.translatable("chatspellcheck.settings.languages.hint").getString();
		text = PopoverLayout.ellipsize(text, contentWidth - 2 * PAD, font::width);
		graphics.text(font, text, contentLeft + PAD, cardBottom - PAD - 9, TEXT_DIM, false);
	}

	private void drawLookLabels(GuiGraphicsExtractor graphics, int cardY) {
		boolean above = contentWidth - 2 * PAD < 300;
		int x = contentLeft + PAD;
		int y = cardY + PAD;
		String underline = Component.translatable("chatspellcheck.settings.look.style").getString();
		String colour = Component.translatable("chatspellcheck.settings.look.color").getString();
		if (above) {
			graphics.text(font, underline, x, y, TEXT_HINT, false);
			graphics.text(font, colour, x, y + 11 + ROW + GAP, TEXT_HINT, false);
		} else {
			graphics.text(font, underline, x, y + 5, TEXT_HINT, false);
			graphics.text(font, colour, x, y + ROW + GAP + 5, TEXT_HINT, false);
		}
	}

	/**
	 * Three number tiles (words checked, typos fixed, clean-message streak), a bar chart of
	 * fixes over the last week with today in bright pink, and this session's numbers.
	 */
	private void drawStats(GuiGraphicsExtractor graphics, int cardY) {
		Stats.Snapshot s = service.stats().snapshot();
		int x = contentLeft + PAD;
		int y = cardY + PAD;
		int inner = contentWidth - 2 * PAD;
		int tileWidth = (inner - 2 * GAP) / 3;
		String[][] tiles = {
			{compact(s.wordsChecked()), Component.translatable("chatspellcheck.stats.words").getString()},
			{compact(s.typosFixed()), Component.translatable("chatspellcheck.stats.fixed").getString()},
			{compact(s.streak()), Component.translatable("chatspellcheck.stats.streak", compact(s.bestStreak())).getString()},
		};
		for (int i = 0; i < tiles.length; i++) {
			int tx = x + i * (tileWidth + GAP);
			Paint.rounded(graphics, tx, y, tileWidth, 28, 0x1CFFFFFF);
			graphics.centeredText(font, Component.literal(tiles[i][0]).withStyle(ChatFormatting.BOLD), tx + tileWidth / 2, y + 4, Paint.PINK_LIGHT);
			String label = PopoverLayout.ellipsize(tiles[i][1], tileWidth - 4, font::width);
			graphics.centeredText(font, label, tx + tileWidth / 2, y + 16, TEXT_HINT);
		}

		y += 28 + 8;
		graphics.text(font, Component.translatable("chatspellcheck.stats.week").getString(), x, y, TEXT_HINT, false);
		y += 10;
		int[] days = s.fixesByDay();
		int max = 1;
		for (int d : days) {
			max = Math.max(max, d);
		}
		int chartHeight = 30;
		int slot = inner / days.length;
		int barWidth = Math.max(4, Math.min(22, slot - 6));
		LocalDate first = LocalDate.now().minusDays(days.length - 1);
		Locale locale = gameLocale();
		for (int i = 0; i < days.length; i++) {
			int cx = x + i * slot + slot / 2;
			int barHeight = days[i] == 0 ? 1 : Math.max(3, Math.round((chartHeight - 10) * days[i] / (float) max));
			int bottom = y + chartHeight;
			boolean today = i == days.length - 1;
			int barColor = days[i] == 0 ? 0x40FFFFFF : today ? Paint.PINK : Paint.fade(Paint.PINK_DEEP, 0.8f);
			Paint.rounded(graphics, cx - barWidth / 2, bottom - barHeight, barWidth, barHeight, barColor);
			if (days[i] > 0) {
				graphics.centeredText(font, Integer.toString(days[i]), cx, bottom - barHeight - 9, today ? Paint.PINK_LIGHT : TEXT_DIM);
			}
			String day = first.plusDays(i).getDayOfWeek().getDisplayName(TextStyle.NARROW, locale);
			graphics.centeredText(font, day, cx, bottom + 2, today ? Paint.PINK_LIGHT : TEXT_DIM);
		}
		y += chartHeight + 10 + 4;
		Component session = s.wordsChecked() == 0 && s.typosFixed() == 0
			? Component.translatable("chatspellcheck.stats.empty")
			: Component.translatable("chatspellcheck.stats.session", compact(s.sessionWords()), compact(s.sessionFixes()));
		graphics.text(font, PopoverLayout.ellipsize(session.getString(), inner, font::width), x, y, TEXT_DIM, false);
	}

	/** 999, 1,234, 12.3k, 1.2M. */
	static String compact(long n) {
		if (n < 10_000) {
			return String.format(Locale.ROOT, "%,d", n);
		}
		if (n < 1_000_000) {
			return String.format(Locale.ROOT, "%.1fk", n / 1000.0);
		}
		return String.format(Locale.ROOT, "%.1fM", n / 1_000_000.0);
	}

	private Locale gameLocale() {
		String code = minecraft == null || minecraft.options == null ? "en_us" : minecraft.options.languageCode;
		return Locale.forLanguageTag(code.replace('_', '-'));
	}

	// ---------------------------------------------------------------- closing

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
		s.skipNames = skipNames;
		s.underlineStyle = style;
		s.underlineColor = color;
		if (showTipsAgain) {
			s.welcomeTips = Settings.WELCOME_TIPS;
		}
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
