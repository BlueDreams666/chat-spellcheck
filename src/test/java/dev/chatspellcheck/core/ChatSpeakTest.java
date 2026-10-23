package dev.chatspellcheck.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ChatSpeakTest {
	@Test
	void laughs() {
		for (String w : List.of("haha", "hahaha", "ahahah", "HAHAHA", "hehehe", "jajaja", "xdxd", "lool", "lolol", "חחח", "חחחחח", "ههههه", "kkkk", "www")) {
			assertTrue(ChatSpeak.isLaugh(w), w);
		}
		for (String w : List.of("xd", "ha", "hah", "hat", "habit", "jab", "kkk", "chocolate", "ח")) {
			assertFalse(ChatSpeak.isLaugh(w), w);
		}
	}

	@Test
	void stretchedRunsShrinkToOneAndTwoLetters() {
		assertEquals(List.of("so", "soo"), ChatSpeak.unstretched("sooo"));
		assertEquals(List.of("col", "cool"), ChatSpeak.unstretched("coool"));
		assertEquals(List.of("yes", "yess"), ChatSpeak.unstretched("yesssss"));
		assertEquals(4, ChatSpeak.unstretched("sooo gooood").size());
	}

	@Test
	void doubledLettersAreNotStretching() {
		assertEquals(List.of(), ChatSpeak.unstretched("untill"));
		assertEquals(List.of(), ChatSpeak.unstretched("occured"));
		assertEquals(List.of(), ChatSpeak.unstretched("!!!"));
	}
}
