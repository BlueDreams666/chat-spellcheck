package dev.chatspellcheck.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import dev.chatspellcheck.core.CheckResult;
import dev.chatspellcheck.core.Misspelling;
import dev.chatspellcheck.core.Settings;
import dev.chatspellcheck.core.SpellService;
import dev.chatspellcheck.core.WordReplacement;
import dev.chatspellcheck.mixin.EditBoxAccessor;
import dev.chatspellcheck.ui.PopoverLayout;
import dev.chatspellcheck.ui.PopoverModel;
import dev.chatspellcheck.ui.Rect;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;

/**
 * Spellcheck UI for one chat screen: schedules checks, draws underlines, and runs the
 * suggestion popover. UI updates run on the render thread; checking and suggestion lookups
 * run on separate background threads in {@link SpellService}.
 */
public final class ChatSpellcheckOverlay {
	/** Wait this long after the last keystroke before checking. */
	private static final long DEBOUNCE_MS = 120;
	/** Don't underline the word under the caret while it is still being typed. */
	private static final long TYPING_GRACE_MS = 900;
	private static final long NAME_REFRESH_MS = 2_000;

	/** New underlines grow in from the left over this long. */
	private static final long UNDERLINE_GROW_MS = 140;
	/** The little sparkle over a word fixed from the popover. */
	private static final long SPARKLE_MS = 600;
	private static final int SPARKLE_PARTICLES = 8;

	private static final int PANEL_BG = 0xFA0B0B0F;
	private static final int PANEL_FRAME = 0xFF2B2B34;
	private static final int DIVIDER = 0xFF23232B;
	private static final int ROW_HOVER = 0x1CFFFFFF;
	private static final int ROW_SELECTED = Paint.ACCENT;
	private static final int ROW_SELECTED_EDGE = 0xFFFFFFFF;
	private static final int TEXT_BEST = 0xFFFFFFFF;
	private static final int TEXT_SUGGESTION = 0xFFCFCFD8;
	private static final int TEXT_ACTION = 0xFF9C9CA8;
	private static final int TEXT_DISABLED = 0xFF6A6A76;
	private static final int TEXT_HINT = 0xFF5E5E6A;
	private static final int TEXT_WORD = 0xFFFF5C74;
	private static final int ICON_IGNORE = 0xFF7C7C88;
	private static final int ICON_ADD = Paint.ACCENT;
	private static final int NOTICE_OK = 0xFF7BD88F;
	private static final int NOTICE_WARN = 0xFFFFC266;

	private static final int ROW_HEIGHT = 12;
	private static final int PAD_X = 5;
	private static final int PAD_Y = 4;
	/** Column left of the row text for the Ignore/Add icons and the selection accent. */
	private static final int GUTTER = 9;
	private static final int HEADER_HEIGHT = 14;
	private static final int FOOTER_HEIGHT = 13;
	private static final int SEPARATOR = 5;
	private static final int MIN_WIDTH = 84;
	private static final long FADE_IN_MS = 90;
	private static final long NOTICE_MS = 1_800;
	/** A finished word is only auto-corrected if its check arrives within this time. */
	private static final long AUTOCORRECT_WINDOW_MS = 4_000;

	private final SpellService service;
	private EditBox input;
	private EditBoxAccessor box;
	private final Minecraft minecraft = Minecraft.getInstance();
	private final AtomicReference<CheckResult> latest = new AtomicReference<>(CheckResult.empty());
	private final PopoverModel popover = new PopoverModel();

	private String lastSeenText;
	private long lastEditAt;
	private String lastSubmittedText;
	private int lastSubmittedLexicon = -1;
	private Set<String> playerNames = Set.of();
	private long namesRefreshedAt = Long.MIN_VALUE;

	/** Current-frame state, rebuilt in {@link #extract}. */
	private List<Placed> placed = List.of();
	private List<Row> rows = List.of();
	private Rect popoverRect;
	private Rect keepZone;

	/**
	 * Per-frame work is cached and only redone when its inputs change: remapping the latest
	 * result to the current text, measuring underline positions (font widths of substrings),
	 * and building the popover rows (translations, widths).
	 */
	private CheckResult remappedFrom;
	private String remappedText;
	private List<Misspelling> remapped = List.of();
	private LayoutKey layoutKey;
	private List<Placed> layout = List.of();
	private RowsKey rowsKey;
	private int rowsWidth;
	private String header = "";

	/** When each underline (by {@link #occurrenceKeys}) was first drawn, for the grow-in. */
	private final java.util.Map<String, Long> underlineShownAt = new java.util.HashMap<>();
	private List<Placed> keyedLayout;
	private List<String> underlineKeys = List.of();
	/** Where the last fixed word is, for the sparkle; null when none is playing. */
	private Rect sparkle;
	private long sparkleAt;

	/** When the popover last opened (or switched words), for the fade-in. */
	private String shownWord;
	private long shownAt;
	/** The player moved the keyboard highlight themselves since the popover opened. */
	private boolean keyboardMoved;

	/** Words just finished with a space or punctuation, waiting for their check (autocorrect). */
	private final List<Pending> pending = new ArrayList<>();
	/** When the newest result's check was submitted, to know whether it already saw a word. */
	private volatile long latestResultSubmittedAt = Long.MIN_VALUE;
	/** The last automatic correction, undone by Backspace right after it. */
	private Applied lastAutocorrect;

