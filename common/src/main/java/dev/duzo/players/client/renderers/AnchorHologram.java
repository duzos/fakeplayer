package dev.duzo.players.client.renderers;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.QuadInstance;
import com.mojang.blaze3d.vertex.VertexConsumer;
import commonnetwork.api.Network;
import dev.duzo.players.client.preview.SchematicPreviewModel;
import dev.duzo.players.core.AIMarkerItem;
import dev.duzo.players.core.FPJobs;
import dev.duzo.players.entities.FakePlayerEntity;
import dev.duzo.players.entities.ai.builder.BuilderParams;
import dev.duzo.players.entities.ai.builder.SchematicPreview;
import dev.duzo.players.network.c2s.RequestSchematicPreviewC2S;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.ShapeRenderer;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.RandomSource;
import net.minecraft.util.Util;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * The see-through build shown while an anchor marker is held: the Builder's schematic where a click
 * would put it, turned the way the player faces. Blocks are fetched through the picker's preview
 * packet, so a schematic past the preview cap shows only its first blocks (the box turns amber), and
 * air the Builder would clear is not marked. Blocks already in place are left out.
 */
public final class AnchorHologram {
	private static final int MAX_DRAWN = 2048;
	private static final int REFRESH_TICKS = 10;
	private static final long BUSY_RETRY_MILLIS = 400;
	private static final long SILENT_RETRY_MILLIS = 5000;
	/** Put away for longer than this, the schematic is fetched again, so an edited file shows. */
	private static final long KEEP_MILLIS = 30_000;
	private static final int COLOR_BOUNDS = 0xA066E5FF;
	private static final int COLOR_BOUNDS_PARTIAL = 0xC0FFB040;
	private static final int COLOR_SPECIAL = 0xC066E5FF;
	private static final float LINE_WIDTH = 2.0F;
	/** The model's own colours times (0.55, 0.85, 1.0), at 0.45 alpha; red where a block would be replaced. */
	private static final int COLOR_GHOST = 0x728CD8FF;
	private static final int COLOR_GHOST_WRONG = 0x72FF7272;
	private static final Direction[] SIDES = Direction.values();
	private static final RandomSource RANDOM = RandomSource.create();
	private static final List<BlockStateModelPart> PARTS = new ArrayList<>();

	private static int wantedEntity = -1;
	private static String wantedName = "";
	private static long retryAt;
	private static long idleSince;
	private static @Nullable SchematicPreviewModel model;
	private static boolean failed;

	private static @Nullable Layout layout;
	private static @Nullable BlockPos anchor;
	private static long refreshedAt;
	private static boolean stale = true;
	private static int drawnCount;
	private static int[] drawnX = new int[0];
	private static int[] drawnY = new int[0];
	private static int[] drawnZ = new int[0];
	private static BlockState[] drawnState = new BlockState[0];
	private static int[] drawnTint = new int[0];
	private static boolean[] drawnReplaces = new boolean[0];

	/** The schematic turned and mirrored once, as offsets from the box's minimum corner, so moving the anchor only adds. */
	private record Layout(SchematicPreviewModel model, Rotation rotation, Mirror mirror, int spanX, int spanY, int spanZ,
	                      int[] dx, int[] dy, int[] dz, BlockState[] states) {
		boolean matches(SchematicPreviewModel m, Rotation r, Mirror mi) {
			return model == m && rotation == r && mirror == mi;
		}
	}

	private AnchorHologram() {}

