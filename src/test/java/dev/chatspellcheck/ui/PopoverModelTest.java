package dev.chatspellcheck.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.chatspellcheck.core.Misspelling;
import org.junit.jupiter.api.Test;

class PopoverModelTest {
	private static final Misspelling TEH = new Misspelling(0, 3, "teh");
	private static final Misspelling RECIEVE = new Misspelling(4, 11, "recieve");

	@Test
	void hoverOpensOnlyAfterADelay() {
		PopoverModel m = new PopoverModel();
		m.onPointer(1000, TEH, false, false);
		assertFalse(m.isOpen());
		m.onPointer(1000 + PopoverModel.OPEN_DELAY_MS - 1, TEH, false, false);
		assertFalse(m.isOpen());
		m.onPointer(1000 + PopoverModel.OPEN_DELAY_MS, TEH, false, false);
		assertEquals(TEH, m.target());
		assertFalse(m.isKeyboard());
	}

	@Test
	void passingOverAWordDoesNotOpenIt() {
		PopoverModel m = new PopoverModel();
		m.onPointer(0, TEH, false, false);
		m.onPointer(50, null, false, false);
		m.onPointer(500, null, false, false);
		assertFalse(m.isOpen());
	}

	@Test
	void staysOpenWhilePointerIsInThePopoverThenClosesAfterGrace() {
		PopoverModel m = open();
		m.onPointer(2000, null, true, false); // moved into the popover
		m.onPointer(5000, null, true, false);
		assertTrue(m.isOpen());
		m.onPointer(5001, null, false, false); // left
		m.onPointer(5001 + PopoverModel.CLOSE_GRACE_MS - 1, null, false, false);
		assertTrue(m.isOpen());
		m.onPointer(5001 + PopoverModel.CLOSE_GRACE_MS, null, false, false);
		assertFalse(m.isOpen());
	}

	@Test
	void returningWithinGraceKeepsItOpen() {
		PopoverModel m = open();
		m.onPointer(2000, null, false, false);
		m.onPointer(2100, null, true, false);
		m.onPointer(2000 + PopoverModel.CLOSE_GRACE_MS + 50, null, true, false);
		assertTrue(m.isOpen());
	}

	@Test
	void movingToAnotherUnderlinedWordSwitchesImmediately() {
		PopoverModel m = open();
		m.onPointer(2000, RECIEVE, true, false);
		assertEquals(RECIEVE, m.target());
	}

	@Test
	void doesNotOpenWhileDraggingASelection() {
		PopoverModel m = new PopoverModel();
		m.onPointer(0, TEH, false, true);
		m.onPointer(1000, TEH, false, true);
		assertFalse(m.isOpen());
	}

	@Test
	void escapeSuppressesTheHoveredWordUntilThePointerLeaves() {
		PopoverModel m = open();
		m.dismiss();
		assertFalse(m.isOpen());
		m.onPointer(3000, TEH, false, false);
		m.onPointer(9000, TEH, false, false);
		assertFalse(m.isOpen(), "should stay dismissed while still hovering");
		m.onPointer(9100, null, false, false);
		m.onPointer(9200, TEH, false, false);
		m.onPointer(9200 + PopoverModel.OPEN_DELAY_MS, TEH, false, false);
		assertTrue(m.isOpen());
	}

	@Test
	void keyboardModeIgnoresThePointerAndWrapsSelection() {
		PopoverModel m = new PopoverModel();
		m.openForKeyboard(TEH);
		assertTrue(m.isKeyboard());
		assertEquals(0, m.selected());
		m.onPointer(0, null, false, false);
		m.onPointer(10_000, RECIEVE, false, false);
		assertEquals(TEH, m.target());
		m.move(-1, 4);
		assertEquals(3, m.selected());
		m.move(1, 4);
		assertEquals(0, m.selected());
		m.hoverRow(2); // pointer highlight doesn't steal keyboard selection
		assertEquals(0, m.selected());
	}

	@Test
	void retargetFollowsEditsAndClosesWhenTheWordIsGone() {
		PopoverModel m = open();
		Misspelling shifted = new Misspelling(5, 8, "teh");
		m.retarget(shifted);
		assertEquals(shifted, m.target());
		m.retarget(null);
		assertNull(m.target());
	}

	@Test
	void perFrameRetargetWhileClosedDoesNotResetHover() {
		// Regression: the overlay calls retarget every frame; while closed that must not
		// restart the hover delay, or the popover never opens.
		PopoverModel m = new PopoverModel();
		for (long t = 0; t <= PopoverModel.OPEN_DELAY_MS; t += 16) {
			m.onPointer(t, TEH, false, false);
			m.retarget(null);
		}
		m.onPointer(PopoverModel.OPEN_DELAY_MS + 16, TEH, false, false);
		assertTrue(m.isOpen());
	}

	private static PopoverModel open() {
		PopoverModel m = new PopoverModel();
		m.onPointer(1000, TEH, false, false);
		m.onPointer(1000 + PopoverModel.OPEN_DELAY_MS, TEH, false, false);
		assertTrue(m.isOpen());
		return m;
	}
}
