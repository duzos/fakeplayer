package dev.duzo.players.platform.services;

import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;
import java.util.function.Supplier;

/**
 * A mod-owned registry, created through {@link ICommonRegistry#createRegistry}.
 *
 * <p>Registration happens during {@code PlayersCommon.init()}. Values are only guaranteed to be
 * constructed after that returns, so hold the {@link Supplier} and resolve it lazily rather than
 * calling {@code get()} on it at registration time.
 */
public interface ICustomRegistry<T> {
	Supplier<T> register(String modid, String name, Supplier<T> value);

	/** @return the registered value, or null when nothing is registered under that id. */
	@Nullable T get(ResourceLocation id);
}
