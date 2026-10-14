package dev.duzo.players.client;

import commonnetwork.api.Network;
import dev.duzo.players.Constants;
import dev.duzo.players.entities.FakePlayerEntity;
import dev.duzo.players.entities.possession.PossessionGuard;
import dev.duzo.players.entities.possession.PossessionManager;
import dev.duzo.players.entities.possession.PossessionView;
import dev.duzo.players.network.c2s.PossessActionC2S;
import dev.duzo.players.network.c2s.PossessInputC2S;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import javax.annotation.Nullable;

/**
 * The possessing player's side of a Possession Link. While the camera is a possessed fake the player is a spectator, so
 * vanilla would turn every click into spectator behaviour (a left click on an entity jumps the camera to it). This drains
 * those clicks at the start of the client tick, before vanilla reads them, and sends them to the server as input and action
 * packets instead. Look is client-side: the fake's view rotation is the local player's, through {@link PossessionView}.
 */
public final class PossessionClient {
	private static final double ENTITY_REACH = 3.0;
	private static final double BLOCK_REACH = 4.5;
	private static final int USE_REPEAT_TICKS = 4;

	private static int fakeId = -1;
	@Nullable
	private static BlockPos breaking;
	private static int useDelay;
	private static boolean attackWasDown;
	private static boolean useWasDown;
	private static boolean attackLatched;
	private static boolean useLatched;

	private PossessionClient() {}

	public static void init() {
		PossessionGuard.setClientCheck(player -> player == Minecraft.getInstance().player && possessed(Minecraft.getInstance()) != null);
		FakePlayerEntity.setViewHook(new PossessionView() {
			@Override
			public float viewYRot(FakePlayerEntity fake, float partialTick) {
				LocalPlayer player = Minecraft.getInstance().player;
				if (player == null || fake.getPossessor() != player.getId()) return Float.NaN;
				return Mth.rotLerp(partialTick, player.yRotO, player.getYRot());
			}

			@Override
			public float viewXRot(FakePlayerEntity fake, float partialTick) {
				LocalPlayer player = Minecraft.getInstance().player;
				if (player == null || fake.getPossessor() != player.getId()) return Float.NaN;
				return Mth.lerp(partialTick, player.xRotO, player.getXRot());
			}
		});
	}

	/** The fake this client is possessing, or null. */
	@Nullable
	public static FakePlayerEntity possessed(Minecraft client) {
		LocalPlayer player = client.player;
		if (player == null) return null;
		if (client.getCameraEntity() instanceof FakePlayerEntity fake && fake.getPossessor() == player.getId()) return fake;
		return null;
	}

	/** Start of the client tick: before vanilla reads a single key. */
	public static void startTick(Minecraft client) {
		FakePlayerEntity fake = possessed(client);
		if (fake == null) {
			if (fakeId != -1) end();
			return;
		}
		LocalPlayer player = client.player;
		if (fake.getId() != fakeId) begin(player, fake);

		Options options = client.options;
		boolean canAct = client.screen == null && client.getOverlay() == null && client.mouseHandler.isMouseGrabbed();

		// everything vanilla would do with these as a spectator is wrong for a possessor, so no click survives this tick
		int attackClicks = drain(options.keyAttack);
		int useClicks = drain(options.keyUse);
		drain(options.keyPickItem);
		drain(options.keyInventory);
		drain(options.keyDrop);
		drain(options.keySwapOffhand);
		drain(options.keySpectatorOutlines);
		options.keySpectatorOutlines.setDown(false);
		// 1.21.8 opens the spectator teleport menu straight from a middle click, not through a key to drain
		if (client.gui.getSpectatorGui().isMenuActive()) client.gui.getSpectatorGui().onSpectatorMenuClosed(null);
		int[] hotbar = new int[options.keyHotbarSlots.length];
		for (int i = 0; i < hotbar.length; i++) hotbar[i] = drain(options.keyHotbarSlots[i]);

		boolean toggleAttack = false; // 1.21.8 has no attack toggle option
		boolean toggleUse = false; // 1.21.8 has no use toggle option
		// a toggle key is latched here and the vanilla key released, so it cannot stay on for vanilla to act on
		if (toggleAttack) {
			if ((attackClicks & 1) == 1) attackLatched = !attackLatched;
			releaseToggle(options.keyAttack);
		} else {
			attackLatched = false;
		}
		if (toggleUse) {
			if ((useClicks & 1) == 1) useLatched = !useLatched;
			releaseToggle(options.keyUse);
		} else {
			useLatched = false;
		}
		boolean attackHeld = canAct && (toggleAttack ? attackLatched : options.keyAttack.isDown());
		boolean useHeld = canAct && (toggleUse ? useLatched : options.keyUse.isDown());

		// vanilla keeps attacking and using while a key is held, so the held state is hidden from it and given back at the end
		attackWasDown = !toggleAttack && options.keyAttack.isDown();
		useWasDown = !toggleUse && options.keyUse.isDown();
		options.keyAttack.setDown(false);
		options.keyUse.setDown(false);

		float yaw = player.getYRot();
		float pitch = player.getXRot();
		sendInput(options, fake, yaw, pitch, canAct);
		if (!canAct) {
			stopBreaking(fake);
			return;
		}

		Hit hit = pick(fake, yaw, pitch);
		for (int i = 1; i < hotbar.length; i++) {
			if (hotbar[i] > 0) sendAction(fake, PossessActionC2S.HOTBAR, 0, BlockPos.ZERO, 0, 0, 0, 0, i + 1);
		}

		if (attackClicks > 0 && hit.entity != null) {
			sendAction(fake, PossessActionC2S.ATTACK, hit.entity.getEntity().getId(), BlockPos.ZERO, 0, 0, 0, 0, 0);
		}
		// a tap shorter than a tick still starts a break, and the next tick's release stops it
		BlockPos want = (attackHeld || (!toggleAttack && attackClicks > 0)) && hit.entity == null && hit.block != null ? hit.block.getBlockPos() : null;
		if (want == null) {
			stopBreaking(fake);
		} else if (!want.equals(breaking)) {
			breaking = want;
			sendAction(fake, PossessActionC2S.BREAK_START, 0, want, 0, 0, 0, 0, 0);
		}

		if (useDelay > 0) useDelay--;
		if (((!toggleUse && useClicks > 0) || (useHeld && useDelay == 0)) && hit.entity == null && hit.block != null) {
			useDelay = USE_REPEAT_TICKS;
			BlockPos pos = hit.block.getBlockPos();
			Vec3 at = hit.block.getLocation();
			sendAction(fake, PossessActionC2S.USE, 0, pos, hit.block.getDirection().get3DDataValue(),
					(float) (at.x - pos.getX()), (float) (at.y - pos.getY()), (float) (at.z - pos.getZ()), 0);
		}
	}

