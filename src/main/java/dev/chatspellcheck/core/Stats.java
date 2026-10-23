package dev.chatspellcheck.core;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.function.Supplier;

/**
 * Little numbers for the settings screen: words checked in sent messages, typos fixed, words
 * added, and the streak of messages sent without a typo; for this session and all time, plus
 * fixes for each of the last {@link #DAYS} days. Kept in {@code stats.properties} on this
 * computer only. All methods are synchronized; the render thread counts, the io thread saves.
 */
public final class Stats {
	public static final int DAYS = 7;

	private final Path file;
	private final Supplier<LocalDate> today;

	private long wordsChecked;
	private long typosFixed;
	private long wordsAdded;
	private int streak;
	private int bestStreak;
	/** Fixes per day, for at most the last {@link #DAYS} days. */
	private final TreeMap<LocalDate, Integer> fixesByDay = new TreeMap<>();

	private long sessionWords;
	private long sessionFixes;
	private boolean dirty;

	public Stats(Path file) {
		this(file, LocalDate::now);
	}

	Stats(Path file, Supplier<LocalDate> today) {
		this.file = file;
		this.today = today;
	}

	/** Stats that are counted but never saved. */
	public static Stats inMemory() {
		return new Stats(null);
	}

	/** Reads the file if there is one; unreadable values count as zero. */
	public synchronized void load() throws IOException {
		if (file == null || !Files.exists(file)) {
			return;
		}
		Properties p = new Properties();
		try {
			p.load(new StringReader(TextFiles.read(file)));
		} catch (IllegalArgumentException e) {
			throw new IOException("Malformed stats file " + file + ": " + e.getMessage(), e);
		}
		wordsChecked = number(p, "wordsChecked");
		typosFixed = number(p, "typosFixed");
		wordsAdded = number(p, "wordsAdded");
		streak = (int) Math.min(Integer.MAX_VALUE, number(p, "streak"));
		bestStreak = (int) Math.max(streak, Math.min(Integer.MAX_VALUE, number(p, "bestStreak")));
		fixesByDay.clear();
		for (String key : p.stringPropertyNames()) {
			if (key.startsWith("fixes.")) {
				try {
					fixesByDay.put(LocalDate.parse(key.substring(6)), (int) Math.min(Integer.MAX_VALUE, number(p, key)));
				} catch (DateTimeParseException ignored) {
					// a hand-edited key; skip it
				}
			}
		}
		trimDays();
	}

	private static long number(Properties p, String key) {
		try {
			return Math.max(0, Long.parseLong(p.getProperty(key, "0").strip()));
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	/** Writes the file if anything changed since the last save. */
	public void save() throws IOException {
		Properties p = new Properties();
		synchronized (this) {
			if (!dirty || file == null) {
				return;
			}
			dirty = false;
			p.setProperty("wordsChecked", Long.toString(wordsChecked));
			p.setProperty("typosFixed", Long.toString(typosFixed));
			p.setProperty("wordsAdded", Long.toString(wordsAdded));
			p.setProperty("streak", Integer.toString(streak));
			p.setProperty("bestStreak", Integer.toString(bestStreak));
			fixesByDay.forEach((day, n) -> p.setProperty("fixes." + day, Integer.toString(n)));
		}
		StringWriter w = new StringWriter();
		p.store(w, "Chat Spellcheck stats. Only kept on this computer; delete this file to reset them.");
		try {
			TextFiles.writeAtomically(file, w.toString().getBytes(StandardCharsets.UTF_8));
		} catch (IOException e) {
			synchronized (this) {
				dirty = true;
			}
			throw e;
		}
	}

	/** A message was sent with {@code words} checkable words, {@code typos} of them still underlined. */
	public synchronized void messageSent(int words, int typos) {
		if (words <= 0) {
			return;
		}
		wordsChecked += words;
		sessionWords += words;
		streak = typos == 0 ? streak + 1 : 0;
		bestStreak = Math.max(bestStreak, streak);
		dirty = true;
	}

	/** A suggestion was picked or an autocorrection kept; {@code -1} when an autocorrection is undone. */
	public synchronized void fixed(int delta) {
		typosFixed = Math.max(0, typosFixed + delta);
		sessionFixes = Math.max(0, sessionFixes + delta);
		fixesByDay.put(today.get(), Math.max(0, fixesByDay.getOrDefault(today.get(), 0) + delta));
		trimDays();
		dirty = true;
	}

	public synchronized void wordAdded() {
		wordsAdded++;
		dirty = true;
	}

	private void trimDays() {
		LocalDate first = today.get().minusDays(DAYS - 1);
		fixesByDay.headMap(first).clear();
		fixesByDay.tailMap(today.get(), false).clear(); // clock moved back
	}

	/** An immutable copy of the numbers, for drawing. */
	public synchronized Snapshot snapshot() {
		trimDays();
		int[] days = new int[DAYS];
		LocalDate first = today.get().minusDays(DAYS - 1);
		for (Map.Entry<LocalDate, Integer> e : fixesByDay.entrySet()) {
			days[(int) (e.getKey().toEpochDay() - first.toEpochDay())] = e.getValue();
		}
		return new Snapshot(wordsChecked, typosFixed, wordsAdded, streak, bestStreak, sessionWords, sessionFixes, days);
	}

	/** @param fixesByDay fixes on each of the last {@link #DAYS} days, oldest first, today last */
	public record Snapshot(long wordsChecked, long typosFixed, long wordsAdded, int streak, int bestStreak,
		long sessionWords, long sessionFixes, int[] fixesByDay) {
	}
}
