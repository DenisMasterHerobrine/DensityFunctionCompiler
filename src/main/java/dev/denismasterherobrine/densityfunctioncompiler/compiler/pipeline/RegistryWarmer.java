package dev.denismasterherobrine.densityfunctioncompiler.compiler.pipeline;

import dev.denismasterherobrine.densityfunctioncompiler.DensityFunctionCompiler;
import dev.denismasterherobrine.densityfunctioncompiler.compiler.codegen.CompiledDensityFunction;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.synth.NormalNoise;

/**
 * Pre-warms the JIT cache after the registries have been bound.
 *
 * <p>This runs once on {@code ServerStartingEvent} (and again on each
 * {@code OnDatapackSyncEvent}, because reload rebuilds the registries) — by which
 * point every {@link net.minecraft.core.Holder.Reference Holder.Reference} into
 * {@link Registries#DENSITY_FUNCTION} is resolvable. That makes it the right place to
 * (a) construct a {@link RandomState} for every {@link NoiseGeneratorSettings} so the
 * {@link dev.denismasterherobrine.densityfunctioncompiler.mixin.RandomStateMixin RandomStateMixin}
 * runs the same wired {@link NoiseRouter} compile as real worldgen, and (b) compile every
 * standalone {@code DensityFunction} so any future router that points at it gets a hot
 * cache lookup instead of a cold ASM emit.
 *
 * <p>We cannot use {@link NoiseGeneratorSettings#noiseRouter()} alone: that returns the
 * static data-pack template, which is never replaced in place. Compilation only runs at
 * the end of {@link RandomState}'s constructor after {@code mapAll(NoiseWiringHelper)}.
 *
 * <p>Without this step, the first chunk to spawn would pay the entire compile cost on
 * the chunk-gen worker thread — that's a multi-hundred-millisecond stall per noise
 * preset, which is exactly the latency we're trying to remove.
 */
public final class RegistryWarmer {

    private RegistryWarmer() {}

    public static void warmAll(MinecraftServer server) {
        if (server == null) {
            return;
        }
        // Trigger the same RandomState + wired router compile as production (mixin@RETURN).
        warmNoiseSettings(server);
        // Then compile any density functions that aren't reachable from a router
        // (mod-added DFs registered for use elsewhere). Idempotent w.r.t. the
        // identity-keyed cache that the router walk already populated.
        warmDensityFunctions(server);
    }

    private static void warmNoiseSettings(MinecraftServer server) {
        try {
            Registry<NoiseGeneratorSettings> registry = server.registryAccess()
                    .registryOrThrow(Registries.NOISE_SETTINGS);
            HolderGetter<NormalNoise.NoiseParameters> noiseGetter =
                    server.registryAccess().lookupOrThrow(Registries.NOISE);
            long levelSeed = server.overworld().getSeed();

            int total = 0;
            int compiled = 0;
            int failed = 0;
            for (NoiseGeneratorSettings settings : registry) {
                total++;
                try {
                    // Matches production: wiring + RandomStateMixin compile at <init> RETURN.
                    RandomState state = RandomState.create(settings, noiseGetter, levelSeed);
                    if (containsAnyCompiled(state.router())) {
                        compiled++;
                    }
                } catch (Throwable settingsErr) {
                    failed++;
                    DensityFunctionCompiler.LOGGER.debug(
                            "DFC: warm-up couldn't build RandomState for a noise_settings entry; skipping",
                            settingsErr);
                }
            }
            DensityFunctionCompiler.LOGGER.info(
                    "DFC: warmed {}/{} noise_settings (RandomState + wired router compile){}",
                    compiled, total,
                    failed == 0 ? "" : " (" + failed + " throws)");
        } catch (Throwable t) {
            DensityFunctionCompiler.LOGGER.warn(
                    "DFC: noise_settings warm-up failed; lazy compilation will still pick up callers.", t);
        }
    }

    private static void warmDensityFunctions(MinecraftServer server) {
        try {
            Registry<DensityFunction> registry = server.registryAccess()
                    .registryOrThrow(Registries.DENSITY_FUNCTION);
            CompilingVisitor visitor = CompilingVisitor.global();
            int seen = 0;
            for (DensityFunction df : registry) {
                visitor.apply(df);
                seen++;
            }
            DensityFunctionCompiler.LOGGER.info(
                    "DFC: warmed {} density_function entries; visitor cache now ~{} entries",
                    seen, visitor.cacheSize());
        } catch (Throwable t) {
            DensityFunctionCompiler.LOGGER.warn(
                    "DFC: density_function warm-up failed; lazy compilation will still pick up callers.", t);
        }
    }

    /**
     * Heuristic: a router was successfully compiled iff any of its top-level fields
     * is a {@link CompiledDensityFunction}. We sample a few representative ones
     * rather than all 15 fields — every router that compiles at all will have at
     * least these set, since they're the "main" outputs.
     */
    private static boolean containsAnyCompiled(NoiseRouter router) {
        return router.finalDensity() instanceof CompiledDensityFunction
                || router.initialDensityWithoutJaggedness() instanceof CompiledDensityFunction
                || router.temperature() instanceof CompiledDensityFunction
                || router.vegetation() instanceof CompiledDensityFunction
                || router.continents() instanceof CompiledDensityFunction
                || router.depth() instanceof CompiledDensityFunction
                || router.barrierNoise() instanceof CompiledDensityFunction;
    }
}
