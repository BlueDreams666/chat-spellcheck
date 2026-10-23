package dev.chatspellcheck.gametest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Function;

import com.mojang.blaze3d.platform.InputConstants;
import dev.chatspellcheck.ChatSpellcheckClient;
import dev.chatspellcheck.client.ChatSpellcheckHost;
import dev.chatspellcheck.client.SpellcheckSettingsScreen;
import dev.chatspellcheck.core.Language;
import dev.chatspellcheck.client.ChatSpellcheckOverlay;
import dev.chatspellcheck.core.Misspelling;
import dev.chatspellcheck.core.Settings;
import dev.chatspellcheck.core.Stats;
import dev.chatspellcheck.ui.Rect;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.input.KeyEvent;

/**
 * Drives the real client: opens chat in a singleplayer world, types, hovers, clicks and uses
 * the keyboard, asserting on the overlay's state and saving screenshots along the way.
 * Run with {@code ./gradlew runClientGameTest}; screenshots land in build/run/clientGameTest/screenshots.
 */
public class ChatSpellcheckClientGameTest implements FabricClientGameTest {
	// Gametest ticks run faster than real time, while debounce, hover delay and typing grace
	// are wall-clock based; allow plenty of ticks.
	private static final int TIMEOUT = 20_000;
	// Ctrl modifier bit: SDL_KMOD_LCTRL (26.3+) or GLFW_MOD_CONTROL (26.2), whichever this build uses.
	private static final int CTRL_MODIFIER = new KeyEvent(0, 0, 0x0040).hasControlDown() ? 0x0040 : 0x0002;

	@Override
	public void runTest(ClientGameTestContext ctx) {
		try (TestSingleplayerContext ignored = ctx.worldBuilder().create()) {
			ctx.waitTicks(20);
			ctx.runOnClient(mc -> ChatSpellcheckClient.service().settings().welcomeTips = 0); // timings without the tip
			ChatSpellcheckClient.service().setLanguages(List.of(Language.EN_US)).join();
			ctx.waitFor(mc -> ChatSpellcheckClient.service().isReady(), TIMEOUT);
			renderCost(ctx);
			inputDrawCost(ctx);
			if (System.getenv("CHATSPELLCHECK_TIMING_ONLY") != null) {
				ctx.setScreen(() -> null);
				return;
			}
			welcomeTipFixAndStats(ctx);
			settingsScreen(ctx);
			underlineLooks(ctx);
			underlinesHoverAndClickToReplace(ctx);
			keyboardSelectionAndEscapeLayering(ctx);
			addToDictionaryPersists(ctx);
			scrolledTextAndScreenEdges(ctx);
			commandsAreLeftAlone(ctx);
			largerGuiScale(ctx);
			languagePickerAndMultipleLanguages(ctx);
			halfVisibleWordAtTheLeftEdge(ctx);
			caretAndSelectionStayPut(ctx);
			resizeWhilePopoverIsOpen(ctx);
			tabCompletionAndPopover(ctx);
			switchingLanguagesWhileChatIsOpen(ctx);
			settingsCommandTypedInChat(ctx);
			quickEnterNeverIgnores(ctx);
			modMenuSettingsButton(ctx);
			autocorrectAsYouType(ctx);
			rightToLeftDisplay(ctx);
			ctx.setScreen(() -> null);
		}
	}

	/**
	 * A new player's first chats: the welcome tip shows, and fixing a typo from the popover
	 * sparkles, ends the tip for good, and counts in the stats, as does sending the message.
	 */
	private void welcomeTipFixAndStats(ClientGameTestContext ctx) {
		ctx.runOnClient(mc -> ChatSpellcheckClient.service().settings().welcomeTips = Settings.WELCOME_TIPS);
		Stats.Snapshot before = ChatSpellcheckClient.service().stats().snapshot();
		openChat(ctx, "");
		check(ctx.computeOnClient(mc -> overlay(mc).welcomeTip()) != null, "the welcome tip should show");
		check(ChatSpellcheckClient.service().settings().welcomeTips == Settings.WELCOME_TIPS - 1, "one tip used up");
		ctx.getInput().typeChars("i love this mod its so helpfull");
		waitForWords(ctx, List.of("helpfull"));
		waitMillis(ctx, 300); // the underline has grown in
		ctx.takeScreenshot("00-welcome-tip");

		Rect word = rectOf(ctx, "helpfull");
		hover(ctx, word.x() + word.width() / 2.0, word.y() + word.height() / 2.0);
		ctx.waitFor(mc -> loadedRows(mc) != null, TIMEOUT);
		int[] row0 = ctx.computeOnClient(mc -> overlay(mc).popoverRowCenter(0));
		hover(ctx, word.x() + 3, word.y() - 1);
		hover(ctx, row0[0], row0[1]);
		ctx.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
		ctx.waitTick();
		ctx.takeScreenshot("00b-fix-sparkle");
		check(value(ctx).equals("i love this mod its so helpful"), "after fix: " + value(ctx));
		check(ctx.computeOnClient(mc -> overlay(mc).welcomeTip()) == null, "the tip should go after the first fix");
		check(ChatSpellcheckClient.service().settings().welcomeTips == 0, "no more tips after a fix");

		ctx.getInput().pressKey(InputConstants.KEY_RETURN); // send it
		ctx.waitFor(mc -> mc.gui.screen() == null, TIMEOUT);
		Stats.Snapshot after = ChatSpellcheckClient.service().stats().snapshot();
		check(after.typosFixed() == before.typosFixed() + 1, "fixes: " + before.typosFixed() + " -> " + after.typosFixed());
		check(after.wordsChecked() == before.wordsChecked() + 6, "words: " + before.wordsChecked() + " -> " + after.wordsChecked());
		check(after.streak() == before.streak() + 1, "a clean message adds to the streak");
		waitMillis(ctx, 1000); // the server echoes the message back a moment later
		ctx.runOnClient(mc -> mc.gui.hud.getChat().clearMessages(false)); // keep later screenshots clean
		hover(ctx, 2, 2);
	}

