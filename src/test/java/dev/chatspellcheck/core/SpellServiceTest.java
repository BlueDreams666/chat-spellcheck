package dev.chatspellcheck.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SpellServiceTest {
	@TempDir
	Path dir;
	private SpellService service;

	@BeforeEach
	void setUp() throws Exception {
		service = newService();
		awaitReady(service);
	}

	@AfterEach
	void tearDown() {
		service.close();
	}

	private SpellService newService() throws Exception {
		Settings settings = new Settings(dir.resolve("settings.properties"));
		settings.load();
		UserDictionary dictionary = new UserDictionary(dir.resolve("custom-words.txt"));
		dictionary.load();
		SpellService s = new SpellService(settings, dictionary, TestResources::open);
		s.start();
		return s;
	}

	private static void awaitReady(SpellService s) throws InterruptedException {
		long deadline = System.currentTimeMillis() + 60_000; // generous: dictionaries load slowly on a busy machine
		while (!s.isReady() && System.currentTimeMillis() < deadline) {
			Thread.sleep(10);
		}
		assertTrue(s.isReady(), "dictionary did not load");
	}

	private CheckResult check(String text, Set<String> names) throws InterruptedException {
		CountDownLatch done = new CountDownLatch(1);
		CheckResult[] out = new CheckResult[1];
		service.submitCheck(text, names, r -> {
			out[0] = r;
			done.countDown();
		});
		assertTrue(done.await(5, TimeUnit.SECONDS));
		return out[0];
	}

	private static List<String> words(CheckResult r) {
		return r.misspellings().stream().map(Misspelling::word).toList();
	}

	@Test
	void flagsMisspellingsButNotNamesTermsOrSlang() throws Exception {
		CheckResult r = check("i recieve teh netherite from Notch_ and xXSteveXx lol gg, Alex's creeper's pickaxe", Set.of("alex"));
		assertEquals(List.of("recieve", "teh"), words(r));
	}

	@Test
	void stretchedWordsAndLaughsAreAccepted() throws Exception {
		assertEquals(List.of(), words(check("sooo good yesss hahaha lmaooo xddd coool pleeease noooo", Set.of())));
		// Real typos with a doubled letter are still caught.
		assertEquals(List.of("untill", "wrrrd"), words(check("wait untill sooon, wrrrd helllp", Set.of())));
	}

	@Test
	void capitalisedWordsMidSentenceAreTreatedAsNames() throws Exception {
		assertEquals(List.of("Teh"), words(check("ask Zorblax about it. Teh end", Set.of())));
		service.settings().skipNames = false;
		assertEquals(List.of("Zorblax", "Teh"), words(check("ask Zorblax about it. Teh end", Set.of())));
	}

	@Test
	void namesAreStillCheckedWithGerman() throws Exception {
		service.setLanguages(List.of(Language.DE_DE)).get(60, TimeUnit.SECONDS);
		awaitReady(service);
		assertEquals(List.of("Hasu"), words(check("das Hasu ist groß", Set.of())));
	}

	@Test
	void lowerCaseProperNounsAreAccepted() throws Exception {
		assertEquals(List.of(), words(check("i live in london and speak english", Set.of())));
	}

	@Test
	void olderQueuedChecksAreSkipped() throws Exception {
		List<CheckResult> results = new CopyOnWriteArrayList<>();
		CountDownLatch last = new CountDownLatch(1);
		for (int i = 0; i < 50; i++) {
			String text = "teh ".repeat(i + 1);
			boolean isLast = i == 49;
			service.submitCheck(text, Set.of(), r -> {
				results.add(r);
				if (isLast) {
					last.countDown();
				}
			});
		}
		assertTrue(last.await(5, TimeUnit.SECONDS));
		CheckResult newest = results.get(results.size() - 1);
		assertEquals("teh ".repeat(50), newest.text());
		assertTrue(results.size() < 50, "superseded checks should be dropped, ran " + results.size());
		for (int i = 1; i < results.size(); i++) {
			assertTrue(results.get(i).generation() > results.get(i - 1).generation());
		}
	}

	@Test
	void ignoreAndAddToDictionaryTakeEffectAndPersist() throws Exception {
		int before = service.lexiconVersion();
		service.ignore("Zorbleflax");
		assertTrue(service.lexiconVersion() > before);
		assertEquals(List.of("blorptastic"), words(check("zorbleflax blorptastic", Set.of())));

		assertTrue(service.addToDictionary("Blorptastic").get(5, TimeUnit.SECONDS));
		assertEquals(List.of(), words(check("zorbleflax blorptastic", Set.of())));
		assertTrue(Files.readString(dir.resolve("custom-words.txt")).contains("blorptastic"));

		// A new session: the ignored word is forgotten, the dictionary word is remembered.
		service.close();
		service = newService();
		awaitReady(service);
		assertEquals(List.of("zorbleflax"), words(check("zorbleflax blorptastic", Set.of())));

		assertTrue(service.removeFromDictionary("blorptastic").get(5, TimeUnit.SECONDS));
		assertEquals(List.of("zorbleflax", "blorptastic"), words(check("zorbleflax blorptastic", Set.of())));
	}

	@Test
	void suggestionsAreRankedAndCached() throws Exception {
		List<String> first = service.suggestions("Teh").get(5, TimeUnit.SECONDS);
		assertEquals("The", first.get(0));
		assertTrue(service.suggestions("Teh") == service.suggestions("Teh"), "expected the cached future");
	}

	@Test
	void variantsDiffer() throws Exception {
		assertEquals(List.of("colour"), words(check("color colour", Set.of())));
		service.setLanguages(List.of(Language.EN_GB)).get(10, TimeUnit.SECONDS);
		assertEquals(List.of("color"), words(check("color colour", Set.of())));
		assertTrue(Files.readString(dir.resolve("settings.properties")).contains("languages=en_GB"));
	}

	@Test
	void multipleLanguagesAcceptEachOthersWords() throws Exception {
		service.setLanguages(List.of(Language.EN_US, Language.ES_ES)).get(10, TimeUnit.SECONDS);
		assertEquals(List.of("mundoo", "freind"), words(check("hola mi amigo, hello my mundoo freind", Set.of())));
		List<String> s = service.suggestions("mundoo").get(5, TimeUnit.SECONDS);
		assertEquals("mundo", s.get(0), s.toString());
		assertEquals("friend", service.suggestions("freind").get(5, TimeUnit.SECONDS).get(0));
	}

	@Test
	void eachScriptIsCheckedOnlyByItsOwnDictionaries() throws Exception {
		// English only: Hebrew and Arabic text is left alone rather than flagged.
		assertEquals(List.of("teh"), words(check("teh שלום עולמ مرحبا", Set.of())));
		service.setLanguages(List.of(Language.EN_US, Language.HE_IL, Language.AR)).get(30, TimeUnit.SECONDS);
		assertEquals(List.of("teh", "עולמ", "عالمم"), words(check("teh שלום עולמ مرحبا عالمم", Set.of())));
		assertTrue(service.suggestions("עולמ").get(5, TimeUnit.SECONDS).contains("עולם"));
	}

	@Test
	void frenchElisionWithEitherApostrophe() throws Exception {
		service.setLanguages(List.of(Language.FR_FR)).get(10, TimeUnit.SECONDS);
		assertEquals(List.of("mondee"), words(check("l'homme l’homme aujourd'hui bonjour mondee", Set.of())));
	}

	@Test
	void oldSingleVariantSettingIsMigrated() throws Exception {
		Path file = dir.resolve("old.properties");
		Files.writeString(file, "enabled=true\nvariant=en_GB\n");
		Settings s = new Settings(file);
		s.load();
		assertEquals(List.of(Language.EN_GB), s.languages);
	}

	@Test
	void disabledMeansNothingIsFlagged() throws Exception {
		service.settings().enabled = false;
		assertEquals(List.of(), words(check("teh", Set.of())));
	}

	// ---------------------------------------------------------------- threading and language switching

	private long checkLatencyMs(String text) throws InterruptedException {
		long t0 = System.nanoTime();
		check(text, Set.of());
		return (System.nanoTime() - t0) / 1_000_000;
	}

	@Test
	void checksAreNotDelayedBySlowSuggestionLookups() throws Exception {
		service.setLanguages(List.of(Language.EN_US, Language.NB_NO, Language.DA_DK)).get(60, TimeUnit.SECONDS);
		// Each of these takes ~100-250 ms in Norwegian/Danish; queued together, well over a second.
		for (String w : List.of("sommerferiens", "fotbalkampen", "kjøkkenbordett", "arbeidsplasssen",
			"skolebøkkene", "regjeringsforhandlinger", "studentershjemmet", "jernbanestasjonnen")) {
			service.suggestions(w);
		}
		long ms = checkLatencyMs("hello teh world");
		assertTrue(ms < 300, "check waited " + ms + " ms behind suggestion lookups");
	}

	@Test
	void checksAreNotDelayedByDictionaryLoading() throws Exception {
		service.setLanguages(List.of(Language.EN_US, Language.AR)); // Arabic takes ~0.8 s to load
		long t0 = System.nanoTime();
		CheckResult r = check("hello teh world", Set.of());
		long ms = (System.nanoTime() - t0) / 1_000_000;
		assertEquals(List.of("teh"), words(r));
		assertTrue(ms < 300, "check waited " + ms + " ms for the Arabic dictionary to load");
		awaitReady(service);
	}

	@Test
	void switchingLanguagesWhileCheckingNeverKeepsARemovedLanguage() throws Exception {
		service.setLanguages(List.of(Language.EN_US, Language.ES_ES)).get(10, TimeUnit.SECONDS);
		java.util.concurrent.atomic.AtomicBoolean stop = new java.util.concurrent.atomic.AtomicBoolean();
		Thread checker = new Thread(() -> {
			int i = 0;
			while (!stop.get()) {
				service.submitCheck("hola mundo " + (i++ % 7), Set.of(), r -> {
				});
				Thread.onSpinWait();
			}
		});
		checker.start();
		for (int i = 0; i < 200; i++) {
			service.setLanguages(i % 2 == 0 ? List.of(Language.EN_US) : List.of(Language.EN_US, Language.ES_ES));
		}
		service.setLanguages(List.of(Language.EN_US)).get(10, TimeUnit.SECONDS);
		stop.set(true);
		checker.join();
		assertEquals(List.of("hola", "mundo"), words(check("hola mundo", Set.of())));
		assertTrue(service.suggestions("mundoo").get(5, TimeUnit.SECONDS).stream().noneMatch("mundo"::equals),
			"Spanish suggestion after Spanish was turned off");
	}

	@Test
	void suggestionCacheCanBeReleasedAndRebuilt() throws Exception {
		List<String> before = service.suggestions("recieve").get(5, TimeUnit.SECONDS);
		service.releaseSuggestionCachesNow().get(5, TimeUnit.SECONDS);
		assertEquals(before, service.computeSuggestionsForTest("recieve").get(5, TimeUnit.SECONDS));
	}

	@Test
	void ignoringAWordDoesNotForgetOtherCachedResults() throws Exception {
		assertEquals(List.of("teh", "zorblax"), words(check("teh zorblax", Set.of())));
		service.ignore("zorblax");
		assertEquals(List.of("teh"), words(check("teh zorblax", Set.of())));
	}

	// ---------------------------------------------------------------- right-to-left text

	@Test
	void hebrewAcronymsWithAsciiOrHebrewQuotes() throws Exception {
		service.setLanguages(List.of(Language.EN_US, Language.HE_IL)).get(30, TimeUnit.SECONDS);
		// צה"ל (IDF) is written with an ASCII quote on most keyboards and with gershayim (״) on others.
		assertEquals(List.of(), words(check("שלום צה\"ל וגם צה״ל", Set.of())));
		// A quoted word is still checked on its own; the misspelling inside is still found.
		assertEquals(List.of("עולמ"), words(check("\"שלום\" \"עולמ\"", Set.of())));
		// Invisible right-to-left marks inside a word don't split it.
		assertEquals(List.of(), words(check("של\u200Fום", Set.of())));
	}

	@Test
	void addedAndIgnoredWordsMatchHowTheyAreLookedUp() throws Exception {
		service.setLanguages(List.of(Language.EN_US, Language.HE_IL)).get(30, TimeUnit.SECONDS);
		// Not in Hspell: flagged until the player adds it, whichever quote they typed.
		assertEquals(List.of("זורבל״ק"), words(check("זורבל״ק", Set.of())));
		assertTrue(service.addToDictionary("זורבל״ק").get(5, TimeUnit.SECONDS));
		assertEquals(List.of(), words(check("זורבל״ק זורבל\"ק", Set.of())));
		// Ignore with an invisible right-to-left mark inside the word.
		assertEquals(List.of("פלורב"), words(check("פלו\u200Fרב", Set.of())).stream().map(SpellService::lookupForm).toList());
		service.ignore("פלו\u200Fרב");
		assertEquals(List.of(), words(check("פלו\u200Fרב פלורב", Set.of())));
	}

	@Test
	void autocorrectRespectsEachLanguagesPolicy() throws Exception {
		assertEquals(java.util.Optional.of("the"), service.autocorrection("teh").get(5, TimeUnit.SECONDS));
		// Spanish general typos are suggested; only accent fixes are applied automatically.
		service.setLanguages(List.of(Language.EN_US, Language.ES_ES)).get(10, TimeUnit.SECONDS);
		assertEquals("mundo", service.suggestions("mundoo").get(5, TimeUnit.SECONDS).getFirst());
		assertEquals(java.util.Optional.empty(), service.autocorrection("mundoo").get(5, TimeUnit.SECONDS));
		assertEquals(java.util.Optional.of("the"), service.autocorrection("teh").get(5, TimeUnit.SECONDS));
		service.setLanguages(List.of(Language.ES_ES)).get(10, TimeUnit.SECONDS);
		assertEquals(java.util.Optional.empty(), service.autocorrection("mundoo").get(5, TimeUnit.SECONDS));
		// ...but a missing accent is fixed: that is the narrow rule measured reliable for Spanish.
		assertEquals(java.util.Optional.of("canción"), service.autocorrection("cancion").get(5, TimeUnit.SECONDS));
		service.setLanguages(List.of(Language.EN_US, Language.HE_IL)).get(30, TimeUnit.SECONDS);
		assertEquals(java.util.Optional.of("שלום"), service.autocorrection("שלומ").get(5, TimeUnit.SECONDS));
		assertEquals(java.util.Optional.empty(), service.autocorrection("תדוה").get(5, TimeUnit.SECONDS), "short Hebrew typos remain suggestions only");
		assertEquals(java.util.Optional.of("עכשיו"), service.autocorrection("עכשחו").get(5, TimeUnit.SECONDS));
		assertEquals(java.util.Optional.of("the"), service.autocorrection("teh").get(5, TimeUnit.SECONDS));
		assertEquals(java.util.Optional.empty(), service.autocorrection("בבשקה").get(5, TimeUnit.SECONDS));
		assertEquals(java.util.Optional.of("עכשיו"), service.autocorrection("עכ\u200Fשחו").get(5, TimeUnit.SECONDS));
		service.declineAutocorrection("עכשחו");
		assertEquals(java.util.Optional.empty(), service.autocorrection("עכשחו").get(5, TimeUnit.SECONDS));
		service.setLanguages(List.of(Language.HE_IL)).get(30, TimeUnit.SECONDS);
		assertEquals(java.util.Optional.empty(), service.autocorrection("teh").get(5, TimeUnit.SECONDS));
		service.setLanguages(List.of(Language.DA_DK)).get(30, TimeUnit.SECONDS);
		assertEquals(java.util.Optional.empty(), service.autocorrection("gron").get(5, TimeUnit.SECONDS), "Danish autocorrect is off");
	}
	@Test
	void pendingCorrectionsAreDiscardedAfterLexiconChanges() throws Exception {
		service.setLanguages(List.of(Language.EN_US, Language.HE_IL)).get(30, TimeUnit.SECONDS);
		var field = SpellService.class.getDeclaredField("suggestWorker");
		field.setAccessible(true);
		var worker = (java.util.concurrent.ExecutorService) field.get(service);
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		worker.submit(() -> {
			entered.countDown();
			try {
				release.await(10, TimeUnit.SECONDS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		});
		try {
			assertTrue(entered.await(5, TimeUnit.SECONDS));
			var english = service.autocorrection("teh");
			var hebrew = service.autocorrection("עכשחו");
			service.ignore("teh");
			service.setLanguages(List.of(Language.EN_US)).get(10, TimeUnit.SECONDS);
			release.countDown();
			assertEquals(java.util.Optional.empty(), english.get(5, TimeUnit.SECONDS));
			assertEquals(java.util.Optional.empty(), hebrew.get(5, TimeUnit.SECONDS));
		} finally {
			release.countDown();
		}
	}

	@Test
	void acceptedAndUndoneWordsAreNeverAutocorrected() throws Exception {
		service.ignore("teh");
		assertEquals(java.util.Optional.empty(), service.autocorrection("teh").get(5, TimeUnit.SECONDS));
		service.addToDictionary("recieve").get(5, TimeUnit.SECONDS);
		assertEquals(java.util.Optional.empty(), service.autocorrection("recieve").get(5, TimeUnit.SECONDS));
		int before = service.lexiconVersion();
		service.declineAutocorrection("freind");
		assertTrue(service.lexiconVersion() > before, "undo invalidates pending UI corrections");
		assertEquals(java.util.Optional.empty(), service.autocorrection("freind").get(5, TimeUnit.SECONDS));
	}

}
