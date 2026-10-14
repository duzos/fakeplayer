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
import net.minecraft.world.entity.EntityAttachment;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Speech bubbles above fakes, drawn the way vanilla draws a name tag so they follow the camera and
 * work under shader packs the way name tags do. Client only: the entity keeps plain text lines and
 * everything here reads them.
 */
public final class SpeechBubbles {
	/** Font pixels, so a bubble is about as wide as a short sentence. */
	private static final int WRAP_WIDTH = 140;
	/** Vanilla's score line stacking: 9 font pixels plus 15 percent, at the 0.025 name tag scale. */
	private static final float ROW_HEIGHT = 9.0f * 1.15f * 0.025f;
	/** Rows, not lines: a long line wraps, and a column of eight rows is not a bubble. */
	private static final int MAX_ROWS = 6;

	private static final Map<String, List<Component>> WRAPPED = new LinkedHashMap<>(32, 0.75f, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<String, List<Component>> eldest) {
			return size() > 64;
		}
	};

	private SpeechBubbles() {}

	/** Draws the bubble above the fake. Draws nothing when this fake should not show one. */
	public static void render(FakePlayerEntity entity, boolean nameTagAllowed, boolean customNameVisible, PoseStack pose, MultiBufferSource buffer,
							  int light, EntityRenderDispatcher dispatcher, Font font, float partialTick) {
		// hidden wherever vanilla would hide the name tag (F1, invisible, teams, sneaking, 64 blocks)
		if (!nameTagAllowed) return;

		PlayersConfig config = PlayersConfig.get();
		List<Component> rows = bubbleRows(entity, config, dispatcher.distanceToSqr(entity), dispatcher);
		if (rows.isEmpty()) return;

		// the name tag sits at level 0, so with a name actually drawn the bubble starts one row above it
		int base = customNameVisible ? 1 : 0;
		int last = rows.size() - 1;
		for (int i = 0; i <= last; i++) {
			// not see-through, unlike a name tag: speech should not read through walls
			drawRow(entity, rows.get(i), base + (last - i), false, pose, buffer, light, dispatcher, font, partialTick);
		}
	}

	private static void drawRow(FakePlayerEntity entity, Component text, int level, boolean seeThrough, PoseStack pose,
								MultiBufferSource buffer, int light, EntityRenderDispatcher dispatcher, Font font, float partialTick) {
		// vanilla only draws the name tag while this attachment exists, so there is nothing to hang a row on without it
		Vec3 anchor = entity.getAttachments().getNullable(EntityAttachment.NAME_TAG, 0, entity.getViewYRot(partialTick));
		if (anchor == null) return;
		pose.pushPose();
		pose.translate(anchor.x, anchor.y + 0.5 + level * ROW_HEIGHT, anchor.z);
		pose.mulPose(dispatcher.cameraOrientation());
		pose.scale(0.025f, -0.025f, 0.025f);
		Matrix4f matrix = pose.last().pose();
		int background = (int) (Minecraft.getInstance().options.getBackgroundOpacity(0.25f) * 255.0f) << 24;
		float x = -font.width(text) / 2.0f;
		if (seeThrough) {
			font.drawInBatch(text, x, 0.0f, 0x20FFFFFF, false, matrix, buffer, Font.DisplayMode.SEE_THROUGH, background, light);
			font.drawInBatch(text, x, 0.0f, -1, false, matrix, buffer, Font.DisplayMode.NORMAL, 0, light);
		} else {
			font.drawInBatch(text, x, 0.0f, -1, false, matrix, buffer, Font.DisplayMode.NORMAL, background, light);
		}
		pose.popPose();
	}

	private static List<Component> bubbleRows(FakePlayerEntity entity, PlayersConfig config, double distanceToCameraSq, EntityRenderDispatcher dispatcher) {
		if (!prune(entity, config)) return List.of();
		double range = config.bubbleRange;
		if (distanceToCameraSq > range * range) return List.of();
		if (!isAmongNearestSpeakers(entity, distanceToCameraSq, config, dispatcher)) return List.of();

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

	private static boolean isAmongNearestSpeakers(FakePlayerEntity entity, double distanceSq, PlayersConfig config, EntityRenderDispatcher dispatcher) {
		double reach = config.bubbleRange * 2.0;
		long now = entity.level().getGameTime();
		long maxAge = (long) (config.bubbleSeconds * 20.0);
		int closer = 0;
		for (FakePlayerEntity other : entity.level().getEntitiesOfClass(FakePlayerEntity.class, entity.getBoundingBox().inflate(reach))) {
			if (other == entity || !hasLiveLine(other, now, maxAge)) continue;
			double d = dispatcher.distanceToSqr(other);
			if (d < distanceSq || (d == distanceSq && other.getId() < entity.getId())) closer++;
		}
		return closer < config.bubbleMaxSpeakers;
	}

	/** An expired newest line means nothing is showing, even if prune has not run for that fake yet. */
	private static boolean hasLiveLine(FakePlayerEntity other, long now, long maxAge) {
		FakePlayerEntity.SpeechLine newest = other.speechLines().peekLast();
		return newest != null && now - newest.tick() <= maxAge;
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
