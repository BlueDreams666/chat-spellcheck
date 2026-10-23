package dev.chatspellcheck.core;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Owns the spelling engine and runs every expensive operation in the background, on three
 * threads so that slow work never delays underlines:
 * <ul>
 *   <li><b>check</b>: spell-checks chat text (fast; one word lookup is microseconds once cached);</li>
 *   <li><b>suggest</b>: suggestion lookups, which can take ~250 ms for Danish or Norwegian;</li>
 *   <li><b>io</b>: dictionary loading (Arabic takes ~0.8 s) and file writes.</li>
 * </ul>
 * The render thread only submits work and reads immutable results. See {@link HunspellChecker}
 * for why one dictionary may be checked and searched for suggestions concurrently.
 */
public final class SpellService implements AutoCloseable {
	private static final Logger LOG = LoggerFactory.getLogger("ChatSpellcheck");
	private static final int SUGGESTION_CACHE_LIMIT = 512;
	/** Suggestion entry caches are freed after this long without a lookup (see HunspellChecker). */
	static final long SUGGESTER_IDLE_MS = TimeUnit.MINUTES.toMillis(3);

	private final Settings settings;
	private final UserDictionary userDictionary;
	private final Stats stats;
	private final Function<String, InputStream> resources;
	private final ExecutorService checkWorker = Executors.newSingleThreadExecutor(daemon("Chat Spellcheck"));
	private final ScheduledExecutorService suggestWorker = Executors.newSingleThreadScheduledExecutor(daemon("Chat Spellcheck suggestions"));
	private final ExecutorService ioWorker = Executors.newSingleThreadExecutor(daemon("Chat Spellcheck loader"));

