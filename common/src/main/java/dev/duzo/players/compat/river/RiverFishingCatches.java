package dev.duzo.players.compat.river;

import com.riverfishing.component.ComponentSlot;
import com.riverfishing.component.RigType;
import com.riverfishing.config.RiverFishingConfig;
import com.riverfishing.engine.BarometricPressure;
import com.riverfishing.engine.BiteContext;
import com.riverfishing.engine.BiteEngine;
import com.riverfishing.engine.LureColor;
import com.riverfishing.fish.FishProfile;
import com.riverfishing.fish.FishProfileManager;
import com.riverfishing.fishing.FeedZoneData;
import com.riverfishing.fishing.FishingManager;
import com.riverfishing.fishing.FishingPressureData;
import com.riverfishing.fishing.PondData;
import com.riverfishing.fishing.StockedData;
import com.riverfishing.item.FishItem;
import com.riverfishing.item.HookItem;
import com.riverfishing.item.LineItem;
import com.riverfishing.item.ReelItem;
import com.riverfishing.item.RigItem;
import com.riverfishing.item.RodData;
import com.riverfishing.item.RodItem;
import com.riverfishing.item.WearData;
import com.riverfishing.registry.ModItems;
import com.riverfishing.rig.RigData;
import com.riverfishing.water.WaterBodyCache;
import dev.duzo.players.entities.FakePlayerEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;

import java.util.List;

/** Optional River Fishing calls. Only load this class after the mod-presence check. */
public final class RiverFishingCatches {

	private RiverFishingCatches() {}

	public enum Status { READY, NO_BITE, UNSUPPORTED, INVALID }

	public static boolean supports(ItemStack stack) {
		if (!(stack.getItem() instanceof RodItem rod)) return false;
		return switch (rod.rodType()) {
			case STICK, BAMBOO, POLE, ULTRALIGHT, SPINNING, SEA_SPIN,
					FEEDER, BOTTOM, CARP, SURF -> true;
			default -> false;
		};
	}

	public static Attempt prepare(ServerLevel level, FakePlayerEntity fake, ItemStack rod, BlockPos hookPos) {
		if (!supports(rod)) return new Attempt(Status.UNSUPPORTED, "I can't use this fishing method yet.");
		if (!validTackle(rod)) return new Attempt(Status.INVALID, "This rod needs compatible tackle and bait.");
		if (!level.getFluidState(hookPos).is(FluidTags.WATER))
			return new Attempt(Status.INVALID, "I need water to fish here.");
		if (finiteStock(level, hookPos))
			return new Attempt(Status.UNSUPPORTED, "I can't fish stocked water or claimed ponds yet.");

		BiteContext context = context(level, fake, rod, hookPos);
		FishingPressureData.get(level).addCast(new ChunkPos(hookPos).toLong(), level.getGameTime());
		BiteEngine.Outcome outcome = BiteEngine.evaluate(FishProfileManager.get().all(), context, level.random);
		if (!outcome.willBite()) return new Attempt(Status.NO_BITE, "Nothing is biting on this tackle here.");
		ResourceLocation species = outcome.pickSpecies(level.random);
		FishProfile profile = FishProfileManager.get().byId(species);
		if (profile == null) return new Attempt(Status.NO_BITE, "Nothing is biting on this tackle here.");
		return new Attempt(rod, hookPos, species, outcome.ticksToBite, level.getGameTime());
	}

	private static boolean validTackle(ItemStack rod) {
		if (!supports(rod) || !RodData.isAssembled(rod)) return false;
		var type = ((RodItem) rod.getItem()).rodType();
		ItemStack line = RodData.get(rod, ComponentSlot.LINE);
		ItemStack rig = RodData.get(rod, ComponentSlot.RIG);
		if (!(line.getItem() instanceof LineItem) || !(rig.getItem() instanceof RigItem)) return false;
		if (type.takesReel()) {
			ItemStack reel = RodData.get(rod, ComponentSlot.REEL);
			if (!(reel.getItem() instanceof ReelItem r) || !type.acceptsReel(r)
					|| !r.acceptsLine((LineItem) line.getItem())) return false;
		}
		RigType rigType = ((RigItem) rig.getItem()).rigType();
		if (rigType == RigType.WINTER || rigType == RigType.FLY) return false;
		if (type.nativeRig() != null && type.nativeRig() != rigType) return false;
		return (type.activeRetrieve() || !RigData.hookSizes(rig).isEmpty()) && !RigData.baitIds(rig).isEmpty();
	}