	/** Called every frame the anchor marker is held; draws the bounds into `lines` and returns whether a ghost is ready. */
	public static boolean prepare(PoseStack pose, VertexConsumer lines, Vec3 cam, ClientLevel level, LocalPlayer player,
	                              @Nullable FakePlayerEntity bound, Minecraft mc) {
		if (idleSince != 0) {
			if (Util.getMillis() - idleSince > KEEP_MILLIS) reset();
			idleSince = 0;
			stale = true;
		}
		if (bound == null || !player.getUUID().equals(bound.getAIState().ownerUUID())
				|| !FPJobs.is(bound.getAIState().jobId(), FPJobs.BUILDER)) return false;
		BuilderParams params = BuilderParams.read(bound.getAIState());
		String name = params.schematic().trim();
		if (name.isEmpty() || name.indexOf(':') >= 0) return false;
		SchematicPreviewModel m = want(bound.getId(), name);
		if (m == null) return false;
		if (!(mc.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) return false;

		BlockPos at = hit.getBlockPos().relative(hit.getDirection());
		Rotation rotation = AIMarkerItem.rotationFor(player.getDirection());
		Layout l = layout;
		if (l == null || !l.matches(m, rotation, params.mirror())) {
			l = lay(m, rotation, params.mirror());
			layout = l;
			stale = true;
		}
		if (!at.equals(anchor)) {
			anchor = at;
			stale = true;
		}
		long now = level.getGameTime();
		if (stale || now - refreshedAt >= REFRESH_TICKS || now < refreshedAt) {
			refresh(level, l, at, mc.getBlockColors());
			refreshedAt = now;
			stale = false;
		}

		ShapeRenderer.renderShape(pose, lines, Shapes.box(0, 0, 0, l.spanX, l.spanY, l.spanZ),
				at.getX() - cam.x, at.getY() - cam.y, at.getZ() - cam.z,
				m.truncated() ? COLOR_BOUNDS_PARTIAL : COLOR_BOUNDS, LINE_WIDTH);
		for (int k = 0; k < drawnCount; k++) {
			// chests, beds and signs are drawn by their block entity, so their block model is empty
			if (drawnState[k].getRenderShape() == RenderShape.MODEL && !drawnState[k].hasBlockEntity()) continue;
			ShapeRenderer.renderShape(pose, lines, Shapes.block(), drawnX[k] - cam.x, drawnY[k] - cam.y, drawnZ[k] - cam.z,
					COLOR_SPECIAL, LINE_WIDTH);
		}
		return drawnCount > 0;
	}

	/** Must run after the lines batch is ended, since it asks the shared buffer for another render type. */
	public static void drawGhost(PoseStack pose, MultiBufferSource.BufferSource buffers, Vec3 cam, Minecraft mc) {
		if (layout == null || drawnCount == 0) return;
		RenderType type = RenderTypes.translucentMovingBlock();
		emitGhost(pose.last(), buffers.getBuffer(type), cam, mc);
		buffers.endBatch(type);
	}

	/**
	 * Every drawn block's model quads, recoloured and faded, full bright. Only the model: a block entity
	 * renderer would ask for its own render type and texture.
	 */
	private static void emitGhost(PoseStack.Pose base, VertexConsumer out, Vec3 cam, Minecraft mc) {
		BlockStateModelSet models = mc.getModelManager().getBlockStateModelSet();
		PoseStack.Pose at = new PoseStack.Pose();
		QuadInstance quad = new QuadInstance();
		quad.setLightCoords(LightCoordsUtil.FULL_BRIGHT);
		quad.setOverlayCoords(OverlayTexture.NO_OVERLAY);
		for (int k = 0; k < drawnCount; k++) {
			BlockState state = drawnState[k];
			if (state.getRenderShape() != RenderShape.MODEL) continue;
			int plain = drawnReplaces[k] ? COLOR_GHOST_WRONG : COLOR_GHOST;
			int tinted = ghostColor(drawnTint[k], plain);
			at.set(base);
			at.translate((float) (drawnX[k] - cam.x), (float) (drawnY[k] - cam.y), (float) (drawnZ[k] - cam.z));
			PARTS.clear();
			RANDOM.setSeed(42L);
			models.get(state).collectParts(RANDOM, PARTS);
			for (BlockStateModelPart part : PARTS) {
				for (Direction side : SIDES) emitQuads(at, out, quad, part.getQuads(side), tinted, plain);
				emitQuads(at, out, quad, part.getQuads(null), tinted, plain);
			}
		}
	}

	private static void emitQuads(PoseStack.Pose at, VertexConsumer out, QuadInstance quad, List<BakedQuad> quads,
	                              int tinted, int plain) {
		for (BakedQuad q : quads) {
			quad.setColor(q.materialInfo().isTinted() ? tinted : plain);
			out.putBakedQuad(at, q, quad);
		}
	}

	/** The BlockColors tint scaled by the ghost's colour, as the tinted quads of a block draw it. */
	private static int ghostColor(int tint, int ghost) {
		return (ghost & 0xFF000000)
				| ((tint >> 16 & 0xFF) * (ghost >> 16 & 0xFF) / 255) << 16
				| ((tint >> 8 & 0xFF) * (ghost >> 8 & 0xFF) / 255) << 8
				| (tint & 0xFF) * (ghost & 0xFF) / 255;
	}

	/** The marker is not held. The fetched schematic is kept a while, so flicking through the hotbar does not refetch it. */
	public static void idle() {
		if (idleSince == 0 && (wantedEntity != -1 || model != null)) idleSince = Util.getMillis();
	}

	public static void reset() {
		wantedEntity = -1;
		wantedName = "";
		retryAt = 0;
		idleSince = 0;
		model = null;
		failed = false;
		layout = null;
		anchor = null;
		stale = true;
		drawnCount = 0;
	}

	/** From the preview packet's receiver; only the reply to this class's own request is taken. */
	public static void onPreview(int entityId, String name, SchematicPreview.@Nullable Data data, String error, boolean busy) {
		if (entityId != wantedEntity || !name.equals(wantedName) || model != null || failed) return;
		if (busy) {
			retryAt = Util.getMillis() + BUSY_RETRY_MILLIS;
			return;
		}
		if (data == null) {
			failed = true;
			return;
		}
		model = SchematicPreviewModel.of(data);
	}

	/** The model for this Builder and name if it is in hand, asking the server for it when it is not. */
	private static @Nullable SchematicPreviewModel want(int entityId, String name) {
		if (entityId != wantedEntity || !name.equals(wantedName)) {
			reset();
			wantedEntity = entityId;
			wantedName = name;
		}
		if (model != null || failed) return model;
		long now = Util.getMillis();
		if (now >= retryAt) {
			// a request the server drops without a reply is asked again later rather than never
			retryAt = now + SILENT_RETRY_MILLIS;
			Network.getNetworkHandler().sendToServer(new RequestSchematicPreviewC2S(entityId, name));
		}
		return null;
	}

	/** Offsets and states the same way BuildPlan.build lays them out: the turned box's minimum corner at the anchor. */
	private static Layout lay(SchematicPreviewModel m, Rotation rotation, Mirror mirror) {
		BlockPos a = StructureTemplate.transform(BlockPos.ZERO, mirror, rotation, BlockPos.ZERO);
		BlockPos b = StructureTemplate.transform(new BlockPos(m.sizeX() - 1, m.sizeY() - 1, m.sizeZ() - 1), mirror, rotation, BlockPos.ZERO);
		int minX = Math.min(a.getX(), b.getX());
		int minY = Math.min(a.getY(), b.getY());
		int minZ = Math.min(a.getZ(), b.getZ());
		int n = m.visible();
		int[] dx = new int[n];
		int[] dy = new int[n];
		int[] dz = new int[n];
		BlockState[] states = new BlockState[n];
		for (int i = 0; i < n; i++) {
			BlockPos p = StructureTemplate.transform(new BlockPos(m.x(i), m.y(i), m.z(i)), mirror, rotation, BlockPos.ZERO);
			dx[i] = p.getX() - minX;
			dy[i] = p.getY() - minY;
			dz[i] = p.getZ() - minZ;
			states[i] = m.state(i).mirror(mirror).rotate(rotation);
		}
		return new Layout(m, rotation, mirror, Math.abs(a.getX() - b.getX()) + 1, Math.abs(a.getY() - b.getY()) + 1,
				Math.abs(a.getZ() - b.getZ()) + 1, dx, dy, dz, states);
	}

	/** Drops blocks already in place and marks those that would replace something; at most MAX_DRAWN, evenly spread. */
	private static void refresh(ClientLevel level, Layout l, BlockPos at, BlockColors colors) {
		int n = l.states.length;
		int[] pending = new int[n];
		int count = 0;
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int i = 0; i < n; i++) {
			pos.set(at.getX() + l.dx[i], at.getY() + l.dy[i], at.getZ() + l.dz[i]);
			if (level.getBlockState(pos) != l.states[i]) pending[count++] = i;
		}
		int keep = Math.min(count, MAX_DRAWN);
		if (drawnX.length < keep) {
			drawnX = new int[keep];
			drawnY = new int[keep];
			drawnZ = new int[keep];
			drawnState = new BlockState[keep];
			drawnTint = new int[keep];
			drawnReplaces = new boolean[keep];
		}
		for (int k = 0; k < keep; k++) {
			int i = pending[keep == count ? k : (int) ((long) k * count / keep)];
			pos.set(at.getX() + l.dx[i], at.getY() + l.dy[i], at.getZ() + l.dz[i]);
			drawnX[k] = pos.getX();
			drawnY[k] = pos.getY();
			drawnZ[k] = pos.getZ();
			drawnState[k] = l.states[i];
			BlockTintSource tint = colors.getTintSource(l.states[i], 0);
			drawnTint[k] = tint == null ? -1 : tint.colorInWorld(l.states[i], level, pos);
			drawnReplaces[k] = !level.getBlockState(pos).isAir();
		}
		drawnCount = keep;
	}
}
