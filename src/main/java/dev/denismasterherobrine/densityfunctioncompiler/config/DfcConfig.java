package dev.denismasterherobrine.densityfunctioncompiler.config;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * NeoForge common config for experimental DFC paths. Conservative toggles remain available
 * for diagnosing regressions on hot paths.
 */
public final class DfcConfig {

    public static final ModConfigSpec COMMON_SPEC;
    public static final ModConfigSpec.BooleanValue CACHE_WRAPPER_DIRECT_READ;
    public static final ModConfigSpec.BooleanValue BEARDIFIER_SPECIALIZE;
    public static final ModConfigSpec.BooleanValue GENERATOR_ACCELERATOR_INLINE;
    public static final ModConfigSpec.BooleanValue COMPILE_CLIMATE_SAMPLER;
    /**
     * When true, each of the 15 {@link net.minecraft.world.level.levelgen.NoiseRouter}
     * top-level fields is wrapped in
     * {@link dev.denismasterherobrine.densityfunctioncompiler.compiler.pipeline.OnDemandCompilingDensityFunction}
     * and defers work until the field is first used. Default {@code false}: batch-compile
     * in {@code RandomState} as before. The six {@link
     * net.minecraft.world.level.biome.Climate.Sampler} fields stay eagerly compiled
     * when {@link #COMPILE_CLIMATE_SAMPLER} is true.
     */
    public static final ModConfigSpec.BooleanValue LAZY_NOISE_ROUTER;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        b.comment("DensityFunctionCompiler experimental features.");

        CACHE_WRAPPER_DIRECT_READ = b
                .comment("When true, compiled marker call sites for NoiseChunk cache wrappers (Cache2D / FlatCache / CacheOnce / CacheAllInCell) try dfc$tryDirectRead before compute().")
                .define("cacheWrapperDirectRead", true);

        BEARDIFIER_SPECIALIZE = b
                .comment("When true, Beardifier density instances compile to a dedicated IR node (same runtime compute path; enables fingerprinting / future inlining).")
                .define("beardifierSpecialize", true);

        GENERATOR_ACCELERATOR_INLINE = b
                .comment("When true, unwrap Moonrise Generator Accelerator Fast* density types into the same IR as vanilla so the compiler can fuse them. Set false to compare against black-box Invoke (GA-optimized compute) only.")
                .define("generatorAcceleratorInline", true);

        /**
         * Each {@link net.minecraft.world.level.levelgen.RandomState} compiles 15
         * {@link net.minecraft.world.level.levelgen.NoiseRouter} fields; when this is true, also
         * compiles 6 {@link net.minecraft.world.level.biome.Climate.Sampler} fields. That is
         * <strong>21</strong> DFC root compilations (visitor → compiler) per RandomState—modded
         * worlds often construct dozens of RandomState instances, so /dfc “roots” can be in the
         * high hundreds. Disabling trades single-point climate sampling hotness for a bit of startup
         * work and 6 fewer roots per RandomState.
         */
        COMPILE_CLIMATE_SAMPLER = b
                .comment("Compile the 6 Climate.Sampler fields (in addition to the 15 NoiseRouter fields) in RandomState. false ≈ 6 fewer roots and less startup compile work per RandomState, but those density functions stay on the vanilla/slower single-point path used heavily by biome sampling.")
                .define("compileClimateSampler", true);

        LAZY_NOISE_ROUTER = b
                .comment("When true, defer each NoiseRouter field until first use (experimental; default off to avoid rebind + compile churn on hot paths).")
                .define("lazyNoiseRouter", false);

        COMMON_SPEC = b.build();
    }

    public static boolean cacheWrapperDirectRead() {
        return CACHE_WRAPPER_DIRECT_READ.get();
    }

    public static boolean beardifierSpecialize() {
        return BEARDIFIER_SPECIALIZE.get();
    }

    public static boolean generatorAcceleratorInline() {
        return GENERATOR_ACCELERATOR_INLINE.get();
    }

    public static boolean compileClimateSampler() {
        return COMPILE_CLIMATE_SAMPLER.get();
    }

    public static boolean lazyNoiseRouter() {
        return LAZY_NOISE_ROUTER.get();
    }

    private DfcConfig() {}
}
