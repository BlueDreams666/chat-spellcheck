package dev.chatspellcheck.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.text.ParseException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import org.apache.lucene.analysis.hunspell.Dictionary;
import org.apache.lucene.analysis.hunspell.Hunspell;
import org.apache.lucene.analysis.hunspell.SortingStrategy;
import org.apache.lucene.analysis.hunspell.Suggester;
import org.apache.lucene.analysis.hunspell.SuggestionTimeoutException;

/**
 * Thin wrapper over Lucene's pure-Java Hunspell implementation.
 *
 * <p>Threading: the parsed {@link Dictionary} is immutable once its constructor returns (its
 * only mutable fields are written while parsing) and is shared. {@link #isCorrect} uses a
 * {@link Hunspell}, which holds only final references to the dictionary and a stateless
 * stemmer, so it may be called from any thread (checks, and autocorrect's final English test).
 * {@link #suggest} and {@link #releaseSuggestionCache} must be called from a single thread
 * (SpellService's suggestion thread); the {@link Suggester} creates its own Hunspell per call.
 * So checking and suggesting can run on different threads at the same time.
 */
public final class HunspellChecker {
	/** Upper bound for one suggestion lookup; Hunspell's n-gram fallback can be slow on long words. */
	private static final long SUGGEST_TIMEOUT_MS = 250;
	private static final int CACHE_LIMIT = 20_000;

	private final Language language;
	private final Dictionary dictionary;
	private final Hunspell hunspell;
	/** Word → correct, for this dictionary only; dictionaries never change, so neither do entries. */
	private final Map<String, Boolean> cache = new ConcurrentHashMap<>();
	/**
	 * Built on first use and dropped by {@link #releaseSuggestionCache}: its entry cache roughly
	 * doubles suggestion speed but can hold tens of MB (29 MB for Norwegian).
	 */
	private Suggester suggester;

	private HunspellChecker(Language language, Dictionary dictionary) {
		this.language = language;
		this.dictionary = dictionary;
		this.hunspell = new Hunspell(dictionary);
	}

	/**
	 * Loads a bundled dictionary. {@code opener} resolves a resource name such as
	 * {@code en_US.dic} to a stream; this keeps the class free of Minecraft/Fabric types.
	 */
	public static HunspellChecker load(Language language, Function<String, InputStream> opener) {
		String base = language.code();
		try (InputStream aff = Objects.requireNonNull(opener.apply(base + ".aff"), base + ".aff missing");
			 InputStream dic = Objects.requireNonNull(opener.apply(base + ".dic"), base + ".dic missing")) {
			Dictionary dictionary = new Dictionary(aff, List.of(dic), false, SortingStrategy.inMemory());
			return new HunspellChecker(language, dictionary);
		} catch (IOException e) {
			throw new UncheckedIOException("Could not read dictionary " + base, e);
		} catch (ParseException e) {
			throw new IllegalStateException("Could not parse dictionary " + base, e);
		}
	}

	public Language language() {
		return language;
	}

	public boolean isCorrect(String word) {
		return hunspell.spell(word);
	}

	/**
	 * Cached {@code test(this)}: whether this dictionary accepts {@code word} under the caller's
	 * rules (apostrophe and capitalisation variants). The rule must depend only on the word and
	 * this dictionary, so entries stay valid until the dictionary itself is dropped.
	 */
	boolean isAccepted(String word, java.util.function.BiPredicate<HunspellChecker, String> rule) {
		Boolean cached = cache.get(word);
		if (cached != null) {
			return cached;
		}
		boolean ok = rule.test(this, word);
		if (cache.size() >= CACHE_LIMIT) {
			cache.clear();
		}
		cache.put(word, ok);
		return ok;
	}

	/** Hunspell's own ordered suggestions (best first); empty if none or on timeout. Suggestion thread only. */
	public List<String> suggest(String word) {
		if (suggester == null) {
			suggester = new Suggester(dictionary).withSuggestibleEntryCache().proceedPastRep();
		}
		try {
			return suggester.suggestWithTimeout(word, SUGGEST_TIMEOUT_MS, () -> {});
		} catch (SuggestionTimeoutException e) {
			List<String> partial = e.getPartialResult();
			return partial == null ? List.of() : partial;
		}
	}

	/** Frees the suggestion entry cache; the next {@link #suggest} rebuilds it. Suggestion thread only. */
	void releaseSuggestionCache() {
		suggester = null;
	}
}
