package dev.chatspellcheck.gametest;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

/**
 * Captures frames for the listing video: one screenshot per step plus how long to show it and
 * where the (otherwise invisible) mouse cursor is. tools/make-demo-video.swift turns the
 * manifest into an MP4.
 */
final class DemoRecorder {
	private final Path manifest;
	private int count;
	private double cursorX = -1;
	private double cursorY = -1;
	private String caption = "";

	DemoRecorder(Path manifest) {
		this.manifest = manifest;
		try {
			Files.createDirectories(manifest.getParent());
			Files.writeString(manifest, "");
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/** Caption shown over the following frames. */
	void caption(String text) {
		caption = text;
	}

	/** Remembers the cursor position in window pixels; -1 hides it. */
	void cursor(double x, double y) {
		cursorX = x;
		cursorY = y;
	}

	void frame(ClientGameTestContext ctx, int holdMillis) {
		Path shot = ctx.takeScreenshot(String.format("demo-%03d", count++));
		String line = shot.toAbsolutePath() + "\t" + holdMillis + "\t" + Math.round(cursorX) + "\t" + Math.round(cursorY) + "\t" + caption + "\n";
		try {
			Files.writeString(manifest, line, StandardOpenOption.APPEND);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}
