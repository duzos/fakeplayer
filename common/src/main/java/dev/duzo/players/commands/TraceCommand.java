package dev.duzo.players.commands;

import com.mojang.brigadier.CommandDispatcher;
import dev.duzo.players.Constants;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

public class TraceCommand {
	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(
				Commands.literal(Constants.MOD_ID)
						.then(Commands.literal("trace")
								.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
								.then(Commands.literal("on").executes(ctx -> set(ctx.getSource(), true)))
								.then(Commands.literal("off").executes(ctx -> set(ctx.getSource(), false)))));
	}

	private static int set(CommandSourceStack source, boolean on) {
		Constants.setTrace(on);
		source.sendSuccess(() -> Component.literal("Fake Players trace " + (on ? "on" : "off")), true);
		return 1;
	}
}
