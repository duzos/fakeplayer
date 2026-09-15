package dev.duzo.players.platform;

import dev.duzo.players.Constants;
import dev.duzo.players.platform.services.ICommonRegistry;
import dev.duzo.players.platform.services.IPlatformHelper;
import dev.duzo.players.platform.services.ITackleProvider;

import java.util.ServiceLoader;


public class Services {
    public static final IPlatformHelper PLATFORM = load(IPlatformHelper.class);
    public static final ICommonRegistry COMMON_REGISTRY = load(
            ICommonRegistry.class);
    public static final ITackleProvider TACKLE = load(ITackleProvider.class);


    public static <T> T load(Class<T> clazz) {

        final T loadedService = ServiceLoader.load(clazz)
                .findFirst()
                .orElseThrow(() -> new NullPointerException("Failed to load service for " + clazz.getName()));
        Constants.LOG.debug("Loaded {} for service {}", loadedService, clazz);
        return loadedService;
    }
}