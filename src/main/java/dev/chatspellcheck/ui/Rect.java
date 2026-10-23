package dev.chatspellcheck.ui;

/** Integer rectangle in GUI-scaled coordinates; {@code right}/{@code bottom} are exclusive. */
public record Rect(int x, int y, int width, int height) {
	public int right() {
		return x + width;
	}

	public int bottom() {
		return y + height;
	}

	public boolean contains(double px, double py) {
		return px >= x && px < right() && py >= y && py < bottom();
	}

	/** Smallest rectangle covering both (used for the hover "bridge" between word and popover). */
	public Rect union(Rect o) {
		int nx = Math.min(x, o.x);
		int ny = Math.min(y, o.y);
		return new Rect(nx, ny, Math.max(right(), o.right()) - nx, Math.max(bottom(), o.bottom()) - ny);
	}
}