	/**
	 * The settings screen: sections scroll when they don't fit, nothing overlaps or leaves the
	 * screen at several sizes, and the look choices apply on Done.
	 */
	private void settingsScreen(ClientGameTestContext ctx) {
		ctx.setScreen(() -> new SpellcheckSettingsScreen(ChatSpellcheckClient.service()));
		ctx.waitForScreen(SpellcheckSettingsScreen.class);
		waitMillis(ctx, 400);
		ctx.takeScreenshot("18-settings-top");
		checkSettingsLayout(ctx, "854x480");
		check(ctx.computeOnClient(mc -> ((SpellcheckSettingsScreen) mc.gui.screen()).scrollRange()) > 0, "the sections should scroll at 854x480");
		ctx.runOnClient(mc -> ((SpellcheckSettingsScreen) mc.gui.screen()).scrollTo(0.55));
		ctx.waitTicks(2);
		ctx.takeScreenshot("18b-settings-middle");
		ctx.runOnClient(mc -> ((SpellcheckSettingsScreen) mc.gui.screen()).scrollTo(1));
		ctx.waitTicks(2);
		ctx.takeScreenshot("18c-settings-bottom");

		// Mouse wheel scrolling.
		ctx.getInput().setCursorPos(427, 240);
		ctx.getInput().scroll(5);
		ctx.waitTicks(2);
		check(ctx.computeOnClient(mc -> ((SpellcheckSettingsScreen) mc.gui.screen()).scrollRange()) > 0, "still scrollable");

		// Look choices show in the preview right away, and apply on Done.
		ctx.runOnClient(mc -> ((SpellcheckSettingsScreen) mc.gui.screen()).scrollTo(0));
		ctx.clickScreenButton("Dotted");
		ctx.clickScreenButton("Blue");
		ctx.clickScreenButton("Skip names"); // off: the name in the preview gets underlined too
		waitMillis(ctx, 400);
		ctx.takeScreenshot("18d-settings-preview-dotted-blue");
		ctx.clickScreenButton("Skip names");
		ctx.clickScreenButton("gui.done");
		Settings settings = ChatSpellcheckClient.service().settings();
		check(settings.underlineStyle == Settings.UnderlineStyle.DOTTED && settings.underlineColor == Settings.UnderlineColor.BLUE,
			"look after Done: " + settings.underlineStyle + " " + settings.underlineColor);
		check(settings.skipNames, "Skip names was toggled twice");

		for (int[] size : new int[][] {{1920, 1080, 4}, {640, 480, 2}, {854, 480, 4}}) {
			ctx.getInput().resizeWindow(size[0], size[1]);
			ctx.waitTicks(2);
			int before = ctx.computeOnClient(mc -> mc.options.guiScale().get());
			ctx.runOnClient(mc -> {
				mc.options.guiScale().set(size[2]);
				mc.resizeGui();
			});
			ctx.setScreen(() -> new SpellcheckSettingsScreen(ChatSpellcheckClient.service()));
			ctx.waitForScreen(SpellcheckSettingsScreen.class);
			ctx.waitTicks(2);
			String label = size[0] + "x" + size[1] + " gui " + size[2];
			checkSettingsLayout(ctx, label);
			ctx.takeScreenshot("18e-settings-" + size[0] + "x" + size[1] + "-gui" + size[2]);
			ctx.clickScreenButton("gui.done");
			ctx.runOnClient(mc -> {
				mc.options.guiScale().set(before);
				mc.resizeGui();
			});
		}
		ctx.getInput().resizeWindow(854, 480);
		ctx.waitTicks(2);
	}

	/** Every button is inside the window and no two overlap, at any scroll position. */
	private static void checkSettingsLayout(ClientGameTestContext ctx, String label) {
		String problem = ctx.computeOnClient(mc -> {
			SpellcheckSettingsScreen screen = (SpellcheckSettingsScreen) mc.gui.screen();
			List<net.minecraft.client.gui.components.AbstractWidget> widgets = screen.children().stream()
				.filter(c -> c instanceof net.minecraft.client.gui.components.AbstractWidget)
				.map(c -> (net.minecraft.client.gui.components.AbstractWidget) c).toList();
			for (double at : new double[] {0, 0.5, 1}) {
				screen.scrollTo(at);
				List<net.minecraft.client.gui.components.AbstractWidget> shown = widgets.stream().filter(w -> screen.shownRect(w) != null).toList();
				for (var w : shown) {
					if (w.getX() < 0 || w.getRight() > screen.width || w.getWidth() < 30) {
						return w.getMessage().getString() + " is off screen or squashed at " + w.getRectangle();
					}
					for (var o : shown) {
						if (o != w && screen.shownRect(w).intersects(screen.shownRect(o))) {
							return w.getMessage().getString() + " overlaps " + o.getMessage().getString();
						}
					}
				}
			}
			screen.scrollTo(0);
			return null;
		});
		check(problem == null, "settings layout at " + label + ": " + problem);
	}

	/** The chosen underline style and colour in the real chat box (set by settingsScreen). */
	private void underlineLooks(ClientGameTestContext ctx) {
		openChat(ctx, "the dotted blue look is for wierd words");
		waitForWords(ctx, List.of("wierd"));
		waitMillis(ctx, 300);
		ctx.takeScreenshot("19-dotted-blue-underline");
		ctx.runOnClient(mc -> {
			Settings settings = ChatSpellcheckClient.service().settings();
			settings.underlineStyle = Settings.UnderlineStyle.STRAIGHT;
			settings.underlineColor = Settings.UnderlineColor.PINK;
		});
		ctx.waitTicks(2);
		ctx.takeScreenshot("19b-straight-pink-underline");
		ctx.runOnClient(mc -> {
			Settings settings = ChatSpellcheckClient.service().settings();
			settings.underlineStyle = Settings.UnderlineStyle.SQUIGGLE;
			settings.underlineColor = Settings.UnderlineColor.RED;
		});
		ctx.setScreen(() -> null);
	}

	private void underlinesHoverAndClickToReplace(ClientGameTestContext ctx) {
		openChat(ctx, "");
		ctx.getInput().typeChars("i recieve teh diamonds from netherite https://exmaple.com");
		waitForWords(ctx, List.of("recieve", "teh"));
		ctx.takeScreenshot("01-underlines");

		Rect word = rectOf(ctx, "recieve");
		hover(ctx, word.x() + word.width() / 2.0, word.y() + word.height() / 2.0);
		ctx.waitFor(mc -> loadedRows(mc) != null, TIMEOUT);
		List<String> rows = ctx.computeOnClient(ChatSpellcheckClientGameTest::loadedRows);
		check(rows.getFirst().equals("receive"), "first suggestion was " + rows);
		check(rows.contains("Ignore") && rows.contains("Add to dictionary"), "actions missing: " + rows);
		Rect popover = ctx.computeOnClient(mc -> overlay(mc).popoverRect());
		check(popover.bottom() == word.y(), "popover should sit flush above the word: " + popover + " vs " + word);
		ctx.takeScreenshot("02-hover-popover");

		// Glide from the word into the popover; it must stay open on the way.
		int[] row0 = ctx.computeOnClient(mc -> overlay(mc).popoverRowCenter(0));
		hover(ctx, word.x() + 3, word.y() - 1);
		hover(ctx, row0[0], row0[1]);
		check(ctx.computeOnClient(mc -> overlay(mc).popoverRect()) != null, "popover closed while moving into it");
		ctx.takeScreenshot("03-hover-row");
		int caretBefore = cursor(ctx);
		ctx.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
		ctx.waitTick();
		check(value(ctx).equals("i receive teh diamonds from netherite https://exmaple.com"), "after click: " + value(ctx));
		check(cursor(ctx) == caretBefore, "the caret must stay where it was: " + caretBefore + " -> " + cursor(ctx));
		check(ctx.computeOnClient(mc -> overlay(mc).popoverRect()) == null, "popover should close after choosing");
		waitForWords(ctx, List.of("teh"));
		hover(ctx, 2, 2);
	}

