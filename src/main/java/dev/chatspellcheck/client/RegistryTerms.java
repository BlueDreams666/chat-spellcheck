package dev.chatspellcheck.client;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;

/**
 * Words taken from Minecraft's own identifiers (items, blocks, entities, effects, potions,
 * villager professions), split on underscores: "netherite_ingot" → "netherite", "ingot".
 * Includes content added by other mods, so modded item names aren't flagged either.
 */
public final class RegistryTerms {
	private RegistryTerms() {
	}

	public static Set<String> collect() {
		Set<String> terms = new HashSet<>();
		for (Registry<?> registry : new Registry<?>[] {
			BuiltInRegistries.ITEM, BuiltInRegistries.BLOCK, BuiltInRegistries.ENTITY_TYPE,
			BuiltInRegistries.MOB_EFFECT, BuiltInRegistries.POTION, BuiltInRegistries.VILLAGER_PROFESSION}) {
			registry.keySet().forEach(id -> {
				terms.add(id.getNamespace());
				terms.addAll(Arrays.asList(id.getPath().split("[_/.]")));
			});
		}
		terms.removeIf(t -> t.length() < 3);
		return terms;
	}
}
