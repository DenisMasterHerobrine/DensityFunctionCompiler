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

    private DfcConfig() {}
}