	private void keyboardSelectionAndEscapeLayering(ClientGameTestContext ctx) {
		waitMillis(ctx, 700); // let the sparkle from the previous fix fade out of the screenshot
		ctx.runOnClient(mc -> input(mc).moveCursorTo("i receive te".length(), false));
		pressShortcut(ctx);
		check(ctx.computeOnClient(mc -> overlay(mc).popoverIsKeyboard()), "Ctrl+. should open the keyboard popover");
		ctx.waitFor(mc -> loadedRows(mc) != null, TIMEOUT);
		ctx.takeScreenshot("04-keyboard-popover");
		// Down then Up must move the highlight, not scroll chat history.
		ctx.getInput().pressKey(InputConstants.KEY_DOWN);
		ctx.getInput().pressKey(InputConstants.KEY_UP);
		ctx.getInput().pressKey(InputConstants.KEY_RETURN);
		ctx.waitTick();
		check(ctx.computeOnClient(mc -> mc.gui.screen() instanceof ChatScreen), "Enter in the popover must not send the message");
		check(value(ctx).startsWith("i receive the diamonds"), "after Enter: " + value(ctx));
		check(cursor(ctx) == "i receive te".length(), "keyboard fix moved the caret to " + cursor(ctx));

		// Escape closes the popover first, and only the next Escape closes chat.
		ctx.runOnClient(mc -> input(mc).setValue("teh"));
		waitForWords(ctx, List.of("teh"));
		pressShortcut(ctx);
		check(ctx.computeOnClient(mc -> overlay(mc).popoverIsKeyboard()), "popover should be open");
		ctx.getInput().pressKey(InputConstants.KEY_ESCAPE);
		ctx.waitTick();
		check(ctx.computeOnClient(mc -> mc.gui.screen() instanceof ChatScreen), "first Escape closed chat");
		check(ctx.computeOnClient(mc -> overlay(mc).popoverRect()) == null, "first Escape should close the popover");

		// Typing while the keyboard popover is open just types.
		pressShortcut(ctx);
		ctx.getInput().typeChars("x");
		ctx.waitTick();
		check(value(ctx).equals("tehx"), "typing should pass through: " + value(ctx));
		check(!ctx.computeOnClient(mc -> overlay(mc).popoverIsKeyboard()), "typing should close the popover");
	}

	private void addToDictionaryPersists(ClientGameTestContext ctx) {
		ctx.runOnClient(mc -> input(mc).setValue("my zorblax farm"));
		waitForWords(ctx, List.of("zorblax"));
		Rect word = rectOf(ctx, "zorblax");
		hover(ctx, word.x() + 2, word.y() + 4);
		ctx.waitFor(mc -> loadedRows(mc) != null, TIMEOUT);
		List<String> rows = ctx.computeOnClient(ChatSpellcheckClientGameTest::loadedRows);
		int addRow = rows.indexOf("Add to dictionary");
		int[] center = ctx.computeOnClient(mc -> overlay(mc).popoverRowCenter(addRow));
		hover(ctx, word.x() + 2, word.y() - 1);
		hover(ctx, center[0], center[1]);
		ctx.takeScreenshot("05-add-to-dictionary");
		ctx.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
		waitForWords(ctx, List.of());
		check(value(ctx).equals("my zorblax farm"), "add-to-dictionary must not edit the text");
		String notice = ctx.computeOnClient(mc -> overlay(mc).noticeText());
		check(notice != null && notice.contains("zorblax"), "expected a confirmation, got " + notice);
		ctx.takeScreenshot("05b-added-notice");
		Path file = FabricLoader.getInstance().getConfigDir().resolve("chatspellcheck/custom-words.txt");
		ctx.waitFor(mc -> {
			try {
				return Files.exists(file) && Files.readString(file).contains("zorblax");
			} catch (Exception e) {
				return false;
			}
		}, TIMEOUT);
		hover(ctx, 2, 2);
	}

	private void scrolledTextAndScreenEdges(ClientGameTestContext ctx) {
		String longText = "this line is long enough to scroll the chat box sideways ".repeat(4) + "and ends with mispeled";
		ctx.runOnClient(mc -> input(mc).setValue(longText));
		waitForWords(ctx, List.of("mispeled"));
		Rect word = rectOf(ctx, "mispeled");
		int guiWidth = ctx.computeOnClient(mc -> mc.getWindow().getGuiScaledWidth());
		check(word.right() <= guiWidth, "underline past the screen edge: " + word);
		check(ctx.computeOnClient(mc -> mc.font.width(longText)) > guiWidth, "test text should overflow");
		// The underline must cover exactly the glyphs of the word in the scrolled view.
		int expectedX = ctx.computeOnClient(mc -> {
			EditBox box = input(mc);
			int start = longText.indexOf("mispeled");
			int displayPos = ((dev.chatspellcheck.mixin.EditBoxAccessor) box).chatspellcheck$displayPos();
			return ((dev.chatspellcheck.mixin.EditBoxAccessor) box).chatspellcheck$textX() + mc.font.width(longText.substring(displayPos, start));
		});
		check(word.x() == expectedX, "underline x " + word.x() + " expected " + expectedX);

		hover(ctx, word.right() - 2, word.y() + 4);
		ctx.waitFor(mc -> loadedRows(mc) != null, TIMEOUT);
		Rect popover = ctx.computeOnClient(mc -> overlay(mc).popoverRect());
		check(popover.right() <= guiWidth - 2 && popover.x() >= 2, "popover off screen: " + popover);
		ctx.takeScreenshot("06-scrolled-right-edge");
		hover(ctx, 2, 2);

		// Scrolling back to the start hides the (now off-screen) underline.
		ctx.runOnClient(mc -> input(mc).moveCursorToStart(false));
		waitMillis(ctx, 100);
		check(ctx.computeOnClient(mc -> overlay(mc).underlineRects().isEmpty()), "off-screen word still underlined");
	}

	private void commandsAreLeftAlone(ClientGameTestContext ctx) {
		ctx.runOnClient(mc -> input(mc).setValue("/gamemode creatve"));
		waitMillis(ctx, 1200);
		check(ctx.computeOnClient(mc -> overlay(mc).underlinedWords().isEmpty()), "commands should not be checked");
	}

