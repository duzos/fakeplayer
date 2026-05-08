package dev.duzo.players.api;

import dev.duzo.players.Constants;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

public final class LocalSkinStore {
	public static final LocalSkinStore INSTANCE = new LocalSkinStore();
	public static final String URL_PREFIX = "local:";
	public static final int MAX_BYTES = 32 * 1024;

	private static final byte[] PNG_MAGIC = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
	private static final Path ROOT = Paths.get(SkinGrabber.DEFAULT_DIR, "local-skins");

	private LocalSkinStore() {
	}

	public static boolean isLocalUrl(String url) {
		return url != null && url.startsWith(URL_PREFIX);
	}

	public static String keyFromUrl(String url) {
		return url.substring(URL_PREFIX.length());
	}

	public static String urlForKey(String key) {
		return URL_PREFIX + key;
	}

	public boolean has(String key) {
		return Files.isRegularFile(pathFor(key));
	}

	public synchronized void save(String key, byte[] data) throws IOException {
		Files.createDirectories(ROOT);
		Files.write(pathFor(key), data);
	}

	public Optional<byte[]> load(String key) {
		Path p = pathFor(key);
		if (!Files.isRegularFile(p)) return Optional.empty();
		try {
			return Optional.of(Files.readAllBytes(p));
		} catch (IOException e) {
			Constants.LOG.error("Failed to load local skin {}", key, e);
			return Optional.empty();
		}
	}

	public Path pathFor(String key) {
		return ROOT.resolve(sanitize(key) + ".png");
	}

	public static void validate(byte[] data) throws ValidationException {
		if (data == null || data.length == 0) {
			throw new ValidationException("empty");
		}
		if (data.length > MAX_BYTES) {
			throw new ValidationException("size");
		}
		// PNG signature (8) + IHDR length (4) + "IHDR" (4) + width (4) + height (4)
		if (data.length < 24) {
			throw new ValidationException("format");
		}
		for (int i = 0; i < PNG_MAGIC.length; i++) {
			if (data[i] != PNG_MAGIC[i]) throw new ValidationException("format");
		}
		if (data[12] != 'I' || data[13] != 'H' || data[14] != 'D' || data[15] != 'R') {
			throw new ValidationException("format");
		}
		int w = readBigEndianInt(data, 16);
		int h = readBigEndianInt(data, 20);
		if (w != 64 || (h != 64 && h != 32)) {
			throw new ValidationException("dimensions");
		}
	}

	private static int readBigEndianInt(byte[] data, int off) {
		return ((data[off] & 0xff) << 24)
				| ((data[off + 1] & 0xff) << 16)
				| ((data[off + 2] & 0xff) << 8)
				| (data[off + 3] & 0xff);
	}

	private static String sanitize(String key) {
		StringBuilder sb = new StringBuilder(key.length());
		for (int i = 0; i < key.length(); i++) {
			char c = key.charAt(i);
			if ((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F')) {
				sb.append(c);
			}
		}
		String out = sb.toString();
		if (out.isEmpty()) throw new IllegalArgumentException("invalid key");
		return out;
	}

	public static final class ValidationException extends Exception {
		public final String reason;

		public ValidationException(String reason) {
			super(reason);
			this.reason = reason;
		}
	}
}
