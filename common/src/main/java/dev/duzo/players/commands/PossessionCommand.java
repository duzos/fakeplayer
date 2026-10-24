package dev.duzo.players.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.duzo.players.Constants;
import dev.duzo.players.entities.possession.PossessionManager;
import dev.duzo.players.entities.possession.PossessionSelfTest;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;

public class PossessionCommand {
	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(
				Commands.literal(Constants.MOD_ID)
						.then(Commands.literal("release")
								.executes(ctx -> releaseSelf(ctx.getSource()))
								.then(Commands.argument("targets", EntityArgument.players())
										.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
										.executes(ctx -> releaseTargets(ctx.getSource(), ctx))))
						.then(Commands.literal("possession")
								.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
								.then(Commands.literal("selftest").executes(ctx -> selfTest(ctx.getSource())))));
	}

	private static int releaseSelf(CommandSourceStack source) {
		ServerPlayer player = source.getPlayer();
		if (player == null || !PossessionManager.isPossessing(player)) {
			source.sendFailure(Component.literal("You are not possessing a fake"));
			return 0;
		}
		return PossessionManager.release(player, PossessionManager.Reason.RELEASED, null) ? 1 : 0;
	}

	private static int releaseTargets(CommandSourceStack source, com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx) {
		int released = 0;
		try {
			for (ServerPlayer target : EntityArgument.getPlayers(ctx, "targets")) {
				if (!PossessionManager.isPossessing(target)) continue;
				if (PossessionManager.release(target, PossessionManager.Reason.RELEASED, null)) released++;
			}
		} catch (CommandSyntaxException e) {
			source.sendFailure(Component.literal("Invalid target"));
			return 0;
		}
		if (released == 0) {
			source.sendFailure(Component.literal("None of those players is possessing a fake"));
			return 0;
		}
		int count = released;
		source.sendSuccess(() -> Component.literal("Released " + count + " player" + (count == 1 ? "" : "s")), true);
		return released;
	}

	private static int selfTest(CommandSourceStack source) {
		List<String> failures = PossessionSelfTest.run(source.getServer(), source.getPlayer());
		if (failures.isEmpty()) {
			source.sendSuccess(() -> Component.literal("Possession self test passed"), false);
			return 1;
		}
		failures.forEach(f -> source.sendFailure(Component.literal(f)));
		return 0;
	}
}
