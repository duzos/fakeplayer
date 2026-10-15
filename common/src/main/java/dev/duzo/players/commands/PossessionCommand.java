package dev.duzo.players.commands;

import com.mojang.brigadier.CommandDispatcher;
import dev.duzo.players.Constants;
import dev.duzo.players.entities.possession.PossessionSelfTest;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;

public class PossessionCommand {
	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(
				Commands.literal(Constants.MOD_ID)
						.then(Commands.literal("possession")
								.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
								.then(Commands.literal("selftest").executes(ctx -> selfTest(ctx.getSource())))));
	}

	private static int selfTest(CommandSourceStack source) {
		ServerPlayer player = source.getPlayer();
		if (player == null) {
			source.sendFailure(Component.literal("Run this as a player"));
			return 0;
		}
		List<String> failures = PossessionSelfTest.run(player);
		if (failures.isEmpty()) {
			source.sendSuccess(() -> Component.literal("Possession self test passed"), false);
			return 1;
		}
		failures.forEach(f -> source.sendFailure(Component.literal(f)));
		return 0;
	}
}
