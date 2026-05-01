package dev.duzo.players.api;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.datafixers.util.Pair;
import dev.duzo.players.Constants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.Identifier;

import javax.annotation.Nullable;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.URL;
import java.net.URLConnection;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Most of this code is referenced from jeryn's regeneration mod, love u craig
 */
public class SkinGrabber {
	public static final SkinGrabber INSTANCE = new SkinGrabber();
	public static final String SKIN_URL = "https://mineskin.eu/skin/";
	public static final String DEFAULT_DIR = "./" + Constants.MOD_ID + "/";
	public static final String SKIN_DIR = DEFAULT_DIR + "/skins/";
	private static final Identifier MISSING = Identifier.fromNamespaceAndPath(Constants.MOD_ID, "textures/skins/error.png");
	private static final String USER_AGENT = Constants.MOD_ID + "/1.0";

	private final ConcurrentHashMap<String, Identifier> downloads;
	private final ConcurrentHashMap<String, String> urls;
	private final ConcurrentQueueMap<String, String> downloadQueue;
	private final SkinCache cache;
	public final JerynSkins jeryn;

	private int ticks;
	private boolean connection;

	private SkinGrabber() {
		downloads = new ConcurrentHashMap<>();
		urls = new ConcurrentHashMap<>();
		downloadQueue = new ConcurrentQueueMap<>();
		cache = new SkinCache();
		jeryn = new JerynSkins(new ArrayList<>());
	}

	public static Identifier missing() {
		return MISSING;
	}

	public static String encodeURL(String input) {
		try {
			MessageDigest md = MessageDigest.getInstance("SHA-256");
			byte[] hash = md.digest(input.getBytes());
			StringBuilder hexString = new StringBuilder();
			for (byte b : hash) {
				String hex = Integer.toHexString(0xff & b);
				if (hex.length() == 1) hexString.append('0');
				hexString.append(hex);
			}
			return hexString.toString();
		} catch (NoSuchAlgorithmException e) {
			throw new RuntimeException(e);
		}
	}

	public List<String> getAllKeys() {
		return List.copyOf(downloads.keySet());
	}

	// These functions were coped from HttpTexture by Mojang (thak you moywang(
	@Nullable
	private static NativeImage processLegacySkin(NativeImage image) {
		int i = image.getHeight();
		int j = image.getWidth();
		if (j == 64 && (i == 32 || i == 64)) {
			boolean flag = i == 32;
			if (flag) {
				NativeImage nativeimage = new NativeImage(64, 64, true);
				nativeimage.copyFrom(image);
				image.close();
				image = nativeimage;
				nativeimage.fillRect(0, 32, 64, 32, 0);
				nativeimage.copyRect(4, 16, 16, 32, 4, 4, true, false);
				nativeimage.copyRect(8, 16, 16, 32, 4, 4, true, false);
				nativeimage.copyRect(0, 20, 24, 32, 4, 12, true, false);
				nativeimage.copyRect(4, 20, 16, 32, 4, 12, true, false);
				nativeimage.copyRect(8, 20, 8, 32, 4, 12, true, false);
				nativeimage.copyRect(12, 20, 16, 32, 4, 12, true, false);
				nativeimage.copyRect(44, 16, -8, 32, 4, 4, true, false);
				nativeimage.copyRect(48, 16, -8, 32, 4, 4, true, false);
				nativeimage.copyRect(40, 20, 0, 32, 4, 12, true, false);
				nativeimage.copyRect(44, 20, -8, 32, 4, 12, true, false);
				nativeimage.copyRect(48, 20, -16, 32, 4, 12, true, false);
				nativeimage.copyRect(52, 20, -8, 32, 4, 12, true, false);
			}

			setNoAlpha(image, 0, 0, 32, 16);
			if (flag) {
				doNotchTransparencyHack(image, 32, 0, 64, 32);
			}

			setNoAlpha(image, 0, 16, 64, 32);
			setNoAlpha(image, 16, 48, 48, 64);
			return image;
		} else {
			image.close();
			Constants.LOG.warn("Discarding incorrectly sized ({}x{}) skin texture", j, i);
			return null;
		}
	}

	private static void doNotchTransparencyHack(NativeImage p_118013_, int p_118014_, int p_118015_, int p_118016_, int p_118017_) {
		for (int i = p_118014_; i < p_118016_; ++i) {
			for (int j = p_118015_; j < p_118017_; ++j) {
				int k = p_118013_.getPixel(i, j);
				if ((k >> 24 & 255) < 128) {
					return;
				}
			}
		}

		for (int l = p_118014_; l < p_118016_; ++l) {
			for (int i1 = p_118015_; i1 < p_118017_; ++i1) {
				p_118013_.setPixel(l, i1, p_118013_.getPixel(l, i1) & 16777215);
			}
		}

	}

