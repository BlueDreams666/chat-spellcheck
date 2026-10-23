package dev.chatspellcheck.ui;

import dev.chatspellcheck.core.Misspelling;

/**
 * Open/close state and keyboard selection for the suggestion popover, independent of rendering.
 *
 * <ul>
 *   <li>Hover: opens after the pointer rests on an underlined word for {@link #OPEN_DELAY_MS};
 *       stays open while the pointer is over the word, the popover, or the area between them,
 *       and closes {@link #CLOSE_GRACE_MS} after it leaves.</li>
 *   <li>Keyboard: opened explicitly; owns Up/Down/Enter/Escape until closed; ignores the mouse.</li>
 *   <li>Escape on a hovered word suppresses it until the pointer leaves that word.</li>
 * </ul>
 */
public final class PopoverModel {
	public static final long OPEN_DELAY_MS = 150;
	public static final long CLOSE_GRACE_MS = 250;

	private Misspelling target;
	private boolean keyboard;
	private int selected = -1;
	private Misspelling hoverCandidate;
	private long hoverSince;
	private long outsideSince = -1;
	private Misspelling suppressed;

	public Misspelling target() {
		return target;
	}

	public boolean isOpen() {
		return target != null;
	}

	public boolean isKeyboard() {
		return target != null && keyboard;
	}

	public int selected() {
		return selected;
	}

	/**
	 * Per-frame pointer update.
	 *
	 * @param hovered       underlined word under the pointer, or null
	 * @param insideKeepZone pointer is over the open popover or the bridge between it and the word
	 * @param dragging      a mouse button is held (selecting text): never open then
	 */
	public void onPointer(long now, Misspelling hovered, boolean insideKeepZone, boolean dragging) {
		if (suppressed != null && !suppressed.equals(hovered)) {
			suppressed = null;
		}
		if (keyboard && target != null) {
			return;
		}
		if (target != null) {
			if (target.equals(hovered)) {
				outsideSince = -1;
				return;
			}
			if (hovered != null && !dragging && !hovered.equals(suppressed)) {
				open(hovered, false); // moved straight onto another underlined word
				return;
			}
			if (insideKeepZone) {
				outsideSince = -1;
				return;
			}
			if (outsideSince < 0) {
				outsideSince = now;
			} else if (now - outsideSince >= CLOSE_GRACE_MS) {
				close();
			}
			return;
		}
		if (hovered == null || dragging || hovered.equals(suppressed)) {
			hoverCandidate = null;
			return;
		}
		if (!hovered.equals(hoverCandidate)) {
			hoverCandidate = hovered;
			hoverSince = now;
		} else if (now - hoverSince >= OPEN_DELAY_MS) {
			open(hovered, false);
		}
	}

	public void openForKeyboard(Misspelling word) {
		open(word, true);
		selected = 0;
	}

	private void open(Misspelling word, boolean viaKeyboard) {
		target = word;
		keyboard = viaKeyboard;
		selected = viaKeyboard ? 0 : -1;
		outsideSince = -1;
		hoverCandidate = null;
	}

	public void close() {
		target = null;
		keyboard = false;
		selected = -1;
		outsideSince = -1;
		hoverCandidate = null;
	}

	/** Escape: close, and don't reopen on the same hovered word until the pointer leaves it. */
	public void dismiss() {
		if (target != null && !keyboard) {
			suppressed = target;
		}
		close();
	}

	/** Keeps the popover attached after edits elsewhere in the line; closes if the word is gone. */
	public void retarget(Misspelling remapped) {
		if (target == null) {
			return; // nothing open; must not reset a pending hover
		}
		if (remapped == null) {
			close();
		} else {
			target = remapped;
		}
	}

	/** Moves the keyboard highlight, wrapping, over {@code rowCount} selectable rows. */
	public void move(int direction, int rowCount) {
		if (rowCount <= 0) {
			selected = -1;
			return;
		}
		int current = selected < 0 ? (direction > 0 ? -1 : 0) : selected;
		selected = Math.floorMod(current + direction, rowCount);
	}

	/** Pointer highlight in hover mode (keyboard mode keeps its own selection). */
	public void hoverRow(int row) {
		if (!keyboard) {
			selected = row;
		}
	}
}