	/** End of the client tick: gives back the held attack and use keys that were hidden from vanilla. */
	public static void endTick(Minecraft client) {
		// the outline key would draw every entity glowing for a spectator, even for one frame
		if (fakeId != -1) client.options.keySpectatorOutlines.setDown(false);
		if (!attackWasDown && !useWasDown) return;
		// a screen opened during the tick already released every key, and restoring one now would stick it
		if (client.screen == null) {
			if (attackWasDown) client.options.keyAttack.setDown(true);
			if (useWasDown) client.options.keyUse.setDown(true);
		}
		attackWasDown = false;
		useWasDown = false;
	}

	/** setDown(false) does nothing to a toggle key; pressing it again is what turns it off. */
	private static void releaseToggle(KeyMapping key) {
		if (key.isDown()) key.setDown(true);
	}

	private static int drain(KeyMapping key) {
		int n = 0;
		while (key.consumeClick()) n++;
		return n;
	}

	private static void begin(LocalPlayer player, FakePlayerEntity fake) {
		fakeId = fake.getId();
		breaking = null;
		useDelay = 0;
		attackLatched = false;
		useLatched = false;
		// the camera is now the fake, so the mouse has to start from where the fake is already looking
		player.setYRot(fake.getYRot());
		player.setXRot(fake.getXRot());
		player.yRotO = fake.getYRot();
		player.xRotO = fake.getXRot();
		Constants.traceEvent("possession client start fake={}", fake.getUUID());
	}

	private static void end() {
		Constants.traceEvent("possession client end fakeId={}", fakeId);
		fakeId = -1;
		breaking = null;
		useDelay = 0;
		attackLatched = false;
		useLatched = false;
	}

	private static void stopBreaking(FakePlayerEntity fake) {
		if (breaking == null) return;
		breaking = null;
		sendAction(fake, PossessActionC2S.BREAK_STOP, 0, BlockPos.ZERO, 0, 0, 0, 0, 0);
	}

	private static void sendInput(Options options, FakePlayerEntity fake, float yaw, float pitch, boolean canAct) {
		int flags = 0;
		if (canAct) {
			if (options.keyUp.isDown()) flags |= PossessionManager.FLAG_FORWARD;
			if (options.keyDown.isDown()) flags |= PossessionManager.FLAG_BACK;
			if (options.keyLeft.isDown()) flags |= PossessionManager.FLAG_LEFT;
			if (options.keyRight.isDown()) flags |= PossessionManager.FLAG_RIGHT;
			if (options.keyJump.isDown()) flags |= PossessionManager.FLAG_JUMP;
		}
		Network.getNetworkHandler().sendToServer(new PossessInputC2S(fake.getId(), yaw, pitch, flags));
	}

	private static void sendAction(FakePlayerEntity fake, byte action, int targetId, BlockPos pos, int face, float hx, float hy, float hz, int slot) {
		Constants.traceEvent("possession client action {} target={} pos={} face={} slot={}", action, targetId, pos.toShortString(), face, slot);
		Network.getNetworkHandler().sendToServer(new PossessActionC2S(fake.getId(), action, targetId, pos, (byte) face, hx, hy, hz, (byte) slot));
	}

	private record Hit(@Nullable BlockHitResult block, @Nullable EntityHitResult entity) {}

	/**
	 * What the crosshair is on, from the fake's eyes with the possessor's rotation and the same reach the server enforces.
	 * An entity wins only when it is nearer than the block behind it.
	 */
	private static Hit pick(FakePlayerEntity fake, float yaw, float pitch) {
		Vec3 eye = fake.getEyePosition();
		Vec3 look = fake.calculateViewVector(pitch, yaw);
		BlockHitResult block = fake.level().clip(new ClipContext(eye, eye.add(look.scale(BLOCK_REACH)),
				ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, fake));
		double blockDistSqr = block.getType() == HitResult.Type.BLOCK ? eye.distanceToSqr(block.getLocation()) : Double.MAX_VALUE;

		Vec3 entityEnd = eye.add(look.scale(ENTITY_REACH));
		AABB box = fake.getBoundingBox().expandTowards(look.scale(ENTITY_REACH)).inflate(1.0);
		EntityHitResult entity = ProjectileUtil.getEntityHitResult(fake, eye, entityEnd, box,
				e -> !e.isSpectator() && e.isPickable(), ENTITY_REACH * ENTITY_REACH);
		if (entity != null && eye.distanceToSqr(entity.getLocation()) >= blockDistSqr) entity = null;
		return new Hit(block.getType() == HitResult.Type.BLOCK ? block : null, entity);
	}
}