	/**
	 * @param word    the finished word in {@code text}
	 * @param fix     the correction lookup, once the word turned out to be misspelled
	 */
	private record Pending(Misspelling word, String text, long typedAt, int lexicon, CompletableFuture<Optional<String>> fix) {
	}

	/** @param edit the correction as applied; undoing it applies the reverse edit */
	private record Applied(String before, String after, int cursorAfter, WordReplacement.Edit edit) {
	}

	/** Short confirmation after Ignore / Add to dictionary, drawn above the word. */
	private volatile String notice;
	private volatile int noticeColor;
	private volatile long noticeUntil;
	private int noticeX;
	private int noticeY;

	/** The first few times chat opens, a tip explains how to use the mod (see {@link Settings#welcomeTips}). */
	private boolean showWelcome;
	private final long openedAt = Util.getMillis();

	private record Placed(Misspelling span, Rect rect, boolean beingTyped) {
	}

	private record LayoutKey(List<Misspelling> spans, String text, int displayPos, int textX, int textY, int innerWidth, Font font) {
	}

	private record RowsKey(String word, List<String> suggestions, Object translations, Font font, int maxWidth, boolean keyboard) {
	}

	private enum RowKind { SUGGESTION, IGNORE, ADD, INFO }

	/** @param text what the row stands for (the full suggestion); @param label what is drawn, possibly shortened */
	private record Row(RowKind kind, String text, String label) {
		boolean selectable() {
			return kind != RowKind.INFO;
		}
	}

	public ChatSpellcheckOverlay(SpellService service, EditBox input) {
		this.service = service;
		rebind(input);
		Settings settings = service.settings();
		if (settings.enabled && settings.welcomeTips > 0) {
			showWelcome = true;
			settings.welcomeTips--;
			service.saveSettingsQuietly();
		}
	}

	/** The player fixed a typo, so they know how it works: no more welcome tips. */
	private void welcomeDone() {
		showWelcome = false;
		if (service.settings().welcomeTips > 0) {
			service.settings().welcomeTips = 0;
			service.saveSettingsQuietly();
		}
	}

	/** The message in the box is being sent: count its words, and whether it still had typos. */
	public void messageSent(String message) {
		if (!service.settings().enabled) {
			return;
		}
		String text = input.getValue();
		service.stats().messageSent(service.checkableWords(text), remapped(text).size());
	}

	/** Chat closed. */
	public void closed() {
		service.saveStatsQuietly();
	}

	/**
	 * ChatScreen.init() runs again on resize and GUI-scale changes and creates a new EditBox
	 * with the same text. Keep the check result and any open popover (it is re-anchored next
	 * frame), but forget last frame's geometry so no click lands on a stale rectangle.
	 */
	public void rebind(EditBox input) {
		if (this.input != null) {
			((ChatInputHooks) this.input).chatspellcheck$setTypingListener(null);
			((ChatInputHooks) this.input).chatspellcheck$setDisplay(null);
		}
		this.input = input;
		this.box = (EditBoxAccessor) input;
		((ChatInputHooks) input).chatspellcheck$setTypingListener(this::onCharTyped);
		// Right-to-left words are shown in reading order (display only; the text is unchanged).
		((ChatInputHooks) input).chatspellcheck$setDisplay(new RtlInputDisplay(input));
		placed = List.of();
		popoverRect = null;
		keepZone = null;
		layoutKey = null;
		rowsKey = null;
	}

	// ---------------------------------------------------------------- frame

	public void extract(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean commandSuggestionsVisible, boolean dragging) {
		long now = Util.getMillis();
		String text = input.getValue();
		trackEdits(text, now);
		scheduleCheck(text, now);
		if (service.settings().enabled && service.settings().autocorrect) {
			text = autocorrect(text, now);
		} else {
			pending.clear();
		}

		boolean composing = box.chatspellcheck$preeditOverlay() != null;
		if (!service.settings().enabled || composing) {
			// IME composition draws its own overlay at the caret; stay out of its way entirely.
			popover.close();
			placed = List.of();
			return;
		}

		placed = place(remapped(text), text, now);
		drawUnderlines(graphics, now);
		drawSparkle(graphics, now);
		updatePopover(graphics, mouseX, mouseY, now, commandSuggestionsVisible, dragging);
		if (popoverRect == null) {
			drawNotice(graphics, now);
			if (showWelcome && notice == null && !commandSuggestionsVisible) {
				drawWelcome(graphics, now);
			}
		}
	}

	private void trackEdits(String text, long now) {
		if (text.equals(lastSeenText)) {
			return;
		}
		if (lastAutocorrect != null && !text.equals(lastAutocorrect.after())) {
			lastAutocorrect = null; // any other edit means the correction was accepted
		}
		if (lastSeenText != null) {
			lastEditAt = now; // the initial text (e.g. a draft) is checked immediately
			if (popover.isKeyboard()) {
				popover.close(); // typing while the keyboard popover is open means "never mind"
			}
		}
		lastSeenText = text;
	}

	private void scheduleCheck(String text, long now) {
		int lexicon = service.lexiconVersion();
		boolean stale = !text.equals(lastSubmittedText) || lexicon != lastSubmittedLexicon;
		if (!stale || now - lastEditAt < DEBOUNCE_MS) {
			return;
		}
		lastSubmittedText = text;
		lastSubmittedLexicon = lexicon;
		long submittedAt = now;
		service.submitCheck(text, playerNames(now), result -> {
			if (latest.accumulateAndGet(result, (old, neu) -> neu.generation() > old.generation() ? neu : old) == result) {
				latestResultSubmittedAt = submittedAt;
			}
		});
	}

