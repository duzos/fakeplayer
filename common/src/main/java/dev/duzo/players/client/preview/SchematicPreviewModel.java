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
	/** Drawn every frame, so the list handed to the renderer stops here. */
	public static final int MAX_DRAWN = 8192;
	/** Above this many cells the occlusion test is skipped and every block is a candidate. */
	private static final int MAX_OCCLUSION_CELLS = 32768;

	private final int sizeX;
	private final int sizeY;
	private final int sizeZ;
	private final int total;
	private final int carried;
	private final boolean simplified;
	private final BlockState[] states;
	private final int[] xs;
	private final int[] ys;
	private final int[] zs;

	private SchematicPreviewModel(SchematicPreview.Data data, boolean simplified, BlockState[] states, int[] xs, int[] ys, int[] zs) {
		this.sizeX = data.sizeX();
		this.sizeY = data.sizeY();
		this.sizeZ = data.sizeZ();
		this.total = data.total();
		this.carried = data.cells();
		this.simplified = simplified;
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
		// keyed by packed position, so the test costs by the blocks present and not by the volume they sit in
		Map<Long, BlockState> grid = null;
		if (cells <= MAX_OCCLUSION_CELLS) {
			grid = new HashMap<>(cells * 2);
			for (int i = 0; i < cells; i++) {
				int slot = data.cellState()[i];
				int pos = data.cellPos()[i];
				if (slot >= 0 && slot < palette.length && pos >= 0 && pos < volume) grid.put((long) pos, palette[slot]);
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

		if (n > MAX_DRAWN) {
			int[] keep = choose(ox, oy, oz, n, sx);
			BlockState[] ks = new BlockState[keep.length];
			int[] kx = new int[keep.length];
			int[] ky = new int[keep.length];
			int[] kz = new int[keep.length];
			for (int k = 0; k < keep.length; k++) {
				ks[k] = outStates[keep[k]];
				kx[k] = ox[keep[k]];
				ky[k] = oy[keep[k]];
				kz[k] = oz[keep[k]];
			}
			return new SchematicPreviewModel(data, true, ks, kx, ky, kz);
		}
		return new SchematicPreviewModel(data, false, java.util.Arrays.copyOf(outStates, n), java.util.Arrays.copyOf(ox, n),
				java.util.Arrays.copyOf(oy, n), java.util.Arrays.copyOf(oz, n));
	}

	/** The top visible block of every x,z column first, so the outline survives, then an even stride through the rest. */
	private static int[] choose(int[] ox, int[] oy, int[] oz, int n, int sx) {
		Map<Long, Integer> top = new HashMap<>();
		for (int i = 0; i < n; i++) {
			long column = ox[i] + (long) sx * oz[i];
			Integer held = top.get(column);
			if (held == null || oy[i] > oy[held]) top.put(column, i);
		}
		boolean[] isTop = new boolean[n];
		int[] tops = new int[top.size()];
		int t = 0;
		for (int index : top.values()) {
			isTop[index] = true;
			tops[t++] = index;
		}
		java.util.Arrays.sort(tops);
		if (tops.length >= MAX_DRAWN) return stride(tops, MAX_DRAWN);
		int[] others = new int[n - tops.length];
		int o = 0;
		for (int i = 0; i < n; i++) if (!isTop[i]) others[o++] = i;
		int[] extra = stride(others, MAX_DRAWN - tops.length);
		int[] out = new int[tops.length + extra.length];
		System.arraycopy(tops, 0, out, 0, tops.length);
		System.arraycopy(extra, 0, out, tops.length, extra.length);
		return out;
	}

	private static int[] stride(int[] from, int count) {
		int[] out = new int[Math.min(count, from.length)];
		for (int k = 0; k < out.length; k++) out[k] = from[(int) ((long) k * from.length / out.length)];
		return out;
	}

	/** Every face is against a block that hides it: a full opaque cube, or the same state (glass, leaves, slabs). */
	private static boolean buried(Map<Long, BlockState> grid, Map<BlockState, Boolean> fullShapes, int sx, int sy, int sz, int x, int y, int z) {
		if (x <= 0 || x >= sx - 1 || y <= 0 || y >= sy - 1 || z <= 0 || z >= sz - 1) return false;
		BlockState self = grid.get(index(sx, sy, x, y, z));
		return hides(fullShapes, self, grid.get(index(sx, sy, x - 1, y, z))) && hides(fullShapes, self, grid.get(index(sx, sy, x + 1, y, z)))
				&& hides(fullShapes, self, grid.get(index(sx, sy, x, y - 1, z))) && hides(fullShapes, self, grid.get(index(sx, sy, x, y + 1, z)))
				&& hides(fullShapes, self, grid.get(index(sx, sy, x, y, z - 1))) && hides(fullShapes, self, grid.get(index(sx, sy, x, y, z + 1)));
	}

	private static boolean hides(Map<BlockState, Boolean> fullShapes, BlockState self, BlockState neighbour) {
		if (neighbour == null) return false;
		return neighbour.isSolidRender(EmptyBlockGetter.INSTANCE, BlockPos.ZERO) || (neighbour == self && fullShapes.computeIfAbsent(self, SchematicPreviewModel::fullCube));
	}

	/** Slabs and stairs have the same state next to them and still show faces, so only a whole cube counts. */
	private static boolean fullCube(BlockState state) {
		try {
			return Block.isShapeFullBlock(state.getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO));
		} catch (RuntimeException e) {
			return false;
		}
	}

	private static long index(int sx, int sy, int x, int y, int z) {
		return x + (long) sx * (y + (long) sy * z);
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

	public boolean simplified() {
		return simplified;
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
