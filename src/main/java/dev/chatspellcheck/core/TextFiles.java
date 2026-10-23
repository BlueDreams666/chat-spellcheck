package dev.chatspellcheck.core;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Reading hand-edited config files and replacing them safely. */
final class TextFiles {
	private TextFiles() {
	}

	/**
	 * Decodes a config file as UTF-8, dropping a leading byte-order mark. Files saved in a legacy
	 * encoding (e.g. Notepad's "ANSI") aren't valid UTF-8; they are read as Windows-1252 instead
	 * of failing, so nothing the player typed is lost.
	 */
	static String read(Path file) throws IOException {
		byte[] bytes = Files.readAllBytes(file);
		String text;
		try {
			text = StandardCharsets.UTF_8.newDecoder()
				.onMalformedInput(CodingErrorAction.REPORT)
				.onUnmappableCharacter(CodingErrorAction.REPORT)
				.decode(ByteBuffer.wrap(bytes)).toString();
		} catch (CharacterCodingException e) {
			text = new String(bytes, Charset.forName("windows-1252"));
		}
		return text.startsWith("﻿") ? text.substring(1) : text;
	}

	/**
	 * Replaces {@code file} with {@code content} via a temporary file in the same folder and a
	 * rename, so readers never see a partial file and a failed write leaves the old one intact.
	 */
	static void writeAtomically(Path file, byte[] content) throws IOException {
		Path dir = file.toAbsolutePath().getParent();
		Files.createDirectories(dir);
		Path tmp = Files.createTempFile(dir, file.getFileName().toString(), ".tmp");
		try {
			Files.write(tmp, content);
			try {
				Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (AtomicMoveNotSupportedException e) {
				Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
			}
		} finally {
			Files.deleteIfExists(tmp);
		}
	}
}