	// ---------------------------------------------------------------- autocorrect

	/**
	 * Called after each typed character. A space or punctuation mark typed right after a word
	 * finishes that word: remember it, so it can be corrected once it has been checked. Pasted
	 * text, history recall and deletions never trigger autocorrect.
	 */
	private void onCharTyped() {
		if (!service.settings().enabled || !service.settings().autocorrect) {
			return;
		}
		String after = input.getValue();
		int i = input.getCursorPosition() - 1;
		if (i < 1 || i >= after.length() || !isWordEnd(after.charAt(i))) {
			return;
		}
		if (i + 1 < after.length() && isWordPart(after.charAt(i + 1))) {
			return; // a space typed inside a word splits it; the left half isn't a finished word
		}
		long now = Util.getMillis();
		int start = i;
		while (start > 0 && isWordPart(after.charAt(start - 1))) {
			start--;
		}
		if (i - start < 3 || after.startsWith("/")) {
			return;
		}
		String word = after.substring(start, i);
		// Capitalised words are only fixed at the start of a sentence; elsewhere they are
		// probably names ("Zorblax"), which must never be "corrected".
		if (Character.isUpperCase(word.charAt(0)) && !sentenceStart(after, start)) {
			return;
		}
		if (pending.size() >= 4) {
			pending.removeFirst();
		}
		pending.add(new Pending(new Misspelling(start, i, word), after, now, service.lexiconVersion(), null));
	}

	private static boolean isWordEnd(char c) {
		return Character.isWhitespace(c) || ".,!?;:)".indexOf(c) >= 0;
	}

	private static boolean isWordPart(char c) {
		return Character.isLetter(c) || c == '\'' || c == '’' || Character.getType(c) == Character.NON_SPACING_MARK;
	}

	private static boolean sentenceStart(String text, int index) {
		int i = index - 1;
		while (i >= 0 && Character.isWhitespace(text.charAt(i))) {
			i--;
		}
		return i < 0 || ".!?".indexOf(text.charAt(i)) >= 0;
	}

	/** Resolves pending finished words; returns the (possibly corrected) current text. */
	private String autocorrect(String text, long now) {
		for (int k = 0; k < pending.size(); k++) {
			Pending p = pending.get(k);
			if (p.lexicon() != service.lexiconVersion() || now - p.typedAt() > AUTOCORRECT_WINDOW_MS) {
				pending.remove(k--);
				continue;
			}
			Misspelling word = p.word();
			if (!text.equals(p.text())) {
				// Follow the word through later edits elsewhere; drop it if it was edited itself.
				List<Misspelling> moved = new CheckResult(p.text(), 0, List.of(word)).remapTo(text);
				if (moved.isEmpty()) {
					pending.remove(k--);
					continue;
				}
				word = moved.getFirst();
				p = new Pending(word, text, p.typedAt(), p.lexicon(), p.fix());
				pending.set(k, p);
			}
			if (p.fix() == null) {
				if (latestResultSubmittedAt >= p.typedAt()) {
					// The newest check saw the finished word: correct it only if it was flagged.
					if (remapped(text).contains(word)) {
						pending.set(k, new Pending(word, text, p.typedAt(), p.lexicon(), service.autocorrection(word.word())));
					} else {
						pending.remove(k--);
					}
				}
				continue;
			}
			if (!p.fix().isDone()) {
				continue;
			}
			pending.remove(k--);
			Optional<String> fix = p.fix().getNow(Optional.empty());
			if (fix.isPresent()) {
				text = applyAutocorrect(text, word, fix.get(), now);
			}
		}
		return text;
	}

	private String applyAutocorrect(String text, Misspelling word, String replacement, long now) {
		int cursor = input.getCursorPosition();
		boolean selecting = box.chatspellcheck$highlightPos() != cursor;
		if (selecting || (cursor > word.start() && cursor < word.end())) {
			return text; // the player is working on that word right now; leave it alone
		}
		Optional<WordReplacement.Edit> edit = WordReplacement.apply(text, word, replacement, box.chatspellcheck$maxLength());
		if (edit.isEmpty()) {
			return text;
		}
		String after = edit.get().text();
		showNotice(word, "chatspellcheck.notice.autocorrected", NOTICE_OK, replacement);
		service.stats().fixed(1);
		setValueKeepingCaret(edit.get());
		lastAutocorrect = new Applied(text, after, input.getCursorPosition(), edit.get());
		lastSeenText = after; // our own edit: not a finished word, not "typing"
		return after;
	}

	/** Backspace right after an automatic correction puts the original word back. */
	private boolean undoAutocorrect() {
		Applied a = lastAutocorrect;
		lastAutocorrect = null;
		if (a == null || !input.getValue().equals(a.after()) || input.getCursorPosition() != a.cursorAfter()) {
			return false;
		}
		WordReplacement.Edit applied = a.edit();
		Misspelling corrected = new Misspelling(applied.target().start(), applied.target().start() + applied.replacement().length(), applied.replacement());
		WordReplacement.apply(a.after(), corrected, applied.target().word(), Integer.MAX_VALUE).ifPresent(this::setValueKeepingCaret);
		lastSeenText = input.getValue();
		lastEditAt = Util.getMillis();
		service.declineAutocorrection(applied.target().word());
		service.stats().fixed(-1);
		notice = null;
		return true;
	}

