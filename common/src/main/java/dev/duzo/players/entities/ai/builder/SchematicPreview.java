package dev.duzo.players.entities.ai.builder;

import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
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

	/** `total` counts every placeable block, so it can exceed the number of cells actually carried. */
	public record Data(int sizeX, int sizeY, int sizeZ, int[] palette, int[] cellState, int[] cellPos, int total) {
		public int cells() {
			return cellPos.length;
		}
	}

	private SchematicPreview() {}

	public static Data of(StructureTemplate template) {
		CompoundTag tag = template.save(new CompoundTag());
		List<BlockState> palette = BuildPlan.readPalette(tag);
		Vec3i size = template.getSize();
		ListTag blocks = tag.getList(StructureTemplate.BLOCKS_TAG).orElseGet(ListTag::new);

		Map<Integer, Integer> slots = new HashMap<>();
		int[] ids = new int[16];
		int idCount = 0;
		int[] cellState = new int[Math.min(blocks.size(), MAX_BLOCKS)];
		int[] cellPos = new int[cellState.length];
		int cells = 0;
		int total = 0;
		for (int i = 0; i < blocks.size(); i++) {
			CompoundTag b = blocks.getCompoundOrEmpty(i);
			int index = b.getIntOr(StructureTemplate.BLOCK_TAG_STATE, -1);
			if (index < 0 || index >= palette.size()) continue;
			BlockState state = palette.get(index);
			if (state.isAir() || state.is(Blocks.JIGSAW) || state.is(Blocks.STRUCTURE_BLOCK) || state.is(Blocks.STRUCTURE_VOID)) continue;
			ListTag p = b.getList(StructureTemplate.BLOCK_TAG_POS).orElse(null);
			if (p == null || p.size() < 3) continue;
			int x = p.getIntOr(0, 0);
			int y = p.getIntOr(1, 0);
			int z = p.getIntOr(2, 0);
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
