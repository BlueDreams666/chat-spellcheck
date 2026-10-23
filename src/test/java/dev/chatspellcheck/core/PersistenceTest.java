package dev.chatspellcheck.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Hand-edited, corrupted, read-only and concurrently written config files. */
class PersistenceTest {
	@TempDir
	Path dir;

	@Test
	void brokenUnicodeEscapeInSettingsIsAnIOExceptionNotACrash() throws Exception {
		Path file = dir.resolve("settings.properties");
		Files.writeString(file, "enabled=true\nlanguages=de_DE\nnote=\\u12G4\n");
		Settings s = new Settings(file);
		// Properties.load throws IllegalArgumentException here, which used to escape the mod initialiser.
		assertThrows(IOException.class, s::load);
		assertEquals(List.of(Language.EN_US), s.languages, "defaults are kept");
		assertTrue(Files.readString(file).contains("\\u12G4"), "the player's file is not overwritten on load");
	}

	@Test
	void newSettingsRoundTripAndBadValuesKeepDefaults() throws Exception {
		Path file = dir.resolve("settings.properties");
		Settings s = new Settings(file);
		s.load();
		assertTrue(s.firstRun, "no file yet");
		assertTrue(s.skipNames);
		assertEquals(Settings.UnderlineStyle.SQUIGGLE, s.underlineStyle);
		assertEquals(Settings.WELCOME_TIPS, s.welcomeTips);
		s.skipNames = false;
		s.underlineStyle = Settings.UnderlineStyle.DOTTED;
		s.underlineColor = Settings.UnderlineColor.BLUE;
		s.welcomeTips = 0;
		s.save();

		Settings again = new Settings(file);
		again.load();
		assertFalse(again.firstRun);
		assertFalse(again.skipNames);
		assertEquals(Settings.UnderlineStyle.DOTTED, again.underlineStyle);
		assertEquals(Settings.UnderlineColor.BLUE, again.underlineColor);
		assertEquals(0, again.welcomeTips);

		Files.writeString(file, "underlineStyle=wavy\nunderlineColor= Pink \nwelcomeTips=lots\n");
		again.load();
		assertEquals(Settings.UnderlineStyle.SQUIGGLE, again.underlineStyle);
		assertEquals(Settings.UnderlineColor.PINK, again.underlineColor);
		assertEquals(Settings.WELCOME_TIPS, again.welcomeTips);
	}

	@Test
	void handEditedValuesAreReadLeniently() throws Exception {
		Path file = dir.resolve("settings.properties");
		Files.write(file, ("\uFEFF# edited by hand\nenabled = Yes\nlanguages = DE, fr-fr, klingon,de\ncheckMessageCommands=maybe\n").getBytes(StandardCharsets.UTF_8));
		Settings s = new Settings(file);
		s.load();
		assertTrue(s.enabled);
		assertEquals(List.of(Language.DE_DE, Language.FR_FR), s.languages);
		assertFalse(s.checkMessageCommands);

		Files.writeString(file, "enabled=off\n");
		s.load();
		assertFalse(s.enabled);
		assertEquals(List.of(Language.EN_US), s.languages);
	}

	@Test
	void customWordsSavedInALegacyEncodingAreNotLost() throws Exception {
		Path file = dir.resolve("custom-words.txt");
		// Notepad "ANSI": café and the curly apostrophe are single bytes, invalid as UTF-8.
		Files.write(file, "café\r\nmy’word\r\nnetherrack\r\n".getBytes(Charset.forName("windows-1252")));
		UserDictionary d = new UserDictionary(file);
		d.load();
		assertTrue(d.contains("café") && d.contains("my'word") && d.contains("netherrack"));
		assertTrue(d.add("zorblax"));
		UserDictionary reloaded = new UserDictionary(file);
		reloaded.load();
		assertEquals(4, reloaded.size(), "adding a word must keep the hand-edited ones");
		assertTrue(reloaded.contains("café"));
	}

