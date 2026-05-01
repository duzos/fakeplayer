package dev.duzo.players.event;


import dev.duzo.players.Constants;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import static dev.duzo.players.platform.ForgeCommonRegistry.COMMANDS;

@Mod.EventBusSubscriber(modid = Constants.MOD_ID)
public class ForgeModEvents {
	@SubscribeEvent
	public static void registerCommands(RegisterCommandsEvent e) {
		COMMANDS.forEach(command -> command.accept(e.getDispatcher()));
	}
}
