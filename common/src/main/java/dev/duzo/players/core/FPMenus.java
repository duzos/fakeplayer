package dev.duzo.players.core;

import dev.duzo.players.Constants;
import dev.duzo.players.menu.FakePlayerMenu;
import dev.duzo.players.platform.Services;
import net.minecraft.world.inventory.MenuType;

import java.util.function.Supplier;

public class FPMenus {
	public static final Supplier<MenuType<FakePlayerMenu>> FAKE_PLAYER =
			Services.COMMON_REGISTRY.registerMenu(Constants.MOD_ID, "fake_player", FakePlayerMenu::new);

	public static void init() {
	}
}