	private void largerGuiScale(ClientGameTestContext ctx) {
		// Scale 4 only fits a large enough window; Minecraft silently clamps otherwise.
		ctx.getInput().resizeWindow(1920, 1080);
		ctx.waitTicks(2);
		int before = ctx.computeOnClient(mc -> mc.options.guiScale().get());
		ctx.runOnClient(mc -> {
			mc.options.guiScale().set(4);
			mc.resizeGui();
		});
		openChat(ctx, "wierd stuff");
		check(ctx.computeOnClient(mc -> mc.getWindow().getGuiScale()) == 4, "effective GUI scale is not 4");
		waitForWords(ctx, List.of("wierd"));
		Rect word = rectOf(ctx, "wierd");
		hover(ctx, word.x() + 3, word.y() + 4);
		ctx.waitFor(mc -> loadedRows(mc) != null, TIMEOUT);
		check(ctx.computeOnClient(ChatSpellcheckClientGameTest::loadedRows).getFirst().equals("weird"), "gui-scale 4 suggestions");
		ctx.takeScreenshot("07-gui-scale-4");
		ctx.runOnClient(mc -> {
			mc.options.guiScale().set(before);
			mc.resizeGui();
		});
		ctx.getInput().resizeWindow(854, 480);
		hover(ctx, 2, 2);
	}

	private void languagePickerAndMultipleLanguages(ClientGameTestContext ctx) {
		// Turn Spanish and Hebrew on through the in-game picker.
		ctx.setScreen(() -> new SpellcheckSettingsScreen(ChatSpellcheckClient.service()));
		ctx.waitForScreen(SpellcheckSettingsScreen.class);
		check(SpellcheckSettingsScreen.canOpenConfigFolder(), "no way to open the config folder on this version");
		ctx.clickScreenButton("Español");
		ctx.clickScreenButton("עברית");
		ctx.takeScreenshot("08-language-picker");
		ctx.clickScreenButton("gui.done");
		check(ChatSpellcheckClient.service().settings().languages.equals(List.of(Language.EN_US, Language.ES_ES, Language.HE_IL)),
			"languages after picker: " + ChatSpellcheckClient.service().settings().languages);
		ctx.waitFor(mc -> ChatSpellcheckClient.service().isReady(), TIMEOUT);

		openChat(ctx, "hola amigo, my freind dijo mundoo שלום עולמ");
		waitForWords(ctx, List.of("freind", "mundoo", "עולמ"));
		Rect word = rectOf(ctx, "mundoo");
		hover(ctx, word.x() + 3, word.y() + 4);
		ctx.waitFor(mc -> loadedRows(mc) != null, TIMEOUT);
		List<String> rows = ctx.computeOnClient(ChatSpellcheckClientGameTest::loadedRows);
		check(rows.getFirst().equals("mundo"), "Spanish suggestion: " + rows);
		ctx.takeScreenshot("09-spanish-and-hebrew");
		hover(ctx, 2, 2);

		// Hebrew suggestions are drawn in reading order; choosing one inserts the typed-order word.
		Rect hebrew = rectOf(ctx, "עולמ");
		hover(ctx, hebrew.x() + 3, hebrew.y() + 4);
		ctx.waitFor(mc -> loadedRows(mc) != null, TIMEOUT);
		List<String> hebrewRows = ctx.computeOnClient(ChatSpellcheckClientGameTest::loadedRows);
		List<String> labels = ctx.computeOnClient(mc -> overlay(mc).popoverLabels());
		check(hebrewRows.contains("עולם"), "Hebrew suggestions: " + hebrewRows);
		// Stored in typing order: Minecraft's text drawing puts it in reading order (reversing it
		// here as well would show it backwards).
		check(labels.contains("עולם"), "Hebrew row label: " + labels);
		ctx.takeScreenshot("09b-hebrew-popover");
		hover(ctx, 2, 2);

		// Restore English-only for any later runs.
		ChatSpellcheckClient.service().setLanguages(List.of(Language.EN_US)).join();
		ctx.setScreen(() -> null);
	}

	/** Median/p95 render-thread time of the overlay per frame, printed for the report. */
	private void renderCost(ClientGameTestContext ctx) {
		// Fits the default 854x480 window at GUI scale 2 without scrolling.
		String text = "i recieve teh wierd stuf from the vilage, definately seperate freind";
		openChat(ctx, text);
		waitForWords(ctx, List.of("recieve", "teh", "wierd", "stuf", "vilage", "definately", "seperate", "freind"));
		waitMillis(ctx, 1000); // past the typing grace period
		measure(ctx, "underlines only");
		Rect word = rectOf(ctx, "wierd");
		hover(ctx, word.x() + 3, word.y() + 4);
		ctx.waitFor(mc -> loadedRows(mc) != null, TIMEOUT);
		measure(ctx, "underlines + open popover");
		hover(ctx, 2, 2);
		ctx.setScreen(() -> null);
	}

	/**
	 * Cost of drawing the chat input box, with the right-to-left display hook attached (the mod)
	 * and detached (vanilla), for an English line and a Hebrew line.
	 */
	private void inputDrawCost(ClientGameTestContext ctx) {
		for (String text : List.of("i recieve teh diamonds and wierd stuff from the vilage", "שלום עולם מה שלומך היום חברים יקרים שלי")) {
			openChat(ctx, text);
			ctx.waitTicks(5);
			ctx.runOnClient(mc -> InputTiming.target = input(mc));
			var hooks = ctx.computeOnClient(mc -> (dev.chatspellcheck.client.ChatInputHooks) input(mc));
			var display = ctx.computeOnClient(mc -> hooks.chatspellcheck$display());
			String label = text.startsWith("i ") ? "English line" : "Hebrew line";
			for (boolean attached : new boolean[] {true, false, true, false}) {
				ctx.runOnClient(mc -> hooks.chatspellcheck$setDisplay(attached ? display : null));
				ctx.waitTicks(2);
				ctx.runOnClient(mc -> InputTiming.TIMING.record(300));
				ctx.waitFor(mc -> InputTiming.TIMING.done(), TIMEOUT);
				long[] t = ctx.computeOnClient(mc -> InputTiming.TIMING.stop());
				System.out.printf("[chatspellcheck-timing] input box, %s, %s: median %.1f us, p95 %.1f us%n",
					label, attached ? "mod display" : "vanilla", t[0] / 1000.0, t[1] / 1000.0);
			}
			ctx.runOnClient(mc -> hooks.chatspellcheck$setDisplay(display));
			InputTiming.target = null;
		}
		ctx.setScreen(() -> null);
	}

	private static void measure(ClientGameTestContext ctx, String label) {
		ctx.runOnClient(mc -> OverlayTiming.record(300));
		ctx.waitFor(mc -> OverlayTiming.done(), TIMEOUT);
		long[] t = ctx.computeOnClient(mc -> OverlayTiming.stop());
		System.out.printf("[chatspellcheck-timing] %s: median %.1f us, p95 %.1f us, %d bytes allocated per frame%n",
			label, t[0] / 1000.0, t[1] / 1000.0, t[2]);
	}

