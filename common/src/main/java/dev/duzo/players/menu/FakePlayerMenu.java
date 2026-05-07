package dev.duzo.players.menu;

import dev.duzo.players.core.FPMenus;
import dev.duzo.players.entities.FakePlayerEntity;
import dev.duzo.players.entities.inventory.FakePlayerInventory;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ItemStack;

public class FakePlayerMenu extends AbstractContainerMenu {
	private static final EquipmentSlot[] ARMOR_SLOTS = {
			EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
	};

	// Slot index ranges
	public static final int ARMOR_START = 0;
	public static final int ARMOR_END = 4;            // [0, 4)
	public static final int OFFHAND_INDEX = 4;
	public static final int FP_STORAGE_START = 5;
	public static final int FP_STORAGE_END = 32;      // [5, 32) — 27 row slots
	public static final int MAINHAND_INDEX = 32;
	public static final int FP_HOTBAR_START = 33;
	public static final int FP_HOTBAR_END = 41;       // [33, 41) — 8 hotbar slots (1..8)
	public static final int PLAYER_START = 41;
	public static final int PLAYER_END = 77;          // [41, 77)

	private final FakePlayerEntity entity;
	private final Container storage;

	public FakePlayerMenu(int id, Inventory playerInventory, FakePlayerEntity entity) {
		super(FPMenus.FAKE_PLAYER.get(), id);
		this.entity = entity;
		this.storage = entity != null ? entity.getInventory() : new SimpleContainer(FakePlayerInventory.SIZE);

		// 0..3 armor, vanilla positions
		for (int i = 0; i < 4; i++) {
			final EquipmentSlot eq = ARMOR_SLOTS[i];
			this.addSlot(new EquipmentBoundSlot(entity, eq, 8, 8 + i * 18) {
				@Override
				public boolean mayPlace(ItemStack stack) {
					return Mob.getEquipmentSlotForItem(stack) == eq;
				}

				@Override
				public int getMaxStackSize() {
					return 1;
				}
			});
		}

		// 4: offhand at vanilla position
		this.addSlot(new EquipmentBoundSlot(entity, EquipmentSlot.OFFHAND, 77, 62));

		// 5..31 storage rows (3 x 9) — backed by storage[0..26]
		for (int row = 0; row < 3; row++) {
			for (int col = 0; col < 9; col++) {
				this.addSlot(new Slot(this.storage, col + row * 9, 8 + col * 18, 84 + row * 18));
			}
		}

		// 32: main hand — first hotbar slot
		this.addSlot(new EquipmentBoundSlot(entity, EquipmentSlot.MAINHAND, 8, 142));

		// 33..40 hotbar 1..8 — backed by storage[27..34]
		for (int col = 1; col < 9; col++) {
			this.addSlot(new Slot(this.storage, 27 + (col - 1), 8 + col * 18, 142));
		}

		// 41..67 player inventory rows, 68..76 player hotbar
		for (int row = 0; row < 3; row++) {
			for (int col = 0; col < 9; col++) {
				this.addSlot(new Slot(playerInventory, col + row * 9 + 9, 8 + col * 18, 174 + row * 18));
			}
		}
		for (int col = 0; col < 9; col++) {
			this.addSlot(new Slot(playerInventory, col, 8 + col * 18, 232));
		}
	}

	public FakePlayerMenu(int id, Inventory playerInventory, FriendlyByteBuf buf) {
		this(id, playerInventory, resolveEntity(buf.readInt()));
	}

	private static FakePlayerEntity resolveEntity(int entityId) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) return null;
		Entity e = mc.level.getEntity(entityId);
		return e instanceof FakePlayerEntity fp ? fp : null;
	}

	public FakePlayerEntity getEntity() {
		return entity;
	}

	@Override
	public boolean stillValid(Player player) {
		return entity != null && entity.isAlive() && entity.distanceToSqr(player) <= 64.0D;
	}

	@Override
	public ItemStack quickMoveStack(Player player, int index) {
		Slot slot = this.slots.get(index);
		if (!slot.hasItem()) return ItemStack.EMPTY;
		ItemStack stack = slot.getItem();
		ItemStack copy = stack.copy();

		boolean fromFakePlayer = index < PLAYER_START;

		if (fromFakePlayer) {
			if (!this.moveItemStackTo(stack, PLAYER_START, PLAYER_END, true)) return ItemStack.EMPTY;
		} else {
			// Player -> try armor by item type, then offhand if it's an offhand-equippable, then storage rows, then hotbar
			boolean placed = false;
			if (stack.getItem() instanceof ArmorItem armor) {
				int armorIdx = ARMOR_START + armor.getEquipmentSlot().getIndex();
				if (armorIdx >= ARMOR_START && armorIdx < ARMOR_END) {
					Slot target = this.slots.get(armorIdx);
					if (target.mayPlace(stack) && !target.hasItem()) {
						placed = this.moveItemStackTo(stack, armorIdx, armorIdx + 1, false);
					}
				}
			}
			if (!placed) {
				placed = this.moveItemStackTo(stack, FP_STORAGE_START, FP_HOTBAR_END, false);
			}
			if (!placed) return ItemStack.EMPTY;
		}

		if (stack.isEmpty()) {
			slot.set(ItemStack.EMPTY);
		} else {
			slot.setChanged();
		}

		if (stack.getCount() == copy.getCount()) return ItemStack.EMPTY;
		slot.onTake(player, stack);
		return copy;
	}

	private static class EquipmentBoundSlot extends Slot {
		private static final SimpleContainer DUMMY = new SimpleContainer(1);
		private final FakePlayerEntity bound;
		private final EquipmentSlot equipment;

		EquipmentBoundSlot(FakePlayerEntity bound, EquipmentSlot equipment, int x, int y) {
			super(DUMMY, 0, x, y);
			this.bound = bound;
			this.equipment = equipment;
		}

		@Override
		public ItemStack getItem() {
			return bound != null ? bound.getItemBySlot(equipment) : ItemStack.EMPTY;
		}

		@Override
		public void set(ItemStack stack) {
			if (bound != null) bound.setItemSlot(equipment, stack);
			this.setChanged();
		}

		@Override
		public ItemStack remove(int amount) {
			if (bound == null) return ItemStack.EMPTY;
			ItemStack current = bound.getItemBySlot(equipment);
			if (current.isEmpty()) return ItemStack.EMPTY;
			ItemStack split = current.split(amount);
			bound.setItemSlot(equipment, current);
			return split;
		}

		@Override
		public boolean hasItem() {
			return bound != null && !bound.getItemBySlot(equipment).isEmpty();
		}

		@Override
		public boolean mayPickup(Player player) {
			return bound != null;
		}

		@Override
		public void setChanged() {
		}
	}
}