	@Test
	void byteOrderMarkIsIgnored() throws Exception {
		Path file = dir.resolve("custom-words.txt");
		Files.write(file, "\uFEFFzorblax\n".getBytes(StandardCharsets.UTF_8));
		UserDictionary d = new UserDictionary(file);
		d.load();
		assertTrue(d.contains("zorblax"));
	}

	@Test
	void anUnreadableWordListIsNeverOverwritten() throws Exception {
		Path file = dir.resolve("custom-words.txt");
		Files.createDirectories(file); // exists but can't be read as a file
		UserDictionary d = new UserDictionary(file);
		assertThrows(IOException.class, d::load);
		assertThrows(IOException.class, () -> d.add("zorblax"));
		assertTrue(d.contains("zorblax"), "still accepted for this session");
		assertTrue(Files.isDirectory(file));
	}

	@Test
	void readOnlyConfigFolderFailsQuietly() throws Exception {
		Path ro = dir.resolve("ro");
		Files.createDirectories(ro);
		Files.writeString(ro.resolve("custom-words.txt"), "netherrack\n");
		assumeTrue(Files.getFileStore(ro).supportsFileAttributeView("posix"));
		Files.setPosixFilePermissions(ro, PosixFilePermissions.fromString("r-xr-xr-x"));
		try {
			assumeTrue(!Files.isWritable(ro), "running as a user who can write anyway");
			UserDictionary d = new UserDictionary(ro.resolve("custom-words.txt"));
			d.load();
			assertTrue(d.contains("netherrack"));
			assertThrows(IOException.class, () -> d.add("zorblax"));
			assertTrue(d.contains("zorblax"));
			assertEquals("netherrack\n", Files.readString(ro.resolve("custom-words.txt")));
			assertEquals(List.of("custom-words.txt"), list(ro), "no temporary files left behind");

			Settings s = new Settings(ro.resolve("settings.properties"));
			assertThrows(IOException.class, s::load); // can't create it; caller logs and uses defaults
			assertEquals(List.of(Language.EN_US), s.languages);
		} finally {
			Files.setPosixFilePermissions(ro, PosixFilePermissions.fromString("rwxr-xr-x"));
		}
	}

	@Test
	void concurrentSavesNeverLeaveAPartialSettingsFile() throws Exception {
		Path file = dir.resolve("settings.properties");
		Settings s = new Settings(file);
		s.save();
		AtomicBoolean stop = new AtomicBoolean();
		AtomicReference<String> bad = new AtomicReference<>();
		List<Thread> writers = new ArrayList<>();
		for (int t = 0; t < 4; t++) {
			int id = t;
			writers.add(Thread.ofPlatform().start(() -> {
				int i = 0;
				while (!stop.get()) {
					s.languages = (i++ + id) % 2 == 0 ? List.of(Language.EN_US) : List.of(Language.EN_GB, Language.ES_ES, Language.HE_IL);
					try {
						s.save();
					} catch (IOException e) {
						bad.compareAndSet(null, e.toString());
					}
				}
			}));
		}
		long until = System.currentTimeMillis() + 1500;
		int reads = 0;
		while (System.currentTimeMillis() < until && bad.get() == null) {
			try {
				Properties p = new Properties();
				p.load(new StringReader(Files.readString(file)));
				String langs = p.getProperty("languages");
				if (!"en_US".equals(langs) && !"en_GB,es_ES,he_IL".equals(langs)) {
					bad.set("read languages=" + langs);
				}
				reads++;
			} catch (IOException e) {
				bad.set(e.toString());
			}
		}
		stop.set(true);
		for (Thread w : writers) {
			w.join();
		}
		assertEquals(null, bad.get(), "after " + reads + " reads");
		assertEquals(List.of("settings.properties"), list(dir));
	}

	private static List<String> list(Path dir) throws IOException {
		try (var s = Files.list(dir)) {
			return s.map(p -> p.getFileName().toString()).sorted().toList();
		}
	}
}