	/** Choosing a fix that changes the word's length keeps the caret and selection on the same text. */
	private void caretAndSelectionStayPut(ClientGameTestContext ctx) {
		String text = "so alot of more words";
		openChat(ctx, "");
		ctx.runOnClient(mc -> {
			input(mc).setValue(text);
			input(mc).moveCursorTo(text.indexOf("more"), false);
			input(mc).moveCursorTo(text.indexOf("more") + 4, true); // "more" selected
		});
		waitForWords(ctx, List.of("alot"));
		Rect word = rectOf(ctx, "alot");
		hover(ctx, word.x() + 3, word.y() + 4);
		ctx.waitFor(mc -> loadedRows(mc) != null, TIMEOUT);
		check(ctx.computeOnClient(ChatSpellcheckClientGameTest::loadedRows).getFirst().equals("a lot"), "setup: first fix should be 'a lot'");
		int[] row0 = ctx.computeOnClient(mc -> overlay(mc).popoverRowCenter(0));
		hover(ctx, word.x() + 3, word.y() - 1);
		hover(ctx, row0[0], row0[1]);
		ctx.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
		ctx.waitTick();
		String after = "so a lot of more words";
		check(value(ctx).equals(after), "after fix: " + value(ctx));
		check(cursor(ctx) == after.indexOf("more") + 4, "caret moved to " + cursor(ctx));
		check(ctx.computeOnClient(mc -> input(mc).getHighlighted()).equals("more"), "selection lost: '" + ctx.computeOnClient(mc -> input(mc).getHighlighted()) + "'");
		hover(ctx, 2, 2);
	}

	/** The chat line is scrolled so a misspelled word is cut off at the left edge. */
	private void halfVisibleWordAtTheLeftEdge(ClientGameTestContext ctx) {
		String text = "mispeled" + " ok".repeat(60); // wider than the box, shorter than chat's 256-character limit
		openChat(ctx, "");
		ctx.runOnClient(mc -> {
			input(mc).setValue(text);
			input(mc).moveCursorToEnd(false);
			// One scrollTo(3): the view now starts at "peled". (moveCursorTo scrolls twice, and the
			// second pass snaps back to the start.)
			input(mc).setCursorPosition(3);
		});
		check(ctx.computeOnClient(mc -> ((dev.chatspellcheck.mixin.EditBoxAccessor) input(mc)).chatspellcheck$displayPos()) == 3,
			"view should start inside the word");
		waitForWords(ctx, List.of("mispeled"));
		Rect word = rectOf(ctx, "mispeled");
		int[] expected = ctx.computeOnClient(mc -> new int[] {
			((dev.chatspellcheck.mixin.EditBoxAccessor) input(mc)).chatspellcheck$textX(), mc.font.width("peled")});
		check(word.x() == expected[0] && word.width() == expected[1], "half-visible underline " + word + " expected x=" + expected[0] + " w=" + expected[1]);
		hover(ctx, word.x() + 2, word.y() + 4);
		ctx.waitFor(mc -> loadedRows(mc) != null, TIMEOUT);
		Rect popover = ctx.computeOnClient(mc -> overlay(mc).popoverRect());
		check(popover.x() >= 2 && popover.bottom() == word.y(), "popover for a half-visible word: " + popover);
		ctx.takeScreenshot("10-half-visible-word");
		int[] row0 = ctx.computeOnClient(mc -> overlay(mc).popoverRowCenter(0));
		String first = ctx.computeOnClient(ChatSpellcheckClientGameTest::loadedRows).getFirst();
		hover(ctx, word.x() + 2, word.y() - 1);
		hover(ctx, row0[0], row0[1]);
		ctx.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
		ctx.waitTick();
		check(value(ctx).startsWith(first + " ok"), "the whole word is replaced, not just its visible part: " + value(ctx).substring(0, 20));
		hover(ctx, 2, 2);
	}

	/** Window resize / GUI-scale change re-runs ChatScreen.init(); the keyboard popover must follow its word. */
	private void resizeWhilePopoverIsOpen(ClientGameTestContext ctx) {
		openChat(ctx, "so teh end");
		waitForWords(ctx, List.of("teh"));
		ctx.runOnClient(mc -> input(mc).moveCursorTo(4, false));
		pressShortcut(ctx);
		ctx.waitFor(mc -> loadedRows(mc) != null, TIMEOUT);
		ctx.getInput().resizeWindow(1280, 720);
		ctx.waitTicks(3);
		check(ctx.computeOnClient(mc -> overlay(mc).popoverIsKeyboard()), "popover closed by the resize");
		ctx.waitFor(mc -> overlay(mc).popoverRect() != null && !overlay(mc).underlineRects().isEmpty(), TIMEOUT);
		Rect word = rectOf(ctx, "teh");
		Rect popover = ctx.computeOnClient(mc -> overlay(mc).popoverRect());
		int[] gui = ctx.computeOnClient(mc -> new int[] {mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight()});
		check(popover.bottom() == word.y() && popover.right() <= gui[0] && popover.y() >= 0, "after resize: " + popover + " word " + word);
		ctx.takeScreenshot("11-after-resize");
		ctx.getInput().pressKey(InputConstants.KEY_RETURN);
		ctx.waitTick();
		check(value(ctx).equals("so the end"), "Enter after resize: " + value(ctx));
		ctx.getInput().resizeWindow(854, 480);
		ctx.waitTicks(3);
	}

	/** Vanilla's Tab list and the popover never show at once; Ctrl+. takes over from the Tab list. */
	private void tabCompletionAndPopover(ClientGameTestContext ctx) {
		openChat(ctx, "");
		ctx.getInput().typeChars("teh ");
		waitForWords(ctx, List.of("teh"));
		waitMillis(ctx, 1000);
		Rect word = rectOf(ctx, "teh");
		hover(ctx, word.x() + 2, word.y() + 4);
		ctx.waitFor(mc -> loadedRows(mc) != null, TIMEOUT);
		ctx.getInput().pressKey(InputConstants.KEY_TAB);
		ctx.waitTicks(2);
		boolean listShown = ctx.computeOnClient(ChatSpellcheckClientGameTest::tabListVisible);
		check(listShown, "Tab should show the player-name list (test setup)");
		check(ctx.computeOnClient(mc -> overlay(mc).popoverRect()) == null, "popover must close when the Tab list opens");
		ctx.takeScreenshot("12-tab-list");
		hover(ctx, 2, 2);
		ctx.runOnClient(mc -> input(mc).moveCursorTo(1, false));
		pressShortcut(ctx);
		ctx.waitTicks(2);
		check(!ctx.computeOnClient(ChatSpellcheckClientGameTest::tabListVisible), "Ctrl+. should replace the Tab list");
		check(ctx.computeOnClient(mc -> overlay(mc).popoverIsKeyboard()), "Ctrl+. popover was closed by the Tab list");
		ctx.getInput().pressKey(InputConstants.KEY_ESCAPE);
		ctx.waitTick();
	}

