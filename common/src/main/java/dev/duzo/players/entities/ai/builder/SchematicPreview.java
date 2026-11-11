package dev.duzo.players.entities.ai.builder;

import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A schematic flattened for the picker: global block state ids, and for each shown block its palette
 * slot and its index x + sizeX * (y + sizeY * z). Only blocks a Builder would place are included.
 */
public final class SchematicPreview {
	public static final int MAX_BLOCKS = 32768;
	/** Shared by the encoder's check and the decoder's clamp, so what is sent is exactly what is read. */
	public static final int MAX_PALETTE = 8192;
	public static final int MAX_SIDE = 4096;

	/** `total` counts every placeable block, so it can exceed the number of cells actually carried. */
	public record Data(int sizeX, int sizeY, int sizeZ, int[] palette, int[] cellState, int[] cellPos, int total) {
		public int cells() {
			return cellPos.length;
		}

		public boolean withinCaps() {
			return sizeX <= MAX_SIDE && sizeY <= MAX_SIDE && sizeZ <= MAX_SIDE
					&& palette.length <= MAX_PALETTE && cells() <= MAX_BLOCKS;
		}
	}

	private SchematicPreview() {}

	public static Data of(StructureTemplate template) {
		CompoundTag tag = template.save(new CompoundTag());
		List<BlockState> palette = BuildPlan.readPalette(tag);
		Vec3i size = template.getSize();
		ListTag blocks = tag.getList(StructureTemplate.BLOCKS_TAG, Tag.TAG_COMPOUND);

		Map<Integer, Integer> slots = new HashMap<>();
		int[] ids = new int[16];
		int idCount = 0;
		int[] cellState = new int[Math.min(blocks.size(), MAX_BLOCKS)];
		int[] cellPos = new int[cellState.length];
		int cells = 0;
		int total = 0;
		for (int i = 0; i < blocks.size(); i++) {
			CompoundTag b = blocks.getCompound(i);
			int index = b.contains(StructureTemplate.BLOCK_TAG_STATE, Tag.TAG_ANY_NUMERIC) ? b.getInt(StructureTemplate.BLOCK_TAG_STATE) : -1;
			if (index < 0 || index >= palette.size()) continue;
			BlockState state = palette.get(index);
			if (state.isAir() || state.is(Blocks.JIGSAW) || state.is(Blocks.STRUCTURE_BLOCK) || state.is(Blocks.STRUCTURE_VOID)) continue;
			ListTag p = b.getList(StructureTemplate.BLOCK_TAG_POS, Tag.TAG_INT);
			if (p.size() < 3) continue;
			int x = p.getInt(0);
			int y = p.getInt(1);
			int z = p.getInt(2);
			if (x < 0 || y < 0 || z < 0 || x >= size.getX() || y >= size.getY() || z >= size.getZ()) continue;
			total++;
			if (cells >= MAX_BLOCKS) continue;
			Integer slot = slots.get(index);
			if (slot == null) {
				if (idCount == ids.length) ids = Arrays.copyOf(ids, idCount * 2);
				ids[idCount] = Block.getId(state);
				slot = idCount++;
				slots.put(index, slot);
			}
			cellState[cells] = slot;
			cellPos[cells] = x + size.getX() * (y + size.getY() * z);
			cells++;
		}
		return new Data(size.getX(), size.getY(), size.getZ(), Arrays.copyOf(ids, idCount),
				Arrays.copyOf(cellState, cells), Arrays.copyOf(cellPos, cells), total);
	}
}