	private Set<String> playerNames(long now) {
		if (now - namesRefreshedAt < NAME_REFRESH_MS) {
			return playerNames;
		}
		namesRefreshedAt = now;
		ClientPacketListener connection = minecraft.getConnection();
		if (connection == null) {
			return playerNames = Set.of();
		}
		Set<String> names = new java.util.HashSet<>();
		for (PlayerInfo info : connection.getOnlinePlayers()) {
			names.add(info.getProfile().name().toLowerCase(Locale.ROOT));
		}
		return playerNames = Set.copyOf(names);
	}

	/** The latest result's spans for {@code text}; recomputed only when either changes. */
	private List<Misspelling> remapped(String text) {
		CheckResult result = latest.get();
		if (result != remappedFrom || !text.equals(remappedText)) {
			remappedFrom = result;
			remappedText = text;
			remapped = result.remapTo(text);
		}
		return remapped;
	}

	/** Maps spans to on-screen rectangles, mirroring EditBox's own text layout and scrolling. */
	private List<Placed> place(List<Misspelling> spans, String text, long now) {
		if (spans.isEmpty()) {
			return List.of();
		}
		LayoutKey key = new LayoutKey(spans, text, Math.min(box.chatspellcheck$displayPos(), text.length()),
			box.chatspellcheck$textX(), box.chatspellcheck$textY(), input.getInnerWidth(), box.chatspellcheck$font());
		if (!key.equals(layoutKey)) {
			layoutKey = key;
			layout = measure(key);
		}
		// Only "is this word still being typed" changes from frame to frame.
		int cursor = input.getCursorPosition();
		boolean typing = now - lastEditAt < TYPING_GRACE_MS;
		if (!typing) {
			return layout;
		}
		List<Placed> out = new ArrayList<>(layout.size());
		for (Placed p : layout) {
			out.add(p.span().contains(cursor) ? new Placed(p.span(), p.rect(), true) : p);
		}
		return out;
	}

	private static List<Placed> measure(LayoutKey k) {
		Font font = k.font();
		String text = k.text();
		int displayPos = k.displayPos();
		String visible = font.plainSubstrByWidth(text.substring(displayPos), k.innerWidth());
		int visibleEnd = displayPos + visible.length();
		// With right-to-left text the line is drawn reordered (RtlInputDisplay); follow it.
		dev.chatspellcheck.ui.BidiLine bidi = dev.chatspellcheck.ui.BidiLine.needsReordering(visible) ? RtlInputDisplay.layout(visible, font) : null;
		List<Placed> out = new ArrayList<>(k.spans().size());
		for (Misspelling m : k.spans()) {
			if (m.end() <= displayPos || m.start() >= visibleEnd) {
				continue; // scrolled out of view
			}
			// A word cut off at either edge is underlined only where it is visible.
			int from = Math.max(m.start(), displayPos);
			int to = Math.min(m.end(), visibleEnd);
			int x0;
			int x1;
			if (bidi != null) {
				List<int[]> pieces = bidi.ranges(from - displayPos, to - displayPos);
				if (pieces.isEmpty()) {
					continue;
				}
				x0 = k.textX() + pieces.stream().mapToInt(p -> p[0]).min().orElse(0);
				x1 = k.textX() + pieces.stream().mapToInt(p -> p[1]).max().orElse(0);
			} else {
				x0 = k.textX() + font.width(text.substring(displayPos, from));
				x1 = k.textX() + font.width(text.substring(displayPos, to));
			}
			if (x1 <= x0) {
				continue;
			}
			out.add(new Placed(m, new Rect(x0, k.textY() - 1, x1 - x0, font.lineHeight + 2), false));
		}
		return List.copyOf(out);
	}

	/**
	 * Underlines in the player's style and colour. Each one grows in from the left the first
	 * time it is shown; words are told apart by text and occurrence, so editing elsewhere in the
	 * line doesn't replay the animation.
	 */
	private void drawUnderlines(GuiGraphicsExtractor graphics, long now) {
		if (layout != keyedLayout) {
			keyedLayout = layout;
			underlineKeys = occurrenceKeys(layout);
			underlineShownAt.keySet().retainAll(underlineKeys);
		}
		Settings settings = service.settings();
		for (int i = 0; i < placed.size(); i++) {
			Placed p = placed.get(i);
			if (p.beingTyped()) {
				continue;
			}
			String key = underlineKeys.get(i);
			Long shown = underlineShownAt.get(key);
			if (shown == null) {
				underlineShownAt.put(key, now);
				shown = now;
			}
			float t = (now - shown) / (float) UNDERLINE_GROW_MS;
			float progress = t >= 1f ? 1f : 1 - (1 - t) * (1 - t);
			Rect r = p.rect();
			Paint.underline(graphics, r.x(), r.right(), r.bottom() - 1, settings.underlineStyle, settings.underlineColor.argb, progress);
		}
	}

	private static List<String> occurrenceKeys(List<Placed> placed) {
		List<String> keys = new ArrayList<>(placed.size());
		for (int i = 0; i < placed.size(); i++) {
			String word = placed.get(i).span().word();
			int n = 0;
			for (int j = 0; j < i; j++) {
				if (placed.get(j).span().word().equals(word)) {
					n++;
				}
			}
			keys.add(n + ":" + word);
		}
		return List.copyOf(keys);
	}