	private void switchingLanguagesWhileChatIsOpen(ClientGameTestContext ctx) {
		ChatSpellcheckClient.service().setLanguages(List.of(Language.EN_US, Language.ES_ES)).join();
		openChat(ctx, "hola mundo teh");
		waitForWords(ctx, List.of("teh"));
		ChatSpellcheckClient.service().setLanguages(List.of(Language.EN_US));
		waitForWords(ctx, List.of("hola", "mundo", "teh"));
		ChatSpellcheckClient.service().setLanguages(List.of(Language.EN_US, Language.ES_ES));
		waitForWords(ctx, List.of("teh"));
		ChatSpellcheckClient.service().setLanguages(List.of(Language.EN_US)).join();
	}

	/**
	 * /spellcheck typed in chat: the command runs while chat is still open, and the screen is
	 * opened on the next tick, after chat has closed. Also from a restored chat draft.
	 */
	private void settingsCommandTypedInChat(ClientGameTestContext ctx) {
		ctx.runOnClient(mc -> mc.options.saveChatDrafts().set(true));
		openChat(ctx, "");
		ctx.getInput().typeChars("/spellcheck");
		ctx.getInput().pressKey(InputConstants.KEY_RETURN);
		ctx.waitForScreen(SpellcheckSettingsScreen.class);
		ctx.takeScreenshot("13-settings-from-command");
		ctx.clickScreenButton("gui.done");
		ctx.waitFor(mc -> mc.gui.screen() == null, TIMEOUT);
		check(reopenedChatText(ctx).isEmpty(), "a sent command must not come back as a draft: " + reopenedChatText(ctx));

		// Typed, then chat closed with Escape: saved as a draft; reopened and sent from the draft.
		openChat(ctx, "");
		ctx.getInput().typeChars("/spellcheck");
		ctx.getInput().pressKey(InputConstants.KEY_ESCAPE);
		ctx.waitFor(mc -> mc.gui.screen() == null, TIMEOUT);
		ctx.runOnClient(mc -> mc.gui.hud.getChat().openScreen(ChatComponent.ChatMethod.COMMAND, ChatScreen::new));
		ctx.waitForScreen(ChatScreen.class);
		check(value(ctx).equals("/spellcheck"), "draft not restored: " + value(ctx));
		ctx.getInput().pressKey(InputConstants.KEY_RETURN);
		ctx.waitForScreen(SpellcheckSettingsScreen.class);
		ctx.clickScreenButton("gui.done");
		ctx.waitFor(mc -> mc.gui.screen() == null, TIMEOUT);
		check(reopenedChatText(ctx).isEmpty(), "draft should be gone after sending it");
	}

	/**
	 * Ctrl+. immediately followed by Enter, before (slow, Norwegian) suggestions arrive, must
	 * either apply a suggestion or do nothing, never silently "Ignore" the word.
	 */
	private void quickEnterNeverIgnores(ClientGameTestContext ctx) {
		ChatSpellcheckClient.service().setLanguages(List.of(Language.EN_US, Language.NB_NO)).join();
		ctx.waitFor(mc -> ChatSpellcheckClient.service().isReady(), TIMEOUT);
		openChat(ctx, "fotbalkampen");
		waitForWords(ctx, List.of("fotbalkampen"));
		pressShortcut(ctx); // one frame: the popover shows "Looking up…"
		boolean wasLoading = ctx.computeOnClient(mc -> {
			boolean loading = overlay(mc).popoverRect() != null && loadedRows(mc) == null;
			mc.gui.screen().keyPressed(new KeyEvent(InputConstants.KEY_RETURN, InputConstants.KEY_RETURN, 0));
			return loading;
		});
		System.out.println("[chatspellcheck] quick Enter arrived while suggestions were loading: " + wasLoading);
		check(ctx.computeOnClient(mc -> mc.gui.screen() instanceof ChatScreen), "Enter in the popover must not send the message");
		waitMillis(ctx, 1500);
		boolean changed = !value(ctx).equals("fotbalkampen");
		boolean stillFlagged = ctx.computeOnClient(mc -> overlay(mc).underlinedWords().stream().anyMatch(m -> m.word().equals("fotbalkampen")));
		check(changed || stillFlagged, "quick Ctrl+. Enter ignored the word");
		ctx.getInput().pressKey(InputConstants.KEY_ESCAPE);
		ChatSpellcheckClient.service().setLanguages(List.of(Language.EN_US)).join();
		ctx.setScreen(() -> null);
	}

	/** Mod Menu lists the mod with its icon, and its settings button opens our screen and returns to the list. */
	private void modMenuSettingsButton(ClientGameTestContext ctx) {
		ctx.setScreen(() -> com.terraformersmc.modmenu.api.ModMenuApi.createModsScreen(null));
		ctx.waitTicks(5);
		net.minecraft.client.gui.screens.Screen modList = ctx.computeOnClient(mc -> mc.gui.screen());
		ctx.takeScreenshot("14-mod-menu");
		List<com.terraformersmc.modmenu.api.ModMenuApi> apis = FabricLoader.getInstance()
			.getEntrypointContainers("modmenu", com.terraformersmc.modmenu.api.ModMenuApi.class).stream()
			.filter(c -> c.getProvider().getMetadata().getId().equals("chatspellcheck"))
			.map(net.fabricmc.loader.api.entrypoint.EntrypointContainer::getEntrypoint).toList();
		check(apis.size() == 1, "Mod Menu entrypoint missing");
		ctx.setScreen(() -> apis.getFirst().getModConfigScreenFactory().create(modList));
		ctx.waitForScreen(SpellcheckSettingsScreen.class);
		ctx.clickScreenButton("gui.done");
		ctx.waitTicks(2);
		check(ctx.computeOnClient(mc -> mc.gui.screen() == modList), "Done should return to the mod list");
		ctx.setScreen(() -> null);
	}

