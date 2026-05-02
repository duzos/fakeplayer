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
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;

import java.util.Collection;

public class SendChatCommand {
	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(
				Commands.literal(Constants.MOD_ID)
						.then(Commands.literal("chat")
								.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
								.then(Commands.argument("targets", EntityArgument.entities())
										.then(Commands.argument("message", StringArgumentType.greedyString())
												.executes(SendChatCommand::execute)))));
	}

	private static int execute(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();

		String message = StringArgumentType.getString(context, "message");

		try {
			Collection<? extends Entity> found = EntityArgument.getEntities(context, "targets");

			found.forEach(e -> execute(e, message));
		} catch (CommandSyntaxException exception) {
			source.sendFailure(Component.literal("Invalid target entity"));
			return 0;
		}

		return Command.SINGLE_SUCCESS;
	}

	private static void execute(Entity e, String message) {
		if (e instanceof FakePlayerEntity) {
			((FakePlayerEntity) e).sendChat(message);
		}
	}
}
