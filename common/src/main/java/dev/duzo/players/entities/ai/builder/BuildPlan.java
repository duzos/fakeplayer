package dev.duzo.players.entities.ai.builder;

import dev.duzo.players.config.PlayersConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * An immutable placement of one schematic: world-space cells grouped into stripes, plus the bill of materials.
 * `anchor` is the minimum corner of the rotated and mirrored bounding box. Air entries are kept as CLEAR cells
 * so the site is cleared; entities, jigsaw, structure blocks and block entity data are dropped.
 */
public final class BuildPlan {
	/** `uploader` is whose upload folder the schematic may come from; empty for none. */
	public record BuildKey(ResourceKey<Level> dim, String schematic, BlockPos anchor, Rotation rotation, Mirror mirror, String uploader) {}

	/** `partner` is the other half placed in the same action, always after this cell: upper door half, bed head. */
	public record Cell(BlockPos pos, BlockState state, PlacePass pass, @Nullable BlockPos partnerPos, @Nullable BlockState partnerState) {}

	/** `cells` is in work order: CLEAR top-down, then SOLID bottom-up, then ATTACH bottom-up. */
	public record Stripe(int index, BoundingBox box, List<Cell> cells) {}

	private static final int CACHE_MAX = 8;
	private static final Map<BuildKey, BuildPlan> CACHE = new LinkedHashMap<>(16, 0.75F, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<BuildKey, BuildPlan> eldest) {
			return size() > CACHE_MAX;
		}
	};

	private static final long MISS_MILLIS = 5000L;
	private static final Map<BuildKey, Long> MISSES = new HashMap<>();

	public final BuildKey key;
	public final BoundingBox bounds;
	public final int stripeWidth;
	public final List<Stripe> stripes;
	public final Map<Item, Integer> bom;
	/** Blocks the Builder cannot place, by block, with how many the schematic holds. Their cells are not in any stripe. */
	public final Map<Block, Integer> unsupported;

	private BuildPlan(BuildKey key, BoundingBox bounds, int stripeWidth, List<Stripe> stripes,
					  Map<Item, Integer> bom, Map<Block, Integer> unsupported) {
		this.key = key;
		this.bounds = bounds;
		this.stripeWidth = stripeWidth;
		this.stripes = stripes;
		this.bom = bom;
		this.unsupported = unsupported;
	}

	public int cellCount() {
		int n = 0;
		for (Stripe s : stripes) n += s.cells().size();
		return n;
	}

	/** Cached by key. A failed load is remembered for a few seconds so a blocked Builder does not hit the disk every recheck. */
	public static synchronized SchematicStore.Result<BuildPlan> get(ServerLevel level, BuildKey key) {
		int width = PlayersConfig.get().builderStripeWidth;
		BuildPlan cached = CACHE.get(key);
		if (cached != null && cached.stripeWidth == width) return SchematicStore.Result.ok(cached);
		long now = System.currentTimeMillis();
		MISSES.values().removeIf(until -> until <= now);
		if (MISSES.containsKey(key)) return SchematicStore.Result.fail("schematic not found: " + key.schematic());
		SchematicStore.Result<StructureTemplate> t = SchematicStore.load(level, key.schematic(), key.uploader());
		if (!t.ok()) {
			MISSES.put(key, now + MISS_MILLIS);
			return SchematicStore.Result.fail(t.error());
		}
		SchematicStore.Result<BuildPlan> built = build(t.value(), key, width);
		if (built.ok()) CACHE.put(key, built.value());
		return built;
	}

	/** For a schematic edited on disk, whose cached plans are now stale. */
	static synchronized void clearCache() {
		CACHE.clear();
		MISSES.clear();
	}

	static SchematicStore.Result<BuildPlan> build(StructureTemplate template, BuildKey key, int stripeWidth) {
		CompoundTag tag = template.save(new CompoundTag());
		List<BlockState> palette = readPalette(tag);
		BoundingBox raw = template.getBoundingBox(BlockPos.ZERO, key.rotation(), BlockPos.ZERO, key.mirror());
		BlockPos shift = key.anchor().subtract(new BlockPos(raw.minX(), raw.minY(), raw.minZ()));
		BoundingBox bounds = raw.moved(shift.getX(), shift.getY(), shift.getZ());

		Vec3i size = template.getSize();
		int max = PlayersConfig.get().builderMaxVolume;
		Map<BlockPos, BlockState> world = new HashMap<>();
		ListTag blocks = tag.getList(StructureTemplate.BLOCKS_TAG, Tag.TAG_COMPOUND);
		for (int i = 0; i < blocks.size(); i++) {
			CompoundTag b = blocks.getCompound(i);
			int id = b.contains(StructureTemplate.BLOCK_TAG_STATE) ? b.getInt(StructureTemplate.BLOCK_TAG_STATE) : -1;
			if (id < 0 || id >= palette.size()) continue;
			BlockState state = palette.get(id);
			if (state.is(Blocks.JIGSAW) || state.is(Blocks.STRUCTURE_BLOCK) || state.is(Blocks.STRUCTURE_VOID)) continue;
			ListTag p = b.getList(StructureTemplate.BLOCK_TAG_POS, Tag.TAG_INT);
			if (p == null || p.size() < 3) continue;
			BlockPos local = new BlockPos(p.getInt(0), p.getInt(1), p.getInt(2));
			// a hand-edited file can name positions outside its own size, which would escape the claimed bounds
			if (local.getX() < 0 || local.getY() < 0 || local.getZ() < 0
					|| local.getX() >= size.getX() || local.getY() >= size.getY() || local.getZ() >= size.getZ()) continue;
			BlockPos at = StructureTemplate.transform(local, key.mirror(), key.rotation(), BlockPos.ZERO).offset(shift);
			world.put(at, state.mirror(key.mirror()).rotate(key.rotation()));
			if (world.size() > max) {
				return SchematicStore.Result.fail("schematic has too many blocks, limit " + max);
			}
		}

		Map<Item, Integer> bom = new LinkedHashMap<>();
		Map<Block, Integer> unsupported = new LinkedHashMap<>();
		boolean alongX = bounds.getXSpan() >= bounds.getZSpan();
		int min = alongX ? bounds.minX() : bounds.minZ();
		Map<Integer, List<Cell>> byStripe = new TreeMap<>();
		Map<Integer, BoundingBox> stripeBoxes = new HashMap<>();

		for (Map.Entry<BlockPos, BlockState> e : world.entrySet()) {
			BlockPos pos = e.getKey();
			BlockState state = e.getValue();
			PlacePass pass = PlacePass.of(state);
			MaterialRules.Cost cost = MaterialRules.cost(state);
			if (!cost.supported()) {
				unsupported.merge(state.getBlock(), 1, Integer::sum);
				continue;
			}
			BlockPos partnerPos = partnerOf(pos, state);
			if (isCompanion(state)) {
				// a second half is placed with its first, and cannot be placed on its own
				BlockPos first = firstHalfOf(pos, state);
				BlockState firstState = world.get(first);
				if (firstState == null || !pairs(firstState, state)) unsupported.merge(state.getBlock(), 1, Integer::sum);
				continue;
			}
			BlockState partnerState = partnerPos == null ? null : world.get(partnerPos);
			if (partnerPos != null && (partnerState == null || !pairs(state, partnerState))) {
				// a first half without its matching other half is not a door or bed, so it is left out
				unsupported.merge(state.getBlock(), 1, Integer::sum);
				continue;
			}
			if (cost.count() > 0) bom.merge(cost.item(), cost.count(), Integer::sum);

			int idx = ((alongX ? pos.getX() : pos.getZ()) - min) / stripeWidth;
			byStripe.computeIfAbsent(idx, k -> new ArrayList<>()).add(new Cell(pos, state, pass, partnerPos, partnerState));
			BoundingBox slab = stripeBox(bounds, alongX, min, idx, stripeWidth);
			if (partnerPos != null) slab = slab.encapsulate(partnerPos);
			stripeBoxes.merge(idx, slab, BoundingBox::encapsulate);
		}

		List<Stripe> stripes = new ArrayList<>();
		for (Map.Entry<Integer, List<Cell>> e : byStripe.entrySet()) {
			stripes.add(new Stripe(e.getKey(), stripeBoxes.get(e.getKey()), order(e.getValue())));
		}
		return SchematicStore.Result.ok(new BuildPlan(key, bounds, stripeWidth, List.copyOf(stripes), Map.copyOf(bom), Map.copyOf(unsupported)));
	}

	/** Whether `second` is the other half of `first`: the same block, the opposite half, facing the same way. */
	private static boolean pairs(BlockState first, BlockState second) {
		if (first.getBlock() != second.getBlock()) return false;
		if (first.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)) {
			if (first.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) != DoubleBlockHalf.LOWER
					|| second.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) != DoubleBlockHalf.UPPER) return false;
		}
		if (first.hasProperty(BlockStateProperties.BED_PART)) {
			if (first.getValue(BlockStateProperties.BED_PART) != BedPart.FOOT
					|| second.getValue(BlockStateProperties.BED_PART) != BedPart.HEAD) return false;
		}
		if (first.hasProperty(BlockStateProperties.HORIZONTAL_FACING)
				&& first.getValue(BlockStateProperties.HORIZONTAL_FACING) != second.getValue(BlockStateProperties.HORIZONTAL_FACING)) {
			return false;
		}
		return true;
	}

	private static List<BlockState> readPalette(CompoundTag tag) {
		ListTag list;
		if (tag.contains(StructureTemplate.PALETTE_TAG)) {
			list = tag.getList(StructureTemplate.PALETTE_TAG, Tag.TAG_COMPOUND);
		} else {
			ListTag palettes = tag.getList(StructureTemplate.PALETTE_LIST_TAG, Tag.TAG_LIST);
			list = palettes.isEmpty() ? new ListTag() : palettes.getList(0);
		}
		List<BlockState> out = new ArrayList<>(list.size());
		for (int i = 0; i < list.size(); i++) {
			out.add(NbtUtils.readBlockState(BuiltInRegistries.BLOCK.asLookup(), list.getCompound(i)));
		}
		return out;
	}

	/** The slab's full extent, clamped to the build, so an empty row of it still counts as claimed ground. */
	private static BoundingBox stripeBox(BoundingBox b, boolean alongX, int min, int idx, int width) {
		int lo = min + idx * width;
		int hi = lo + width - 1;
		return alongX
				? new BoundingBox(lo, b.minY(), b.minZ(), Math.min(hi, b.maxX()), b.maxY(), b.maxZ())
				: new BoundingBox(b.minX(), b.minY(), lo, b.maxX(), b.maxY(), Math.min(hi, b.maxZ()));
	}

	private static boolean isCompanion(BlockState s) {
		if (s.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
				&& s.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER) return true;
		return s.hasProperty(BlockStateProperties.BED_PART) && s.getValue(BlockStateProperties.BED_PART) == BedPart.HEAD;
	}

	private static @Nullable BlockPos partnerOf(BlockPos pos, BlockState s) {
		if (s.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
				&& s.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.LOWER) return pos.above();
		if (s.hasProperty(BlockStateProperties.BED_PART) && s.getValue(BlockStateProperties.BED_PART) == BedPart.FOOT
				&& s.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
			return pos.relative(s.getValue(BlockStateProperties.HORIZONTAL_FACING));
		}
		return null;
	}

	private static BlockPos firstHalfOf(BlockPos pos, BlockState s) {
		if (s.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)) return pos.below();
		return pos.relative(s.getValue(BlockStateProperties.HORIZONTAL_FACING).getOpposite());
	}

	private static List<Cell> order(List<Cell> cells) {
		Comparator<Cell> flat = Comparator.<Cell>comparingInt(c -> c.pos().getX()).thenComparingInt(c -> c.pos().getZ());
		List<Cell> clear = new ArrayList<>();
		List<Cell> solid = new ArrayList<>();
		List<Cell> attach = new ArrayList<>();
		for (Cell c : cells) {
			switch (c.pass()) {
				case CLEAR -> clear.add(c);
				case SOLID -> solid.add(c);
				case ATTACH -> attach.add(c);
				default -> {}
			}
		}
		Comparator<Cell> up = Comparator.<Cell>comparingInt(c -> c.pos().getY()).thenComparing(flat);
		clear.sort(Comparator.<Cell>comparingInt(c -> -c.pos().getY()).thenComparing(flat));
		solid.sort(up);
		attach.sort(up);
		List<Cell> out = new ArrayList<>(cells.size());
		out.addAll(clear);
		out.addAll(solid);
		out.addAll(attach);
		return List.copyOf(out);
	}
}