	/** Pink and gold specks rising and fading over a word that was just fixed. */
	private void drawSparkle(GuiGraphicsExtractor graphics, long now) {
		Rect r = sparkle;
		if (r == null) {
			return;
		}
		float t = (now - sparkleAt) / (float) SPARKLE_MS;
		if (t >= 1f) {
			sparkle = null;
			return;
		}
		float alpha = 1 - t * t;
		int rise = Math.round(10 * (1 - (1 - t) * (1 - t)));
		graphics.nextStratum();
		for (int i = 0; i < SPARKLE_PARTICLES; i++) {
			int x = r.x() + (r.width() * (2 * i + 1)) / (2 * SPARKLE_PARTICLES);
			int y = r.y() + 1 - rise - (i % 3) * 3;
			int color = Paint.fade(i % 2 == 0 ? Paint.PINK : Paint.GOLD, alpha);
			if (i % 3 == 0) { // "+" stars
				graphics.fill(x - 1, y, x + 2, y + 1, color);
				graphics.fill(x, y - 1, x + 1, y + 2, color);
			} else {
				graphics.fill(x, y, x + 2, y + 2, color);
			}
		}
	}

	// ---------------------------------------------------------------- popover

	private void updatePopover(GuiGraphicsExtractor graphics, int mouseX, int mouseY, long now, boolean suggestionsVisible, boolean dragging) {
		if (suggestionsVisible) {
			popover.close(); // vanilla's Tab-completion list owns that space
		} else {
			Placed hovered = null;
			for (Placed p : placed) {
				if (!p.beingTyped() && p.rect().contains(mouseX, mouseY)) {
					hovered = p;
				}
			}
			boolean inKeepZone = keepZone != null && keepZone.contains(mouseX, mouseY);
			popover.onPointer(now, hovered == null ? null : hovered.span(), inKeepZone, dragging);
		}

		Placed target = popover.isOpen() ? find(popover.target()) : null;
		if (popover.isOpen()) {
			popover.retarget(target == null ? null : target.span());
		}
		if (target == null) {
			popoverRect = null;
			keepZone = null;
			rows = List.of();
			rowsKey = null;
			shownWord = null;
			return;
		}
		if (!target.span().word().equals(shownWord)) {
			shownWord = target.span().word();
			shownAt = now;
			keyboardMoved = false;
			notice = null; // a new popover replaces any confirmation still showing
		}

		Font font = box.chatspellcheck$font();
		updateRows(target.span(), font, PopoverLayout.maxWidth(graphics.guiWidth()));
		int height = PAD_Y * 2 + HEADER_HEIGHT + rows.size() * ROW_HEIGHT + SEPARATOR + (popover.isKeyboard() ? FOOTER_HEIGHT : 0);
		popoverRect = PopoverLayout.place(target.rect(), rowsWidth, height, graphics.guiWidth(), graphics.guiHeight());
		keepZone = popoverRect.union(target.rect());

		int hoveredRow = rowAt(mouseX, mouseY);
		if (!popover.isKeyboard()) {
			popover.hoverRow(hoveredRow >= 0 && rows.get(hoveredRow).selectable() ? selectableIndex(hoveredRow) : -1);
		}
		// While suggestions load, the keyboard highlight would land on "Ignore"; show none until
		// they arrive (or the player picks a row with the arrow keys).
		int selectedRow = popover.isKeyboard() && suggestionsLoading() && !keyboardMoved ? -1 : rowForSelectable(popover.selected());
		if (hoveredRow >= 0 && rows.get(hoveredRow).selectable()) {
			graphics.requestCursor(CursorTypes.POINTING_HAND);
		}
		float t = Math.min(1f, (now - shownAt) / (float) FADE_IN_MS);
		float eased = 1 - (1 - t) * (1 - t);
		float alpha = 0.35f + 0.65f * eased; // ease out, never fully transparent
		// Slides up 2 pixels as it fades in (drawing only; clicks use the final position).
		graphics.pose().pushMatrix();
		graphics.pose().translate(0, 2 * (1 - eased));
		drawPopover(graphics, font, selectedRow, hoveredRow, alpha);
		graphics.pose().popMatrix();
	}

	private Placed find(Misspelling span) {
		for (Placed p : placed) {
			if (p.span().equals(span)) {
				return p;
			}
		}
		return null;
	}