	/** Finite pond/transplant ledgers need a dedicated conservation path before NPCs can use them. */
	private static boolean finiteStock(ServerLevel level, BlockPos pos) {
		if (PondData.claim(level, pos) != null) return true;
		StockedData stocked = StockedData.get(level);
		long region = StockedData.regionAt(level, pos);
		if (stocked.isPond(region)) return true;
		FishingPressureData pressure = FishingPressureData.get(level);
		for (FishProfile profile : FishProfileManager.get().all()) {
			String species = profile.id.getPath();
			if (stocked.isStocked(region, species) || stocked.hasBrood(region, species)
					|| stocked.rememberedFish(region, species) > 0 || stocked.adults(region, species) > 0
					|| pressure.surplusAround(pos.getX() >> 4, pos.getZ() >> 4, species, level.getGameTime()) > 0)
				return true;
		}
		return false;
	}

	private static BiteContext context(ServerLevel level, FakePlayerEntity fake, ItemStack rod, BlockPos pos) {
		var body = WaterBodyCache.forLevel(level).get(level, pos);
		BiteContext ctx = FishingManager.environmentAt(level, pos, body);
		// environmentAt is also used by admin probes and deliberately disables progression gates.
		ctx.anglerLevel = 0;
		ctx.skillBiteBonus = 0;
		ctx.rod = ((RodItem) rod.getItem()).rodType();
		ctx.biomeRiver = body.river();
		ctx.biomeSwamp = body.swamp();
		ctx.biomeOcean = body.ocean();
		ctx.castDistance = Math.sqrt(fake.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5));
		ctx.pressureFactor = BarometricPressure.biteFactor(level);
		long now = level.getGameTime();
		long chunk = new ChunkPos(pos).toLong();
		ctx.speciesFactor = id -> FishingPressureData.get(level).speciesAttractiveness(chunk, id.getPath(), now, 1.0);
		ItemStack reel = RodData.get(rod, ComponentSlot.REEL);
		if (reel.getItem() instanceof ReelItem r) ctx.reelSize = r.size();
		LineItem line = (LineItem) RodData.get(rod, ComponentSlot.LINE).getItem();
		ctx.lineType = line.lineType();
		ctx.lineDiameterMm = line.diameterMm();
		ItemStack rig = RodData.get(rod, ComponentSlot.RIG);
		ctx.rig = ((RigItem) rig.getItem()).rigType();
		ctx.hookSizes = RigData.hookSizes(rig);
		ctx.baits = RigData.baitIds(rig);
		ctx.castWeightG = RigData.effectiveWeightG(rig);
		ctx.lureWeightG = RigData.lureTackleWeightG(rig);
		ctx.livebaitG = RigData.livebaitWeightG(rig);
		ctx.tied = RigData.tiedLure(rig);
		int rgb = RigData.lureColorRgb(rig);
		ctx.lureColor = rgb < 0 ? null : LureColor.fromRgb(rgb);
		ctx.hasLeader = RigData.hasLeader(rig);
		ctx.leaderProtection = RigData.leaderProtection(rig);
		ctx.leaderStealth = RigData.leaderStealth(rig);
		if (RigData.hasFloat(rig)) ctx.floatDepth = RodData.getDepth(rod);
		var feed = FeedZoneData.get(level).query(pos, now);
		ctx.inFeedZone = feed.inZone();
		ctx.feedFreshness = feed.freshness();
		ctx.feedMix = feed.mix();
		return ctx;
	}

	private static int wearPoints(double rate, RandomSource random) {
		if (!Double.isFinite(rate) || rate <= 0) return 0;
		double bounded = Math.min(100, rate);
		int whole = (int) bounded;
		return whole + (random.nextDouble() < bounded - whole ? 1 : 0);
	}

	public static final class Attempt {
		private final Status status;
		private final String message;
		private final ItemStack identity;
		private ItemStack expected;
		private final BlockPos pos;
		private final ResourceLocation species;
		private final long delay;
		private final long preparedAt;
		private boolean struck;
		private boolean finished;
		private boolean landed;
		private int weight;
		private int length;
		private long finishAt;
		private ItemStack baitBeforeStrike;

		private Attempt(Status status, String message) {
			this.status = status;
			this.message = message;
			identity = ItemStack.EMPTY;
			expected = ItemStack.EMPTY;
			pos = BlockPos.ZERO;
			species = null;
			delay = 400;
			preparedAt = 0;
		}

		private Attempt(ItemStack rod, BlockPos pos, ResourceLocation species, long delay, long preparedAt) {
			status = Status.READY;
			message = "";
			identity = rod;
			expected = rod.copy();
			this.pos = pos.immutable();
			this.species = species;
			this.delay = delay;
			this.preparedAt = preparedAt;
		}

		public Status status() { return status; }
		public String message() { return message; }
		public long waitTicks() { return delay; }

		public boolean matches(ItemStack rod) {
			return status == Status.READY && !finished && rod == identity && !rod.isEmpty()
					&& rod.getCount() == expected.getCount() && ItemStack.isSameItemSameComponents(rod, expected);
		}

		private FishProfile eligible(ServerLevel level, FakePlayerEntity fake, ItemStack rod) {
			if (!matches(rod) || !level.getFluidState(pos).is(FluidTags.WATER) || finiteStock(level, pos)) return null;
			FishProfile profile = FishProfileManager.get().byId(species);
			// Revalidate the bait that was actually taken, even after the strike consumed its last item.
			ItemStack query = struck ? baitBeforeStrike : rod;
			if (profile == null || query == null || !validTackle(query)) return null;
			return BiteEngine.speciesWeight(profile, context(level, fake, query, pos)) > 1.0e-6 ? profile : null;
		}

		public int strike(ServerLevel level, FakePlayerEntity fake, ItemStack rod) {
			if (struck || finished || level.getGameTime() - preparedAt < delay) return -1;
			FishProfile profile = eligible(level, fake, rod);
			if (profile == null) { finished = true; return -1; }
			baitBeforeStrike = rod.copy();
			struck = true;
			RandomSource random = level.random;
			double fraction = random.nextDouble() * 0.25;
			weight = Math.max(1, (int) Math.round(profile.weightMin + (profile.weightMax - profile.weightMin) * fraction));
			length = Math.max(1, (int) Math.round(profile.lengthMin + (profile.lengthMax - profile.lengthMin) * fraction));
			ItemStack line = RodData.get(rod, ComponentSlot.LINE);
			ItemStack rig = RodData.get(rod, ComponentSlot.RIG);
			double capacity = Math.min(((RodItem) rod.getItem()).rodType().fightPowerKg(),
					((LineItem) line.getItem()).breakingStrainKg() * WearData.lineStrainMultiplier(WearData.get(line)));
			double kg = weight / 1000.0;
			double demand = Math.max(kg, FishingManager.sizeStrength(profile, kg) * kg);
			landed = demand <= capacity && weight >= RigData.livebaitWeightG(rig) * BiteEngine.PREY_RATIO;
			var contents = RigData.load(rig);
			int hook = -1;
			for (int i = 0; i < contents.size(); i++) {
				if (contents.get(i).getItem() instanceof HookItem
						&& (hook < 0 || WearData.get(contents.get(i)) < WearData.get(contents.get(hook)))) hook = i;
			}
			if (hook >= 0) {
				ItemStack sharpest = contents.get(hook);
				if (random.nextDouble() < WearData.hookEmptySetChance(WearData.get(sharpest))) landed = false;
				WearData.add(sharpest, wearPoints(RiverFishingConfig.hookWearRate(), random));
				RigData.save(rig, contents);
			}
			if (RiverFishingConfig.consumeBait()) RigData.consumeBait(rig, profile::baitScore);
			WearData.add(line, wearPoints(RiverFishingConfig.lineWearRate(), random));
			RodData.set(rod, ComponentSlot.LINE, line);
			RodData.set(rod, ComponentSlot.RIG, rig);
			if (rod.isDamageableItem()) rod.hurtAndBreak(1, fake,
					fake.getMainHandItem() == rod ? EquipmentSlot.MAINHAND : EquipmentSlot.OFFHAND);
			expected = rod.copy();
			finishAt = level.getGameTime() + 120;
			return 120;
		}

		public List<ItemStack> finish(ServerLevel level, FakePlayerEntity fake, ItemStack rod) {
			if (!struck || finished || level.getGameTime() < finishAt) return List.of();
			FishProfile profile = eligible(level, fake, rod);
			finished = true;
			if (!landed || profile == null) return List.of();
			ItemStack fish = FishItem.create(ModItems.fishItem(species), species, weight, length, true, false);
			if (fish.isEmpty()) return List.of();
			FishingPressureData.get(level).addCatch(new ChunkPos(pos).toLong(), species.getPath(), level.getGameTime());
			return List.of(fish);
		}
	}
}