	/** Opt-in autocorrect: fixes finished words, Backspace undoes, names/slang/ambiguous words are left alone. */
	private void autocorrectAsYouType(ClientGameTestContext ctx) {
		ctx.runOnClient(mc -> ChatSpellcheckClient.service().settings().autocorrect = true);
		openChat(ctx, "");
		ctx.getInput().typeChars("i recieve teh ");
		waitForValue(ctx, "i receive the ");
		String notice = ctx.computeOnClient(mc -> overlay(mc).noticeText());
		check(notice != null && notice.contains("teh") && notice.contains("the"), "expected a correction notice, got " + notice);
		ctx.takeScreenshot("15-autocorrected");
		check(cursor(ctx) == "i receive the ".length(), "cursor after correction at " + cursor(ctx));

		ctx.getInput().typeChars("freind ");
		waitForValue(ctx, "i receive the friend ");
		ctx.getInput().pressKey(InputConstants.KEY_BACKSPACE);
		ctx.waitTick();
		check(value(ctx).equals("i receive the freind "), "Backspace should undo the correction: " + value(ctx));
		ctx.getInput().typeChars("and freind ");
		waitMillis(ctx, 1500);
		check(value(ctx).equals("i receive the freind and freind "), "an undone word must not be corrected again: " + value(ctx));

		ctx.runOnClient(mc -> input(mc).setValue(""));
		ctx.getInput().typeChars("so Zorblax said wnat yeet ok. ");
		waitMillis(ctx, 1500);
		check(value(ctx).equals("so Zorblax said wnat yeet ok. "), "names, ambiguous typos and slang must be left alone: " + value(ctx));

		// Minecraft words are fixed too, not just dictionary words.
		ctx.runOnClient(mc -> input(mc).setValue(""));
		ctx.getInput().typeChars("i love minectaft ");
		waitForValue(ctx, "i love minecraft ");

		// Sentence start, and a fix that is longer than the typo (cursor must follow).
		ctx.runOnClient(mc -> input(mc).setValue(""));
		ctx.getInput().typeChars("Teh end. i dont ");
		waitForValue(ctx, "The end. i don't ");
		check(cursor(ctx) == "The end. i don't ".length(), "cursor after a longer fix at " + cursor(ctx));

		// A space typed inside a word splits it; the left half isn't "finished".
		ctx.runOnClient(mc -> {
			input(mc).setValue("so tehcake");
			input(mc).moveCursorTo("so teh".length(), false);
		});
		ctx.getInput().typeChars(" ");
		waitMillis(ctx, 1200);
		check(value(ctx).equals("so teh cake"), "split word must not be corrected: " + value(ctx));

		// Commands are never auto-corrected.
		ctx.runOnClient(mc -> input(mc).setValue(""));
		ctx.getInput().typeChars("/msg Alex teh ");
		waitMillis(ctx, 1200);
		check(value(ctx).equals("/msg Alex teh "), "commands must not be corrected: " + value(ctx));
		ChatSpellcheckClient.service().setLanguages(List.of(Language.EN_US, Language.HE_IL)).join();
		ctx.waitFor(mc -> ChatSpellcheckClient.service().isReady(), TIMEOUT);
		// Hebrew: final letters and unambiguous longer typos are fixed.
		ctx.runOnClient(mc -> input(mc).setValue("שלום עולמ"));
		ctx.runOnClient(mc -> input(mc).moveCursorToEnd(false));
		ctx.getInput().typeChars(" ");
		waitForValue(ctx, "שלום עולם ");
		ctx.takeScreenshot("15b-hebrew-final-letter");
		String hebrewNotice = ctx.computeOnClient(mc -> overlay(mc).noticeText());
		check(hebrewNotice != null && hebrewNotice.contains("עולמ") && hebrewNotice.contains("עולם"), "Hebrew notice: " + hebrewNotice);
		ctx.runOnClient(mc -> input(mc).setValue("שלום תדוה"));
		ctx.runOnClient(mc -> input(mc).moveCursorToEnd(false));
		ctx.getInput().typeChars(" ");
		waitMillis(ctx, 1500);
		check(value(ctx).equals("שלום תדוה "), "short Hebrew typos must not be auto-fixed: " + value(ctx));
		ctx.runOnClient(mc -> input(mc).setValue("עכשחו"));
		ctx.runOnClient(mc -> input(mc).moveCursorToEnd(false));
		ctx.getInput().typeChars(" ");
		waitForValue(ctx, "עכשיו ");
		ctx.takeScreenshot("15c-hebrew-typo");
		ctx.getInput().pressKey(InputConstants.KEY_BACKSPACE);
		ctx.waitTick();
		check(value(ctx).equals("עכשחו "), "Backspace should undo the Hebrew correction: " + value(ctx));
		ctx.getInput().typeChars("עכשחו ");
		waitMillis(ctx, 1500);
		check(value(ctx).equals("עכשחו עכשחו "), "an undone Hebrew word must not be corrected again: " + value(ctx));

		// Spanish: a missing accent is fixed while typing.
		ChatSpellcheckClient.service().setLanguages(List.of(Language.EN_US, Language.ES_ES)).join();
		ctx.waitFor(mc -> ChatSpellcheckClient.service().isReady(), TIMEOUT);
		ctx.runOnClient(mc -> input(mc).setValue(""));
		ctx.getInput().typeChars("una cancion ");
		waitForValue(ctx, "una canción ");
		ChatSpellcheckClient.service().setLanguages(List.of(Language.EN_US)).join();

		ctx.runOnClient(mc -> ChatSpellcheckClient.service().settings().autocorrect = false);
		ctx.runOnClient(mc -> input(mc).setValue(""));
		ctx.getInput().typeChars("teh ");
		waitMillis(ctx, 1200);
		check(value(ctx).equals("teh "), "autocorrect must stay off when disabled: " + value(ctx));
		ctx.setScreen(() -> null);
	}

