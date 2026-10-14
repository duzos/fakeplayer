package dev.duzo.players.client.renderers;

import dev.duzo.players.config.PlayersConfig;
import dev.duzo.players.entities.FakePlayerEntity;
import dev.duzo.players.entities.FakeProblem;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.world.entity.EntityAttachment;
import net.minecraft.world.phys.Vec3;

import com.mojang.blaze3d.vertex.PoseStack;

import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Speech bubbles above fakes, drawn with vanilla's own name tag call so they follow the camera and
 * work under shader packs the way name tags do. Client only: the entity keeps plain text lines and
 * everything here reads them.
 */
public final class SpeechBubbles {
	/** Font pixels, so a bubble is about as wide as a short sentence. */
	private static final int WRAP_WIDTH = 140;
	private static final int LINE_HEIGHT = 10;
	/** Rows, not lines: a long line wraps, and a column of eight rows is not a bubble. */
	private static final int MAX_ROWS = 6;

	private static final Map<String, List<Component>> WRAPPED = new LinkedHashMap<>(32, 0.75f, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<String, List<Component>> eldest) {
			return size() > 64;
		}
	};

	private SpeechBubbles() {}

	/** Fills the bubble and status mark fields of the render state. Leaves them empty when this fake should show neither. */
	public static void extract(FakePlayerEntity entity, FakeAvatarRenderState fake, boolean nameTagAllowed, boolean customNameVisible, double distanceToCameraSq, float partialTick) {
		fake.bubbleRows = List.of();
		fake.bubbleAnchor = null;
		fake.statusMark = null;
		// hidden wherever vanilla would hide the name tag (F1, invisible, teams, sneaking, 64 blocks)
		if (!nameTagAllowed) return;

		// vanilla only fills the name tag attachment while the name shows, so it is read here directly
		Vec3 anchor = entity.getAttachments().getNullable(EntityAttachment.NAME_TAG, 0, entity.getYRot(partialTick));
		if (anchor == null) return;
		fake.bubbleAnchor = anchor;
		// the name tag sits at offset 0, so with a name actually drawn the bubble starts one row above it
		fake.bubbleBase = customNameVisible ? -LINE_HEIGHT : 0;

		PlayersConfig config = PlayersConfig.get();
		List<Component> rows = bubbleRows(entity, config, distanceToCameraSq);
		fake.bubbleRows = rows;

		FakeProblem problem = entity.getProblem();
		if (problem != FakeProblem.NONE) {
			fake.statusMark = markFor(problem);
			// above the name and any bubble
			fake.markBase = fake.bubbleBase - rows.size() * LINE_HEIGHT;
		}
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

	private static Component markFor(FakeProblem problem) {
		return problem == FakeProblem.STUCK
			? Component.literal("?").withStyle(ChatFormatting.RED, ChatFormatting.BOLD)
			: Component.literal("!").withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD);
	}

	public static void submit(FakeAvatarRenderState state, PoseStack pose, SubmitNodeCollector collector, CameraRenderState camera) {
		if (state.bubbleAnchor == null) return;
		// not see-through, unlike a name tag: speech should not read through walls
		int last = state.bubbleRows.size() - 1;
		for (int i = 0; i <= last; i++) {
			collector.submitNameTag(pose, state.bubbleAnchor, state.bubbleBase - (last - i) * LINE_HEIGHT,
				state.bubbleRows.get(i), false, state.lightCoords, state.distanceToCameraSq, camera);
		}
		// the mark may be seen through walls, so a fake in trouble can be found
		if (state.statusMark != null) {
			collector.submitNameTag(pose, state.bubbleAnchor, state.markBase, state.statusMark, true,
				state.lightCoords, state.distanceToCameraSq, camera);
		}
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
