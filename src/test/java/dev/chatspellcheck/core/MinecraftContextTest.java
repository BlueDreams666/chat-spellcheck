package dev.chatspellcheck.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import dev.chatspellcheck.client.RegistryTerms;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Ranking and allow-listing with the real vocabulary the mod uses in game. */
class MinecraftContextTest {
	private static Set<String> domain;
	private static Set<String> curated;

	@BeforeAll
	static void bootstrap() throws Exception {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
		domain = new HashSet<>(RegistryTerms.collect());
		curated = new HashSet<>();
		try (BufferedReader r = new BufferedReader(new InputStreamReader(TestResources.open("allowlist.txt"), StandardCharsets.UTF_8))) {
			r.lines().map(String::strip).filter(l -> !l.isEmpty() && !l.startsWith("#")).forEach(curated::add);
		domain.addAll(curated);
		}
		System.out.println("domain terms: " + domain.size());
	}

	@Test
	void registryProvidesMinecraftWords() {
		for (String w : List.of("netherite", "enderman", "axolotl", "pickaxe", "elytra", "piglin", "deepslate", "sniffer")) {
			assertTrue(domain.contains(w), w);
		}
	}

	@Test
	void noTestTypoIsAccidentallyAllowListed() {
		List<String> leaked = new ArrayList<>();
		for (String[][] set : List.of(RankingTest.TUNING, RankingTest.HELD_OUT, RankingTest.FINAL)) {
			for (String[] c : set) {
				if (!c[0].equals(c[1]) && domain.contains(c[0].toLowerCase())) {
					leaked.add(c[0]);
				}
			}
		}
		assertEquals(List.of(), leaked);
	}

	@Test
	void curatedBonusHelpsMinecraftWordsWithoutHurtingGeneralTypos() {
		SuggestionRanker inGame = new SuggestionRanker(TestResources.frequency(), curated::contains);
		int[] tuning = RankingTest.score("TUNING (in-game vocab)", RankingTest.TUNING, inGame);
		int[] heldOut = RankingTest.score("HELD_OUT (in-game vocab)", RankingTest.HELD_OUT, inGame);
		int[] fin = RankingTest.score("FINAL (in-game vocab)", RankingTest.FINAL, inGame);
		assertTrue(tuning[0] >= 67 && heldOut[0] >= 61 && fin[0] >= 45);
		assertEquals("crafting", RankingTest.suggest(inGame, "crafing").get(0));
		assertEquals("enchantment", RankingTest.suggest(inGame, "enchantmnet").get(0));
		assertEquals("which", RankingTest.suggest(inGame, "wich").get(0)); // not "witch"
		assertEquals("whether", RankingTest.suggest(inGame, "wether").get(0)); // not "wither"
		assertEquals("time", RankingTest.suggest(inGame, "tiem").get(0)); // not "item"
	}
}
