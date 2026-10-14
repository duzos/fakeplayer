package dev.duzo.players.client.renderers;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.duzo.players.config.PlayersConfig;
import dev.duzo.players.entities.FakePlayerEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Speech bubbles above fakes, drawn the way vanilla draws a name tag so they follow the camera.
 * Client only: the entity keeps plain text lines and everything here reads them.
 */
public final class SpeechBubbles {
	/** Font pixels, so a bubble is about as wide as a short sentence. */
	private static final int WRAP_WIDTH = 140;
	/** Vanilla's gap between a name tag and its score line, in blocks. */
	private static final float ROW_STEP = 9.0F * 1.15F * 0.025F;
	/** Rows, not lines: a long line wraps, and a column of eight rows is not a bubble. */
	private static final int MAX_ROWS = 6;

	private static final Map<String, List<Component>> WRAPPED = new LinkedHashMap<>(32, 0.75f, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<String, List<Component>> eldest) {
			return size() > 64;
		}
	};

	private SpeechBubbles() {}

	/** Draws this fake's bubble above its name tag position. Call with the pose at the entity origin. */
	public static void render(FakePlayerEntity entity, PoseStack pose, MultiBufferSource buffers, int light,
							  EntityRenderDispatcher dispatcher, Font font, boolean nameShown) {
		PlayersConfig config = PlayersConfig.get();
		double distanceSq = dispatcher.distanceToSqr(entity);
		List<Component> rows = bubbleRows(entity, config, distanceSq);
		if (rows.isEmpty()) return;

		// the name tag sits at step 0, so with a name actually drawn the bubble starts one row above it
		int base = nameShown ? 1 : 0;
		int last = rows.size() - 1;
		for (int i = 0; i <= last; i++) {
			// not see-through, unlike a name tag: speech should not read through walls
			drawLine(entity, rows.get(i), base + (last - i), pose, buffers, light, dispatcher, font, false);
		}
	}

	private static void drawLine(FakePlayerEntity entity, Component text, int step, PoseStack pose, MultiBufferSource buffers,
								 int light, EntityRenderDispatcher dispatcher, Font font, boolean seeThrough) {
		pose.pushPose();
		pose.translate(0.0F, entity.getNameTagOffsetY() + step * ROW_STEP, 0.0F);
		pose.mulPose(dispatcher.cameraOrientation());
		pose.scale(-0.025F, -0.025F, 0.025F);
		Matrix4f matrix = pose.last().pose();
		int background = (int) (Minecraft.getInstance().options.getBackgroundOpacity(0.25F) * 255.0F) << 24;
		float x = -font.width(text) / 2.0F;
		if (seeThrough) {
			font.drawInBatch(text, x, 0, 0x20FFFFFF, false, matrix, buffers, Font.DisplayMode.SEE_THROUGH, background, light);
			font.drawInBatch(text, x, 0, -1, false, matrix, buffers, Font.DisplayMode.NORMAL, 0, light);
		} else {
			font.drawInBatch(text, x, 0, -1, false, matrix, buffers, Font.DisplayMode.NORMAL, background, light);
		}
		pose.popPose();
	}

	private static List<Component> bubbleRows(FakePlayerEntity entity, PlayersConfig config, double distanceToCameraSq) {
		if (!prune(entity, config)) return List.of();
		double range = config.bubbleRange;
		if (distanceToCameraSq > range * range) return List.of();
		if (!isAmongNearestSpeakers(entity, distanceToCameraSq, config)) return List.of();

		List<FakePlayerEntity.SpeechLine> lines = new ArrayList<>(entity.speechLines());
		if (lines.size() > config.bubbleMaxLines) lines = lines.subList(lines.size() - config.bubbleMaxLines, lines.size());

		List<Component> rows = new ArrayList<>();
		for (FakePlayerEntity.SpeechLine line : lines) rows.addAll(wrap(line.text()));
		if (rows.size() > MAX_ROWS) rows = rows.subList(rows.size() - MAX_ROWS, rows.size());
		return rows;
	}

	/** Drops expired lines. @return true when something is left to show. */
	private static boolean prune(FakePlayerEntity entity, PlayersConfig config) {
		Deque<FakePlayerEntity.SpeechLine> buffer = entity.speechLines();
		if (buffer.isEmpty()) return false;
		long now = entity.level().getGameTime();
		long maxAge = (long) (config.bubbleSeconds * 20.0);
		while (!buffer.isEmpty() && now - buffer.peekFirst().tick() > maxAge) buffer.removeFirst();
		return !buffer.isEmpty();
	}

	private static boolean isAmongNearestSpeakers(FakePlayerEntity entity, double distanceSq, PlayersConfig config) {
		double reach = config.bubbleRange * 2.0;
		var dispatcher = Minecraft.getInstance().getEntityRenderDispatcher();
		int closer = 0;
		for (FakePlayerEntity other : entity.level().getEntitiesOfClass(FakePlayerEntity.class, entity.getBoundingBox().inflate(reach))) {
			if (other == entity || other.speechLines().isEmpty()) continue;
			double d = dispatcher.distanceToSqr(other);
			if (d < distanceSq || (d == distanceSq && other.getId() < entity.getId())) closer++;
		}
		return closer < config.bubbleMaxSpeakers;
	}

	private static List<Component> wrap(String text) {
		return WRAPPED.computeIfAbsent(text, t -> {
			List<Component> out = new ArrayList<>();
			Minecraft.getInstance().font.getSplitter().splitLines(t, WRAP_WIDTH, Style.EMPTY)
				.forEach(piece -> out.add(Component.literal(piece.getString())));
			return out;
		});
	}
}
