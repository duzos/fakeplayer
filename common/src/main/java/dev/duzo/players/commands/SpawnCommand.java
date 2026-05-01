package dev.duzo.players.commands;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.duzo.players.Constants;
import dev.duzo.players.entities.FakePlayerEntity;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

public class SpawnCommand {
	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(
				Commands.literal(Constants.MOD_ID)
						.then(Commands.literal("spawn")
								.requires((p) -> p.hasPermission(Commands.LEVEL_GAMEMASTERS))
								.then(Commands.argument("skin", StringArgumentType.word())
										.executes(ctx -> spawn(ctx, ctx.getSource().getPosition()))
										.then(Commands.argument("pos", Vec3Argument.vec3())
												.executes(ctx -> spawn(ctx, Vec3Argument.getVec3(ctx, "pos")))))));
	}

	private static int spawn(CommandContext<CommandSourceStack> context, Vec3 pos) throws CommandSyntaxException {
		CommandSourceStack source = context.getSource();
		ServerLevel level = source.getLevel();
		String skin = StringArgumentType.getString(context, "skin");

		FakePlayerEntity entity = new FakePlayerEntity(level);
		entity.setPos(pos.x, pos.y, pos.z);
		entity.setPersistenceRequired();
		entity.setSkin(skin);
		level.addFreshEntity(entity);

		source.sendSuccess(() -> Component.literal("Spawned fake player " + skin), true);
		return Command.SINGLE_SUCCESS;
	}
}