	/**
	 * Hebrew/Arabic are shown in reading order while typing (display only). Underlines and mouse
	 * clicks follow the displayed positions; the text itself is unchanged.
	 */
	private void rightToLeftDisplay(ClientGameTestContext ctx) {
		ChatSpellcheckClient.service().setLanguages(List.of(Language.EN_US, Language.HE_IL, Language.AR)).join();
		ctx.waitFor(mc -> ChatSpellcheckClient.service().isReady(), TIMEOUT);
		String text = "hello שלום עולמ ok";
		openChat(ctx, text);
		waitForWords(ctx, List.of("עולמ"));
		ctx.takeScreenshot("16-hebrew-display");
		int start = text.indexOf("עולמ");
		int[] expected = ctx.computeOnClient(mc -> {
			var b = (dev.chatspellcheck.mixin.EditBoxAccessor) input(mc);
			dev.chatspellcheck.ui.BidiLine line = dev.chatspellcheck.ui.BidiLine.layout(text, mc.font::width, x -> x);
			List<int[]> r = line.ranges(start, start + 4);
			return new int[] {b.chatspellcheck$textX() + r.getFirst()[0], b.chatspellcheck$textX() + line.caretX(start + 1)};
		});
		Rect word = rectOf(ctx, "עולמ");
		check(word.x() == expected[0], "underline should follow the displayed word: x=" + word.x() + " expected " + expected[0]);
		// "עולמ" is displayed before "שלום" (right-to-left run), unlike typing order.
		check(word.x() < rectOfText(ctx, text, "שלום"), "Hebrew run should be reversed on screen");

		// A click between ע and ו on screen puts the caret between them in the text.
		double[] window = ctx.computeOnClient(mc -> new double[] {
			expected[1] * (double) mc.getWindow().getScreenWidth() / mc.getWindow().getGuiScaledWidth(),
			(word.y() + 4) * (double) mc.getWindow().getScreenHeight() / mc.getWindow().getGuiScaledHeight()});
		ctx.getInput().setCursorPos(window[0], window[1]);
		ctx.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
		ctx.waitTick();
		check(cursor(ctx) == start + 1, "click mapped to " + cursor(ctx) + ", expected " + (start + 1));
		check(value(ctx).equals(text), "the text itself must not change: " + value(ctx));
		hover(ctx, 2, 2);

		// Selecting part of a Hebrew line highlights the displayed (reordered) letters.
		ctx.runOnClient(mc -> {
			input(mc).moveCursorTo(text.indexOf("שלום"), false);
			input(mc).moveCursorTo(text.indexOf("עולמ") + 2, true);
		});
		ctx.waitTicks(2);
		ctx.takeScreenshot("16b-hebrew-selection");

		// A long Hebrew line scrolled sideways: the underline stays inside the box.
		String longText = "שלום ".repeat(30) + "עולמ";
		openChat(ctx, longText);
		ctx.runOnClient(mc -> input(mc).moveCursorToEnd(false));
		waitForWords(ctx, List.of("עולמ"));
		Rect far = rectOf(ctx, "עולמ");
		int[] box = ctx.computeOnClient(mc -> new int[] {((dev.chatspellcheck.mixin.EditBoxAccessor) input(mc)).chatspellcheck$textX(), input(mc).getInnerWidth()});
		check(far.x() >= box[0] && far.right() <= box[0] + box[1], "scrolled underline outside the box: " + far);
		ctx.takeScreenshot("16c-hebrew-scrolled");

		openChat(ctx, "مرحبا بالعالم hello");
		ctx.waitTicks(5);
		ctx.takeScreenshot("17-arabic-display");
		check(value(ctx).equals("مرحبا بالعالم hello"), "Arabic text must not change");
		ChatSpellcheckClient.service().setLanguages(List.of(Language.EN_US)).join();
		ctx.setScreen(() -> null);
	}

	private static int rectOfText(ClientGameTestContext ctx, String text, String word) {
		return ctx.computeOnClient(mc -> {
			var b = (dev.chatspellcheck.mixin.EditBoxAccessor) input(mc);
			int i = text.indexOf(word);
			return b.chatspellcheck$textX() + dev.chatspellcheck.ui.BidiLine.layout(text, mc.font::width, x -> x).ranges(i, i + word.length()).getFirst()[0];
		});
	}

	private static void waitForValue(ClientGameTestContext ctx, String expected) {
		ctx.waitFor(mc -> input(mc).getValue().equals(expected), TIMEOUT);
	}

	private static String reopenedChatText(ClientGameTestContext ctx) {
		return ctx.computeOnClient(mc -> {
			String[] text = new String[1];
			mc.gui.hud.getChat().createScreen(ChatComponent.ChatMethod.MESSAGE, (initial, isDraft) -> {
				text[0] = initial;
				return new ChatScreen(initial, isDraft);
			});
			return text[0];
		});
	}

	private static boolean tabListVisible(Minecraft mc) {
		try {
			java.lang.reflect.Field f = ChatScreen.class.getDeclaredField("commandSuggestions");
			f.setAccessible(true);
			return ((net.minecraft.client.gui.components.CommandSuggestions) f.get(mc.gui.screen())).isVisible();
		} catch (ReflectiveOperationException e) {
			throw new AssertionError(e);
		}
	}

	// ---------------------------------------------------------------- helpers

	private static void openChat(ClientGameTestContext ctx, String initial) {
		ctx.setScreen(() -> new ChatScreen(initial, false));
		ctx.waitForScreen(ChatScreen.class);
		ctx.waitTick();
	}

	private static ChatSpellcheckOverlay overlay(Minecraft mc) {
		return ((ChatSpellcheckHost) mc.gui.screen()).chatspellcheck$overlay();
	}

	private static EditBox input(Minecraft mc) {
		return (EditBox) mc.gui.screen().getFocused();
	}

	private static String value(ClientGameTestContext ctx) {
		return ctx.computeOnClient(mc -> input(mc).getValue());
	}

	private static int cursor(ClientGameTestContext ctx) {
		return ctx.computeOnClient(mc -> input(mc).getCursorPosition());
	}

	private static List<String> loadedRows(Minecraft mc) {
		ChatSpellcheckOverlay o = overlay(mc);
		if (o.popoverRect() == null) {
			return null;
		}
		List<String> rows = o.popoverRows();
		return rows.isEmpty() || rows.getFirst().startsWith("Looking up") ? null : rows;
	}

	private static void waitForWords(ClientGameTestContext ctx, List<String> expected) {
		Function<Minecraft, List<String>> words = mc -> overlay(mc).underlinedWords().stream().map(Misspelling::word).toList();
		ctx.waitFor(mc -> words.apply(mc).equals(expected), TIMEOUT);
	}

	private static Rect rectOf(ClientGameTestContext ctx, String word) {
		return ctx.computeOnClient(mc -> {
			ChatSpellcheckOverlay o = overlay(mc);
			List<Misspelling> words = o.underlinedWords();
			for (int i = 0; i < words.size(); i++) {
				if (words.get(i).word().equals(word)) {
					return o.underlineRects().get(i);
				}
			}
			throw new AssertionError(word + " is not underlined: " + words);
		});
	}

	/** Moves the real mouse to a GUI-space point and waits long enough for hover to register. */
	private static void hover(ClientGameTestContext ctx, double guiX, double guiY) {
		double[] window = ctx.computeOnClient(mc -> new double[] {
			guiX * mc.getWindow().getScreenWidth() / mc.getWindow().getGuiScaledWidth(),
			guiY * mc.getWindow().getScreenHeight() / mc.getWindow().getGuiScaledHeight()});
		ctx.getInput().setCursorPos(window[0], window[1]);
		waitMillis(ctx, 250); // > PopoverModel.OPEN_DELAY_MS (the mod uses wall-clock time)
	}

	private static void waitMillis(ClientGameTestContext ctx, long millis) {
		long until = System.currentTimeMillis() + millis;
		while (System.currentTimeMillis() < until) {
			ctx.waitTick();
		}
	}

	/**
	 * Ctrl+. delivered through the real ChatScreen.keyPressed path. TestInput's synthetic key
	 * events carry no modifier bits (modifiers=0 even while holdControl is active), unlike real
	 * SDL input, so the event is built here with the Ctrl modifier set.
	 */
	private static void pressShortcut(ClientGameTestContext ctx) {
		ctx.runOnClient(mc -> mc.gui.screen().keyPressed(new KeyEvent(InputConstants.KEY_PERIOD, InputConstants.KEY_PERIOD, CTRL_MODIFIER)));
		ctx.waitTick();
	}

	private static void check(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError(message);
		}
	}
}
