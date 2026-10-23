package dev.chatspellcheck.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StatsTest {
	@TempDir
	Path dir;

	@Test
	void countsSavesAndReloads() throws Exception {
		AtomicReference<LocalDate> today = new AtomicReference<>(LocalDate.of(2026, 10, 20));
		Path file = dir.resolve("stats.properties");
		Stats stats = new Stats(file, today::get);
		stats.messageSent(5, 0);
		stats.messageSent(3, 0);
		stats.fixed(1);
		today.set(today.get().plusDays(2));
		stats.fixed(1);
		stats.fixed(1);
		stats.fixed(-1); // an undone autocorrection
		stats.wordAdded();
		stats.save();

		Stats.Snapshot s = stats.snapshot();
		assertEquals(8, s.wordsChecked());
		assertEquals(2, s.typosFixed());
		assertEquals(2, s.streak());
		assertArrayEquals(new int[] {0, 0, 0, 0, 1, 0, 1}, s.fixesByDay());

		Stats again = new Stats(file, today::get);
		again.load();
		Stats.Snapshot t = again.snapshot();
		assertEquals(8, t.wordsChecked());
		assertEquals(2, t.typosFixed());
		assertEquals(1, t.wordsAdded());
		assertEquals(0, t.sessionWords(), "session numbers start from zero");
		assertArrayEquals(s.fixesByDay(), t.fixesByDay());
	}

	@Test
	void aMessageWithTyposEndsTheStreakButKeepsTheBest() {
		Stats stats = Stats.inMemory();
		stats.messageSent(4, 0);
		stats.messageSent(4, 0);
		stats.messageSent(4, 1);
		stats.messageSent(0, 0); // nothing checkable, e.g. a command: doesn't count
		assertEquals(0, stats.snapshot().streak());
		assertEquals(2, stats.snapshot().bestStreak());
	}

	@Test
	void oldDaysDropOffAndBadValuesAreZero() throws Exception {
		AtomicReference<LocalDate> today = new AtomicReference<>(LocalDate.of(2026, 10, 1));
		Path file = dir.resolve("stats.properties");
		Files.writeString(file, "typosFixed=lots\nwordsChecked=-4\nfixes.2026-09-20=9\nfixes.2026-09-30=2\nfixes.someday=3\n");
		Stats stats = new Stats(file, today::get);
		stats.load();
		Stats.Snapshot s = stats.snapshot();
		assertEquals(0, s.typosFixed());
		assertEquals(0, s.wordsChecked());
		assertArrayEquals(new int[] {0, 0, 0, 0, 0, 2, 0}, s.fixesByDay());
		today.set(LocalDate.of(2026, 10, 7));
		assertArrayEquals(new int[7], stats.snapshot().fixesByDay());
	}
}
