package dev.duzo.players.core;

import dev.duzo.players.entities.FakePlayerEntity;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.phys.Vec3;

public class PlayerEggItem extends Item {
	public PlayerEggItem(Properties properties) {
		super(properties);
	}

	@Override
	public InteractionResult useOn(UseOnContext context) {
		if (context.getLevel().isClientSide) {
			return InteractionResult.SUCCESS;
		}

		if (trySpawnPlayer(context.getPlayer().getItemInHand(context.getHand()), (ServerLevel) context.getLevel(), context.getClickLocation())) {
			context.getItemInHand().shrink(1);
			return InteractionResult.CONSUME;
		}

		return super.useOn(context);
	}

	private boolean trySpawnPlayer(ItemStack source, ServerLevel level, Vec3 pos) {
		FakePlayerEntity entity = new FakePlayerEntity(level);
		entity.setPos(pos.x, pos.y, pos.z);
		entity.setPersistenceRequired();
		level.addFreshEntity(entity);

		Component customName = source.get(DataComponents.CUSTOM_NAME);
		if (customName != null) {
			entity.setCustomName(customName);
		}

		return true;
	}
}
