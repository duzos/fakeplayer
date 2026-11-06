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
	/** Immediate-mode drawing pays per block per frame, so the drawn list is capped. */
	public static final int MAX_DRAWN = 8192;

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

	private SchematicPreviewModel(SchematicPreview.Data data, BlockState[] states, int[] xs, int[] ys, int[] zs, boolean simplified) {
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
		// keyed by position, so the occlusion test costs the same for a huge sparse build as a small one
		Map<Long, BlockState> grid = new HashMap<>(cells * 2);
		for (int i = 0; i < cells; i++) {
			int slot = data.cellState()[i];
			int pos = data.cellPos()[i];
			if (slot >= 0 && slot < palette.length && pos >= 0 && pos < volume) grid.put((long) pos, palette[slot]);
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
			if (buried(grid, fullShapes, sx, sy, sz, x, y, z)) continue;
			outStates[n] = palette[slot];
			ox[n] = x;
			oy[n] = y;
			oz[n] = z;
			n++;
		}
		boolean simplified = n > MAX_DRAWN;
		int[] keep = simplified ? pick(oy, ox, oz, n) : null;
		int count = keep == null ? n : keep.length;
		BlockState[] fs = new BlockState[count];
		int[] fx = new int[count];
		int[] fy = new int[count];
		int[] fz = new int[count];
		for (int i = 0; i < count; i++) {
			int from = keep == null ? i : keep[i];
			fs[i] = outStates[from];
			fx[i] = ox[from];
			fy[i] = oy[from];
			fz[i] = oz[from];
		}
		return new SchematicPreviewModel(data, fs, fx, fy, fz, simplified);
	}

	/**
	 * Indices of at most MAX_DRAWN blocks: the highest visible block of each column first, since those
	 * are the surfaces seen from above, then an even stride through the rest. Ascending, so draw order holds.
	 */
	private static int[] pick(int[] ys, int[] xs, int[] zs, int n) {
		Map<Long, Integer> top = new HashMap<>();
		for (int i = 0; i < n; i++) {
			long column = ((long) xs[i] << 32) | (zs[i] & 0xFFFFFFFFL);
			Integer at = top.get(column);
			if (at == null || ys[i] > ys[at]) top.put(column, i);
		}
		boolean[] chosen = new boolean[n];
		int[] tops = new int[top.size()];
		int t = 0;
		for (int i : top.values()) tops[t++] = i;
		java.util.Arrays.sort(tops);
		int picked = 0;
		if (tops.length >= MAX_DRAWN) {
			for (int k = 0; k < MAX_DRAWN; k++) {
				chosen[tops[(int) ((long) k * tops.length / MAX_DRAWN)]] = true;
				picked++;
			}
		} else {
			for (int i : tops) chosen[i] = true;
			int rest = n - tops.length;
			int need = MAX_DRAWN - tops.length;
			int[] others = new int[rest];
			int o = 0;
			for (int i = 0; i < n; i++) if (!chosen[i]) others[o++] = i;
			for (int k = 0; k < need; k++) chosen[others[(int) ((long) k * rest / need)]] = true;
			picked = MAX_DRAWN;
		}
		int[] out = new int[picked];
		int w = 0;
		for (int i = 0; i < n; i++) if (chosen[i]) out[w++] = i;
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

	public boolean truncated() {
		return total > carried;
	}

	public int carried() {
		return carried;
	}

	public boolean simplified() {
		return simplified;
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
