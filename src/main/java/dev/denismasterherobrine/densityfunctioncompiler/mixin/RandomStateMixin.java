package dev.denismasterherobrine.densityfunctioncompiler.mixin;

import dev.denismasterherobrine.densityfunctioncompiler.DensityFunctionCompiler;
import dev.denismasterherobrine.densityfunctioncompiler.compat.FabricBiomeApiClimateRebind;
import dev.denismasterherobrine.densityfunctioncompiler.config.DfcConfig;
import dev.denismasterherobrine.densityfunctioncompiler.compiler.pipeline.RouterPipeline;
import net.neoforged.fml.ModList;
import dev.denismasterherobrine.densityfunctioncompiler.debug.RouterParityCheck;
import net.minecraft.core.HolderGetter;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Compile the {@link NoiseRouter} and {@link Climate.Sampler} stored on a freshly
 * constructed {@link RandomState}.
 *
 * <h2>Why this hook (and not {@link NoiseGeneratorSettings#noiseRouter()})</h2>
 *
 * <p>Router fields can be compiled eagerly (legacy) or, when
 * {@code DfcConfig.lazyNoiseRouter()} is true, on first use via
 * {@link dev.denismasterherobrine.densityfunctioncompiler.compiler.pipeline.OnDemandCompilingDensityFunction}
 * (still after wiring, never before). A previous <em>incorrect</em> design compiled
 * lazily on the first call to {@code NoiseGeneratorSettings.noiseRouter()}. That looked
 * correct in isolation (parity check passed 15/15 across every dimension) but quietly
 * destroyed worldgen:
 * the first {@code noiseRouter()} caller is exactly {@link RandomState}'s constructor,
 * <em>before</em> it runs {@code mapAll(NoiseWiringHelper)}. At that point every
 * {@link net.minecraft.world.level.levelgen.DensityFunction.NoiseHolder NoiseHolder}
 * still has a {@code null} {@link NormalNoise}, and our IR builder dutifully matches
 * vanilla's "noise is null → 0.0" fallback by collapsing the node to a constant zero.
 *
 * <p>Both the original and the compiled router agree on those zeros (which is why
 * parity passed), but the compiled router then keeps emitting zero forever — the
 * subsequent {@code mapAll(NoiseWiringHelper)} that <em>should</em> swap the unbound
 * noise references for chunk-bound {@link NormalNoise} instances has nothing to swap;
 * we baked the zeros into bytecode. Climate sampling then returns
 * {@code (0, 0, 0, 0, 0, 0)} for every column, and {@link Climate} picks the closest
 * defined biome to that point — which is River everywhere — while the terrain routers
 * collapse to flat sea level (since {@code finalDensity} reads zero too) and the world
 * fills with ocean. This is exactly the symptom we were seeing.
 *
 * <h2>The fix</h2>
 *
 * <p>Compile at the very end of {@link RandomState}'s constructor. By then:
 * <ul>
 *   <li>{@code this.router} has gone through {@code mapAll(NoiseWiringHelper)} and
 *       holds wired noises (correctly seeded for this {@code RandomState}'s level seed);</li>
 *   <li>{@code this.sampler} has been built from the wired router with markers stripped
 *       (so single-point biome lookups skip cell caching).</li>
 * </ul>
 * Compiling both gives us a JIT-compiled hot path with the right noises baked in.
 *
 * <p>This matches C2ME's {@code MixinNoiseConfig} approach (Yarn's {@code NoiseConfig}
 * is the same class as Mojmap's {@code RandomState}). The choice is forced — there is
 * no point earlier in the call chain where noises are bound but the router has not yet
 * been consumed by callers.
 *
 * <h2>Forgified Fabric + Sinytra Connector</h2>
 * <p>When the Forgified Fabric API is present, {@code fabric-biome-api-v1} adds
 * {@link MultiNoiseSamplerHooks} to {@code Climate$Sampler}. After
 * {@link RouterPipeline#compileSampler} replaces the sampler, we call
 * {@link FabricBiomeApiClimateRebind#propagateToCompiledSampler} (see also
 * {@link ClimateSamplerFabricDfcFapiSupportMixin}). Gated on {@code ModList} so environments
 * without FFAPI do not load Fabric types.</p>
 */
@Mixin(value = RandomState.class, priority = 2000)
public abstract class RandomStateMixin {

    @Mutable
    @Shadow @Final
    private NoiseRouter router;

    @Mutable
    @Shadow @Final
    private Climate.Sampler sampler;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void dfc$compileWiredRouter(NoiseGeneratorSettings settings,
                                        HolderGetter<NormalNoise.NoiseParameters> noises,
                                        long levelSeed,
                                        CallbackInfo ci) {
        // :dfc-c2me redirects C2ME's BytecodeGen.compile to our Compiler while reusing C2ME's
        // RandomState hook; compiling here too would stack two pipelines on the same router.
        if (ModList.get().isLoaded("dfc_c2me")) {
            return;
        }
        long start = System.nanoTime();
        NoiseRouter wiredRouter = this.router;
        Climate.Sampler wiredSampler = this.sampler;

        NoiseRouter compiledRouter;
        try {
            compiledRouter = RouterPipeline.compile(wiredRouter);
        } catch (Throwable t) {
            DensityFunctionCompiler.LOGGER.warn(
                    "RouterPipeline.compile threw for wired router (settings={}); "
                            + "leaving the vanilla router in place", settings, t);
            compiledRouter = wiredRouter;
        }

        Climate.Sampler compiledSampler;
        if (DfcConfig.compileClimateSampler()) {
            try {
                compiledSampler = RouterPipeline.compileSampler(wiredSampler);
            } catch (Throwable t) {
                DensityFunctionCompiler.LOGGER.warn(
                        "RouterPipeline.compileSampler threw for wired sampler (settings={}); "
                                + "leaving the vanilla sampler in place", settings, t);
                compiledSampler = wiredSampler;
            }
        } else {
            compiledSampler = wiredSampler;
        }

        if (compiledRouter != wiredRouter && RouterParityCheck.enabledAtStartup()) {
            // Now that we compile post-wiring, the parity check actually exercises the
            // bound-noise path — pre-wiring parity was vacuous (both sides read zero).
            try {
                String label = "RandomState@" + System.identityHashCode(this)
                        + " seed=" + Long.toHexString(levelSeed);
                var report = RouterParityCheck.compareRouters(
                        label, wiredRouter, compiledRouter, 1024, 0xC0FFEE_DECAFL);
                RouterParityCheck.logReport(report);
            } catch (Throwable parityErr) {
                DensityFunctionCompiler.LOGGER.warn(
                        "DFC parity diagnostic threw; continuing with compiled router.",
                        parityErr);
            }
        }

        this.router = compiledRouter;
        this.sampler = compiledSampler;
        ModList modList = ModList.get();
        if (modList.isLoaded("forgified_fabric_api") || modList.isLoaded("fabric_api")) {
            FabricBiomeApiClimateRebind.propagateToCompiledSampler(wiredSampler, compiledSampler, levelSeed);
        }

        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
        DensityFunctionCompiler.LOGGER.info(
                "DFC compiled NoiseRouter + Climate.Sampler for RandomState(seed={}) in {}ms",
                Long.toHexString(levelSeed), elapsedMs);
    }
}
