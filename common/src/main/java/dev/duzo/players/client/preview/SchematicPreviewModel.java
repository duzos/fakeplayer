package dev.duzo.players.client.preview;

import dev.duzo.players.entities.ai.builder.SchematicPreview;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashMap;
import java.util.Map;

/**
 * A received preview reduced to the blocks that can be seen. Built once on arrival, so the per-frame
 * render never has to look at a block that has all six faces buried.
 */
public final class SchematicPreviewModel {
	/** Above this the occlusion grid is skipped and every block is drawn, rather than allocating it. */
	private static final long MAX_GRID = 1024L * 1024;

	private final int sizeX;
	private final int sizeY;
	private final int sizeZ;
	private final int total;
	private final int carried;
	private final BlockState[] states;
	private final int[] xs;
	private final int[] ys;
	private final int[] zs;

	private SchematicPreviewModel(SchematicPreview.Data data, BlockState[] states, int[] xs, int[] ys, int[] zs) {
		this.sizeX = data.sizeX();
		this.sizeY = data.sizeY();
		this.sizeZ = data.sizeZ();
		this.total = data.total();
		this.carried = data.cells();
		this.states = states;
		this.xs = xs;
		this.ys = ys;
		this.zs = zs;
	}

	public static SchematicPreviewModel of(SchematicPreview.Data data) {
		BlockState[] palette = new BlockState[data.palette().length];
		for (int i = 0; i < palette.length; i++) palette[i] = Block.stateById(data.palette()[i]);

		Map<BlockState, Boolean> fullShapes = new HashMap<>();
		int sx = data.sizeX();
		int sy = data.sizeY();
		int sz = data.sizeZ();
		long volume = (long) sx * sy * sz;
		int cells = data.cells();
		BlockState[] grid = volume <= MAX_GRID ? new BlockState[(int) volume] : null;
		if (grid != null) {
			for (int i = 0; i < cells; i++) {
				int slot = data.cellState()[i];
				int pos = data.cellPos()[i];
				if (slot >= 0 && slot < palette.length && pos >= 0 && pos < volume) grid[pos] = palette[slot];
			}
		}

		BlockState[] outStates = new BlockState[cells];
		int[] ox = new int[cells];
		int[] oy = new int[cells];
		int[] oz = new int[cells];
		int n = 0;
		for (int i = 0; i < cells; i++) {
			int slot = data.cellState()[i];
			int pos = data.cellPos()[i];
			if (slot < 0 || slot >= palette.length || pos < 0 || pos >= volume) continue;
			int x = pos % sx;
			int y = (pos / sx) % sy;
			int z = pos / (sx * sy);
			if (grid != null && buried(grid, fullShapes, sx, sy, sz, x, y, z)) continue;
			outStates[n] = palette[slot];
			ox[n] = x;
			oy[n] = y;
			oz[n] = z;
			n++;
		}
		return new SchematicPreviewModel(data, java.util.Arrays.copyOf(outStates, n), java.util.Arrays.copyOf(ox, n),
				java.util.Arrays.copyOf(oy, n), java.util.Arrays.copyOf(oz, n));
	}

	/** Every face is against a block that hides it: a full opaque cube, or the same state (glass, leaves, slabs). */
	private static boolean buried(BlockState[] grid, Map<BlockState, Boolean> fullShapes, int sx, int sy, int sz, int x, int y, int z) {
		if (x <= 0 || x >= sx - 1 || y <= 0 || y >= sy - 1 || z <= 0 || z >= sz - 1) return false;
		BlockState self = grid[index(sx, sy, x, y, z)];
		return hides(fullShapes, self, grid[index(sx, sy, x - 1, y, z)]) && hides(fullShapes, self, grid[index(sx, sy, x + 1, y, z)])
				&& hides(fullShapes, self, grid[index(sx, sy, x, y - 1, z)]) && hides(fullShapes, self, grid[index(sx, sy, x, y + 1, z)])
				&& hides(fullShapes, self, grid[index(sx, sy, x, y, z - 1)]) && hides(fullShapes, self, grid[index(sx, sy, x, y, z + 1)]);
	}

	private static boolean hides(Map<BlockState, Boolean> fullShapes, BlockState self, BlockState neighbour) {
		if (neighbour == null) return false;
		return neighbour.isSolidRender() || (neighbour == self && fullShapes.computeIfAbsent(self, SchematicPreviewModel::fullCube));
	}

	/** Slabs and stairs have the same state next to them and still show faces, so only a whole cube counts. */
	private static boolean fullCube(BlockState state) {
		try {
			return Block.isShapeFullBlock(state.getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO));
		} catch (RuntimeException e) {
			return false;
		}
	}

	private static int index(int sx, int sy, int x, int y, int z) {
		return x + sx * (y + sy * z);
	}

	public int sizeX() {
		return sizeX;
	}

	public int sizeY() {
		return sizeY;
	}

	public int sizeZ() {
		return sizeZ;
	}

	public int total() {
		return total;
	}

	public boolean truncated() {
		return total > carried;
	}

	public int carried() {
		return carried;
	}

	public int visible() {
		return states.length;
	}

	public BlockState state(int i) {
		return states[i];
	}

	public int x(int i) {
		return xs[i];
	}

	public int y(int i) {
		return ys[i];
	}

	public int z(int i) {
		return zs[i];
	}
}