	private static void setNoAlpha(NativeImage p_118023_, int p_118024_, int p_118025_, int p_118026_, int p_118027_) {
		for (int i = p_118024_; i < p_118026_; ++i) {
			for (int j = p_118025_; j < p_118027_; ++j) {
				p_118023_.setPixel(i, j, p_118023_.getPixel(i, j) | -16777216);
			}
		}
	}

	/**
	 * Get the skin for a player
	 * This will download the skin if it doesn't exist
	 *
	 * @param name The name of the player
	 * @return The skin, or a missing texture if it doesn't exist / is downloading
	 */
	public Identifier getSkin(String name) {
		return getSkinOrDownload(name, SKIN_URL + name);
	}

	public Optional<Identifier> getPossibleSkin(String id) {
		id = id.toLowerCase().replace(" ", "_");

		if (downloads.containsKey(id)) {
			return Optional.of(downloads.get(id));
		}

		return Optional.empty();
	}

	public Identifier getSkinOrDownload(String id, String url) {
		id = id.toLowerCase().replace(" ", "_");

		Identifier existing = getPossibleSkin(id).orElse(null);
		if (existing != null) {
			return existing;
		}

		if (downloadQueue.get(id) != null) {
			return missing();
		}

		url = url.toLowerCase().replace(" ", "_");
		this.enqueueDownload(id, url);
		return missing();
	}

	public String getUrl(String key) {
		return urls.get(key);
	}

	private Identifier registerSkin(String name) {
		// register new skin to prepare
		File file = new File(SKIN_DIR + name.toLowerCase().replace(" ", "_") + ".png");
		Identifier location = fileToLocation(file);
		downloads.put(name, location);
		return location;
	}

