package dev.duzo.players.api;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** SHA-256 hex helpers for skin keys. No client imports: the server reaches this from commands. */
public final class SkinHash {
	private SkinHash() {
	}

	public static String ofBytes(byte[] data) {
		try {
			byte[] hash = MessageDigest.getInstance("SHA-256").digest(data);
			StringBuilder hex = new StringBuilder(hash.length * 2);
			for (byte b : hash) {
				String h = Integer.toHexString(0xff & b);
				if (h.length() == 1) hex.append('0');
				hex.append(h);
			}
			return hex.toString();
		} catch (NoSuchAlgorithmException e) {
			throw new RuntimeException(e);
		}
	}

	public static String ofText(String input) {
		return ofBytes(input.getBytes(StandardCharsets.UTF_8));
	}
}
