package dev.duzo.players.menu;

import dev.duzo.players.core.FPMenus;
import dev.duzo.players.entities.FakePlayerEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ResultContainer;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.inventory.TransientCraftingContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeType;

/**
 * Learn-by-example crafting GUI bound to a fake player. The 3x3 grid is filled from the opening
 * player's inventory; the result slot is a live, read-only preview (vanilla RecipeManager). Pressing
 * LEARN (see {@code LearnRecipePacketC2S}) captures the grid pattern into the fake's recipe param.
 */
public class FakeCrafterMenu extends AbstractContainerMenu {
	public static final int GRID_START = 0;
	public static final int GRID_END = 9;     // [0, 9)
	public static final int RESULT_SLOT = 9;
	public static final int INV_START = 10;
	public static final int INV_END = 46;     // [10, 46)

	private final FakePlayerEntity fake;
	private final Player owner;
	private final TransientCraftingContainer grid = new TransientCraftingContainer(this, 3, 3);
	private final ResultContainer result = new ResultContainer();

	public FakeCrafterMenu(int id, Inventory inv, FakePlayerEntity fake) {
		super(FPMenus.CRAFTER_LEARN.get(), id);
		this.fake = fake;
		this.owner = inv.player;

		for (int row = 0; row < 3; row++)
			for (int col = 0; col < 3; col++)
				this.addSlot(new Slot(grid, col + row * 3, 30 + col * 18, 17 + row * 18));

		this.addSlot(new Slot(result, 0, 124, 35) {
			@Override
			public boolean mayPlace(ItemStack stack) {
				return false;
			}

			@Override
			public boolean mayPickup(Player player) {
				return false;
			}
		});

		for (int row = 0; row < 3; row++)
			for (int col = 0; col < 9; col++)
				this.addSlot(new Slot(inv, col + row * 9 + 9, 8 + col * 18, 84 + row * 18));
		for (int col = 0; col < 9; col++)
			this.addSlot(new Slot(inv, col, 8 + col * 18, 142));
	}

	public FakeCrafterMenu(int id, Inventory inv, FriendlyByteBuf buf) {
		this(id, inv, resolve(buf.readInt()));
	}

	private static FakePlayerEntity resolve(int entityId) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) return null;
		Entity e = mc.level.getEntity(entityId);
		return e instanceof FakePlayerEntity fp ? fp : null;
	}

	@Override
	public void slotsChanged(Container container) {
		if (!(owner instanceof ServerPlayer sp)) return;
		ItemStack out = sp.level().getServer().getRecipeManager()
				.getRecipeFor(RecipeType.CRAFTING, grid, sp.level())
				.map(recipe -> recipe.assemble(grid, sp.level().registryAccess()))
				.orElse(ItemStack.EMPTY);
		result.setItem(0, out);
		sp.connection.send(new ClientboundContainerSetSlotPacket(this.containerId, this.incrementStateId(), RESULT_SLOT, out));
	}

	public TransientCraftingContainer grid() {
		return grid;
	}

	public ResultContainer result() {
		return result;
	}

	public FakePlayerEntity fake() {
		return fake;
	}

	@Override
	public boolean stillValid(Player player) {
		return fake != null && fake.isAlive() && fake.distanceToSqr(player) <= 64.0D;
	}

	@Override
	public void removed(Player player) {
		super.removed(player);
		if (!player.level().isClientSide()) this.clearContainer(player, grid);
	}

	@Override
	public ItemStack quickMoveStack(Player player, int index) {
		Slot slot = this.slots.get(index);
		if (!slot.hasItem()) return ItemStack.EMPTY;
		ItemStack stack = slot.getItem();
		ItemStack copy = stack.copy();

		if (index == RESULT_SLOT) {
			return ItemStack.EMPTY; // preview only
		} else if (index < RESULT_SLOT) {
			if (!this.moveItemStackTo(stack, INV_START, INV_END, true)) return ItemStack.EMPTY;
		} else {
			if (!this.moveItemStackTo(stack, GRID_START, GRID_END, false)) return ItemStack.EMPTY;
		}

		if (stack.isEmpty()) slot.set(ItemStack.EMPTY);
		else slot.setChanged();

		if (stack.getCount() == copy.getCount()) return ItemStack.EMPTY;
		slot.onTake(player, stack);
		return copy;
	}
}