	public void clearTextures() {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.level == null) {
			TextureManager manager = minecraft.getTextureManager();

			// Release the textures if the cache isnt empty
			if (!this.downloads.isEmpty()) {
				this.downloads.forEach((key, value) -> manager.release(value));
				this.downloads.clear();
			}
		}
	}

	private Identifier fileToLocation(File file) {
		NativeImage image;
		try {
			image = processLegacySkin(NativeImage.read(new FileInputStream(file)));
		} catch (IOException e) {
			Constants.LOG.error("Failed to load Identifier from file", e);
			return missing();
		}
		if (image == null) {
			return missing();
		}
		return registerImage(image);
	}

	private Identifier registerImage(NativeImage image) {
		TextureManager manager = Minecraft.getInstance().getTextureManager();
		Identifier id = Identifier.fromNamespaceAndPath(Constants.MOD_ID, "player_" + System.nanoTime());
		manager.register(id, new DynamicTexture(id::toString, image));
		return id;
	}

	private static boolean isValidUrl(String url) {
		try {
			new URL(url).toURI();
			return true;
		} catch (Exception e) {
			return false;
		}
	}

	private void downloadImageFromURL(String filename, File filepath, String URL) throws IOException {
		URL url = new URL(URL);
		URLConnection connection = url.openConnection();
		connection.setConnectTimeout(5000);
		connection.setReadTimeout(5000);
		connection.setRequestProperty("User-Agent", USER_AGENT);
		connection.connect();

		BufferedImage image = ImageIO.read(connection.getInputStream());

		if (!filepath.exists()) {
			filepath.mkdirs();
		}

		ImageIO.write(image, "png", new File(filepath, filename + ".png"));
	}

	public void tick() {
		ticks++;

		if (/*ticks % 5 != 0 ||*/ connection) return;
		// called every second
		this.downloadNext();

		ticks = 0;
	}

	private void enqueueDownload(String id, String url) {
		this.downloadQueue.put(id, url);

		Constants.debug("Enqueued Download {} for {}", url, id);
	}

	private void downloadNext() {
		if (this.downloadQueue.isEmpty()) {
			return;
		}

		Pair<String, String> data = this.downloadQueue.remove();

		this.download(data.getFirst(), data.getSecond());
	}

	private void download(String id, String url) {
		Constants.debug("Downloading {} for {}", url, id);

		if (!(isValidUrl(url))) {
			Constants.LOG.error("Discarding Invalid URL: {}", url);
			return;
		}

		connection = true;

		// check cache
		SkinCache.CacheData data = cache.get(id).orElse(null);
		if (data != null) {
			try {
				Constants.debug("Using cached skin for {}", id);
				urls.put(id, data.url());
				this.registerSkin(id);
				connection = false;
				return;
			} catch (Exception exception) {
				Constants.LOG.error("Failed to load cached skin for {}", id, exception);
			}
		}

		urls.put(id, url);

		new Thread(() -> {
			try {
				this.downloadImageFromURL(id, new File(SKIN_DIR), url);
				this.registerSkin(id);
				this.cache.add(id, url);
				Constants.debug("Downloaded {} for {}!", url, id);
			} catch (Exception exception) {
				Constants.LOG.error("Failed to download {} for {}", url, id, exception);
			} finally {
				connection = false;
			}
		}, Constants.MOD_ID + "-Download").start();
	}

	public boolean hasDownloads() {
		return !downloadQueue.isEmpty();
	}

	public int getDownloadsRemaining() {
		return downloadQueue.size();
	}

	public void onStopping() {
		// this.clearTextures();
		cache.save();
	}

	public interface IDownloadSource {
		default void download() {
			Constants.debug("Downloading {}", getId());

			getTracker().connection = true;

			if (this.isDownloaded()) {
				Constants.LOG.warn("{} is already downloaded", getId());
			}

			new Thread(() -> {
				try {
					this.downloadThreaded();
				} catch (Exception exception) {
					Constants.LOG.error("Failed to download {}", getId(), exception);
				} finally {
					getTracker().connection = false;
				}
			}, this.getId() + "-Download").start();
		}

		void downloadThreaded();

		String getId();

		boolean isDownloaded();

		default SkinGrabber getTracker() {
			return SkinGrabber.INSTANCE;
		}
	}

	public static class JerynSkins implements IDownloadSource {
		public static final String JERYN_URL = "https://api.jeryn.dev/mc/skins/random";
		private final ArrayList<String> keys;

		public JerynSkins(ArrayList<String> keys) {
			this.keys = keys;
		}

		@Override
		public boolean isDownloaded() {
			return !this.getTracker().cache.isJerynOutdated() || !keys.isEmpty() && new HashSet<>(this.getTracker().getAllKeys()).containsAll(keys);
		}

		@Override
		public void downloadThreaded() {
			try {
				if (this.isDownloaded()) {
					Constants.debug("JerynSkins is already downloaded");
					return;
				}

				this.getTracker().cache.lastJerynCheck = System.currentTimeMillis();

				URL api = new URL(JERYN_URL);
				URLConnection connection = api.openConnection();
				connection.setConnectTimeout(5000);
				connection.setReadTimeout(5000);
				connection.setRequestProperty("User-Agent", USER_AGENT);
				connection.connect();

				InputStream inputStream = connection.getInputStream();
				BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
				StringBuilder stringBuilder = new StringBuilder();
				String line;

				while ((line = reader.readLine()) != null) {
					stringBuilder.append(line);
				}

				JsonElement data = new GsonBuilder().create().fromJson(stringBuilder.toString(), JsonElement.class);
				if (!data.isJsonArray()) {
					throw new IllegalStateException("Expected array");
				}

				HashMap<String, String> skins = new HashMap<>();

				data.getAsJsonArray().forEach(element -> {
					if (!element.isJsonObject()) {
						throw new IllegalStateException("Expected object");
					}

					String url = element.getAsJsonObject().get("link").getAsString();
					String id = encodeURL(url);

					skins.put(id, url);
				});

				keys.clear();
				keys.addAll(skins.keySet());

				skins.forEach(SkinGrabber.INSTANCE::enqueueDownload);
			} catch (Exception exception) {
				Constants.LOG.error("Failed to download JerynSkins", exception);
			}
		}

		@Override
		public String getId() {
			return Constants.MOD_ID + "-JerynSkins";
		}
	}

	public static class SimpleDownloadSource implements IDownloadSource {
		protected final Consumer<SimpleDownloadSource> download;
		protected final Function<SimpleDownloadSource, Boolean> isDownloaded;
		private final String id;

		public SimpleDownloadSource(String id, Consumer<SimpleDownloadSource> download, Function<SimpleDownloadSource, Boolean> isDownloaded) {
			this.id = id;
			this.download = download;
			this.isDownloaded = isDownloaded;
		}

		public boolean isDownloaded() {
			return this.isDownloaded.apply(this);
		}

		public void downloadThreaded() {
			this.download.accept(this);
		}

		public String getId() {
			return Constants.MOD_ID + id;
		}
	}
}
