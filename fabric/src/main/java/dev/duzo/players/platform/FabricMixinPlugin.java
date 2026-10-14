package dev.duzo.players.platform;

import net.fabricmc.loader.api.FabricLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/** Keeps mixins that name another mod's types from being applied, and so loaded, when that mod is absent. */
public class FabricMixinPlugin implements IMixinConfigPlugin {
	@Override
	public void onLoad(String mixinPackage) {}

	@Override
	public String getRefMapperConfig() { return null; }

	@Override
	public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
		if (mixinClassName.endsWith(".FakePlayerTaczAmmoMixin")) return taczHasItemHandlerHook();
		return true;
	}

	// the interface is checked for rather than a version, so a TACZ build that lacks it is skipped instead of crashing
	private static boolean taczHasItemHandlerHook() {
		return FabricLoader.getInstance().getModContainer("tacz")
				.flatMap(tacz -> tacz.findPath("cn/sh1rocu/tacz/api/mixin/ItemHandlerCapability.class"))
				.isPresent();
	}

	@Override
	public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}

	@Override
	public List<String> getMixins() { return null; }

	@Override
	public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}

	@Override
	public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
}