	/** Rebuilds the rows only when the word, its suggestions, the game language or the screen width change. */
	private void updateRows(Misspelling target, Font font, int maxWidth) {
		List<String> suggestions = service.suggestions(target.word()).getNow(null);
		RowsKey key = new RowsKey(target.word(), suggestions, net.minecraft.locale.Language.getInstance(), font, maxWidth, popover.isKeyboard());
		if (key.equals(rowsKey)) {
			return;
		}
		rowsKey = key;
		List<Row> out = new ArrayList<>();
		if (suggestions == null) {
			out.add(row(RowKind.INFO, Component.translatable("chatspellcheck.popover.loading").getString()));
		} else if (suggestions.isEmpty()) {
			out.add(row(RowKind.INFO, Component.translatable("chatspellcheck.popover.none").getString()));
		} else {
			for (String s : suggestions) {
				out.add(row(RowKind.SUGGESTION, s));
			}
		}
		out.add(row(RowKind.IGNORE, Component.translatable("chatspellcheck.popover.ignore").getString()));
		out.add(row(RowKind.ADD, Component.translatable("chatspellcheck.popover.add").getString()));
		// Rows wider than the screen (a very long suggestion) are shortened for display only;
		// choosing one still inserts the whole suggestion.
		int textMax = maxWidth - PAD_X * 2 - GUTTER;
		int width = Math.min(MIN_WIDTH, maxWidth);
		for (int i = 0; i < out.size(); i++) {
			Row r = out.get(i);
			String label = PopoverLayout.ellipsize(r.text(), textMax, font::width);
			out.set(i, new Row(r.kind(), r.text(), label));
			width = Math.max(width, font.width(label) + PAD_X * 2 + GUTTER);
		}
		// No reordering needed here: unlike the chat input line, GuiGraphicsExtractor.text(String)
		// already shows Hebrew/Arabic in reading order (Language.getVisualOrder).
		header = PopoverLayout.ellipsize(target.word(), maxWidth - PAD_X * 2, font::width);
		width = Math.max(width, font.width(header) + PAD_X * 2);
		if (popover.isKeyboard()) {
			width = Math.max(width, font.width(keysHint()) + PAD_X * 2);
		}
		rows = List.copyOf(out);
		rowsWidth = Math.min(width, maxWidth);
	}

	private static Row row(RowKind kind, String text) {
		return new Row(kind, text, text);
	}

	private boolean suggestionsLoading() {
		return rowsKey != null && rowsKey.suggestions() == null;
	}

	private static String keysHint() {
		return Component.translatable("chatspellcheck.popover.keys").getString();
	}

	private int rowTop(int row) {
		int y = popoverRect.y() + PAD_Y + HEADER_HEIGHT + row * ROW_HEIGHT;
		return rows.get(row).kind() == RowKind.IGNORE || rows.get(row).kind() == RowKind.ADD ? y + SEPARATOR : y;
	}

	private int rowAt(double mouseX, double mouseY) {
		if (popoverRect == null || !popoverRect.contains(mouseX, mouseY)) {
			return -1;
		}
		for (int i = 0; i < rows.size(); i++) {
			int top = rowTop(i);
			if (mouseY >= top && mouseY < top + ROW_HEIGHT) {
				return i;
			}
		}
		return -1;
	}

	private int selectableIndex(int row) {
		int index = -1;
		for (int i = 0; i <= row; i++) {
			if (rows.get(i).selectable()) {
				index++;
			}
		}
		return index;
	}

	private int rowForSelectable(int selectable) {
		if (selectable < 0) {
			return -1;
		}
		int index = -1;
		for (int i = 0; i < rows.size(); i++) {
			if (rows.get(i).selectable() && ++index == selectable) {
				return i;
			}
		}
		return -1;
	}

	private int selectableCount() {
		int n = 0;
		for (Row r : rows) {
			if (r.selectable()) {
				n++;
			}
		}
		return n;
	}

	private void drawPopover(GuiGraphicsExtractor graphics, Font font, int selectedRow, int hoveredRow, float alpha) {
		graphics.nextStratum(); // above chat text and the input line
		Rect r = popoverRect;
		Paint.panel(graphics, r, PANEL_BG, PANEL_FRAME, Paint.ACCENT, alpha);
		int textX = r.x() + PAD_X + GUTTER;

		// Header: the word being corrected, struck through.
		int top = r.y() + PAD_Y;
		Component word = Component.literal(header).withStyle(ChatFormatting.STRIKETHROUGH);
		graphics.text(font, word, r.x() + PAD_X, top + 1, Paint.fade(TEXT_WORD, alpha), false);
		divider(graphics, r, top + HEADER_HEIGHT - 4, alpha);

		for (int i = 0; i < rows.size(); i++) {
			Row row = rows.get(i);
			int rowY = rowTop(i);
			if (row.kind() == RowKind.IGNORE) {
				divider(graphics, r, rowY - SEPARATOR / 2 - 1, alpha);
			}
			if (i == selectedRow && popover.isKeyboard()) {
				graphics.fill(r.x() + 2, rowY, r.right() - 1, rowY + ROW_HEIGHT, Paint.fade(ROW_SELECTED, alpha));
				graphics.fill(r.x() + 2, rowY, r.x() + 3, rowY + ROW_HEIGHT, Paint.fade(ROW_SELECTED_EDGE, alpha));
			} else if (i == selectedRow || (i == hoveredRow && row.selectable())) {
				graphics.fill(r.x() + 2, rowY, r.right() - 1, rowY + ROW_HEIGHT, Paint.fade(ROW_HOVER, alpha));
			}
			boolean highlighted = i == selectedRow;
			int color = switch (row.kind()) {
				case SUGGESTION -> i == 0 || highlighted ? TEXT_BEST : TEXT_SUGGESTION;
				case INFO -> TEXT_DISABLED;
				default -> highlighted ? TEXT_BEST : TEXT_ACTION;
			};
			int textY = rowY + (ROW_HEIGHT - 8) / 2;
			if (row.kind() == RowKind.IGNORE || row.kind() == RowKind.ADD) {
				String icon = row.kind() == RowKind.IGNORE ? "×" : "+";
				int iconColor = highlighted && popover.isKeyboard() ? TEXT_BEST : row.kind() == RowKind.IGNORE ? ICON_IGNORE : ICON_ADD;
				graphics.text(font, icon, r.x() + PAD_X + 1, textY, Paint.fade(iconColor, alpha), false);
			}
			graphics.text(font, row.label(), textX, textY, Paint.fade(color, alpha), false);
		}

		if (popover.isKeyboard()) {
			int footerY = r.bottom() - PAD_Y - FOOTER_HEIGHT;
			divider(graphics, r, footerY + 2, alpha);
			graphics.text(font, keysHint(), r.x() + PAD_X, footerY + 5, Paint.fade(TEXT_HINT, alpha), false);
		}
	}

