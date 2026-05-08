package dev.duzo.players.api;

import dev.duzo.players.Constants;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;

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
		if (data.length < PNG_MAGIC.length) {
			throw new ValidationException("format");
		}
		for (int i = 0; i < PNG_MAGIC.length; i++) {
			if (data[i] != PNG_MAGIC[i]) throw new ValidationException("format");
		}
		BufferedImage img;
		try (InputStream in = new ByteArrayInputStream(data)) {
			img = ImageIO.read(in);
		} catch (IOException e) {
			throw new ValidationException("format");
		}
		if (img == null) throw new ValidationException("format");
		int w = img.getWidth();
		int h = img.getHeight();
		if (w != 64 || (h != 64 && h != 32)) {
			throw new ValidationException("dimensions");
		}
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