	/** Session-only "Ignore" list. */
	private final Set<String> ignored = ConcurrentHashMap.newKeySet();
	/** Bundled allow-list plus Minecraft registry ids; never flagged. */
	private final Set<String> domainTerms = ConcurrentHashMap.newKeySet();
	/**
	 * Only the curated allow-list boosts ranking. Registry ids also contain everyday words
	 * ("item", "plate", "wither") that would otherwise outrank "time", "please", "whether".
	 */
	private final Set<String> rankingTerms = ConcurrentHashMap.newKeySet();
	private final Map<String, CompletableFuture<List<String>>> suggestionCache = new LinkedHashMap<>(64, 0.75f, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<String, CompletableFuture<List<String>>> eldest) {
			return size() > SUGGESTION_CACHE_LIMIT;
		}
	};
	private final AtomicLong latestCheck = new AtomicLong();
	/** Bumped whenever the set of accepted words changes, so open screens re-check. */
	private final AtomicInteger lexiconVersion = new AtomicInteger();

	/** Loaded dictionaries; only the active languages are kept in memory. */
	private final Map<Language, HunspellChecker> engines = new ConcurrentHashMap<>();
	private final Map<Language, String> loadErrors = new ConcurrentHashMap<>();
	/** Rankers for loaded frequency lists, keyed by {@link Language#frequencyList()}. */
	private final Map<String, SuggestionRanker> rankers = new ConcurrentHashMap<>();
	private final Map<String, WordFrequency> frequencies = new ConcurrentHashMap<>();
	/** Words whose automatic correction the player undid this session; never auto-corrected again. */
	private final Set<String> declinedAutocorrections = ConcurrentHashMap.newKeySet();
	/** For languages without a frequency list; null until {@link #start} has read the allow-list. */
	private volatile SuggestionRanker otherRanker;

	/** Suggestion thread only. */
	private long lastSuggestionAt;
	private boolean releaseScheduled;

	public SpellService(Settings settings, UserDictionary userDictionary, Function<String, InputStream> resources) {
		this(settings, userDictionary, Stats.inMemory(), resources);
	}

	public SpellService(Settings settings, UserDictionary userDictionary, Stats stats, Function<String, InputStream> resources) {
		this.settings = settings;
		this.userDictionary = userDictionary;
		this.stats = stats;
		this.resources = resources;
	}

	private static ThreadFactory daemon(String name) {
		return r -> {
			Thread t = new Thread(r, name);
			t.setDaemon(true);
			t.setPriority(Thread.NORM_PRIORITY - 1);
			return t;
		};
	}

	public Settings settings() {
		return settings;
	}

	public UserDictionary userDictionary() {
		return userDictionary;
	}

	public Stats stats() {
		return stats;
	}

	/** Writes the stats file in the background, if anything changed. */
	public void saveStatsQuietly() {
		ioWorker.execute(() -> {
			try {
				stats.save();
			} catch (IOException e) {
				LOG.warn("Could not save stats", e);
			}
		});
	}

	/** Loads the allow-list, then the configured dictionaries and their frequency lists, in the background. */
	public void start() {
		ioWorker.execute(() -> {
			try (InputStream in = resources.apply("allowlist.txt")) {
				if (in != null) {
					readWordList(in, rankingTerms);
					domainTerms.addAll(rankingTerms);
				}
			} catch (IOException e) {
				LOG.warn("Could not load allow-list", e);
			}
			otherRanker = new SuggestionRanker(WordFrequency.empty(), rankingTerms::contains, false);
			syncEngines();
		});
	}

	/** True once every active dictionary has loaded (or failed to). */
	public boolean isReady() {
		for (Language l : settings.languages) {
			if (!engines.containsKey(l) && !loadErrors.containsKey(l)) {
				return false;
			}
		}
		return true;
	}

	public enum Status { INACTIVE, LOADING, READY, FAILED }

	/** Whether {@code language}'s dictionary is in use, still loading, or failed to load. */
	public Status status(Language language) {
		if (!settings.languages.contains(language)) {
			return Status.INACTIVE;
		}
		if (engines.containsKey(language)) {
			return Status.READY;
		}
		return loadErrors.containsKey(language) ? Status.FAILED : Status.LOADING;
	}

	/** Null if all active dictionaries loaded; otherwise a short description of what failed. */
	public String loadError() {
		return loadErrors.isEmpty() ? null : loadErrors.toString();
	}

	public int lexiconVersion() {
		return lexiconVersion.get();
	}

	/**
	 * Switches the active dictionaries (in priority order). Loading happens in the background;
	 * open screens re-check when it finishes. Unused dictionaries are released.
	 */
	public CompletableFuture<Void> setLanguages(List<Language> languages) {
		if (languages.isEmpty()) {
			throw new IllegalArgumentException("At least one language is required");
		}
		settings.languages = List.copyOf(new java.util.LinkedHashSet<>(languages));
		lexiconChanged(true);
		return CompletableFuture.runAsync(() -> {
			syncEngines();
			saveSettingsNow();
		}, ioWorker);
	}

	/**
	 * Loads and releases dictionaries to match the <em>current</em> setting (io thread). Reading
	 * the setting each time, rather than a captured copy, means a quick on/off/on of a language
	 * neither loads a dictionary nobody wants nor keeps one that was switched off.
	 */
	private void syncEngines() {
		retainWanted();
		for (Language language : settings.languages) {
			if (engines.containsKey(language) || !settings.languages.contains(language)) {
				continue;
			}
			loadFrequency(language);
			try {
				long t0 = System.nanoTime();
				HunspellChecker checker = HunspellChecker.load(language, name -> resources.apply("dictionaries/" + name));
				if (settings.languages.contains(language)) {
					engines.put(language, checker);
					loadErrors.remove(language);
					LOG.info("Loaded {} dictionary in {} ms", language.code(), (System.nanoTime() - t0) / 1_000_000);
				}
			} catch (RuntimeException e) {
				loadErrors.put(language, String.valueOf(e.getMessage()));
				LOG.error("Could not load dictionary " + language.code(), e);
			}
			lexiconChanged(true); // check again as soon as each dictionary is usable
		}
		retainWanted();
	}

	private void retainWanted() {
		List<Language> wanted = settings.languages;
		engines.keySet().retainAll(wanted);
		loadErrors.keySet().retainAll(wanted);
		Set<String> lists = new java.util.HashSet<>();
		for (Language l : wanted) {
			lists.add(l.frequencyList());
		}
		rankers.keySet().retainAll(lists);
		frequencies.keySet().retainAll(lists);
	}

	/** Loads {@code language}'s frequency list if it has one that isn't loaded yet (io thread). */
	private void loadFrequency(Language language) {
		String list = language.frequencyList();
		if (list == null || rankers.containsKey(list)) {
			return;
		}
		WordFrequency frequency = WordFrequency.empty();
		try (InputStream in = resources.apply("frequency/" + list + "_word_frequency.txt")) {
			if (in != null) {
				frequency = WordFrequency.load(in);
			}
		} catch (IOException | RuntimeException e) {
			LOG.warn("Could not load {} word frequencies; its suggestions will be unranked", list, e);
		}
		frequencies.put(list, frequency);
		rankers.put(list, new SuggestionRanker(frequency, rankingTerms::contains, language.isEnglish()));
	}

	/** The ranker for {@code language}: its frequency list's, or the plain one while that loads. */
	private SuggestionRanker rankerFor(Language language) {
		String list = language.frequencyList();
		SuggestionRanker ranker = list == null ? null : rankers.get(list);
		return ranker != null ? ranker : otherRanker;
	}

	public void addDomainTerms(Collection<String> terms) {
		ioWorker.execute(() -> {
			for (String t : terms) {
				domainTerms.add(t.toLowerCase(Locale.ROOT));
			}
			lexiconChanged(false);
		});
	}

	/**
	 * Checks {@code text} in the background and hands the result to {@code onResult} (on the
	 * check thread). If another check is submitted before this one starts, this one is
	 * skipped: only the newest text matters.
	 *
	 * @param names lower-case player names that should never be flagged
	 */
	public void submitCheck(String text, Set<String> names, Consumer<CheckResult> onResult) {
		long generation = latestCheck.incrementAndGet();
		checkWorker.execute(() -> {
			if (generation != latestCheck.get()) {
				return; // superseded while queued
			}
			CheckResult result;
			try {
				result = check(text, names, generation);
			} catch (RuntimeException e) {
				LOG.warn("Spellcheck failed", e);
				result = new CheckResult(text, generation, List.of());
			}
			onResult.accept(result);
		});
	}

	CheckResult check(String text, Set<String> names, long generation) {
		List<HunspellChecker> active = activeEngines(settings.languages);
		if (active.isEmpty() || !settings.enabled) {
			return new CheckResult(text, generation, List.of());
		}
		Set<Character.UnicodeScript> scripts = new java.util.HashSet<>();
		for (HunspellChecker c : active) {
			scripts.add(c.language().script());
		}
		// German capitalises every noun, so a capital letter says nothing about names there.
		boolean skipNames = settings.skipNames && active.stream().noneMatch(c -> c.language().code().startsWith("de_"));
		List<Misspelling> found = new ArrayList<>();
		for (TextScanner.Word w : TextScanner.scan(text, settings.checkMessageCommands, scripts)) {
			if (skipNames && looksLikeName(text, w)) {
				continue;
			}
			if (!isAccepted(enginesFor(active, w.text()), w.text(), names)) {
				found.add(new Misspelling(w.start(), w.end(), w.text()));
			}
		}
		return new CheckResult(text, generation, found);
	}

	/**
	 * A capitalised word in the middle of a sentence ("ask Zorblax"), which is probably a name.
	 * The first word of a sentence is still checked, so "Teh" after a full stop is caught.
	 */
	static boolean looksLikeName(String text, TextScanner.Word w) {
		if (SuggestionRanker.Casing.of(w.text()) != SuggestionRanker.Casing.CAPITALIZED) {
			return false;
		}
		int i = w.start() - 1;
		while (i >= 0 && !Character.isLetterOrDigit(text.charAt(i)) && ".!?".indexOf(text.charAt(i)) < 0) {
			i--; // spaces, quotes, brackets
		}
		return i >= 0 && ".!?".indexOf(text.charAt(i)) < 0;
	}

	/** How many words in {@code text} would be checked, for the stats. */
	public int checkableWords(String text) {
		Set<Character.UnicodeScript> scripts = new java.util.HashSet<>();
		for (Language l : settings.languages) {
			scripts.add(l.script());
		}
		return TextScanner.scan(text, settings.checkMessageCommands, scripts).size();
	}

	/** Loaded engines for {@code languages}, in priority order. */
	private List<HunspellChecker> activeEngines(List<Language> languages) {
		List<HunspellChecker> out = new ArrayList<>();
		for (Language l : languages) {
			HunspellChecker c = engines.get(l);
			if (c != null) {
				out.add(c);
			}
		}
		return out;
	}

	/** The engines whose writing system matches the word's (Hebrew words go to Hebrew, etc.). */
	private static List<HunspellChecker> enginesFor(List<HunspellChecker> active, String word) {
		Character.UnicodeScript script = scriptOf(word);
		List<HunspellChecker> out = new ArrayList<>();
		for (HunspellChecker c : active) {
			if (c.language().script() == script) {
				out.add(c);
			}
		}
		return out;
	}

	static Character.UnicodeScript scriptOf(String word) {
		for (int i = 0; i < word.length(); ) {
			int cp = word.codePointAt(i);
			Character.UnicodeScript s = Character.UnicodeScript.of(cp);
			if (Character.isLetter(cp) && s != Character.UnicodeScript.COMMON && s != Character.UnicodeScript.INHERITED) {
				return s;
			}
			i += Character.charCount(cp);
		}
		return Character.UnicodeScript.LATIN;
	}

	private boolean isAccepted(List<HunspellChecker> checkers, String word, Set<String> names) {
		if (checkers.isEmpty()) {
			return true; // no dictionary for this writing system: leave it alone
		}
		String lookup = lookupForm(word);
		String plain = lookup.replace('’', '\'');
		if (isAllowListed(plain, names)) {
			return true;
		}
		// Possessives of allow-listed words: "Steve's", "creeper's".
		if (plain.length() > 2 && plain.regionMatches(true, plain.length() - 2, "'s", 0, 2)
			&& isAllowListed(plain.substring(0, plain.length() - 2), names)) {
			return true;
		}
		for (HunspellChecker checker : checkers) {
			if (checker.isAccepted(lookup, SpellService::isCorrect)) {
				return true;
			}
		}
		if (ChatSpeak.isLaugh(plain)) {
			return true;
		}
		// Stretched on purpose: "sooo", "yesss", "lmaooo".
		for (String shorter : ChatSpeak.unstretched(plain)) {
			if (isAllowListed(shorter, names)) {
				return true;
			}
			for (HunspellChecker checker : checkers) {
				if (checker.isAccepted(shorter, SpellService::isCorrect)) {
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * The form looked up in the dictionaries: NFC, without invisible direction marks or joiners
	 * (some keyboards insert them in right-to-left text), and with Hebrew geresh/gershayim
	 * (׳ ״) mapped to the ASCII ' and " that Hspell uses ("צה״ל" → "צה\"ל").
	 */
	static String lookupForm(String word) {
		String nfc = Normalizer.normalize(word, Normalizer.Form.NFC);
		StringBuilder b = null;
		for (int i = 0; i < nfc.length(); i++) {
			char c = nfc.charAt(i);
			char mapped = c == '׳' ? '\'' : c == '״' ? '"' : c;
			boolean drop = TextScanner.isInvisibleMark(c);
			if (b == null && (drop || mapped != c)) {
				b = new StringBuilder(nfc.length()).append(nfc, 0, i);
			}
			if (b != null && !drop) {
				b.append(mapped);
			}
		}
		return b == null ? nfc : b.toString();
	}

	private static boolean isCorrect(HunspellChecker checker, String word) {
		// Dictionaries differ on ' versus ’ (French uses the typographic one); accept either.
		for (String w : apostropheForms(word)) {
			if (checker.isCorrect(w)) {
				return true;
			}
			// Chat is often typed in lower case; accept "london" if "London" is a word, rather
			// than nagging about capitalisation.
			int first = w.codePointAt(0);
			if (Character.isLowerCase(first)) {
				String capitalized = new StringBuilder().appendCodePoint(Character.toUpperCase(first))
					.append(w, Character.charCount(first), w.length()).toString();
				if (checker.isCorrect(capitalized)) {
					return true;
				}
			}
		}
		return false;
	}

	private static List<String> apostropheForms(String word) {
		String straight = word.replace('’', '\'');
		String curly = word.replace('\'', '’');
		return straight.equals(curly) ? List.of(word) : List.of(straight, curly);
	}

	private boolean isAllowListed(String word, Set<String> names) {
		String lower = word.toLowerCase(Locale.ROOT);
		return names.contains(lower) || domainTerms.contains(lower) || ignored.contains(lower) || userDictionary.contains(lower);
	}

	/**
	 * Ranked suggestions for {@code word}, computed in the background and cached. Callers poll
	 * the returned future (e.g. {@code getNow}) from the render thread; it never completes
	 * exceptionally.
	 */
	public CompletableFuture<List<String>> suggestions(String word) {
		if (otherRanker == null) {
			return CompletableFuture.completedFuture(List.of()); // still starting; not cached
		}
		List<Language> languages = settings.languages;
		String key = languages + "\u0000" + word;
		synchronized (suggestionCache) {
			CompletableFuture<List<String>> cached = suggestionCache.get(key);
			if (cached != null) {
				return cached;
			}
			// The lookup uses the same language list as the cache key, so a result can never be
			// filed under a different language selection than the one it was computed for.
			CompletableFuture<List<String>> future = CompletableFuture.supplyAsync(() -> suggestOnWorker(languages, word), suggestWorker);
			suggestionCache.put(key, future);
			return future;
		}
	}

	private List<String> suggestOnWorker(List<Language> languages, String word) {
		try {
			return computeSuggestions(languages, word);
		} catch (RuntimeException e) {
			LOG.warn("Suggestion lookup failed for '{}'", word, e);
			return List.of();
		} finally {
			noteSuggestionUse();
		}
	}

	List<String> computeSuggestions(List<Language> languages, String word) {
		List<HunspellChecker> checkers = enginesFor(activeEngines(languages), word);
		if (checkers.isEmpty() || otherRanker == null) {
			return List.of();
		}
		// Hunspell orders suggestions for "Teh" oddly; ask about "teh" and restore case later.
		String query = lookupForm(word);
		if (SuggestionRanker.Casing.of(query) == SuggestionRanker.Casing.CAPITALIZED) {
			query = query.toLowerCase(Locale.ROOT);
		}
		List<List<String>> perLanguage = new ArrayList<>();
		for (HunspellChecker checker : checkers) {
			SuggestionRanker ranker = rankerFor(checker.language());
			List<String> raw = new ArrayList<>();
			for (String form : apostropheForms(query)) {
				for (String s : checker.suggest(form)) {
					if (!raw.contains(s)) {
						raw.add(s);
					}
				}
			}
			perLanguage.add(ranker.rank(word, raw));
		}
		return mergeSuggestions(word, perLanguage);
	}

	/**
	 * The correction to apply automatically for {@code word}, if one is near-certain (see
	 * {@link Autocorrect}); empty if not, or if the player undid it before. What may be fixed
	 * depends on the language ({@link Autocorrect#policy}), as measured. Never completes exceptionally.
	 */
	public CompletableFuture<java.util.Optional<String>> autocorrection(String word) {
		int lexicon = lexiconVersion();
		return computeAutocorrection(word).thenApply(fix -> lexicon == lexiconVersion()
			&& !declinedAutocorrections.contains(UserDictionary.normalize(word))
			&& !isAllowListed(UserDictionary.normalize(word), Set.of()) ? fix : java.util.Optional.empty());
	}

	private CompletableFuture<java.util.Optional<String>> computeAutocorrection(String word) {
		List<HunspellChecker> checkers = enginesFor(activeEngines(settings.languages), word);
		if (checkers.isEmpty() || declinedAutocorrections.contains(UserDictionary.normalize(word))
			|| isAllowListed(UserDictionary.normalize(word), Set.of())) {
			return CompletableFuture.completedFuture(java.util.Optional.empty());
		}
		java.util.function.Predicate<Autocorrect.Policy> any = p -> checkers.stream().anyMatch(c -> Autocorrect.policy(c.language()) == p);
		if (any.test(Autocorrect.Policy.HEBREW_TYPOS)) {
			List<HunspellChecker> hebrew = checkers.stream().filter(c -> Autocorrect.policy(c.language()) == Autocorrect.Policy.HEBREW_TYPOS).toList();
			// Candidate enumeration is bounded and runs off the render thread. Avoid filling the
			// normal check cache with hundreds of synthetic words for each typo.
			return CompletableFuture.supplyAsync(() -> Autocorrect.hebrewTypo(lookupForm(word),
				w -> hebrew.stream().anyMatch(c -> c.isCorrect(w))), suggestWorker)
				.exceptionally(e -> java.util.Optional.empty());
		}
		if (any.test(Autocorrect.Policy.HEBREW_FINALS)) {
			List<HunspellChecker> hebrew = checkers.stream().filter(c -> Autocorrect.policy(c.language()) == Autocorrect.Policy.HEBREW_FINALS).toList();
			return CompletableFuture.supplyAsync(() -> Autocorrect.hebrewFinalLetter(word, w -> accepts(hebrew, w)), suggestWorker)
				.exceptionally(e -> java.util.Optional.empty());
		}
		if (!any.test(Autocorrect.Policy.GENERAL) && !any.test(Autocorrect.Policy.ACCENTS)) {
			return CompletableFuture.completedFuture(java.util.Optional.empty());
		}
		List<HunspellChecker> general = checkers.stream().filter(c -> Autocorrect.policy(c.language()) == Autocorrect.Policy.GENERAL).toList();
		List<HunspellChecker> accents = checkers.stream().filter(c -> Autocorrect.policy(c.language()) != Autocorrect.Policy.OFF).toList();
		return suggestions(word).thenApply(list -> {
			// A fix must be a word in a language whose rule allows it: any near-certain fix for
			// English, only an accent fix for the others.
			java.util.Optional<String> fix = Autocorrect.choose(word, list, frequencies.getOrDefault("en", WordFrequency.empty())).filter(f -> accepts(general, f));
			if (fix.isEmpty()) {
				fix = Autocorrect.equivalentSpelling(word, list).filter(f -> accepts(accents, f));
			}
			return fix;
		}).exceptionally(e -> java.util.Optional.empty());
	}

	private static boolean accepts(List<HunspellChecker> checkers, String word) {
		return checkers.stream().anyMatch(c -> c.isAccepted(lookupForm(word), SpellService::isCorrect));
	}

	/** The player undid an automatic correction of {@code word}: don't do it again this session. */
	public void declineAutocorrection(String word) {
		if (declinedAutocorrections.add(UserDictionary.normalize(word))) {
			lexiconChanged(false);
		}
	}

	/** Suggestion thread: frees idle suggestion caches a while after the last lookup. */
	private void noteSuggestionUse() {
		lastSuggestionAt = System.nanoTime();
		if (!releaseScheduled) {
			releaseScheduled = true;
			suggestWorker.schedule(this::releaseIfIdle, SUGGESTER_IDLE_MS, TimeUnit.MILLISECONDS);
		}
	}

	private void releaseIfIdle() {
		long idleMs = (System.nanoTime() - lastSuggestionAt) / 1_000_000;
		if (idleMs < SUGGESTER_IDLE_MS) {
			suggestWorker.schedule(this::releaseIfIdle, SUGGESTER_IDLE_MS - idleMs, TimeUnit.MILLISECONDS);
			return;
		}
		releaseScheduled = false;
		releaseSuggestionCaches();
	}

	/** Suggestion thread only. */
	void releaseSuggestionCaches() {
		for (HunspellChecker c : engines.values()) {
			c.releaseSuggestionCache();
		}
	}

	/** For tests: a fresh (uncached) suggestion lookup on the suggestion thread. */
	CompletableFuture<List<String>> computeSuggestionsForTest(String word) {
		List<Language> languages = settings.languages;
		return CompletableFuture.supplyAsync(() -> suggestOnWorker(languages, word), suggestWorker);
	}

	/** For tests: frees suggestion caches now, on the suggestion thread. */
	CompletableFuture<Void> releaseSuggestionCachesNow() {
		return CompletableFuture.runAsync(this::releaseSuggestionCaches, suggestWorker);
	}

	/**
	 * With one language its ranking is used as is. With several, each language's own rank leads,
	 * nudged by closeness to the typed word, then by language priority.
	 */
	static List<String> mergeSuggestions(String word, List<List<String>> perLanguage) {
		if (perLanguage.size() == 1) {
			return perLanguage.getFirst();
		}
		record Candidate(String text, double key) {
		}
		String lowerWord = word.toLowerCase(Locale.ROOT);
		List<Candidate> all = new ArrayList<>();
		for (int lang = 0; lang < perLanguage.size(); lang++) {
			List<String> list = perLanguage.get(lang);
			for (int rank = 0; rank < list.size(); rank++) {
				String s = list.get(rank);
				double distance = TypoDistance.between(lowerWord, s.toLowerCase(Locale.ROOT));
				all.add(new Candidate(s, rank + 0.5 * distance + 0.01 * lang));
			}
		}
		all.sort(java.util.Comparator.comparingDouble(Candidate::key));
		java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
		for (Candidate c : all) {
			out.add(c.text());
			if (out.size() == SuggestionRanker.MAX_SUGGESTIONS) {
				break;
			}
		}
		return List.copyOf(out);
	}

	/** Ignore everywhere until the game closes. */
	public void ignore(String word) {
		ignored.add(UserDictionary.normalize(word));
		lexiconChanged(false);
	}

	/** @return a future that is true once the word is saved (or was already saved), false if saving failed */
	public CompletableFuture<Boolean> addToDictionary(String word) {
		ignored.add(UserDictionary.normalize(word)); // takes effect immediately, before the write
		lexiconChanged(false);
		return CompletableFuture.supplyAsync(() -> {
			try {
				userDictionary.add(word);
				return true;
			} catch (IOException e) {
				LOG.warn("Could not save custom dictionary; '{}' is accepted until the game closes", word, e);
				return false;
			}
		}, ioWorker);
	}

	public CompletableFuture<Boolean> removeFromDictionary(String word) {
		return CompletableFuture.supplyAsync(() -> {
			try {
				boolean removed = userDictionary.remove(word);
				ignored.remove(UserDictionary.normalize(word));
				lexiconChanged(false);
				return removed;
			} catch (IOException e) {
				LOG.warn("Could not save custom dictionary", e);
				return false;
			}
		}, ioWorker);
	}

	/** Re-check open screens after a setting such as enabled/checkMessageCommands changed. */
	public void refresh() {
		lexiconChanged(false);
	}

	/** Writes the settings file in the background (never on the render thread). */
	public CompletableFuture<Void> saveSettingsQuietly() {
		return CompletableFuture.runAsync(this::saveSettingsNow, ioWorker);
	}

	private void saveSettingsNow() {
		try {
			settings.save();
		} catch (IOException e) {
			LOG.warn("Could not save settings", e);
		}
	}

	/**
	 * Per-dictionary check caches never need clearing: a dictionary's answer for a word doesn't
	 * change, and session words (ignore, add, names, registry terms) are tested before them.
	 * Cached suggestions are dropped when the loaded dictionaries change.
	 */
	private void lexiconChanged(boolean dictionariesChanged) {
		if (dictionariesChanged) {
			synchronized (suggestionCache) {
				suggestionCache.clear();
			}
		}
		lexiconVersion.incrementAndGet();
	}

	private static void readWordList(InputStream in, Set<String> into) throws IOException {
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
			String line;
			while ((line = reader.readLine()) != null) {
				String w = line.strip();
				if (!w.isEmpty() && !w.startsWith("#")) {
					into.add(w.toLowerCase(Locale.ROOT));
				}
			}
		}
	}

	@Override
	public void close() {
		saveStatsQuietly();
		// Let pending file writes finish; checks and lookups are simply abandoned.
		checkWorker.shutdownNow();
		suggestWorker.shutdownNow();
		ioWorker.shutdown();
		try {
			ioWorker.awaitTermination(1, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
}