	private static void divider(GuiGraphicsExtractor graphics, Rect r, int y, float alpha) {
		graphics.fill(r.x() + PAD_X, y, r.right() - PAD_X, y + 1, Paint.fade(DIVIDER, alpha));
	}

	/** The confirmation after Ignore / Add: a small pill above the word that fades out. */
	private void drawNotice(GuiGraphicsExtractor graphics, long now) {
		String text = notice;
		long left = noticeUntil - now;
		if (text == null || left <= 0) {
			notice = null;
			return;
		}
		Font font = box.chatspellcheck$font();
		float alpha = Math.min(1f, left / 300f);
		int width = Math.min(font.width(text) + 12, PopoverLayout.maxWidth(graphics.guiWidth()));
		String shown = PopoverLayout.ellipsize(text, width - 12, font::width);
		int x = Math.clamp(noticeX, 2, Math.max(2, graphics.guiWidth() - width - 2));
		Rect r = new Rect(x, noticeY - 14, width, 13);
		graphics.nextStratum();
		Paint.panel(graphics, r, PANEL_BG, PANEL_FRAME, noticeColor, alpha);
		graphics.text(font, shown, r.x() + 6, r.y() + 3, Paint.fade(noticeColor, alpha), false);
	}

	/** The welcome tip: a pink pill just above the right end of the chat box, fading in. */
	private void drawWelcome(GuiGraphicsExtractor graphics, long now) {
		Font font = box.chatspellcheck$font();
		String text = welcomeText();
		int width = Math.min(font.width(text) + 12, PopoverLayout.maxWidth(graphics.guiWidth()));
		String shown = PopoverLayout.ellipsize(text, width - 12, font::width);
		int right = Math.min(input.getX() + input.getWidth(), graphics.guiWidth() - 2);
		Rect r = new Rect(Math.max(2, right - width), input.getY() - 17, width, 13);
		float t = Math.min(1f, (now - openedAt) / 250f);
		float alpha = 1 - (1 - t) * (1 - t);
		graphics.nextStratum();
		Paint.panel(graphics, r, PANEL_BG, PANEL_FRAME, Paint.PINK, alpha);
		graphics.text(font, shown, r.x() + 6, r.y() + 3, Paint.fade(Paint.PINK_LIGHT, alpha), false);
	}

	private static String welcomeText() {
		String shortcut = Util.getPlatform() == Util.OS.OSX ? "Cmd+." : "Ctrl+.";
		return Component.translatable("chatspellcheck.welcome", shortcut).getString();
	}

	private void showNotice(Misspelling target, String key, int color, Object... extra) {
		Placed p = find(target);
		if (p != null) {
			noticeX = p.rect().x() - 3;
			noticeY = p.rect().y();
		}
		Object[] args = new Object[extra.length + 1];
		args[0] = target.word();
		System.arraycopy(extra, 0, args, 1, extra.length);
		notice = Component.translatable(key, args).getString();
		noticeColor = color;
		noticeUntil = Util.getMillis() + NOTICE_MS;
	}

	// ---------------------------------------------------------------- read-only state (tests)

	/** Underlined words as drawn last frame, with their GUI-space rectangles. */
	public List<Rect> underlineRects() {
		return placed.stream().filter(p -> !p.beingTyped()).map(Placed::rect).toList();
	}

	public List<Misspelling> underlinedWords() {
		return placed.stream().filter(p -> !p.beingTyped()).map(Placed::span).toList();
	}

	/** Popover bounds, or null when closed. */
	public Rect popoverRect() {
		return popover.isOpen() ? popoverRect : null;
	}

	/** Row texts (the full suggestion, even if drawn shortened). */
	public List<String> popoverRows() {
		return rows.stream().map(Row::text).toList();
	}

	/** Row texts as drawn. */
	public List<String> popoverLabels() {
		return rows.stream().map(Row::label).toList();
	}

	/** Centre of a popover row in GUI coordinates, for tests that click it. */
	public int[] popoverRowCenter(int row) {
		return new int[] {popoverRect.x() + popoverRect.width() / 2, rowTop(row) + ROW_HEIGHT / 2};
	}

	/** The confirmation currently shown after Ignore / Add to dictionary, or null. */
	public String noticeText() {
		return notice != null && noticeUntil > Util.getMillis() ? notice : null;
	}

	/** The welcome tip, or null when it isn't showing. */
	public String welcomeTip() {
		return showWelcome && service.settings().enabled ? welcomeText() : null;
	}

	public boolean popoverIsKeyboard() {
		return popover.isKeyboard();
	}

	// ---------------------------------------------------------------- input

