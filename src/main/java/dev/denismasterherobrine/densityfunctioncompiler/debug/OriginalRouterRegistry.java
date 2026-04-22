package dev.denismasterherobrine.densityfunctioncompiler.debug;

import com.google.common.collect.MapMaker;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseRouter;

import java.util.concurrent.ConcurrentMap;

/**
 * Side-table holding the pre-JIT {@link NoiseRouter} for each
 * {@link NoiseGeneratorSettings} that the lazy mixin has compiled.
 *
 * <p>Used purely for diagnostics — when {@code -Ddfc.parity=true} (or
 * {@code -Ddfc.parity.capture=true}) is set, the mixin captures the
 * original router into this registry before swapping the field. The
 * {@code /dfc parity} command then reads it back to do an honest
 * vanilla-vs-JIT comparison instead of comparing two JIT outputs.
 *
 * <p>Both keys (settings instances) and values (routers) are weakly
 * referenced so this registry never extends the lifetime of either,
 * and a {@code /reload} that rebuilds settings will let the old
 * entries drop on the next GC. This is important — pinning every
 * router would defeat the GC of the hidden classes inside.
 */
public final class OriginalRouterRegistry {

    private static final ConcurrentMap<NoiseGeneratorSettings, NoiseRouter> ORIGINALS =
            new MapMaker().weakKeys().weakValues().concurrencyLevel(4).makeMap();

    private OriginalRouterRegistry() {}

    /**
     * Should the lazy mixin record originals into this table? True when either
     * {@code dfc.parity} or {@code dfc.parity.capture} is set, so users can
     * enable capture without paying the per-router parity-sweep cost.
     */
    public static boolean captureEnabled() {
        return Boolean.getBoolean("dfc.parity") || Boolean.getBoolean("dfc.parity.capture");
    }

    public static void record(NoiseGeneratorSettings settings, NoiseRouter original) {
        if (settings == null || original == null) return;
        ORIGINALS.putIfAbsent(settings, original);
    }

    public static NoiseRouter find(NoiseGeneratorSettings settings) {
        return settings == null ? null : ORIGINALS.get(settings);
    }

    public static int size() {
        return ORIGINALS.size();
    }
}