	/** @return true if the event was handled and must not reach the chat screen. */
	public boolean keyPressed(KeyEvent event) {
		if (lastAutocorrect != null && event.input() == InputConstants.KEY_BACKSPACE
			&& !event.hasControlDown() && !event.hasAltDown() && undoAutocorrect()) {
			return true;
		}
		if (isShortcut(event)) {
			return openAtCursor();
		}
		if (popover.isKeyboard()) {
			if (event.isEscape()) {
				popover.dismiss();
				return true;
			}
			if (event.isUp() || (event.isCycleFocus() && event.hasShiftDown())) {
				popover.move(-1, selectableCount());
				keyboardMoved = true;
				return true;
			}
			if (event.isDown() || event.isCycleFocus()) {
				popover.move(1, selectableCount());
				keyboardMoved = true;
				return true;
			}
			if (event.isConfirmation()) {
				if (suggestionsLoading() && !keyboardMoved) {
					return true; // a quick Ctrl+. Enter must not "Ignore" the word before suggestions arrive
				}
				int row = rowForSelectable(popover.selected());
				if (row >= 0) {
					activate(rows.get(row));
				}
				return true;
			}
			// Anything else (typing, arrows, clipboard) goes to the text box as usual.
			popover.close();
			return false;
		}
		if (popover.isOpen() && event.isEscape()) {
			popover.dismiss(); // first Escape closes the popover, the next one closes chat
			return true;
		}
		return false;
	}

	public static boolean isShortcut(KeyEvent event) {
		// input() is the GLFW key on 26.2 and the SDL scancode on 26.3; InputConstants matches
		// whichever version this JAR was compiled for.
		boolean period = event.input() == InputConstants.KEY_PERIOD;
		return period && (event.hasControlDown() || event.hasControlDownWithQuirk())
			&& !event.hasAltDown() && !event.hasShiftDown();
	}

	/** Ctrl/Cmd + . : the misspelling at the caret, else the nearest one before it, else the first. */
	private boolean openAtCursor() {
		if (placed.isEmpty()) {
			return false;
		}
		int cursor = input.getCursorPosition();
		Placed best = null;
		for (Placed p : placed) {
			if (p.span().contains(cursor)) {
				best = p;
				break;
			}
			if (p.span().end() <= cursor) {
				best = p;
			}
		}
		popover.openForKeyboard((best != null ? best : placed.getFirst()).span());
		return true;
	}

	public boolean mouseClicked(MouseButtonEvent event) {
		if (popoverRect != null && popoverRect.contains(event.x(), event.y())) {
			if (event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
				int row = rowAt(event.x(), event.y());
				if (row >= 0 && rows.get(row).selectable()) {
					activate(rows.get(row));
				}
			}
			return true; // never let a click inside the popover move the text caret
		}
		if (popover.isKeyboard()) {
			popover.close();
		}
		return false;
	}

	private void activate(Row row) {
		Misspelling target = popover.target();
		popover.close();
		if (target == null) {
			return;
		}
		switch (row.kind()) {
			case SUGGESTION -> replace(target, row.text());
			case IGNORE -> {
				service.ignore(target.word());
				showNotice(target, "chatspellcheck.notice.ignored", NOTICE_OK);
			}
			case ADD -> {
				showNotice(target, "chatspellcheck.notice.added", NOTICE_OK);
				service.stats().wordAdded();
				service.addToDictionary(target.word()).thenAccept(saved -> {
					if (!saved && notice != null) {
						// The word still counts for this session; say so instead of failing silently.
						notice = Component.translatable("chatspellcheck.notice.save_failed", target.word()).getString();
						noticeColor = NOTICE_WARN;
						noticeUntil = Util.getMillis() + NOTICE_MS * 2;
					}
				});
			}
			case INFO -> {
			}
		}
	}

	private void replace(Misspelling target, String replacement) {
		Placed before = find(target);
		WordReplacement.apply(input.getValue(), target, replacement, box.chatspellcheck$maxLength()).ifPresent(edit -> {
			setValueKeepingCaret(edit);
			if (before != null) {
				Rect r = before.rect();
				sparkle = new Rect(r.x(), r.y(), Math.max(r.width(), box.chatspellcheck$font().width(replacement)), r.height());
				sparkleAt = Util.getMillis();
			}
			service.stats().fixed(1);
			welcomeDone();
		});
	}

	/**
	 * Sets the edited text without moving the player's caret: the caret, the selection anchor and
	 * the scroll position are carried across the edit (EditBox.setValue would jump to the end).
	 */
	private void setValueKeepingCaret(WordReplacement.Edit edit) {
		int cursor = input.getCursorPosition();
		int highlight = box.chatspellcheck$highlightPos();
		int display = box.chatspellcheck$displayPos();
		input.setValue(edit.text());
		int length = input.getValue().length();
		int newCursor = Math.clamp(edit.map(cursor), 0, length);
		int newDisplay = Math.clamp(edit.map(display), 0, length);
		box.chatspellcheck$setDisplayPos(newDisplay);
		box.chatspellcheck$setCursorPos(newCursor);
		box.chatspellcheck$setHighlightPos(Math.clamp(edit.map(highlight), 0, length));
		String visible = box.chatspellcheck$font().plainSubstrByWidth(input.getValue().substring(newDisplay), input.getInnerWidth());
		if (newCursor < newDisplay || newCursor > newDisplay + visible.length()) {
			// A longer word pushed the caret out of view: scroll just enough to show it.
			input.setCursorPosition(newCursor);
		}
	}
}
